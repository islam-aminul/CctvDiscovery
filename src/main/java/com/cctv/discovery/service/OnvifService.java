package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.AuthUtils;
import com.cctv.discovery.util.NetworkUtils;
import com.cctv.discovery.util.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/**
 * ONVIF client: WS-Discovery, device information, capabilities and media
 * profiles.
 *
 * <p>All XML is parsed with {@link XmlUtils}, which refuses DOCTYPE
 * declarations and external entities, because every response here comes from an
 * untrusted host on the network. TLS verification is relaxed only for this
 * client's own connections, since cameras ship self-signed certificates; the
 * JVM-wide defaults are left untouched.
 */
public final class OnvifService {

    private static final Logger logger = LoggerFactory.getLogger(OnvifService.class);

    private static final String WS_DISCOVERY_ADDRESS = "239.255.255.250";
    private static final int WS_DISCOVERY_PORT = 3702;

    private static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    private static final String NS_MEDIA2 = "http://www.onvif.org/ver20/media/wsdl";
    private static final String NS_SCHEMA = "http://www.onvif.org/ver10/schema";

    private final AppConfig config = AppConfig.getInstance();
    private final HttpClient httpClient;

    /** Device clock minus host clock, per device service URL. */
    private final Map<String, Long> clockOffsets = new ConcurrentHashMap<>();

    public OnvifService() {
        this.httpClient = buildHttpClient(config.getSocketConnectTimeout());
    }

    private static HttpClient buildHttpClient(int connectTimeoutMs) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)));
        try {
            // Cameras use self-signed certificates with IP-address subjects. This
            // relaxation is confined to this HttpClient instance.
            TrustManager[] acceptAll = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // discovery client does not authenticate peers
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // discovery client does not authenticate peers
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, acceptAll, new java.security.SecureRandom());
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm(null);
            builder.sslContext(sslContext).sslParameters(params);
        } catch (Exception e) {
            logger.warn("Could not relax TLS verification for ONVIF; HTTPS cameras may fail: {}", e.getMessage());
        }
        return builder.build();
    }

    // ------------------------------------------------------------ WS-Discovery

    /**
     * Multicast Probe on every IPv4-capable interface, collecting replies for
     * {@link AppConfig#getOnvifTimeout()} milliseconds.
     *
     * <p>Sending per interface matters on machines with several networks (Wi-Fi
     * plus a wired camera VLAN), where a probe on the default route alone
     * reaches nothing.
     */
    public List<Device> discoverDevices() {
        List<java.net.NetworkInterface> interfaces = NetworkUtils.getMulticastInterfaces();
        if (interfaces.isEmpty()) {
            logger.info("No multicast-capable interfaces; skipping WS-Discovery");
            return List.of();
        }

        Map<String, Device> byKey = new ConcurrentHashMap<>();
        Duration window = Duration.ofMillis(config.getOnvifTimeout());

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("ws-discovery").withTimeout(window.plusMillis(500)))) {

            for (java.net.NetworkInterface ni : interfaces) {
                scope.fork(() -> {
                    probeInterface(ni, window, byKey);
                    return null;
                });
            }
            scope.join();
        } catch (StructuredTaskScope.TimeoutException e) {
            logger.debug("WS-Discovery window elapsed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<Device> devices = new ArrayList<>(byKey.values());
        logger.info("WS-Discovery found {} device(s) on {} interface(s)", devices.size(), interfaces.size());
        return devices;
    }

    private void probeInterface(java.net.NetworkInterface ni, Duration window, Map<String, Device> byKey) {
        // Binding to the interface's own address makes the OS send the multicast
        // out of that interface, which a socket on the wildcard address would
        // send only over the default route.
        InetAddress bindAddress = ni.getInterfaceAddresses().stream()
                .map(java.net.InterfaceAddress::getAddress)
                .filter(a -> a instanceof java.net.Inet4Address && !a.isLinkLocalAddress())
                .findFirst().orElse(null);
        if (bindAddress == null) {
            return;
        }

        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(bindAddress, 0))) {
            socket.setSoTimeout(250);

            byte[] probe = buildProbe().getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(WS_DISCOVERY_ADDRESS);
            socket.send(new DatagramPacket(probe, probe.length, group, WS_DISCOVERY_PORT));
            logger.debug("WS-Discovery probe sent on {}", ni.getName());

            byte[] buffer = new byte[16 * 1024];
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String xml = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    parseProbeMatch(xml, packet.getAddress().getHostAddress())
                            .ifPresent(d -> byKey.merge(discoveryKey(d), d, OnvifService::mergeDiscovered));
                } catch (java.net.SocketTimeoutException e) {
                    // keep listening until the window closes
                } catch (Exception e) {
                    logger.debug("Malformed WS-Discovery reply on {}: {}", ni.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.debug("WS-Discovery failed on {}: {}", ni.getName(), e.getMessage());
        }
    }

    private static String discoveryKey(Device device) {
        return device.getIpAddress();
    }

    private static Device mergeDiscovered(Device existing, Device candidate) {
        if (existing.getOnvifServiceUrl() == null) {
            existing.setOnvifServiceUrl(candidate.getOnvifServiceUrl());
        }
        if (existing.getDeviceName() == null) {
            existing.setDeviceName(candidate.getDeviceName());
        }
        if (existing.getManufacturer() == null) {
            existing.setManufacturer(candidate.getManufacturer());
        }
        return existing;
    }

    private String buildProbe() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="%s" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"\
                 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"\
                 xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
                <s:Header>
                <a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
                <a:MessageID>%s</a:MessageID>
                <a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
                </s:Header>
                <s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body>
                </s:Envelope>""".formatted(NS_SOAP, AuthUtils.uuidUrn());
    }

    /**
     * Build a Device from a ProbeMatch.
     *
     * <p>No MAC address is derived from the endpoint UUID: many vendors put a
     * random value there. On the test camera that produced a multicast address
     * that is not a device MAC at all, which then suppressed the real ARP lookup.
     */
    Optional<Device> parseProbeMatch(String xml, String sourceAddress) {
        try {
            Document doc = XmlUtils.parse(xml);
            String xAddrs = XmlUtils.text(doc, "XAddrs");
            if (xAddrs == null || xAddrs.isBlank()) {
                return Optional.empty();
            }

            String serviceUrl = null;
            String ip = null;
            for (String candidate : xAddrs.trim().split("\\s+")) {
                String hostAddress = hostOf(candidate);
                if (hostAddress != null && NetworkUtils.isValidIP(hostAddress)) {
                    serviceUrl = candidate;
                    ip = hostAddress;
                    break;
                }
            }
            if (ip == null) {
                // Hostname-only XAddrs: fall back to the datagram source.
                if (!NetworkUtils.isValidIP(sourceAddress)) {
                    return Optional.empty();
                }
                ip = sourceAddress;
                serviceUrl = xAddrs.trim().split("\\s+")[0];
            }

            Device device = new Device(ip);
            device.setOnvifServiceUrl(serviceUrl);
            device.setType(Device.DeviceType.CAMERA);
            device.addDiscoverySource("WS-Discovery");
            applyScopes(device, XmlUtils.text(doc, "Scopes"));
            return Optional.of(device);
        } catch (Exception e) {
            logger.debug("Cannot parse ProbeMatch from {}: {}", sourceAddress, e.getMessage());
            return Optional.empty();
        }
    }

    /** Read name/hardware/location hints from the ONVIF scope list. */
    private void applyScopes(Device device, String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return;
        }
        for (String scope : scopes.trim().split("\\s+")) {
            String value = scope.substring(scope.lastIndexOf('/') + 1);
            if (value.isEmpty()) {
                continue;
            }
            String decoded = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (scope.contains("/name/") && device.getDeviceName() == null) {
                device.setDeviceName(decoded);
            } else if (scope.contains("/hardware/") && device.getHardwareId() == null) {
                device.setHardwareId(decoded);
            } else if (scope.contains("/type/") && decoded.toLowerCase(Locale.ROOT).contains("networkvideostorage")) {
                device.setType(Device.DeviceType.RECORDER);
            }
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --------------------------------------------------------------- Requests

    /** Outcome of one SOAP exchange. */
    private record SoapResult(int status, String body, List<String> authenticateHeaders) {
        boolean ok() {
            return status == 200;
        }

        boolean unauthorized() {
            return status == 401;
        }
    }

    /**
     * POST a SOAP body. When credentials are supplied the request carries a
     * WS-Security UsernameToken; if the device answers 401 with an HTTP
     * challenge instead, the request is retried with HTTP Digest or Basic, which
     * is what several recorders require.
     */
    private SoapResult post(String serviceUrl, String bodyXml, String username, String password) {
        long offset = clockOffsets.getOrDefault(serviceUrl, 0L);
        String envelope = envelope(bodyXml, username, password, offset);
        SoapResult first = send(serviceUrl, envelope, null);
        if (!first.unauthorized() || username == null || username.isEmpty()) {
            return first;
        }

        List<AuthUtils.AuthChallenge> challenges = AuthUtils.parseChallenges(first.authenticateHeaders());
        if (challenges.isEmpty()) {
            return first;
        }
        AuthUtils.Authenticator authenticator = new AuthUtils.Authenticator(challenges.getFirst(), username, password);
        logger.debug("ONVIF {} requires HTTP authentication ({})", serviceUrl, challenges.getFirst());
        String uri = pathOf(serviceUrl);
        return send(serviceUrl, envelope, authenticator.authorization("POST", uri));
    }

    private SoapResult send(String serviceUrl, String envelope, String authorization) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceUrl))
                    .timeout(Duration.ofMillis(config.getSocketReadTimeout()))
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .header("User-Agent", "CCTV-Discovery/2.0")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logger.isTraceEnabled()) {
                logger.trace("ONVIF {} -> {} body:\n{}", serviceUrl, response.statusCode(),
                        AuthUtils.redact(response.body()));
            }
            return new SoapResult(response.statusCode(), response.body(),
                    response.headers().allValues("WWW-Authenticate"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SoapResult(-1, "", List.of());
        } catch (Exception e) {
            logger.debug("ONVIF request to {} failed: {}", serviceUrl, e.getMessage());
            return new SoapResult(-1, "", List.of());
        }
    }

    private static String pathOf(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (RuntimeException e) {
            return "/";
        }
    }

    private String envelope(String bodyXml, String username, String password, long clockOffsetMillis) {
        String header = username == null || username.isEmpty()
                ? ""
                : "<s:Header>" + AuthUtils.wsSecurityHeader(username, password, clockOffsetMillis) + "</s:Header>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"" + NS_SOAP + "\""
                + " xmlns:tds=\"" + NS_DEVICE + "\""
                + " xmlns:trt=\"" + NS_MEDIA + "\""
                + " xmlns:tr2=\"" + NS_MEDIA2 + "\""
                + " xmlns:tt=\"" + NS_SCHEMA + "\">"
                + header
                + "<s:Body>" + bodyXml + "</s:Body></s:Envelope>";
    }

    /** True when the SOAP body is a Fault reporting an authentication failure. */
    private static boolean isAuthFault(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("notauthorized") || lower.contains("failedauthentication")
                || lower.contains("sender not authorized");
    }

    // ---------------------------------------------------------------- Queries

    /**
     * Unauthenticated GetSystemDateAndTime. Every ONVIF device must answer this
     * without credentials; it gives the device clock (needed for the audit and
     * for WS-Security tokens that cameras with a skewed clock will accept).
     *
     * @return device clock minus host clock in milliseconds, or empty
     */
    public Optional<Long> getSystemClockOffset(String serviceUrl) {
        SoapResult result = post(serviceUrl, "<tds:GetSystemDateAndTime/>", null, null);
        if (!result.ok()) {
            return Optional.empty();
        }
        try {
            Document doc = XmlUtils.parse(result.body());
            List<Element> utc = XmlUtils.elementList(doc, "UTCDateTime");
            if (utc.isEmpty()) {
                return Optional.empty();
            }
            Element time = (Element) XmlUtils.elements(utc.getFirst(), "Time").item(0);
            Element date = (Element) XmlUtils.elements(utc.getFirst(), "Date").item(0);
            if (time == null || date == null) {
                return Optional.empty();
            }
            LocalDateTime deviceTime = LocalDateTime.of(
                    intOf(date, "Year"), intOf(date, "Month"), intOf(date, "Day"),
                    intOf(time, "Hour"), intOf(time, "Minute"), intOf(time, "Second"));
            long deviceMillis = deviceTime.toInstant(ZoneOffset.UTC).toEpochMilli();
            long offset = deviceMillis - System.currentTimeMillis();
            clockOffsets.put(serviceUrl, offset);
            return Optional.of(offset);
        } catch (Exception e) {
            logger.debug("Cannot read device clock from {}: {}", serviceUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private static int intOf(Element parent, String name) {
        String value = XmlUtils.text(parent, name);
        return value == null ? 0 : Integer.parseInt(value.trim());
    }

    /**
     * Authenticate against the device service and fill identity fields.
     *
     * @return true when the device accepted the credentials
     */
    public boolean getDeviceInformation(Device device, String serviceUrl, String username, String password) {
        // Align the WS-Security timestamp with the device clock before authenticating.
        clockOffsets.computeIfAbsent(serviceUrl, url -> getSystemClockOffset(url).orElse(0L));

        SoapResult result = post(serviceUrl, "<tds:GetDeviceInformation/>", username, password);
        if (!result.ok() || isAuthFault(result.body())) {
            logger.debug("GetDeviceInformation rejected by {} (status {})", serviceUrl, result.status());
            return false;
        }
        try {
            Document doc = XmlUtils.parse(result.body());
            String manufacturer = XmlUtils.text(doc, "Manufacturer");
            String model = XmlUtils.text(doc, "Model");
            String firmware = XmlUtils.text(doc, "FirmwareVersion");
            String serial = XmlUtils.text(doc, "SerialNumber");
            String hardwareId = XmlUtils.text(doc, "HardwareId");

            if (manufacturer != null && !manufacturer.isBlank()) {
                device.setManufacturer(MacLookupService.getInstance().brandFor(manufacturer));
            }
            if (model != null && !model.isBlank()) {
                device.setModel(model);
            }
            if (firmware != null && !firmware.isBlank()) {
                device.setFirmwareVersion(firmware);
            }
            if (serial != null && !serial.isBlank()) {
                device.setSerialNumber(serial);
            }
            if (hardwareId != null && !hardwareId.isBlank()) {
                device.setHardwareId(hardwareId);
            }
            device.setOnvifServiceUrl(serviceUrl);
            device.setUsername(username);
            device.setPassword(password);
            device.setOnvifAuthMethod(Device.OnvifAuthMethod.WS_SECURITY);
            device.addDiscoverySource("ONVIF");

            long offset = clockOffsets.getOrDefault(serviceUrl, 0L);
            device.setTimeDifferenceSeconds(Math.round(offset / 1000.0));

            logger.info("ONVIF identified {}: {} {} (firmware {})", device.getIpAddress(),
                    device.getManufacturer(), device.getModel(), device.getFirmwareVersion());
            return true;
        } catch (Exception e) {
            logger.debug("Cannot parse GetDeviceInformation from {}: {}", serviceUrl, e.getMessage());
            return false;
        }
    }

    /** Candidate device service URLs for a port, http or https as appropriate. */
    public static List<String> serviceUrlsFor(String ip, int port) {
        String scheme = (port == 443 || port == 8443) ? "https" : "http";
        String base = scheme + "://" + ip + ":" + port;
        return List.of(base + "/onvif/device_service", base + "/onvif/services", base + "/onvif/device");
    }

    /**
     * Probe one HTTP port for an ONVIF device service without credentials.
     * GetSystemDateAndTime must be answered unauthenticated, so a well-formed
     * reply confirms ONVIF even when the password is unknown.
     *
     * @return the working device service URL, or empty
     */
    public Optional<String> findDeviceService(String ip, int port) {
        for (String url : serviceUrlsFor(ip, port)) {
            SoapResult result = post(url, "<tds:GetSystemDateAndTime/>", null, null);
            if (result.ok() && result.body().contains("GetSystemDateAndTimeResponse")) {
                logger.debug("ONVIF device service at {}", url);
                return Optional.of(url);
            }
            if (result.unauthorized() || isAuthFault(result.body())) {
                // Answering with a challenge still proves an ONVIF endpoint exists.
                logger.debug("ONVIF device service at {} (requires authentication)", url);
                return Optional.of(url);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the media service address via GetCapabilities.
     *
     * <p>Media requests must go to this address: the test camera answers
     * {@code ActionNotSupported} when GetProfiles is sent to the device service.
     */
    public String resolveMediaUrl(Device device) {
        if (device.getOnvifMediaUrl() != null) {
            return device.getOnvifMediaUrl();
        }
        String serviceUrl = device.getOnvifServiceUrl();
        SoapResult result = post(serviceUrl,
                "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>",
                device.getUsername(), device.getPassword());
        if (result.ok()) {
            try {
                Document doc = XmlUtils.parse(result.body());
                for (Element media : XmlUtils.elementList(doc, "Media")) {
                    String xAddr = XmlUtils.text(media, "XAddr");
                    if (xAddr != null && !xAddr.isBlank()) {
                        String resolved = rehost(xAddr.trim(), device.getIpAddress());
                        device.setOnvifMediaUrl(resolved);
                        logger.debug("ONVIF media service for {}: {}", device.getIpAddress(), resolved);
                        return resolved;
                    }
                }
            } catch (Exception e) {
                logger.debug("Cannot parse GetCapabilities from {}: {}", serviceUrl, e.getMessage());
            }
        }
        device.setOnvifMediaUrl(serviceUrl);
        return serviceUrl;
    }

    /**
     * Replace the host in a device-advertised URL with the address we reached it
     * on. Cameras behind NAT or with a stale static address often advertise an
     * unreachable host here.
     */
    private static String rehost(String url, String reachableIp) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.equals(reachableIp)) {
                return url;
            }
            int port = uri.getPort();
            return new URI(uri.getScheme(), null, reachableIp, port, uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Decide whether a device is a recorder or a single camera.
     *
     * <p>Counting video sources alone is not enough: the CP Plus test camera
     * publishes one "video source" per encoder profile, so a plain camera
     * reports two. Recorders are recognised by a larger channel count, by an
     * ONVIF scope that names storage, or by the model name.
     */
    public Device.DeviceType classifyType(Device device, int videoSourceCount) {
        if (device.getType() == Device.DeviceType.RECORDER) {
            return Device.DeviceType.RECORDER; // already established from scopes
        }
        String text = ((device.getModel() == null ? "" : device.getModel()) + " "
                + (device.getDeviceName() == null ? "" : device.getDeviceName())).toUpperCase(Locale.ROOT);
        if (text.contains("NVR") || text.contains("DVR") || text.contains("XVR") || text.contains("RECORDER")) {
            return Device.DeviceType.RECORDER;
        }
        if (videoSourceCount >= 4) {
            return Device.DeviceType.RECORDER;
        }
        return videoSourceCount >= 1 ? Device.DeviceType.CAMERA : device.getType();
    }

    /** Number of video sources the media service reports. */
    public int getVideoSourceCount(Device device) {
        String mediaUrl = resolveMediaUrl(device);
        SoapResult result = post(mediaUrl, "<trt:GetVideoSources/>", device.getUsername(), device.getPassword());
        if (!result.ok()) {
            return 0;
        }
        try {
            Document doc = XmlUtils.parse(result.body());
            int count = XmlUtils.elementList(doc, "VideoSources").size();
            logger.debug("{} reports {} video source(s)", device.getIpAddress(), count);
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** A media profile token and its display name. */
    private record Profile(String token, String name) {
    }

    /**
     * Authoritative stream URLs via GetProfiles then GetStreamUri, sent to the
     * media service. When these succeed the caller must not guess RTSP paths.
     */
    public List<RTSPStream> getStreamUris(Device device) {
        String mediaUrl = resolveMediaUrl(device);
        SoapResult profilesResult = post(mediaUrl, "<trt:GetProfiles/>", device.getUsername(), device.getPassword());
        if (!profilesResult.ok()) {
            logger.debug("GetProfiles failed for {} (status {})", device.getIpAddress(), profilesResult.status());
            return List.of();
        }

        List<Profile> profiles = parseProfiles(profilesResult.body());
        if (profiles.isEmpty()) {
            return List.of();
        }

        List<RTSPStream> streams = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Profile profile : profiles) {
            String body = "<trt:GetStreamUri>"
                    + "<trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>"
                    + "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>"
                    + "<trt:ProfileToken>" + XmlUtils.escape(profile.token()) + "</trt:ProfileToken>"
                    + "</trt:GetStreamUri>";
            SoapResult uriResult = post(mediaUrl, body, device.getUsername(), device.getPassword());
            if (!uriResult.ok()) {
                continue;
            }
            try {
                Document doc = XmlUtils.parse(uriResult.body());
                String uri = XmlUtils.text(doc, "Uri");
                if (uri == null || uri.isBlank()) {
                    continue;
                }
                String normalized = rehostRtsp(uri.trim(), device.getIpAddress());
                if (!seen.add(normalized)) {
                    continue;
                }
                String name = profile.name() == null || profile.name().isBlank() ? profile.token() : profile.name();
                RTSPStream stream = new RTSPStream(name, normalized);
                stream.setSource("ONVIF");
                streams.add(stream);
                logger.info("ONVIF profile '{}' on {} -> {}", name, device.getIpAddress(), normalized);
            } catch (Exception e) {
                logger.debug("Cannot parse GetStreamUri for {}: {}", profile.token(), e.getMessage());
            }
        }
        assignRoles(streams);
        return streams;
    }

    /**
     * Label the highest-resolution stream Main and the rest Sub.
     *
     * <p>Resolution is not known before analysis, so ordering falls back to the
     * profile order the device returned, which is main-first on every device
     * seen. Roles are refined after analysis by the stream analyzer.
     */
    private static void assignRoles(List<RTSPStream> streams) {
        for (int i = 0; i < streams.size(); i++) {
            RTSPStream stream = streams.get(i);
            String lower = stream.getStreamName() == null ? "" : stream.getStreamName().toLowerCase(Locale.ROOT);
            if (lower.contains("sub") || lower.contains("second") || lower.contains("low")) {
                stream.setRole(RTSPStream.Role.SUB);
            } else if (lower.contains("main") || lower.contains("primary") || lower.contains("high")) {
                stream.setRole(RTSPStream.Role.MAIN);
            } else {
                stream.setRole(i == 0 ? RTSPStream.Role.MAIN : RTSPStream.Role.SUB);
            }
        }
    }

    private static String rehostRtsp(String uri, String reachableIp) {
        try {
            URI parsed = URI.create(uri);
            String host = parsed.getHost();
            if (host == null || host.equals(reachableIp)) {
                return uri;
            }
            int port = parsed.getPort();
            String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
            String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
            return "rtsp://" + reachableIp + (port > 0 ? ":" + port : "") + path + query;
        } catch (Exception e) {
            return uri;
        }
    }

    private List<Profile> parseProfiles(String xml) {
        List<Profile> profiles = new ArrayList<>();
        try {
            Document doc = XmlUtils.parse(xml);
            List<Element> elements = XmlUtils.elementList(doc, "Profiles");
            if (elements.isEmpty()) {
                elements = XmlUtils.elementList(doc, "Profile"); // Media2 spelling
            }
            for (Element element : elements) {
                String token = element.getAttribute("token");
                if (token.isEmpty()) {
                    continue;
                }
                profiles.add(new Profile(token, XmlUtils.text(element, "Name")));
            }
        } catch (Exception e) {
            logger.debug("Cannot parse GetProfiles: {}", e.getMessage());
        }
        return profiles;
    }

    /** Device hostname, stored as the display name when none is known yet. */
    public void getHostname(Device device) {
        SoapResult result = post(device.getOnvifServiceUrl(), "<tds:GetHostname/>",
                device.getUsername(), device.getPassword());
        if (!result.ok()) {
            return;
        }
        try {
            Document doc = XmlUtils.parse(result.body());
            String name = XmlUtils.text(doc, "Name");
            if (name != null && !name.isBlank() && device.getDeviceName() == null) {
                device.setDeviceName(name.trim());
            }
        } catch (Exception e) {
            logger.debug("Cannot parse GetHostname for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    /**
     * MAC address straight from the device, used when ARP cannot help (the
     * device is on another subnet). Only genuine unicast addresses are accepted.
     */
    public Optional<String> getMacAddress(Device device) {
        SoapResult result = post(device.getOnvifServiceUrl(), "<tds:GetNetworkInterfaces/>",
                device.getUsername(), device.getPassword());
        if (!result.ok()) {
            return Optional.empty();
        }
        try {
            Document doc = XmlUtils.parse(result.body());
            for (Element info : XmlUtils.elementList(doc, "Info")) {
                String hwAddress = XmlUtils.text(info, "HwAddress");
                String mac = NetworkUtils.normalizeMac(hwAddress);
                if (mac != null && NetworkUtils.isUnicastMac(mac)) {
                    return Optional.of(mac);
                }
            }
        } catch (Exception e) {
            logger.debug("Cannot parse GetNetworkInterfaces for {}: {}", device.getIpAddress(), e.getMessage());
        }
        return Optional.empty();
    }
}
