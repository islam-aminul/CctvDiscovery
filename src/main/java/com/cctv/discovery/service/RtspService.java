package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.FFmpegSupport;
import com.cctv.discovery.util.RtspClient;
import com.cctv.discovery.util.SdpParser;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * RTSP probing: confirming RTSP servers, validating stream URLs and guessing
 * paths for devices that do not publish them over ONVIF.
 */
public final class RtspService {

    private static final Logger logger = LoggerFactory.getLogger(RtspService.class);

    /** How thoroughly a candidate URL is checked. */
    public enum RtspValidationMethod {
        SDP_ONLY("Quick check", 3000),
        RTP_PACKET("Stream test", 6000),
        FRAME_CAPTURE("Video capture", 12000);

        private final String displayName;
        private final int defaultTimeoutMs;

        RtspValidationMethod(String displayName, int defaultTimeoutMs) {
            this.displayName = displayName;
            this.defaultTimeoutMs = defaultTimeoutMs;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getDefaultTimeout() {
            return defaultTimeoutMs;
        }
    }

    /** Validation settings shared by a discovery run. */
    public static final class RtspDiscoveryConfig {
        private RtspValidationMethod validationMethod = RtspValidationMethod.FRAME_CAPTURE;
        private int customTimeoutMs;

        public RtspValidationMethod getValidationMethod() {
            return validationMethod;
        }

        public void setValidationMethod(RtspValidationMethod method) {
            this.validationMethod = method == null ? RtspValidationMethod.FRAME_CAPTURE : method;
        }

        public int getTimeout() {
            return customTimeoutMs > 0 ? customTimeoutMs : validationMethod.getDefaultTimeout();
        }

        public void setCustomTimeout(int timeoutMs) {
            this.customTimeoutMs = timeoutMs;
        }
    }

    /** What a DESCRIBE revealed about a candidate URL. */
    public record ProbeResult(boolean valid, boolean authRequired, boolean authAccepted, boolean anonymous,
                              SdpParser.Sdp sdp, String reason) {

        static ProbeResult failure(String reason) {
            return new ProbeResult(false, false, false, false, null, reason);
        }
    }

    /** Main and sub path template for one recorder family. */
    public record NvrChannelPattern(String mainPattern, String subPattern) {

        public String getMainPath(int channel) {
            return resolve(mainPattern, channel);
        }

        public String getSubPath(int channel) {
            return resolve(subPattern, channel);
        }

        private static String resolve(String pattern, int channel) {
            if (pattern == null) {
                return null;
            }
            return pattern
                    .replace("{channel*100+1}", String.valueOf(channel * 100 + 1))
                    .replace("{channel*100+2}", String.valueOf(channel * 100 + 2))
                    .replace("{channel+100}", String.valueOf(channel + 100))
                    .replace("{channel01}", String.format("%02d", channel))
                    .replace("{channel}", String.valueOf(channel));
        }
    }

    private static final Map<String, String[]> MANUFACTURER_PATHS = new LinkedHashMap<>();
    private static final Map<String, NvrChannelPattern> NVR_PATTERNS = new HashMap<>();

    /** Paths that worked before, kept between runs and tried first. */
    private final RtspPathCache pathCache;

    static {
        FFmpegSupport.init();
        loadTemplates();
    }

    private final AppConfig config = AppConfig.getInstance();
    private volatile RtspDiscoveryConfig discoveryConfig = new RtspDiscoveryConfig();
    private volatile boolean shutdownRequested;
    private final ConcurrentLinkedQueue<FFmpegFrameGrabber> activeGrabbers = new ConcurrentLinkedQueue<>();

    private static RtspDiscoveryConfig sharedConfig = new RtspDiscoveryConfig();

    public static RtspDiscoveryConfig getDiscoveryConfig() {
        return sharedConfig;
    }

    public static void setDiscoveryConfig(RtspDiscoveryConfig config) {
        sharedConfig = config == null ? new RtspDiscoveryConfig() : config;
    }

    public RtspService() {
        this(sharedConfig, RtspPathCache.shared());
    }

    public RtspService(RtspDiscoveryConfig config) {
        this(config, RtspPathCache.shared());
    }

    public RtspService(RtspDiscoveryConfig config, RtspPathCache pathCache) {
        this.discoveryConfig = config == null ? new RtspDiscoveryConfig() : config;
        this.pathCache = pathCache;
    }

    public void reset() {
        shutdownRequested = false;
        this.discoveryConfig = sharedConfig;
    }

    private boolean cancelled() {
        return shutdownRequested || Thread.currentThread().isInterrupted();
    }

    // ------------------------------------------------------------- Templates

    private static void loadTemplates() {
        try (InputStream input = RtspService.class.getResourceAsStream("/rtsp-templates.properties")) {
            if (input == null) {
                logger.warn("rtsp-templates.properties missing; path guessing limited to generic paths");
                return;
            }
            Properties props = new Properties();
            props.load(input);

            Map<String, String> mains = new HashMap<>();
            Map<String, String> subs = new HashMap<>();
            Map<String, String> aliases = new HashMap<>();

            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (key.startsWith("manufacturer.") && key.endsWith(".paths")) {
                    String name = key.substring("manufacturer.".length(), key.length() - ".paths".length());
                    String[] paths = value.split(",");
                    for (int i = 0; i < paths.length; i++) {
                        paths[i] = paths[i].trim();
                    }
                    MANUFACTURER_PATHS.put(normalizeKey(name), paths);
                } else if (key.startsWith("nvr.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length == 3) {
                        String name = normalizeKey(parts[1]);
                        switch (parts[2]) {
                            case "mainPattern" -> mains.put(name, value);
                            case "subPattern" -> subs.put(name, value);
                            case "aliases" -> aliases.put(name, value);
                            default -> { /* ignore */ }
                        }
                    }
                }
            }

            mains.forEach((name, main) -> {
                String sub = subs.get(name);
                if (sub == null) {
                    return;
                }
                NvrChannelPattern pattern = new NvrChannelPattern(main, sub);
                NVR_PATTERNS.put(name, pattern);
                String aliasList = aliases.get(name);
                if (aliasList != null) {
                    for (String alias : aliasList.split(",")) {
                        if (!alias.isBlank()) {
                            NVR_PATTERNS.put(normalizeKey(alias), pattern);
                        }
                    }
                }
            });

            logger.info("Loaded RTSP templates for {} vendors and {} recorder patterns",
                    MANUFACTURER_PATHS.size(), NVR_PATTERNS.size());
        } catch (Exception e) {
            logger.error("Cannot load rtsp-templates.properties", e);
        }
    }

    private static String normalizeKey(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    /** Recorder path pattern for a brand, falling back to a generic one. */
    static NvrChannelPattern findNvrPattern(String manufacturer) {
        if (manufacturer != null && !manufacturer.isBlank()) {
            String key = normalizeKey(manufacturer);
            NvrChannelPattern exact = NVR_PATTERNS.get(key);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, NvrChannelPattern> entry : NVR_PATTERNS.entrySet()) {
                if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                    return entry.getValue();
                }
            }
        }
        return NVR_PATTERNS.get("GENERIC");
    }

    // ----------------------------------------------------------- RTSP probing

    /**
     * Confirm an RTSP server by protocol rather than by port number, so cameras
     * on non-standard ports (the test camera uses 5543) are recognised and
     * unrelated services on 554 are not.
     */
    public boolean isRtspServer(String host, int port) {
        try (RtspClient client = new RtspClient(host, port,
                config.getSocketConnectTimeout(), config.getSocketReadTimeout())) {
            client.connect();
            RtspClient.Response response = client.request("OPTIONS", RtspClient.url(host, port, "/"), Map.of());
            // Any RTSP status line proves the protocol, including 401 and 404.
            boolean rtsp = response.status() > 0;
            if (rtsp) {
                logger.debug("RTSP server confirmed at {}:{} ({})", host, port, response.statusLine());
            }
            return rtsp;
        } catch (Exception e) {
            logger.debug("No RTSP server at {}:{} ({})", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * DESCRIBE a URL, first anonymously so that unauthenticated access is
     * detected as an audit finding, then with credentials if challenged.
     */
    public ProbeResult probe(String rtspUrl, String username, String password) {
        if (cancelled()) {
            return ProbeResult.failure("Cancelled");
        }
        String host = RtspClient.hostOf(rtspUrl);
        int port = RtspClient.portOf(rtspUrl);

        try (RtspClient client = new RtspClient(host, port,
                config.getSocketConnectTimeout(), config.getSocketReadTimeout())
                .withCredentials(username, password)) {
            client.connect();

            RtspClient.Response response = client.request("DESCRIBE", rtspUrl,
                    Map.of("Accept", "application/sdp"));

            if (response.status() == 401) {
                return new ProbeResult(false, true, false, false, null, "Authentication rejected");
            }
            if (!response.ok()) {
                return new ProbeResult(false, client.authenticationRequired(), false, false, null,
                        "RTSP " + response.statusLine());
            }

            SdpParser.Sdp sdp = SdpParser.parse(response.body());
            if (sdp == null) {
                return new ProbeResult(false, client.authenticationRequired(), false, false, null,
                        "Response was not SDP");
            }
            if (sdp.firstVideo() == null) {
                return new ProbeResult(false, client.authenticationRequired(), false, false, sdp,
                        "No video track in SDP");
            }

            boolean anonymous = !client.authenticationRequired();
            return new ProbeResult(true, client.authenticationRequired(), client.authenticationAccepted(),
                    anonymous, sdp, null);
        } catch (Exception e) {
            return ProbeResult.failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Validate a URL using the configured method and return a populated stream,
     * or null when the URL does not serve video.
     */
    public RTSPStream validateRtspStream(String rtspUrl, String username, String password) {
        if (cancelled()) {
            return null;
        }
        ProbeResult probe = probe(rtspUrl, username, password);
        if (!probe.valid()) {
            logger.debug("RTSP {} rejected: {}", RtspClient.stripCredentials(rtspUrl), probe.reason());
            return null;
        }

        RTSPStream stream = new RTSPStream("Stream", RtspClient.stripCredentials(rtspUrl));
        stream.setSource("Path probe");
        applySdp(stream, probe.sdp());

        RtspValidationMethod method = discoveryConfig.getValidationMethod();
        boolean confirmed = switch (method) {
            case SDP_ONLY -> true;
            case RTP_PACKET -> receivesMedia(rtspUrl, username, password);
            case FRAME_CAPTURE -> capturesFrame(rtspUrl, username, password, stream);
        };

        if (!confirmed) {
            logger.debug("RTSP {} described a video track but delivered none ({})",
                    RtspClient.stripCredentials(rtspUrl), method);
            return null;
        }
        return stream;
    }

    /** Copy codec, profile and session name out of the SDP. */
    private static void applySdp(RTSPStream stream, SdpParser.Sdp sdp) {
        if (sdp == null) {
            return;
        }
        if (sdp.sessionName() != null) {
            stream.setSdpSessionName(sdp.sessionName());
        }
        SdpParser.Media video = sdp.firstVideo();
        if (video != null) {
            if (stream.getCodec() == null) {
                stream.setCodec(video.codecName());
            }
            String profile = video.profile();
            if (profile != null && stream.getProfile() == null) {
                stream.setProfile(profile);
            }
        }
        SdpParser.Media audio = sdp.firstAudio();
        if (audio != null && stream.getAudioCodec() == null) {
            stream.setAudioCodec(audio.codecName());
        }
    }

    /**
     * Confirm media delivery by playing the stream with RTP interleaved on the
     * RTSP connection. Interleaving avoids the inbound-UDP problem that made the
     * previous UDP-based check fail on every camera that allows anonymous
     * DESCRIBE.
     */
    private boolean receivesMedia(String rtspUrl, String username, String password) {
        String host = RtspClient.hostOf(rtspUrl);
        int port = RtspClient.portOf(rtspUrl);
        int timeout = discoveryConfig.getTimeout();

        try (RtspClient client = new RtspClient(host, port,
                config.getSocketConnectTimeout(), Math.max(timeout, config.getSocketReadTimeout()))
                .withCredentials(username, password)) {
            client.connect();

            RtspClient.Response describe = client.request("DESCRIBE", rtspUrl,
                    Map.of("Accept", "application/sdp"));
            if (!describe.ok()) {
                return false;
            }
            SdpParser.Sdp sdp = SdpParser.parse(describe.body());
            if (sdp == null || sdp.firstVideo() == null) {
                return false;
            }

            // Control URLs resolve against Content-Base, then Content-Location,
            // then the request URL (RFC 2326 C.1.1).
            String base = firstNonBlank(describe.header("Content-Base"), describe.header("Content-Location"), rtspUrl);
            String control = SdpParser.resolveControl(base, sdp.firstVideo().control());

            RtspClient.Response setup = client.request("SETUP", control,
                    Map.of("Transport", "RTP/AVP/TCP;unicast;interleaved=0-1"));
            if (!setup.ok()) {
                logger.debug("SETUP failed for {}: {}", RtspClient.stripCredentials(control), setup.statusLine());
                return false;
            }

            String sessionBase = firstNonBlank(describe.header("Content-Base"), rtspUrl);
            RtspClient.Response play = client.request("PLAY", sessionBase, Map.of("Range", "npt=0.000-"));
            if (!play.ok()) {
                logger.debug("PLAY failed for {}: {}", RtspClient.stripCredentials(sessionBase), play.statusLine());
                return false;
            }

            int rtpPackets = 0;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
            while (rtpPackets < 5 && System.nanoTime() < deadline && !cancelled()) {
                RtspClient.InterleavedPacket packet = client.readInterleaved(1000);
                if (packet != null && packet.channel() == 0 && packet.isRtp()) {
                    rtpPackets++;
                }
            }
            client.teardown(sessionBase);

            boolean success = rtpPackets >= 5;
            logger.debug("RTP check for {}: {} packet(s)", RtspClient.stripCredentials(rtspUrl), rtpPackets);
            return success;
        } catch (Exception e) {
            logger.debug("RTP check failed for {}: {}", RtspClient.stripCredentials(rtspUrl), e.getMessage());
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Decode one video image. {@code grabImage} is used rather than
     * {@code grabFrame}, because on a camera with audio the first frames
     * returned are audio samples and a check for an image in them always failed.
     */
    private boolean capturesFrame(String rtspUrl, String username, String password, RTSPStream stream) {
        int timeout = discoveryConfig.getTimeout();
        FFmpegFrameGrabber grabber = null;
        long start = System.currentTimeMillis();
        try {
            grabber = FFmpegSupport.grabber(rtspUrl, username, password, timeout);
            activeGrabbers.add(grabber);
            grabber.start();

            Frame image = grabber.grabImage();
            long elapsed = System.currentTimeMillis() - start;
            if (image == null || image.image == null) {
                logger.debug("No image from {} after {} ms", RtspClient.stripCredentials(rtspUrl), elapsed);
                return false;
            }

            if (image.imageWidth > 0 && image.imageHeight > 0) {
                stream.setDimensions(image.imageWidth, image.imageHeight);
            }
            String codec = FFmpegSupport.videoCodecName(grabber);
            if (codec != null) {
                stream.setCodec(codec);
            }
            String profile = FFmpegSupport.videoProfile(grabber);
            if (profile != null) {
                stream.setProfile(profile);
            }
            String audio = FFmpegSupport.audioCodecName(grabber);
            if (audio != null) {
                stream.setAudioCodec(audio);
            }
            logger.debug("Captured {}x{} from {} in {} ms", image.imageWidth, image.imageHeight,
                    RtspClient.stripCredentials(rtspUrl), elapsed);
            return true;
        } catch (Exception e) {
            String message = e.getMessage();
            if (FFmpegSupport.isUnauthorized(message)) {
                logger.debug("Frame capture unauthorized for {}", RtspClient.stripCredentials(rtspUrl));
            } else {
                logger.debug("Frame capture failed for {}: {}", RtspClient.stripCredentials(rtspUrl), message);
            }
            return false;
        } finally {
            if (grabber != null) {
                activeGrabbers.remove(grabber);
                FFmpegSupport.closeQuietly(grabber);
            }
        }
    }

    // --------------------------------------------------------- Path discovery

    /**
     * Find streams by trying known paths, in order: paths cached from a similar
     * device, vendor paths, configured custom pairs, then generic paths.
     * A working main path is paired with its matching sub path.
     */
    public List<RTSPStream> discoverStreams(Device device, String username, String password) {
        List<Integer> ports = device.getOpenRtspPorts();
        if (ports.isEmpty()) {
            logger.debug("No RTSP port on {}; skipping path discovery", device.getIpAddress());
            return List.of();
        }

        List<String> candidates = candidatePaths(device);
        List<RTSPStream> found = new ArrayList<>();

        for (int port : ports) {
            for (String path : candidates) {
                if (cancelled()) {
                    return found;
                }
                String url = RtspClient.url(device.getIpAddress(), port, path);
                RTSPStream main = validateRtspStream(url, username, password);
                if (main == null) {
                    continue;
                }
                main.setStreamName("Main");
                main.setRole(RTSPStream.Role.MAIN);
                found.add(main);
                cachePath(device, path);
                logger.info("RTSP path found on {}: {}", device.getIpAddress(), path);

                String subPath = substreamPath(path);
                if (subPath != null && !subPath.equals(path)) {
                    RTSPStream sub = validateRtspStream(
                            RtspClient.url(device.getIpAddress(), port, subPath), username, password);
                    if (sub != null) {
                        sub.setStreamName("Sub");
                        sub.setRole(RTSPStream.Role.SUB);
                        found.add(sub);
                        // The sub path is not remembered: it is derived from the
                        // main path, and remembering it would see it offered
                        // first on the next run and treated as the main stream.
                    }
                }
                return found;
            }
        }
        return found;
    }

    private List<String> candidatePaths(Device device) {
        Set<String> paths = new LinkedHashSet<>();

        // What worked last time for this vendor, so a repeat survey of the
        // same site finds the stream on the first attempt.
        paths.addAll(pathCache.pathsFor(device.getMacAddress()));

        String manufacturer = device.getManufacturer();
        if (manufacturer != null && !manufacturer.isBlank()
                && !MacLookupService.UNKNOWN.equalsIgnoreCase(manufacturer)) {
            String[] vendorPaths = MANUFACTURER_PATHS.get(normalizeKey(manufacturer));
            if (vendorPaths != null) {
                paths.addAll(List.of(vendorPaths));
            }
        } else {
            // Vendor unknown: try every vendor's paths before the generic ones.
            MANUFACTURER_PATHS.forEach((key, value) -> {
                if (!"GENERIC".equals(key)) {
                    paths.addAll(List.of(value));
                }
            });
        }

        String[] custom = config.getCustomRtspPaths();
        paths.addAll(List.of(custom));

        String[] generic = MANUFACTURER_PATHS.get("GENERIC");
        if (generic != null) {
            paths.addAll(List.of(generic));
        }
        return List.copyOf(paths);
    }

    private void cachePath(Device device, String path) {
        pathCache.remember(device.getMacAddress(), path);
    }

    /** Persist anything learned during this run. */
    public void saveLearnedPaths() {
        pathCache.save();
    }

    /** Derive the sub-stream path that pairs with a main-stream path. */
    static String substreamPath(String mainPath) {
        if (mainPath == null) {
            return null;
        }
        if (mainPath.contains("subtype=0")) {
            return mainPath.replace("subtype=0", "subtype=1");
        }
        if (mainPath.contains("/main/")) {
            return mainPath.replace("/main/", "/sub/");
        }
        if (mainPath.endsWith("/101")) {
            return mainPath.substring(0, mainPath.length() - 3) + "102";
        }
        if (mainPath.contains("channel0")) {
            return mainPath.replace("channel0", "channel1");
        }
        if (mainPath.contains("live1s1")) {
            return mainPath.replace("live1s1", "live1s2");
        }
        if (mainPath.endsWith("_0")) {
            return mainPath.substring(0, mainPath.length() - 2) + "_1";
        }
        if (mainPath.endsWith("1") && !mainPath.endsWith("101")) {
            return mainPath.substring(0, mainPath.length() - 1) + "2";
        }
        return null;
    }

    /**
     * Walk recorder channels until {@code maxChannels} or several consecutive
     * channels fail.
     */
    public List<RTSPStream> iterateNvrChannels(Device device, String username, String password, int maxChannels) {
        List<Integer> ports = device.getOpenRtspPorts();
        if (ports.isEmpty()) {
            return List.of();
        }
        NvrChannelPattern pattern = findNvrPattern(device.getManufacturer());
        if (pattern == null) {
            logger.debug("No recorder path pattern for {}", device.getManufacturer());
            return List.of();
        }

        int port = ports.getFirst();
        int consecutiveFailures = 0;
        int maxFailures = config.getNvrConsecutiveFailures();
        List<RTSPStream> streams = new ArrayList<>();

        for (int channel = 1; channel <= maxChannels && !cancelled(); channel++) {
            String mainUrl = RtspClient.url(device.getIpAddress(), port, pattern.getMainPath(channel));
            RTSPStream main = validateRtspStream(mainUrl, username, password);
            if (main == null) {
                if (++consecutiveFailures >= maxFailures) {
                    logger.debug("Stopping channel walk at {} after {} misses", channel, consecutiveFailures);
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            main.setStreamName("CH" + channel + " Main");
            main.setChannelName("Channel " + channel);
            main.setRole(RTSPStream.Role.MAIN);
            main.setSource("NVR channel");
            streams.add(main);

            String subUrl = RtspClient.url(device.getIpAddress(), port, pattern.getSubPath(channel));
            RTSPStream sub = validateRtspStream(subUrl, username, password);
            if (sub != null) {
                sub.setStreamName("CH" + channel + " Sub");
                sub.setChannelName("Channel " + channel);
                sub.setRole(RTSPStream.Role.SUB);
                sub.setSource("NVR channel");
                streams.add(sub);
            }
        }
        return streams;
    }

    /** Cancel in-flight work and release native resources. */
    public void shutdown() {
        shutdownRequested = true;
        FFmpegFrameGrabber grabber;
        while ((grabber = activeGrabbers.poll()) != null) {
            FFmpegSupport.closeQuietly(grabber);
        }
    }
}
