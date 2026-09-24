package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.util.RtspClient;
import com.cctv.discovery.util.TargetParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a survey: announce, scan, identify, measure.
 *
 * <p>This used to live in {@code MainController}, tangled with the window it
 * was reporting to. Nothing here touches JavaFX, so the decisions it makes —
 * which devices count as cameras, when a missing stream means a rejected
 * password rather than an unknown address, what to raise a finding about — can
 * be tested directly instead of only through a running application.
 *
 * <p>Progress is reported through {@link Listener}. Every call arrives on a
 * background thread, so an implementation that touches the user interface has
 * to hop to the right thread itself; that is the caller's business, not this
 * class's.
 */
public final class ScanCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ScanCoordinator.class);

    /** What a caller hears while a scan runs. All of it off the main thread. */
    public interface Listener {

        /** @param fraction 0 to 1; {@code message} is shown to the operator */
        void onProgress(double fraction, String message);

        /** Devices that have just appeared and are not in the list yet. */
        void onDevicesFound(List<Device> devices);

        /** The complete list, replacing whatever was shown before. */
        void onDeviceList(List<Device> devices);

        /** One device's details have changed. */
        void onDeviceChanged(Device device);

        /** The scan has stopped, either because it finished or because it was cancelled. */
        void onFinished(long seconds, boolean cancelled);
    }

    private final NetworkScanner networkScanner;
    private final OnvifService onvifService;
    private final RtspService rtspService;
    private final StreamAnalyzer streamAnalyzer;
    private final AppConfig config;

    private volatile boolean cancelled;

    public ScanCoordinator(NetworkScanner networkScanner, OnvifService onvifService,
                           RtspService rtspService, StreamAnalyzer streamAnalyzer, AppConfig config) {
        this.networkScanner = networkScanner;
        this.onvifService = onvifService;
        this.rtspService = rtspService;
        this.streamAnalyzer = streamAnalyzer;
        this.config = config;
    }

    /** Ask every running stage to stop. Whatever has been found so far is kept. */
    public void cancel() {
        cancelled = true;
        networkScanner.cancel();
        rtspService.shutdown();
        streamAnalyzer.cancel();
        logger.info("Scan cancelled by the user");
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Release the thread pools the services hold. */
    public void shutdown() {
        rtspService.shutdown();
        networkScanner.shutdown();
        streamAnalyzer.shutdown();
    }

    /** Apply the operator's choice of how thoroughly to confirm each stream. */
    public void configureValidation(String method) {
        try {
            RtspService.RtspValidationMethod validation = switch (method == null ? "" : method) {
                case "SDP_ONLY" -> RtspService.RtspValidationMethod.SDP_ONLY;
                case "RTP_PACKET" -> RtspService.RtspValidationMethod.RTP_PACKET;
                default -> RtspService.RtspValidationMethod.FRAME_CAPTURE;
            };
            int customTimeout = config.getRtspValidationTimeout();

            RtspService.RtspDiscoveryConfig discoveryConfig = new RtspService.RtspDiscoveryConfig();
            discoveryConfig.setValidationMethod(validation);
            discoveryConfig.setCustomTimeout(customTimeout);
            RtspService.setDiscoveryConfig(discoveryConfig);

            logger.info("RTSP validation configured: method={}, timeout={}ms (0=default)",
                    validation, customTimeout);
        } catch (Exception e) {
            logger.error("Error configuring RTSP validation, using defaults", e);
        }
    }

    /**
     * Run a whole survey. Blocks until it is done or cancelled, so call it from
     * a background thread.
     *
     * <p>Devices appear as they are found and identification runs several at a
     * time, rather than collecting everything first and then working through
     * the list one at a time.
     */
    public void run(TargetParser.Targets targets, List<Credential> credentials, Listener listener) {
        cancelled = false;
        rtspService.reset();
        long started = System.currentTimeMillis();

        listener.onProgress(0.02, "Listening for cameras that announce themselves...");
        List<Device> announced = networkScanner.performWsDiscovery();
        if (!announced.isEmpty()) {
            listener.onDevicesFound(announced);
            listener.onProgress(0.08, "Found " + announced.size() + " device(s) by announcement.");
        }
        if (cancelled) {
            finish(listener, started);
            return;
        }

        List<Device> scanned = List.of();
        if (!targets.isEmpty()) {
            long total = targets.count();
            listener.onProgress(0.1, "Scanning " + total + " address(es)...");
            scanned = networkScanner.performPortScan(targets, (current, count) -> {
                if (current % 16 == 0 || current == count) {
                    listener.onProgress(0.1 + 0.35 * current / count,
                            "Scanning address " + current + " of " + count);
                }
            });
        }

        List<Device> all = networkScanner.mergeDeviceLists(announced, scanned);
        listener.onDeviceList(all);

        if (all.isEmpty() || cancelled) {
            finish(listener, started);
            return;
        }

        identifyDevices(all, credentials, listener);
        if (!cancelled) {
            measureStreams(all, listener);
        }
        finish(listener, started);
    }

    private void finish(Listener listener, long started) {
        // Keep what this run learned about stream paths for the next survey.
        rtspService.saveLearnedPaths();
        listener.onFinished((System.currentTimeMillis() - started) / 1000, cancelled);
    }

    /** Identify devices in parallel, bounded by the configured fan-out. */
    private void identifyDevices(List<Device> all, List<Credential> credentials, Listener listener) {
        AtomicInteger done = new AtomicInteger();
        Semaphore permits = new Semaphore(config.getDeviceParallelism());

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("identify"))) {
            for (Device device : all) {
                scope.fork(() -> {
                    if (cancelled) {
                        return null;
                    }
                    permits.acquire();
                    try {
                        identify(device, credentials, listener);
                    } catch (Exception e) {
                        logger.warn("Could not identify {}: {}", device.getIpAddress(), e.toString());
                        device.setStatus(Device.DeviceStatus.ERROR);
                        device.setErrorMessage(String.valueOf(e.getMessage()));
                    } finally {
                        permits.release();
                    }
                    int count = done.incrementAndGet();
                    listener.onProgress(0.45 + 0.35 * count / all.size(),
                            "Identified " + count + " of " + all.size() + " devices");
                    listener.onDeviceChanged(device);
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Measure the streams of every device that has any. */
    private void measureStreams(List<Device> all, Listener listener) {
        List<Device> withStreams = all.stream().filter(d -> !d.getRtspStreams().isEmpty()).toList();
        if (withStreams.isEmpty()) {
            return;
        }
        int index = 0;
        for (Device device : withStreams) {
            if (cancelled) {
                return;
            }
            device.setStatus(Device.DeviceStatus.ANALYZING);
            listener.onDeviceChanged(device);
            streamAnalyzer.analyzeDevice(device);
            device.setStatus(device.getRtspStreams().isEmpty()
                    ? Device.DeviceStatus.AUTH_FAILED : Device.DeviceStatus.COMPLETED);
            index++;
            listener.onProgress(0.8 + 0.2 * index / withStreams.size(),
                    "Measured " + index + " of " + withStreams.size() + " devices");
            listener.onDeviceChanged(device);
        }
    }

    /**
     * Identify one device and collect its streams: ONVIF first, because it
     * publishes the real stream URLs, then RTSP path probing only when ONVIF
     * gave us nothing.
     */
    public void identify(Device device, List<Credential> credentials, Listener listener) {
        logger.info("Identifying {}", device.getIpAddress());
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        listener.onDeviceChanged(device);

        boolean onvifAuthenticated = authenticateOnvif(device, credentials);

        if (onvifAuthenticated) {
            int sources = onvifService.getVideoSourceCount(device);
            device.setVideoSourceCount(sources);
            onvifService.getHostname(device);
            device.setType(onvifService.classifyType(device, sources));
            if (device.getMacAddress() == null) {
                onvifService.getMacAddress(device).ifPresent(mac -> networkScanner.applyMac(device, mac));
            }
            for (RTSPStream stream : onvifService.getStreamUris(device)) {
                device.addStream(stream);
            }
        }

        boolean rtspChallenged = false;
        if (device.getRtspStreams().isEmpty() && !device.getOpenRtspPorts().isEmpty()) {
            logger.info("No ONVIF stream URLs for {}; probing RTSP paths", device.getIpAddress());
            rtspChallenged = discoverStreamsByPath(device, credentials);
        }

        if (device.isNvrDvr() && device.getUsername() != null) {
            for (RTSPStream stream : rtspService.iterateNvrChannels(
                    device, device.getUsername(), device.getPassword(), config.getNvrMaxChannels())) {
                device.addStream(stream);
            }
        }

        checkAnonymousAccess(device);
        recordClockFinding(device);
        finalizeStatus(device, onvifAuthenticated, rtspChallenged);
    }

    /**
     * Try each credential against the device's ONVIF service, discovering the
     * service address first when the port scan has not already found one.
     */
    private boolean authenticateOnvif(Device device, List<Credential> credentials) {
        List<String> serviceUrls = new ArrayList<>();
        if (device.getOnvifServiceUrl() != null) {
            serviceUrls.add(device.getOnvifServiceUrl());
        }
        for (int port : device.getOpenHttpPorts()) {
            onvifService.findDeviceService(device.getIpAddress(), port)
                    .filter(url -> !serviceUrls.contains(url))
                    .ifPresent(serviceUrls::add);
        }
        if (serviceUrls.isEmpty()) {
            return false;
        }

        for (String serviceUrl : serviceUrls) {
            for (Credential credential : credentials) {
                if (onvifService.getDeviceInformation(device, serviceUrl,
                        credential.getUsername(), credential.getPassword())) {
                    logger.info("ONVIF accepted a credential on {}", serviceUrl);
                    return true;
                }
            }
        }
        logger.info("No credential accepted by ONVIF on {}", device.getIpAddress());
        return false;
    }

    /**
     * Probe RTSP paths with each credential.
     *
     * @return true when an RTSP server asked for credentials, which separates a
     *         wrong password from an unknown stream path
     */
    private boolean discoverStreamsByPath(Device device, List<Credential> credentials) {
        for (Credential credential : credentials) {
            List<RTSPStream> streams = rtspService.discoverStreams(
                    device, credential.getUsername(), credential.getPassword());
            if (!streams.isEmpty()) {
                if (device.getUsername() == null) {
                    device.setUsername(credential.getUsername());
                    device.setPassword(credential.getPassword());
                }
                for (RTSPStream stream : streams) {
                    device.addStream(stream);
                    if (device.getDeviceName() == null && stream.getSdpSessionName() != null) {
                        device.setDeviceName(stream.getSdpSessionName());
                    }
                }
                return false;
            }
        }
        for (int port : device.getOpenRtspPorts()) {
            RtspService.ProbeResult probe = rtspService.probe(
                    RtspClient.url(device.getIpAddress(), port, "/"), null, null);
            if (probe.authRequired()) {
                return true;
            }
        }
        return false;
    }

    /** Record whether the video is readable with no credentials at all. */
    private void checkAnonymousAccess(Device device) {
        if (device.getRtspStreams().isEmpty()) {
            return;
        }
        RTSPStream first = device.getRtspStreams().getFirst();
        RtspService.ProbeResult probe = rtspService.probe(first.getRtspUrl(), null, null);
        device.setRtspAnonymousAccess(probe.valid() && probe.anonymous());
        if (Boolean.TRUE.equals(device.getRtspAnonymousAccess())) {
            device.addFinding(new Finding(Finding.Severity.HIGH, "Security",
                    "Video stream readable without a password",
                    "RTSP DESCRIBE succeeded on " + RtspClient.stripCredentials(first.getRtspUrl())
                            + " with no credentials supplied.",
                    "Enable RTSP authentication on the device so the live feed cannot be viewed by anyone "
                            + "who can reach it on the network."));
            logger.warn("{} serves RTSP without authentication", device.getIpAddress());
        }
    }

    /** Flag a device clock that disagrees with this computer. */
    void recordClockFinding(Device device) {
        Long drift = device.getTimeDifferenceSeconds();
        if (drift == null || Math.abs(drift) <= config.getMaxTimeDriftSeconds()) {
            return;
        }
        device.addFinding(new Finding(Finding.Severity.MEDIUM, "Configuration",
                "Device clock is out of step",
                "The device clock differs from this computer by " + drift + " seconds.",
                "Point the device at an NTP server so recordings carry accurate timestamps."));
    }

    /** Set the final row status and, when there are no streams, say why. */
    static void finalizeStatus(Device device, boolean onvifAuthenticated, boolean rtspChallenged) {
        if (!device.getRtspStreams().isEmpty()) {
            device.setStatus(Device.DeviceStatus.COMPLETED);
            device.setAuthFailed(false);
            device.setErrorMessage(null);
            logger.info("{} completed with {} stream(s)", device.getIpAddress(), device.getRtspStreams().size());
            return;
        }

        boolean videoDevice = onvifAuthenticated
                || !device.getOpenOnvifPorts().isEmpty()
                || !device.getOpenRtspPorts().isEmpty();

        device.setStatus(Device.DeviceStatus.AUTH_FAILED);
        if (!videoDevice) {
            device.setAuthFailed(false);
            device.setType(Device.DeviceType.UNKNOWN);
            device.setErrorMessage("Not a camera or recorder");
        } else if (rtspChallenged || (!onvifAuthenticated && !device.getOpenOnvifPorts().isEmpty())) {
            device.setAuthFailed(true);
            device.setErrorMessage("No credential was accepted");
            device.addFinding(new Finding(Finding.Severity.INFO, "Access", "Could not sign in",
                    "The device rejected every credential supplied.",
                    "Add the correct credentials and retry this device from its context menu."));
        } else {
            device.setAuthFailed(false);
            device.setErrorMessage("No stream path matched");
            device.addFinding(new Finding(Finding.Severity.INFO, "Access", "Stream address unknown",
                    "Credentials were accepted but no known RTSP path returned video.",
                    "Add this model's stream path under Settings, RTSP paths."));
        }

        // A device reachable only over its maker's own protocol is worth
        // calling out, because no amount of RTSP probing will reach it.
        for (int port : device.getOpenSpecialPorts()) {
            String vendor = NetworkScanner.vendorForSdkPort(port);
            if (vendor != null) {
                device.addFinding(new Finding(Finding.Severity.INFO, "Access",
                        "Reachable only over the manufacturer's own protocol",
                        vendor + " management port " + port + " is open, but no ONVIF or RTSP service answered.",
                        "Enable ONVIF and RTSP on the device, or record it from " + vendor
                                + "'s own software; this tool speaks ONVIF and RTSP only."));
                break;
            }
        }
    }
}
