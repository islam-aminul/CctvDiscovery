package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.service.MacLookupService;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.util.NetworkUtils;
import com.cctv.discovery.util.TargetParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host discovery: multicast WS-Discovery plus a TCP port scan, followed by
 * protocol confirmation of each open port.
 *
 * <p>Ports are classified by what answers on them, not by their number. The
 * previous version assumed 80/8080 meant ONVIF and 554 meant RTSP, so the test
 * camera (ONVIF on 8000, RTSP on 5543) was found but classified as having
 * neither, and every device with port 8000 open was treated as a recorder.
 */
public final class NetworkScanner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(NetworkScanner.class);

    /** Progress during the port scan. */
    public interface ProgressCallback {
        void onProgress(int current, int total);
    }

    private final AppConfig config = AppConfig.getInstance();
    private final OnvifService onvifService;
    private final RtspService rtspService;
    private final MacLookupService macLookupService = MacLookupService.getInstance();
    private final Semaphore connectionPermits;

    private volatile boolean cancelled;

    public NetworkScanner() {
        this(new OnvifService(), new RtspService());
    }

    public NetworkScanner(OnvifService onvifService, RtspService rtspService) {
        this.onvifService = onvifService;
        this.rtspService = rtspService;
        this.connectionPermits = new Semaphore(config.getPortScanConcurrency());
        logger.info("Scanner ready: {} concurrent connections", config.getPortScanConcurrency());
    }

    public void cancel() {
        cancelled = true;
    }

    private boolean stopped() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    // -------------------------------------------------------------- Discovery

    /** ONVIF devices announcing themselves by multicast. */
    /** Cameras on the chosen network that announce themselves, ONVIF WS-Discovery. */
    public OnvifService.Discovery performWsDiscovery(TargetParser.Targets targets) {
        if (!config.isWsDiscoveryEnabled()) {
            return OnvifService.Discovery.NONE;
        }
        OnvifService.Discovery discovery = onvifService.discoverDevices(targets);
        for (Device device : discovery.devices()) {
            resolveIdentity(device);
        }
        return discovery;
    }

    /** Scan a list of addresses. */
    public List<Device> performPortScan(List<String> ipAddresses, ProgressCallback callback) {
        return performPortScan(TargetParser.parse(String.join(",", ipAddresses)), callback);
    }

    /**
     * Scan every address in {@code targets}, returning the hosts with at least
     * one open port.
     */
    public List<Device> performPortScan(TargetParser.Targets targets, ProgressCallback callback) {
        long total = targets.count();
        if (total == 0) {
            return List.of();
        }
        if (total > config.getMaxTargets()) {
            throw new IllegalArgumentException("Refusing to scan " + total
                    + " addresses; the limit is " + config.getMaxTargets());
        }

        int[] httpPorts = config.getHttpPorts();
        int[] rtspPorts = config.getRtspPorts();
        int[] otherPorts = config.getOtherPorts();
        logger.info("Port scan of {} address(es); HTTP {}, RTSP {}, other {}",
                total, Arrays.toString(httpPorts), Arrays.toString(rtspPorts), Arrays.toString(otherPorts));

        primeNeighbourCache(targets);

        Map<String, Device> found = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger();
        int totalInt = (int) total;

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("port-scan"))) {
            for (String ip : targets) {
                if (stopped()) {
                    break;
                }
                scope.fork(() -> {
                    try {
                        Device device = scanHost(ip, httpPorts, rtspPorts, otherPorts);
                        if (device != null) {
                            found.put(ip, device);
                        }
                    } finally {
                        int done = completed.incrementAndGet();
                        if (callback != null) {
                            callback.onProgress(done, totalInt);
                        }
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        retryUnanswered(targets, total, found, httpPorts, rtspPorts, otherPorts);
        retryRtspPorts(found, rtspPorts);

        List<Device> devices = new ArrayList<>(found.values());
        devices.sort((a, b) -> Long.compare(
                NetworkUtils.ipToLong(a.getIpAddress()), NetworkUtils.ipToLong(b.getIpAddress())));
        logger.info("Port scan finished: {} host(s) responded", devices.size());
        return devices;
    }

    private static final int PRIME_PORT = 9;
    private static final int PRIME_BLOCK = 1024;
    private static final long PRIME_SETTLE_MS = 400;

    /**
     * Fill the computer's neighbour (ARP) cache for targets on a directly
     * attached subnet before the first pass.
     *
     * <p>Measured on 2026-10-10 against the test site: with the cache cleared,
     * a single pass found 0 of 7 devices, three times out of three; after one
     * datagram per address and a 0.25 s pause it found 7 of 7. Large ranges are
     * primed in blocks, each followed by a short pause.
     */
    private void primeNeighbourCache(TargetParser.Targets targets) {
        List<long[]> local = NetworkUtils.localSubnets();
        if (local.isEmpty()) {
            return;
        }
        int primed = 0;
        int inBlock = 0;
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            byte[] payload = new byte[1];
            for (String ip : targets) {
                if (stopped()) {
                    return;
                }
                if (!NetworkUtils.onSubnet(ip, local)) {
                    continue;
                }
                try {
                    socket.send(new java.net.DatagramPacket(payload, payload.length,
                            java.net.InetAddress.getByName(ip), PRIME_PORT));
                    primed++;
                    inBlock++;
                } catch (java.io.IOException ignored) {
                    // an address that cannot be sent to is simply not primed
                }
                if (inBlock == PRIME_BLOCK) {
                    pause();
                    inBlock = 0;
                }
            }
        } catch (java.net.SocketException e) {
            logger.debug("Could not prime the neighbour cache: {}", e.getMessage());
            return;
        }
        if (inBlock > 0) {
            pause();
        }
        if (primed > 0) {
            logger.info("Primed the neighbour cache for {} address(es)", primed);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(PRIME_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Above this many addresses a second pass costs more than it is likely to find. */
    private static final long RETRY_LIMIT = 4096;

    /**
     * Try once more every address where nothing answered.
     *
     * <p>Each port is connected to once, so a moment's loss drops a camera from
     * the survey without trace. On 2026-10-09 a Hikvision camera on Wi-Fi went
     * unanswered on all three of its open ports in one scan and answered
     * normally seconds later. The second pass runs after the first has finished,
     * which gives a brief drop time to clear. It costs a second sweep of the
     * dead addresses, so it is skipped for very large ranges.
     */
    private void retryUnanswered(TargetParser.Targets targets, long total, Map<String, Device> found,
                                 int[] httpPorts, int[] rtspPorts, int[] otherPorts) {
        if (stopped() || total > RETRY_LIMIT) {
            return;
        }
        List<String> unanswered = new ArrayList<>();
        for (String ip : targets) {
            if (!found.containsKey(ip)) {
                unanswered.add(ip);
            }
        }
        if (unanswered.isEmpty()) {
            return;
        }
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("port-scan-retry"))) {
            for (String ip : unanswered) {
                if (stopped()) {
                    break;
                }
                scope.fork(() -> {
                    Device device = scanHost(ip, httpPorts, rtspPorts, otherPorts);
                    if (device != null) {
                        found.put(ip, device);
                        logger.info("Host {} answered only on the second pass", ip);
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Give the RTSP ports one more chance on hosts that answered on other
     * ports but not on any RTSP port.
     *
     * <p>The retry above covers hosts where nothing answered, not hosts where
     * only the stream server was busy. On 2026-10-09 the NVR at 192.168.1.200
     * answered on 80 and 8000 but not 554 for one scan, was ruled "Not a camera
     * or recorder", and answered RTSP normally seconds later. Only hosts already
     * found are retried, and only their RTSP ports, so this costs little; no
     * credentials are sent.
     */
    private void retryRtspPorts(Map<String, Device> found, int[] rtspPorts) {
        if (stopped() || rtspPorts.length == 0) {
            return;
        }
        int connectTimeout = config.getSocketConnectTimeout();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("rtsp-retry"))) {
            for (Device device : found.values()) {
                if (!device.getOpenRtspPorts().isEmpty()) {
                    continue;
                }
                scope.fork(() -> {
                    for (int port : rtspPorts) {
                        if (stopped()) {
                            return null;
                        }
                        String ip = device.getIpAddress();
                        if (NetworkUtils.isPortOpen(ip, port, connectTimeout) && rtspService.isRtspServer(ip, port)) {
                            device.getOpenSpecialPorts().remove(Integer.valueOf(port));
                            device.getOpenRtspPorts().add(port);
                            logger.info("Host {} answered RTSP on {} only on the second attempt", ip, port);
                        }
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Scan one host; null when nothing answers. */
    private Device scanHost(String ip, int[] httpPorts, int[] rtspPorts, int[] otherPorts) {
        Map<Integer, String> openPorts = new ConcurrentHashMap<>();
        int connectTimeout = config.getSocketConnectTimeout();

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("ports-" + ip))) {
            forkPortChecks(scope, ip, httpPorts, "http", openPorts, connectTimeout);
            forkPortChecks(scope, ip, rtspPorts, "rtsp", openPorts, connectTimeout);
            forkPortChecks(scope, ip, otherPorts, "other", openPorts, connectTimeout);
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (openPorts.isEmpty() || stopped()) {
            return null;
        }

        Device device = new Device(ip);
        device.addDiscoverySource("Port scan");
        classifyPorts(device, openPorts);
        resolveIdentity(device);

        logger.info("Host {} open ports {} (ONVIF {}, RTSP {})", ip, device.getAllOpenPorts(),
                device.getOpenOnvifPorts(), device.getOpenRtspPorts());
        return device;
    }

    private void forkPortChecks(StructuredTaskScope<Void, Void> scope, String ip, int[] ports, String kind,
                                Map<Integer, String> openPorts, int connectTimeout) {
        for (int port : ports) {
            scope.fork(() -> {
                if (stopped()) {
                    return null;
                }
                connectionPermits.acquire();
                try {
                    if (NetworkUtils.isPortOpen(ip, port, connectTimeout)) {
                        openPorts.put(port, kind);
                    }
                } finally {
                    connectionPermits.release();
                }
                return null;
            });
        }
    }

    /**
     * Confirm what each open port speaks. An HTTP port counts as ONVIF only
     * when a device service answers, and an RTSP candidate only when an RTSP
     * server answers.
     */
    private void classifyPorts(Device device, Map<Integer, String> openPorts) {
        Map<Integer, String> sorted = new LinkedHashMap<>();
        openPorts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        for (Map.Entry<Integer, String> entry : sorted.entrySet()) {
            if (stopped()) {
                return;
            }
            int port = entry.getKey();
            switch (entry.getValue()) {
                case "http" -> {
                    device.getOpenHttpPorts().add(port);
                    Optional<String> serviceUrl = onvifService.findDeviceService(device.getIpAddress(), port);
                    if (serviceUrl.isPresent()) {
                        device.getOpenOnvifPorts().add(port);
                        if (device.getOnvifServiceUrl() == null) {
                            device.setOnvifServiceUrl(serviceUrl.get());
                        }
                        device.addDiscoverySource("ONVIF");
                    }
                }
                case "rtsp" -> {
                    if (rtspService.isRtspServer(device.getIpAddress(), port)) {
                        device.getOpenRtspPorts().add(port);
                    } else {
                        device.getOpenSpecialPorts().add(port);
                    }
                }
                default -> {
                    device.getOpenSpecialPorts().add(port);
                    applyVendorHint(device, port);
                }
            }
        }

        if (!device.getOpenRtspPorts().isEmpty() || !device.getOpenOnvifPorts().isEmpty()) {
            if (device.getType() == Device.DeviceType.UNKNOWN) {
                device.setType(Device.DeviceType.CAMERA);
            }
        }
    }

    /**
     * A vendor's management port is a strong hint about who made a device.
     *
     * <p>These protocols are proprietary and are not spoken here, but the port
     * being open still tells us the family, which selects the right RTSP path
     * templates when ARP and ONVIF have given us nothing.
     */
    public static String vendorForSdkPort(int port) {
        return switch (port) {
            case 37777, 37778 -> "Dahua";
            case 34567 -> "Xiongmai (XMeye)";
            case 8091, 8899 -> null; // too widely used to mean anything
            default -> null;
        };
    }

    private void applyVendorHint(Device device, int port) {
        String hint = vendorForSdkPort(port);
        if (hint == null) {
            return;
        }
        if (device.getManufacturer() == null || MacLookupService.UNKNOWN.equals(device.getManufacturer())) {
            device.setManufacturer(hint);
            logger.debug("{} has port {} open, so treating it as {}", device.getIpAddress(), port, hint);
        }
        if (device.getType() == Device.DeviceType.UNKNOWN) {
            device.setType(Device.DeviceType.CAMERA);
        }
    }

    /**
     * Fill in MAC and vendor. ARP works only inside the local broadcast domain,
     * so devices on another subnet are resolved later from ONVIF once
     * credentials are known.
     */
    private void resolveIdentity(Device device) {
        if (!config.isMacResolutionEnabled() || device.getMacAddress() != null) {
            return;
        }
        String mac = NetworkUtils.resolveMacAddress(device.getIpAddress());
        if (mac == null) {
            if (!NetworkUtils.isLocalSubnet(device.getIpAddress())) {
                logger.debug("{} is on another subnet; MAC needs ONVIF", device.getIpAddress());
            }
            return;
        }
        applyMac(device, mac);
    }

    /** Record a MAC and the vendor it belongs to. */
    public void applyMac(Device device, String mac) {
        String normalized = NetworkUtils.normalizeMac(mac);
        if (normalized == null || !NetworkUtils.isUnicastMac(normalized)) {
            return;
        }
        device.setMacAddress(normalized);
        String vendor = macLookupService.lookupManufacturer(normalized);
        device.setVendorFromMac(vendor);
        if (device.getManufacturer() == null && !MacLookupService.UNKNOWN.equals(vendor)) {
            device.setManufacturer(vendor);
        }
    }

    /**
     * Combine multicast and scan results, keeping one Device per address and
     * preferring the richer ONVIF information.
     */
    public List<Device> mergeDeviceLists(List<Device> wsDevices, List<Device> portScanDevices) {
        Map<String, Device> merged = new LinkedHashMap<>();
        for (Device device : wsDevices) {
            merged.put(device.getIpAddress(), device);
        }
        for (Device scanned : portScanDevices) {
            Device existing = merged.get(scanned.getIpAddress());
            if (existing == null) {
                merged.put(scanned.getIpAddress(), scanned);
                continue;
            }
            mergeInto(existing, scanned);
        }
        List<Device> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Long.compare(
                NetworkUtils.ipToLong(a.getIpAddress()), NetworkUtils.ipToLong(b.getIpAddress())));
        logger.info("Merged to {} device(s)", result.size());
        return result;
    }

    private void mergeInto(Device target, Device source) {
        addMissing(target.getOpenOnvifPorts(), source.getOpenOnvifPorts());
        addMissing(target.getOpenHttpPorts(), source.getOpenHttpPorts());
        addMissing(target.getOpenRtspPorts(), source.getOpenRtspPorts());
        addMissing(target.getOpenSpecialPorts(), source.getOpenSpecialPorts());

        if (target.getMacAddress() == null && source.getMacAddress() != null) {
            target.setMacAddress(source.getMacAddress());
            target.setVendorFromMac(source.getVendorFromMac());
        }
        if (target.getManufacturer() == null) {
            target.setManufacturer(source.getManufacturer());
        }
        if (target.getOnvifServiceUrl() == null) {
            target.setOnvifServiceUrl(source.getOnvifServiceUrl());
        }
        if (source.getDiscoverySource() != null) {
            target.addDiscoverySource(source.getDiscoverySource());
        }
    }

    private static void addMissing(List<Integer> target, List<Integer> source) {
        for (Integer value : source) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    /** Window used for WS-Discovery, exposed for progress estimates. */
    public Duration discoveryWindow() {
        return Duration.ofMillis(config.getOnvifTimeout());
    }

    public void shutdown() {
        cancel();
    }

    @Override
    public void close() {
        shutdown();
    }
}
