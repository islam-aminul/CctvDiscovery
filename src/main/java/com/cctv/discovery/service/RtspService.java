package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.util.*;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
import org.bytedeco.ffmpeg.global.avutil;

/**
 * RTSP service for stream discovery, URL guessing, and authentication.
 * Implements smart cache, manufacturer-specific paths, and generic fallbacks.
 */
public class RtspService {
    private static final Logger logger = LoggerFactory.getLogger(RtspService.class);

    // Smart cache: MAC prefix -> successful RTSP paths
    private static final Map<String, List<String>> SMART_CACHE = new HashMap<>();

    // Manufacturer-specific RTSP path templates (loaded from resource file)
    private static final Map<String, String[]> MANUFACTURER_PATHS = new HashMap<>();

    // NVR channel URL patterns (loaded from resource file)
    private static final Map<String, NvrChannelPattern> NVR_CHANNEL_PATTERNS = new HashMap<>();

    // Discovery configuration
    private static RtspDiscoveryConfig discoveryConfig = new RtspDiscoveryConfig();

    /**
     * NVR channel URL pattern holder.
     * Patterns use placeholders: {channel}, {channel01}, {channel*100+1}, {channel*100+2}, {channel+100}
     */
    public static class NvrChannelPattern {
        private final String mainPattern;
        private final String subPattern;

        public NvrChannelPattern(String mainPattern, String subPattern) {
            this.mainPattern = mainPattern;
            this.subPattern = subPattern;
        }

        public String getMainPath(int channel) {
            return resolvePlaceholders(mainPattern, channel);
        }

        public String getSubPath(int channel) {
            return resolvePlaceholders(subPattern, channel);
        }

        private String resolvePlaceholders(String pattern, int channel) {
            if (pattern == null) return null;
            return pattern
                    .replace("{channel*100+1}", String.valueOf(channel * 100 + 1))
                    .replace("{channel*100+2}", String.valueOf(channel * 100 + 2))
                    .replace("{channel+100}", String.valueOf(channel + 100))
                    .replace("{channel01}", String.format("%02d", channel))
                    .replace("{channel}", String.valueOf(channel));
        }

        @Override
        public String toString() {
            return "NvrChannelPattern{main='" + mainPattern + "', sub='" + subPattern + "'}";
        }
    }

    static {
        // Route FFmpeg native logs through SLF4J instead of raw stderr.
        // AV_LOG_WARNING = 24: capture warnings and errors (H264 decoder, 401 retries,
        // etc.)
        // Logback routes FFmpegLogCallback logger to file only (not console).
        avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        FFmpegLogCallback.set();
        loadRtspTemplates();
    }

    /**
     * RTSP validation method enum with default timeouts
     */
    public enum RtspValidationMethod {
        SDP_ONLY("SDP Validation Only", 3000), // 3s - fast, ~60% accurate
        RTP_PACKET("RTP Packet Detection", 5000), // 5s - medium, ~90% accurate
        FRAME_CAPTURE("Frame Capture", 10000); // 10s - slow, ~98% accurate

        private final String displayName;
        private final int defaultTimeoutMs;

        RtspValidationMethod(String displayName, int timeoutMs) {
            this.displayName = displayName;
            this.defaultTimeoutMs = timeoutMs;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getDefaultTimeout() {
            return defaultTimeoutMs;
        }
    }

    /**
     * RTSP discovery configuration
     */
    public static class RtspDiscoveryConfig {
        private RtspValidationMethod validationMethod = RtspValidationMethod.FRAME_CAPTURE;
        private int customTimeoutMs = 0; // 0 = use default for method

        public RtspValidationMethod getValidationMethod() {
            return validationMethod;
        }

        public void setValidationMethod(RtspValidationMethod method) {
            this.validationMethod = method;
        }

        public int getTimeout() {
            return customTimeoutMs > 0 ? customTimeoutMs : validationMethod.getDefaultTimeout();
        }

        public void setCustomTimeout(int timeoutMs) {
            this.customTimeoutMs = timeoutMs;
        }
    }

    /**
     * Get current discovery configuration
     */
    public static RtspDiscoveryConfig getDiscoveryConfig() {
        return discoveryConfig;
    }

    /**
     * Set discovery configuration
     */
    public static void setDiscoveryConfig(RtspDiscoveryConfig config) {
        discoveryConfig = config;
    }

    /**
     * Load RTSP path templates from rtsp-templates.properties resource file.
     * Falls back to hardcoded defaults if file not found or parsing fails.
     */
    private static void loadRtspTemplates() {
        try (InputStream input = RtspService.class.getClassLoader()
                .getResourceAsStream("rtsp-templates.properties")) {

            if (input == null) {
                logger.warn("rtsp-templates.properties not found, using hardcoded defaults");
                loadHardcodedDefaults();
                return;
            }

            Properties props = new Properties();
            props.load(input);

            // Parse properties: manufacturer.<NAME>.paths=<comma-separated paths>
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("manufacturer.") && key.endsWith(".paths")) {
                    String manufacturer = key.substring(13, key.length() - 6); // Extract manufacturer name
                    // Normalize manufacturer name: replace spaces with underscores for consistent
                    // lookup
                    manufacturer = manufacturer.replace(" ", "_");
                    String pathsStr = props.getProperty(key);

                    if (pathsStr != null && !pathsStr.trim().isEmpty()) {
                        String[] paths = pathsStr.split(",");
                        // Trim whitespace from each path
                        for (int i = 0; i < paths.length; i++) {
                            paths[i] = paths[i].trim();
                        }
                        MANUFACTURER_PATHS.put(manufacturer.toUpperCase(), paths);
                        logger.info("Loaded {} RTSP paths for manufacturer: {}", paths.length, manufacturer);
                    }
                }
            }

            // Parse NVR channel patterns: nvr.<NAME>.mainPattern, nvr.<NAME>.subPattern, nvr.<NAME>.aliases
            Map<String, String> nvrMainPatterns = new HashMap<>();
            Map<String, String> nvrSubPatterns = new HashMap<>();
            Map<String, String> nvrAliases = new HashMap<>();

            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("nvr.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length == 3) {
                        String nvrName = parts[1].toUpperCase();
                        String propType = parts[2];
                        String value = props.getProperty(key);

                        if ("mainPattern".equals(propType)) {
                            nvrMainPatterns.put(nvrName, value);
                        } else if ("subPattern".equals(propType)) {
                            nvrSubPatterns.put(nvrName, value);
                        } else if ("aliases".equals(propType)) {
                            nvrAliases.put(nvrName, value);
                        }
                    }
                }
            }

            // Register NVR patterns with their aliases
            for (String nvrName : nvrMainPatterns.keySet()) {
                String mainPattern = nvrMainPatterns.get(nvrName);
                String subPattern = nvrSubPatterns.get(nvrName);

                if (mainPattern != null && subPattern != null) {
                    NvrChannelPattern pattern = new NvrChannelPattern(mainPattern, subPattern);

                    // Register under primary name
                    NVR_CHANNEL_PATTERNS.put(nvrName, pattern);

                    // Register under each alias
                    String aliases = nvrAliases.get(nvrName);
                    if (aliases != null && !aliases.trim().isEmpty()) {
                        for (String alias : aliases.split(",")) {
                            alias = alias.trim().toUpperCase().replace(" ", "_");
                            if (!alias.isEmpty()) {
                                NVR_CHANNEL_PATTERNS.put(alias, pattern);
                            }
                        }
                    }
                    logger.info("Loaded NVR channel pattern for {}: {}", nvrName, pattern);
                }
            }

            if (MANUFACTURER_PATHS.isEmpty()) {
                logger.warn("No RTSP templates loaded from file, using hardcoded defaults");
                loadHardcodedDefaults();
            } else {
                logger.info("Successfully loaded RTSP templates for {} manufacturers from resource file",
                        MANUFACTURER_PATHS.size());
            }

            if (NVR_CHANNEL_PATTERNS.isEmpty()) {
                logger.warn("No NVR channel patterns loaded from file, using hardcoded defaults");
                loadHardcodedNvrDefaults();
            } else {
                logger.info("Successfully loaded NVR channel patterns for {} entries from resource file",
                        NVR_CHANNEL_PATTERNS.size());
            }

        } catch (Exception e) {
            logger.error("Error loading rtsp-templates.properties, falling back to hardcoded defaults", e);
            loadHardcodedDefaults();
            loadHardcodedNvrDefaults();
        }
    }

    /**
     * Load hardcoded default RTSP paths as fallback.
     * Used when rtsp-templates.properties is not found or fails to load.
     */
    private static void loadHardcodedDefaults() {
        // Hikvision
        MANUFACTURER_PATHS.put("HIKVISION", new String[] {
                "/Streaming/Channels/101",
                "/Streaming/Channels/102",
                "/h264/ch1/main/av_stream",
                "/h264/ch1/sub/av_stream"
        });

        // Dahua
        MANUFACTURER_PATHS.put("DAHUA", new String[] {
                "/cam/realmonitor?channel=1&subtype=0",
                "/cam/realmonitor?channel=1&subtype=1",
                "/live/ch00_0",
                "/live/ch00_1"
        });

        // Axis
        MANUFACTURER_PATHS.put("AXIS", new String[] {
                "/axis-media/media.amp",
                "/axis-media/media.amp?videocodec=h264",
                "/mpeg4/media.amp"
        });

        // CP Plus
        MANUFACTURER_PATHS.put("CP_PLUS", new String[] {
                "/cam/realmonitor?channel=1&subtype=0",
                "/cam/realmonitor?channel=1&subtype=1"
        });

        // Generic paths
        MANUFACTURER_PATHS.put("GENERIC", new String[] {
                "/live",
                "/live/0",
                "/live/1",
                "/ch0",
                "/ch01",
                "/stream1",
                "/stream2",
                "/video.mjpg",
                "/h264"
        });

        logger.info("Loaded {} manufacturer RTSP templates from hardcoded defaults", MANUFACTURER_PATHS.size());
    }

    /**
     * Load hardcoded default NVR channel patterns as fallback.
     * Used when rtsp-templates.properties is not found or fails to load.
     */
    private static void loadHardcodedNvrDefaults() {
        // Hikvision NVR pattern
        NvrChannelPattern hikvision = new NvrChannelPattern(
                "/Streaming/Channels/{channel*100+1}",
                "/Streaming/Channels/{channel*100+2}");
        NVR_CHANNEL_PATTERNS.put("HIKVISION", hikvision);
        NVR_CHANNEL_PATTERNS.put("HIK", hikvision);

        // Dahua-compatible NVR pattern (Dahua, CP Plus, Amcrest)
        NvrChannelPattern dahua = new NvrChannelPattern(
                "/cam/realmonitor?channel={channel}&subtype=0",
                "/cam/realmonitor?channel={channel}&subtype=1");
        NVR_CHANNEL_PATTERNS.put("DAHUA", dahua);
        NVR_CHANNEL_PATTERNS.put("CP_PLUS", dahua);
        NVR_CHANNEL_PATTERNS.put("CP PLUS", dahua);
        NVR_CHANNEL_PATTERNS.put("AMCREST", dahua);

        // Uniview NVR pattern
        NvrChannelPattern uniview = new NvrChannelPattern(
                "/media/video{channel}",
                "/media/video{channel+100}");
        NVR_CHANNEL_PATTERNS.put("UNIVIEW", uniview);
        NVR_CHANNEL_PATTERNS.put("UNV", uniview);

        // Generic fallback pattern
        NvrChannelPattern generic = new NvrChannelPattern(
                "/ch{channel01}/0",
                "/ch{channel01}/1");
        NVR_CHANNEL_PATTERNS.put("GENERIC", generic);

        logger.info("Loaded {} NVR channel patterns from hardcoded defaults", NVR_CHANNEL_PATTERNS.size());
    }

    /**
     * Find NVR channel pattern for the given manufacturer.
     * Checks exact match first, then partial match against aliases.
     * Returns GENERIC pattern if no match found.
     */
    private static NvrChannelPattern findNvrPattern(String manufacturer) {
        if (manufacturer == null || manufacturer.isEmpty()) {
            return NVR_CHANNEL_PATTERNS.getOrDefault("GENERIC", null);
        }

        String mfgUpper = manufacturer.toUpperCase().replace(" ", "_");

        // Try exact match first
        if (NVR_CHANNEL_PATTERNS.containsKey(mfgUpper)) {
            return NVR_CHANNEL_PATTERNS.get(mfgUpper);
        }

        // Try partial match (manufacturer name contains alias)
        for (String key : NVR_CHANNEL_PATTERNS.keySet()) {
            if (mfgUpper.contains(key) || key.contains(mfgUpper)) {
                return NVR_CHANNEL_PATTERNS.get(key);
            }
        }

        // Fallback to generic
        return NVR_CHANNEL_PATTERNS.getOrDefault("GENERIC", null);
    }

    /**
     * Discover RTSP streams for a device using waterfall approach:
     * 1. Smart cache (paths that worked for similar devices)
     * 2. Manufacturer-specific paths
     * 3. Custom user-configured path pairs (main + sub)
     * 4. Generic paths
     */
    public List<RTSPStream> discoverStreams(Device device, String username, String password) {
        List<RTSPStream> streams = new ArrayList<>();

        String manufacturer = device.getManufacturer();
        String macPrefix = getMacPrefix(device.getMacAddress());

        // Get detected RTSP ports - do NOT test if none found
        List<Integer> rtspPorts = getDetectedRtspPorts(device);
        logger.info("Device {} - Detected RTSP ports: {}", device.getIpAddress(), rtspPorts);

        // Skip RTSP testing if no ports detected
        if (rtspPorts.isEmpty()) {
            logger.info("Skipping RTSP stream discovery for {} - no RTSP ports detected", device.getIpAddress());
            return streams; // Return empty list
        }

        List<String> pathsToTry = new ArrayList<>();

        // 1. Try smart cache first
        if (macPrefix != null && SMART_CACHE.containsKey(macPrefix)) {
            pathsToTry.addAll(SMART_CACHE.get(macPrefix));
            logger.info("Using smart cache for MAC prefix: {}", macPrefix);
        }

        // 2. Try manufacturer-specific paths
        if (manufacturer != null) {
            // Normalize manufacturer name: replace spaces with underscores for consistent
            // lookup
            String normalizedManufacturer = manufacturer.replace(" ", "_").toUpperCase();
            String[] mfgPaths = MANUFACTURER_PATHS.get(normalizedManufacturer);
            if (mfgPaths != null) {
                for (String path : mfgPaths) {
                    if (!pathsToTry.contains(path)) {
                        pathsToTry.add(path);
                    }
                }
            }
        }

        // 3. Try custom user-configured path pairs (main + sub)
        String[] customPaths = AppConfig.getInstance().getCustomRtspPaths();
        if (customPaths.length > 0 && customPaths.length % 2 == 0) {
            logger.info("Trying {} custom RTSP path pairs from configuration", customPaths.length / 2);

            // Process custom paths as pairs
            for (int i = 0; i < customPaths.length; i += 2) {
                String mainPath = customPaths[i];
                String subPath = customPaths[i + 1];

                logger.info("Trying custom path pair: main={}, sub={}", mainPath, subPath);

                // Try main stream on each detected RTSP port
                for (int port : rtspPorts) {
                    String mainUrl = "rtsp://" + device.getIpAddress() + ":" + port + mainPath;
                    RTSPStream mainStream = validateRtspStream(mainUrl, username, password);

                    if (mainStream != null) {
                        streams.add(mainStream);
                        logger.info("Found working custom main stream: {}", mainUrl);

                        // Try paired sub stream on same port
                        String subUrl = "rtsp://" + device.getIpAddress() + ":" + port + subPath;
                        RTSPStream subStream = validateRtspStream(subUrl, username, password);
                        if (subStream != null) {
                            streams.add(subStream);
                            logger.info("Found working custom sub stream: {}", subUrl);
                        }

                        // Add to smart cache
                        if (macPrefix != null) {
                            SMART_CACHE.computeIfAbsent(macPrefix, k -> new ArrayList<>()).add(mainPath);
                            if (subStream != null) {
                                SMART_CACHE.get(macPrefix).add(subPath);
                            }
                        }

                        // Found working custom paths, return
                        if (streams.size() >= 2) {
                            return streams;
                        }
                    }
                }
            }
        }

        // 4. Try generic paths
        for (String path : MANUFACTURER_PATHS.get("GENERIC")) {
            if (!pathsToTry.contains(path)) {
                pathsToTry.add(path);
            }
        }

        // Try each path on each detected RTSP port
        for (String path : pathsToTry) {
            for (int port : rtspPorts) {
                String rtspUrl = "rtsp://" + device.getIpAddress() + ":" + port + path;
                RTSPStream stream = validateRtspStream(rtspUrl, username, password);

                if (stream != null) {
                    streams.add(stream);
                    // Add to smart cache
                    if (macPrefix != null) {
                        SMART_CACHE.computeIfAbsent(macPrefix, k -> new ArrayList<>()).add(path);
                    }
                    logger.info("Found working RTSP stream: {}", rtspUrl);

                    // If we found a stream, try to find sub-stream variant on same port
                    if (streams.size() == 1) {
                        String subPath = guessSubstreamPath(path);
                        if (subPath != null && !subPath.equals(path)) {
                            String subUrl = "rtsp://" + device.getIpAddress() + ":" + port + subPath;
                            RTSPStream subStream = validateRtspStream(subUrl, username, password);
                            if (subStream != null) {
                                streams.add(subStream);
                            }
                        }
                    }

                    if (streams.size() >= 2) {
                        return streams; // Found main and sub stream
                    }
                }
            }
        }

        return streams;
    }

    /**
     * Validate RTSP stream using configured validation method.
     * Routes to appropriate validation method based on discoveryConfig.
     */
    private RTSPStream validateRtspStream(String rtspUrl, String username, String password) {
        switch (discoveryConfig.getValidationMethod()) {
            case SDP_ONLY:
                return testRtspUrl(rtspUrl, username, password); // Existing SDP method

            case RTP_PACKET:
                return validateWithRtpPackets(rtspUrl, username, password);

            case FRAME_CAPTURE:
                return validateWithFrameCapture(rtspUrl, username, password);

            default:
                logger.warn("Unknown validation method: {}, using SDP", discoveryConfig.getValidationMethod());
                return testRtspUrl(rtspUrl, username, password);
        }
    }

    /**
     * Get detected RTSP ports from device port scan results.
     * Returns empty list if no RTSP ports were detected (do NOT fallback to 554).
     */
    private List<Integer> getDetectedRtspPorts(Device device) {
        List<Integer> ports = new ArrayList<>();

        // Add detected RTSP ports
        if (device.getOpenRtspPorts() != null && !device.getOpenRtspPorts().isEmpty()) {
            ports.addAll(device.getOpenRtspPorts());
            logger.info("Using detected RTSP ports for {}: {}", device.getIpAddress(), ports);
        } else {
            logger.info("No RTSP ports detected for {} - will skip RTSP testing", device.getIpAddress());
        }

        return ports;
    }

    /**
     * Test RTSP URL with authentication.
     */
    private RTSPStream testRtspUrl(String rtspUrl, String username, String password) {
        Socket socket = null;
        try {
            String host = extractHost(rtspUrl);
            int port = extractPort(rtspUrl);

            socket = new Socket(host, port);
            socket.setSoTimeout(5000);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send DESCRIBE request
            String request = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: CCTV-Discovery/1.0\r\n" +
                    "Accept: application/sdp\r\n" +
                    "\r\n";

            logger.info("RTSP REQUEST");
            logger.info("Sending RTSP DESCRIBE to: {}", rtspUrl);
            logger.info("Request:\n{}", request.replace("\r\n", "\n"));

            out.write(request.getBytes());
            out.flush();

            // Read response
            String line = in.readLine();
            logger.info("RTSP RESPONSE");
            logger.info("Response Line: {}", line);

            if (line == null) {
                logger.info("No response from RTSP URL: {}", rtspUrl);
                return null;
            }

            // Read and log all response headers
            StringBuilder allHeaders = new StringBuilder();
            String headerLine;
            while ((headerLine = in.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break;
                }
                allHeaders.append(headerLine).append("\n");
                logger.info("Header: {}", headerLine);
            }

            if (line.contains("401")) {
                // Unauthorized - need authentication
                logger.info("RTSP URL requires authentication: {}", rtspUrl);
                // Re-create the header content for authentication parsing
                return testRtspUrlWithAuth(rtspUrl, username, password, socket, in, out, allHeaders.toString());
            } else if (line.contains("200")) {
                // Success - validate SDP content
                logger.info("Got 200 OK from RTSP URL, validating SDP: {}", rtspUrl);

                // Headers already read and logged above, parse Content-Length from allHeaders
                int contentLength = 0;
                for (String hdr : allHeaders.toString().split("\n")) {
                    if (hdr.startsWith("Content-Length:")) {
                        try {
                            contentLength = Integer.parseInt(hdr.substring(15).trim());
                        } catch (NumberFormatException e) {
                            logger.info("Invalid Content-Length header");
                        }
                    }
                }

                // Read SDP body
                StringBuilder sdpBody = new StringBuilder();
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int read = in.read(buffer, 0, contentLength);
                    if (read > 0) {
                        sdpBody.append(buffer, 0, read);
                    }
                } else {
                    // Read until connection closes or timeout
                    String sdpLine;
                    while ((sdpLine = in.readLine()) != null && !sdpLine.isEmpty()) {
                        sdpBody.append(sdpLine).append("\n");
                    }
                }

                logger.info("SDP RESPONSE");
                logger.info("SDP Body (length={}):\n{}", sdpBody.length(), sdpBody.toString());

                // Validate SDP content
                if (validateSdp(sdpBody.toString(), rtspUrl)) {
                    logger.info("RTSP URL validated with SDP: {}", rtspUrl);
                    RTSPStream stream = new RTSPStream("Main", rtspUrl);
                    String sessionName = parseSdpSessionName(sdpBody.toString());
                    if (sessionName != null) {
                        stream.setSdpSessionName(sessionName);
                        logger.info("SDP session name extracted: {}", sessionName);
                    }
                    return stream;
                } else {
                    logger.warn("RTSP URL returned 200 OK but invalid/missing SDP: {}", rtspUrl);
                    return null;
                }
            } else {
                logger.info("RTSP URL returned non-success code: {} - {}", line, rtspUrl);
            }

        } catch (SocketTimeoutException e) {
            logger.info("Timeout testing RTSP URL: {}", rtspUrl);
        } catch (Exception e) {
            logger.info("Error testing RTSP URL {}: {}", rtspUrl, e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        return null;
    }

    /**
     * Test RTSP URL with authentication (supports both Basic and Digest).
     */
    private RTSPStream testRtspUrlWithAuth(String rtspUrl, String username, String password,
            Socket socket, BufferedReader in, OutputStream out, String headers) {
        try {
            // Parse authentication challenges from WWW-Authenticate headers
            java.util.List<AuthUtils.AuthChallenge> challenges = new java.util.ArrayList<>();
            java.util.List<String> rawAuthHeaders = new java.util.ArrayList<>();

            // Parse headers that were already read
            for (String line : headers.split("\n")) {
                if (line.startsWith("WWW-Authenticate:")) {
                    String authHeader = line.substring(17).trim();
                    rawAuthHeaders.add(authHeader);
                    logger.info("Parsing WWW-Authenticate header: {}", authHeader);

                    AuthUtils.AuthChallenge challenge = AuthUtils.parseAuthChallenge(authHeader);
                    if (challenge != null) {
                        challenges.add(challenge);
                        logger.info("Parsed auth challenge: {}", challenge);
                    } else {
                        logger.warn("Failed to parse WWW-Authenticate header: {}", authHeader);
                    }
                }
            }

            if (challenges.isEmpty()) {
                logger.warn("No authentication challenges found. Raw headers: {}", rawAuthHeaders);
                return null;
            }

            // Try authentication methods in order of preference: Digest first, then Basic
            challenges.sort((a, b) -> {
                if (a.type == AuthUtils.AuthType.DIGEST)
                    return -1;
                if (b.type == AuthUtils.AuthType.DIGEST)
                    return 1;
                return 0;
            });

            for (AuthUtils.AuthChallenge challenge : challenges) {
                if (!AuthUtils.isValidChallenge(challenge)) {
                    logger.info("Skipping invalid challenge: {}", challenge);
                    continue;
                }

                RTSPStream result = null;
                if (challenge.type == AuthUtils.AuthType.DIGEST) {
                    logger.info("Attempting Digest authentication for: {}", rtspUrl);
                    result = attemptDigestAuth(rtspUrl, username, password, challenge, socket, in, out);
                } else if (challenge.type == AuthUtils.AuthType.BASIC) {
                    logger.info("Attempting Basic authentication for: {}", rtspUrl);
                    result = attemptBasicAuth(rtspUrl, username, password, socket, in, out);
                }

                if (result != null) {
                    logger.info("Authentication successful using {} for: {}", challenge.type, rtspUrl);
                    return result;
                }
            }

            logger.info("All authentication attempts failed for: {}", rtspUrl);

        } catch (Exception e) {
            logger.info("Error during RTSP authentication for {}: {}", rtspUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Attempt Digest authentication.
     */
    private RTSPStream attemptDigestAuth(String rtspUrl, String username, String password,
            AuthUtils.AuthChallenge challenge,
            Socket socket, BufferedReader in, OutputStream out) {
        try {
            if (challenge.realm == null || challenge.realm.isEmpty()) {
                logger.info("Digest challenge missing realm");
                return null;
            }
            if (challenge.nonce == null || challenge.nonce.isEmpty()) {
                logger.info("Digest challenge missing nonce");
                return null;
            }

            // Send authenticated request
            String uri = extractUri(rtspUrl);
            logger.info("Extracted URI for digest: {}", uri);
            logger.info("Using credentials - Username: {}, Password: {} chars", username,
                    password != null ? password.length() : 0);
            logger.info("Challenge details - Realm: {}, Nonce: {}, Opaque: {}, QOP: {}",
                    challenge.realm, challenge.nonce, challenge.opaque, challenge.qop);

            String authHeader = AuthUtils.buildDigestAuthHeader(
                    username, password, challenge.realm, challenge.nonce, uri, "DESCRIBE", challenge.opaque);

            String authRequest = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: 2\r\n" +
                    "User-Agent: CCTV-Discovery/1.0\r\n" +
                    "Authorization: " + authHeader + "\r\n" +
                    "Accept: application/sdp\r\n" +
                    "\r\n";

            logger.info("RTSP DIGEST AUTH REQUEST");
            logger.info("Request:\n{}", authRequest.replace("\r\n", "\n"));
            logger.info("Authorization Header: {}", authHeader);

            out.write(authRequest.getBytes());
            out.flush();

            return readAuthResponse(rtspUrl, in);

        } catch (Exception e) {
            logger.info("Error during Digest authentication for {}: {}", rtspUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Attempt Basic authentication.
     */
    private RTSPStream attemptBasicAuth(String rtspUrl, String username, String password,
            Socket socket, BufferedReader in, OutputStream out) {
        try {
            logger.info("Using credentials - Username: {}, Password: {} chars", username,
                    password != null ? password.length() : 0);

            String authHeader = AuthUtils.generateBasicAuth(username, password);

            String authRequest = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: 2\r\n" +
                    "User-Agent: CCTV-Discovery/1.0\r\n" +
                    "Authorization: " + authHeader + "\r\n" +
                    "Accept: application/sdp\r\n" +
                    "\r\n";

            logger.info("RTSP BASIC AUTH REQUEST");
            logger.info("Request:\n{}", authRequest.replace("\r\n", "\n"));
            logger.info("Authorization Header: {}", authHeader);

            out.write(authRequest.getBytes());
            out.flush();

            return readAuthResponse(rtspUrl, in);

        } catch (Exception e) {
            logger.info("Error during Basic authentication for {}: {}", rtspUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Read and validate authentication response.
     */
    private RTSPStream readAuthResponse(String rtspUrl, BufferedReader in) throws Exception {
        String responseLine = in.readLine();

        logger.info("RTSP AUTH RESPONSE");
        logger.info("Response Line: {}", responseLine);

        if (responseLine != null && responseLine.contains("200")) {
            logger.info("Got 200 OK with authentication, validating SDP: {}", rtspUrl);

            // Read headers to get Content-Length
            int contentLength = 0;
            StringBuilder allHeaders = new StringBuilder();
            String headerLine;
            while ((headerLine = in.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break; // End of headers
                }
                allHeaders.append(headerLine).append("\n");
                logger.info("Response Header: {}", headerLine);
                if (headerLine.startsWith("Content-Length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    } catch (NumberFormatException e) {
                        logger.info("Invalid Content-Length header");
                    }
                }
            }

            // Read SDP body
            StringBuilder sdpBody = new StringBuilder();
            if (contentLength > 0) {
                char[] buffer = new char[contentLength];
                int read = in.read(buffer, 0, contentLength);
                if (read > 0) {
                    sdpBody.append(buffer, 0, read);
                }
            } else {
                // Read until connection closes or timeout
                String sdpLine;
                while ((sdpLine = in.readLine()) != null && !sdpLine.isEmpty()) {
                    sdpBody.append(sdpLine).append("\n");
                }
            }

            logger.info("SDP RESPONSE");
            logger.info("SDP Body (length={}):\n{}", sdpBody.length(), sdpBody.toString());

            // Validate SDP content
            if (validateSdp(sdpBody.toString(), rtspUrl)) {
                logger.info("RTSP URL authenticated and validated with SDP: {}", rtspUrl);
                RTSPStream stream = new RTSPStream("Main", rtspUrl);
                String sessionName = parseSdpSessionName(sdpBody.toString());
                if (sessionName != null) {
                    stream.setSdpSessionName(sessionName);
                    logger.info("SDP session name extracted: {}", sessionName);
                }
                return stream;
            } else {
                logger.warn("RTSP URL authenticated but invalid/missing SDP: {}", rtspUrl);
                return null;
            }
        } else {
            logger.info("Authentication failed. Response: {}", responseLine);
            // Read and log all remaining headers for diagnosis
            String headerLine;
            while ((headerLine = in.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break;
                }
                logger.info("Response Header: {}", headerLine);
            }
        }
        return null;
    }

    /**
     * Validate RTSP URL by detecting RTP packets.
     * Method: DESCRIBE -> SETUP -> PLAY -> Listen for RTP packets
     * Timeout: From config (default 5000ms)
     * Accuracy: ~90%
     */
    private RTSPStream validateWithRtpPackets(String rtspUrl, String username, String password) {
        int timeout = discoveryConfig.getTimeout();
        Socket socket = null;
        DatagramSocket rtpSocket = null;
        String sessionId = null;
        String authorizationHeader = null;

        try {
            String host = extractHost(rtspUrl);
            int port = extractPort(rtspUrl);

            socket = new Socket(host, port);
            socket.setSoTimeout(timeout);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            logger.info("RTP PACKET VALIDATION");
            logger.info("URL: {}, Timeout: {}ms", rtspUrl, timeout);

            // Step 1: DESCRIBE (unauthenticated first)
            int cseq = 1;
            String describeRequest = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "User-Agent: CCTV-Discovery/1.0\r\n" +
                    "Accept: application/sdp\r\n\r\n";

            out.write(describeRequest.getBytes());
            out.flush();

            String responseLine = in.readLine();
            if (responseLine == null) {
                logger.info("No response from DESCRIBE");
                return null;
            }

            // Read headers from initial DESCRIBE response
            StringBuilder describeHeaders = new StringBuilder();
            String headerLine;
            while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                describeHeaders.append(headerLine).append("\n");
            }

            // Handle authentication - retry DESCRIBE with credentials
            if (responseLine.contains("401")) {
                logger.info("RTP validation: 401 received, attempting authentication for {}", rtspUrl);

                if (username == null || username.isEmpty()) {
                    logger.info("No credentials available for RTP authentication");
                    return null;
                }

                // Parse WWW-Authenticate challenges
                java.util.List<AuthUtils.AuthChallenge> challenges = new java.util.ArrayList<>();
                for (String line : describeHeaders.toString().split("\n")) {
                    if (line.startsWith("WWW-Authenticate:")) {
                        String authHeader = line.substring(17).trim();
                        AuthUtils.AuthChallenge challenge = AuthUtils.parseAuthChallenge(authHeader);
                        if (challenge != null) {
                            challenges.add(challenge);
                        }
                    }
                }

                if (challenges.isEmpty()) {
                    logger.info("No WWW-Authenticate challenges found in 401 response");
                    return null;
                }

                // Sort: Digest preferred over Basic
                challenges.sort((a, b) -> {
                    if (a.type == AuthUtils.AuthType.DIGEST)
                        return -1;
                    if (b.type == AuthUtils.AuthType.DIGEST)
                        return 1;
                    return 0;
                });

                // Build authorization header from first valid challenge
                for (AuthUtils.AuthChallenge challenge : challenges) {
                    if (!AuthUtils.isValidChallenge(challenge))
                        continue;

                    if (challenge.type == AuthUtils.AuthType.DIGEST) {
                        String uri = extractUri(rtspUrl);
                        authorizationHeader = AuthUtils.buildDigestAuthHeader(
                                username, password, challenge.realm, challenge.nonce,
                                uri, "DESCRIBE", challenge.opaque);
                        logger.info("RTP: Using Digest authentication");
                        break;
                    } else if (challenge.type == AuthUtils.AuthType.BASIC) {
                        authorizationHeader = AuthUtils.generateBasicAuth(username, password);
                        logger.info("RTP: Using Basic authentication");
                        break;
                    }
                }

                if (authorizationHeader == null) {
                    logger.info("Failed to build authorization header");
                    return null;
                }

                // Close old socket, open fresh connection for authenticated session
                try {
                    socket.close();
                } catch (Exception ignored) {
                }

                socket = new Socket(host, port);
                socket.setSoTimeout(timeout);
                out = socket.getOutputStream();
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // Re-send DESCRIBE with auth
                cseq++;
                String authDescribe = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                        "CSeq: " + cseq + "\r\n" +
                        "User-Agent: CCTV-Discovery/1.0\r\n" +
                        "Authorization: " + authorizationHeader + "\r\n" +
                        "Accept: application/sdp\r\n\r\n";

                out.write(authDescribe.getBytes());
                out.flush();

                responseLine = in.readLine();
                if (responseLine == null || !responseLine.contains("200")) {
                    logger.info("Authenticated DESCRIBE failed: {}", responseLine);
                    return null;
                }
                logger.info("RTP: Authenticated DESCRIBE succeeded");
            } else if (!responseLine.contains("200")) {
                logger.info("DESCRIBE failed: {}", responseLine);
                return null;
            }

            // Read SDP (from either unauthenticated or authenticated 200 response)
            StringBuilder sdp = new StringBuilder();
            String line;
            boolean inBody = false;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    inBody = true;
                    continue;
                }
                if (inBody) {
                    sdp.append(line).append("\n");
                    if (line.startsWith("a=control"))
                        break; // Got enough
                }
            }

            String sdpContent = sdp.toString();
            if (sdpContent.isEmpty()) {
                logger.info("No SDP received");
                return null;
            }

            // Step 2: Extract control URL
            String controlUrl = extractControlUrlFromSdp(sdpContent, rtspUrl);

            // Step 3: SETUP (with auth if needed)
            rtpSocket = new DatagramSocket();
            int clientRtpPort = rtpSocket.getLocalPort();

            cseq++;
            StringBuilder setupBuilder = new StringBuilder();
            setupBuilder.append("SETUP ").append(controlUrl).append(" RTSP/1.0\r\n");
            setupBuilder.append("CSeq: ").append(cseq).append("\r\n");
            if (authorizationHeader != null) {
                setupBuilder.append("Authorization: ").append(authorizationHeader).append("\r\n");
            }
            setupBuilder.append("Transport: RTP/AVP;unicast;client_port=")
                    .append(clientRtpPort).append("-").append(clientRtpPort + 1).append("\r\n\r\n");

            out.write(setupBuilder.toString().getBytes());
            out.flush();

            responseLine = in.readLine();
            if (responseLine == null || !responseLine.contains("200")) {
                logger.info("SETUP failed: {}", responseLine);
                return null;
            }

            // Parse Session ID
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Session:")) {
                    sessionId = line.substring(8).trim().split(";")[0];
                }
            }

            if (sessionId == null) {
                logger.info("No Session ID received");
                return null;
            }

            // Step 4: PLAY (with auth if needed)
            cseq++;
            StringBuilder playBuilder = new StringBuilder();
            playBuilder.append("PLAY ").append(rtspUrl).append(" RTSP/1.0\r\n");
            playBuilder.append("CSeq: ").append(cseq).append("\r\n");
            playBuilder.append("Session: ").append(sessionId).append("\r\n");
            if (authorizationHeader != null) {
                playBuilder.append("Authorization: ").append(authorizationHeader).append("\r\n");
            }
            playBuilder.append("Range: npt=0.000-\r\n\r\n");

            out.write(playBuilder.toString().getBytes());
            out.flush();

            responseLine = in.readLine();
            while ((line = in.readLine()) != null && !line.isEmpty()) {
            } // Skip headers

            if (responseLine == null || !responseLine.contains("200")) {
                logger.info("PLAY failed: {}", responseLine);
                return null;
            }

            // Step 5: Listen for RTP packets
            rtpSocket.setSoTimeout(2000); // 2 second timeout for packets
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            int packetsReceived = 0;
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < 2000 && packetsReceived < 5) {
                try {
                    rtpSocket.receive(packet);
                    // Verify RTP packet (version should be 2)
                    if (packet.getLength() >= 12 && ((buffer[0] & 0xC0) >> 6) == 2) {
                        packetsReceived++;
                        logger.info("RTP packet {} received ({} bytes)", packetsReceived, packet.getLength());
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }

            if (packetsReceived >= 5) {
                logger.info("RTP validation SUCCESS: {} ({} packets)", rtspUrl, packetsReceived);
                RTSPStream stream = new RTSPStream("Main", rtspUrl);
                String sessionName = parseSdpSessionName(sdpContent);
                if (sessionName != null) {
                    stream.setSdpSessionName(sessionName);
                    logger.info("SDP session name extracted: {}", sessionName);
                }
                return stream;
            } else {
                logger.info("Insufficient RTP packets: {} (need 5)", packetsReceived);
                return null;
            }

        } catch (Exception e) {
            logger.info("RTP validation error: {}", e.getMessage());
            return null;
        } finally {
            // Cleanup - send TEARDOWN with auth
            if (sessionId != null && socket != null) {
                try {
                    OutputStream out = socket.getOutputStream();
                    StringBuilder teardownBuilder = new StringBuilder();
                    teardownBuilder.append("TEARDOWN ").append(rtspUrl).append(" RTSP/1.0\r\n");
                    teardownBuilder.append("CSeq: 5\r\n");
                    teardownBuilder.append("Session: ").append(sessionId).append("\r\n");
                    if (authorizationHeader != null) {
                        teardownBuilder.append("Authorization: ").append(authorizationHeader).append("\r\n");
                    }
                    teardownBuilder.append("\r\n");
                    out.write(teardownBuilder.toString().getBytes());
                    out.flush();
                } catch (Exception ignored) {
                }
            }
            if (rtpSocket != null)
                rtpSocket.close();
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Extract control URL from SDP content
     */
    private String extractControlUrlFromSdp(String sdp, String baseUrl) {
        String[] lines = sdp.split("\n");
        for (String line : lines) {
            if (line.startsWith("a=control:")) {
                String control = line.substring(10).trim();
                if (control.startsWith("rtsp://"))
                    return control;
                if (control.startsWith("/"))
                    return baseUrl + control;
                return baseUrl + "/" + control;
            }
        }
        return baseUrl;
    }

    /**
     * Validate RTSP URL by capturing one frame with FFMPEG.
     * Timeout: From config (default 10000ms)
     * Accuracy: ~98%
     */
    private RTSPStream validateWithFrameCapture(String rtspUrl, String username, String password) {
        int timeout = discoveryConfig.getTimeout();
        FFmpegFrameGrabber grabber = null;
        long startTime = System.currentTimeMillis();

        try {
            // Build authenticated URL
            String authUrl = rtspUrl;
            if (username != null && !username.isEmpty()) {
                String cleanUrl = rtspUrl.replaceFirst("rtsp://[^@]+@", "rtsp://");
                String creds = username + ":" + (password != null ? password : "");
                authUrl = cleanUrl.replace("rtsp://", "rtsp://" + creds + "@");
            }

            logger.info("FRAME CAPTURE VALIDATION");
            logger.info("URL: {}, Timeout: {}ms", rtspUrl, timeout);

            grabber = new FFmpegFrameGrabber(authUrl);

            // Optimized FFMPEG settings
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("stimeout", String.valueOf(timeout * 1000)); // Microseconds
            grabber.setOption("max_delay", "500000"); // 0.5s max delay
            grabber.setOption("reorder_queue_size", "0"); // No reordering
            grabber.setOption("fflags", "nobuffer"); // No buffering
            grabber.setOption("flags", "low_delay"); // Low latency
            grabber.setImageWidth(0); // Native resolution
            grabber.setImageHeight(0);

            grabber.start();

            Frame frame = grabber.grabFrame();
            long elapsedMs = System.currentTimeMillis() - startTime;

            if (frame != null && frame.image != null) {
                logger.info("Frame captured in {}ms - {}x{} pixels",
                        elapsedMs, frame.imageWidth, frame.imageHeight);
                return new RTSPStream("Main", rtspUrl);
            } else {
                logger.info("No valid frame after {}ms", elapsedMs);
                return null;
            }

        } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            logger.info("Frame capture failed after {}ms: {}", elapsedMs, msg);
            return null;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            logger.info("Error after {}ms: {}", elapsedMs, e.getMessage());
            return null;
        } finally {
            if (grabber != null) {
                try {
                    grabber.close();
                } catch (Exception e) {
                    logger.info("Cleanup error: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Validate SDP (Session Description Protocol) content from RTSP DESCRIBE
     * response.
     * Checks for presence of video media track to confirm stream is actually
     * available.
     *
     * SDP format (RFC 4566):
     * v=0 (version)
     * o=- 1234567890 1234567890 IN IP4 192.168.1.100 (origin)
     * s=Session (session name)
     * c=IN IP4 192.168.1.100 (connection info)
     * t=0 0 (time)
     * m=video 0 RTP/AVP 96 (media - THIS IS CRITICAL)
     * a=rtpmap:96 H264/90000 (codec)
     * a=control:track1 (control URL)
     *
     * @param sdpContent The SDP body from RTSP response
     * @param rtspUrl    The RTSP URL being tested (for logging)
     * @return true if SDP contains valid video stream, false otherwise
     */
    private boolean validateSdp(String sdpContent, String rtspUrl) {
        if (sdpContent == null || sdpContent.trim().isEmpty()) {
            logger.info("Empty SDP content for {}", rtspUrl);
            return false;
        }

        logger.info("Validating SDP content for {} - Length: {} bytes", rtspUrl, sdpContent.length());

        // Basic SDP validation
        boolean hasVersion = false;
        boolean hasVideoMedia = false;
        boolean hasCodecInfo = false;

        String[] lines = sdpContent.split("\r?\n");

        for (String line : lines) {
            line = line.trim();

            // Check for SDP version (required - RFC 4566)
            if (line.startsWith("v=")) {
                hasVersion = true;
                logger.info("SDP version line found: {}", line);
            }

            // Check for video media track (critical - proves video stream exists)
            if (line.startsWith("m=video")) {
                hasVideoMedia = true;
                logger.info("SDP video media track found: {}", line);
            }

            // Check for codec info (nice to have - indicates proper stream configuration)
            if (line.startsWith("a=rtpmap:") &&
                    (line.contains("H264") || line.contains("H265") || line.contains("HEVC") ||
                            line.contains("MPEG4") || line.contains("MJPEG") || line.contains("MP4V"))) {
                hasCodecInfo = true;
                logger.info("SDP codec info found: {}", line);
            }
        }

        // Version is required by RFC 4566
        if (!hasVersion) {
            logger.warn("SDP missing version line (v=) for {}", rtspUrl);
            return false;
        }

        // Video media track is critical - without it, there's no video stream
        if (!hasVideoMedia) {
            logger.warn("SDP missing video media track (m=video) for {} - May be audio-only or invalid stream",
                    rtspUrl);
            return false;
        }

        // Log codec info status (informational only - not required for validation)
        if (!hasCodecInfo) {
            logger.info("SDP missing explicit codec info (a=rtpmap) for {} - Stream may still work", rtspUrl);
        }

        logger.info("SDP validation PASSED for {} - Version: {}, Video: {}, Codec: {}",
                rtspUrl, hasVersion, hasVideoMedia, hasCodecInfo);

        return true;
    }

    /**
     * Parse session name (s= line) from SDP content.
     * The SDP session name often contains the device/camera name or model.
     *
     * @param sdpContent The SDP body from RTSP DESCRIBE response
     * @return Session name string, or null if not found or meaningless
     */
    private String parseSdpSessionName(String sdpContent) {
        if (sdpContent == null || sdpContent.isEmpty()) {
            return null;
        }

        String[] lines = sdpContent.split("\r?\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("s=")) {
                String name = line.substring(2).trim();
                // Filter out empty or meaningless placeholder values
                if (!name.isEmpty() && !name.equals("-") && !name.equals(" ")) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * Iterate NVR/DVR channels (1-64) and find working streams.
     * Uses detected RTSP ports from port scan.
     */
    public List<RTSPStream> iterateNvrChannels(Device device, String username, String password, int maxChannels) {
        List<RTSPStream> streams = new ArrayList<>();
        int consecutiveFailures = 0;
        int maxConsecutiveFailures = 3;

        // Get detected RTSP ports - NVR/DVR typically uses same port for all channels
        List<Integer> rtspPorts = getDetectedRtspPorts(device);

        // Skip if no RTSP ports detected
        if (rtspPorts.isEmpty()) {
            logger.info("Skipping NVR channel iteration for {} - no RTSP ports detected", device.getIpAddress());
            return streams;
        }

        int rtspPort = rtspPorts.get(0); // Use first detected port
        logger.info("Using RTSP port {} for NVR channel iteration", rtspPort);

        // Find NVR channel pattern for this manufacturer (from properties file)
        String manufacturer = device.getManufacturer();
        NvrChannelPattern pattern = findNvrPattern(manufacturer);

        if (pattern == null) {
            logger.warn("No NVR channel pattern found for manufacturer: {}", manufacturer);
            return streams;
        }

        logger.info("Using NVR channel pattern for {}: {}", manufacturer, pattern);

        for (int channel = 1; channel <= maxChannels; channel++) {
            String mainPath = pattern.getMainPath(channel);
            String subPath = pattern.getSubPath(channel);

            String mainUrl = "rtsp://" + device.getIpAddress() + ":" + rtspPort + mainPath;
            RTSPStream mainStream = validateRtspStream(mainUrl, username, password);

            if (mainStream != null) {
                mainStream.setStreamName("CH" + channel + " Main");
                mainStream.setChannelName("Channel " + channel);
                streams.add(mainStream);
                consecutiveFailures = 0;

                // Try sub stream
                String subUrl = "rtsp://" + device.getIpAddress() + ":" + rtspPort + subPath;
                RTSPStream subStream = validateRtspStream(subUrl, username, password);
                if (subStream != null) {
                    subStream.setStreamName("CH" + channel + " Sub");
                    subStream.setChannelName("Channel " + channel);
                    streams.add(subStream);
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    logger.info("Stopping NVR iteration after {} consecutive failures at channel {}",
                            maxConsecutiveFailures, channel);
                    break;
                }
            }
        }

        return streams;
    }

    /**
     * Guess sub-stream path from main stream path.
     */
    private String guessSubstreamPath(String mainPath) {
        if (mainPath.contains("/101")) {
            return mainPath.replace("/101", "/102");
        } else if (mainPath.contains("subtype=0")) {
            return mainPath.replace("subtype=0", "subtype=1");
        } else if (mainPath.contains("/main/")) {
            return mainPath.replace("/main/", "/sub/");
        } else if (mainPath.contains("_0")) {
            return mainPath.replace("_0", "_1");
        } else if (mainPath.contains("/0")) {
            return mainPath.replace("/0", "/1");
        } else if (mainPath.equals("/live")) {
            return "/live/1";
        }
        return null;
    }

    private String getMacPrefix(String mac) {
        if (mac != null && mac.length() >= 8) {
            return mac.substring(0, 8);
        }
        return null;
    }

    private String extractHost(String rtspUrl) {
        try {
            String temp = rtspUrl.replace("rtsp://", "");
            int colonIndex = temp.indexOf(":");
            int slashIndex = temp.indexOf("/");
            if (colonIndex > 0) {
                return temp.substring(0, colonIndex);
            } else if (slashIndex > 0) {
                return temp.substring(0, slashIndex);
            } else {
                return temp;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int extractPort(String rtspUrl) {
        try {
            String temp = rtspUrl.replace("rtsp://", "");
            int colonIndex = temp.indexOf(":");
            int slashIndex = temp.indexOf("/");
            if (colonIndex > 0 && slashIndex > colonIndex) {
                return Integer.parseInt(temp.substring(colonIndex + 1, slashIndex));
            }
        } catch (Exception e) {
            // Ignore
        }
        return 554; // Default RTSP port
    }

    private String extractUri(String rtspUrl) {
        try {
            int slashIndex = rtspUrl.indexOf("/", 7); // Skip "rtsp://"
            if (slashIndex > 0) {
                return rtspUrl.substring(slashIndex);
            }
        } catch (Exception e) {
            // Ignore
        }
        return "/";
    }
}
