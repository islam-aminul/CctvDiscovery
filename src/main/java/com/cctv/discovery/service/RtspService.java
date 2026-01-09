package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RTSP service for stream discovery, URL guessing, and authentication.
 * Implements smart cache, manufacturer-specific paths, and generic fallbacks.
 */
public class RtspService {
    private static final Logger logger = LoggerFactory.getLogger(RtspService.class);

    // Smart cache: MAC prefix -> successful RTSP paths
    private static final Map<String, List<String>> SMART_CACHE = new HashMap<>();

    // Manufacturer-specific RTSP path templates
    private static final Map<String, String[]> MANUFACTURER_PATHS = new HashMap<>();

    static {
        // Hikvision
        MANUFACTURER_PATHS.put("HIKVISION", new String[]{
                "/Streaming/Channels/101", // Main stream
                "/Streaming/Channels/102", // Sub stream
                "/h264/ch1/main/av_stream",
                "/h264/ch1/sub/av_stream"
        });

        // Dahua
        MANUFACTURER_PATHS.put("DAHUA", new String[]{
                "/cam/realmonitor?channel=1&subtype=0", // Main
                "/cam/realmonitor?channel=1&subtype=1", // Sub
                "/live/ch00_0",
                "/live/ch00_1"
        });

        // Axis
        MANUFACTURER_PATHS.put("AXIS", new String[]{
                "/axis-media/media.amp",
                "/axis-media/media.amp?videocodec=h264",
                "/mpeg4/media.amp"
        });

        // CP Plus (India)
        MANUFACTURER_PATHS.put("CP PLUS", new String[]{
                "/cam/realmonitor?channel=1&subtype=0",
                "/cam/realmonitor?channel=1&subtype=1"
        });

        // Generic paths
        MANUFACTURER_PATHS.put("GENERIC", new String[]{
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

        // Get detected RTSP ports, default to [554] if none found
        List<Integer> rtspPorts = getDetectedRtspPorts(device);
        logger.debug("Device {} - Detected RTSP ports: {}", device.getIpAddress(), rtspPorts);

        List<String> pathsToTry = new ArrayList<>();

        // 1. Try smart cache first
        if (macPrefix != null && SMART_CACHE.containsKey(macPrefix)) {
            pathsToTry.addAll(SMART_CACHE.get(macPrefix));
            logger.debug("Using smart cache for MAC prefix: {}", macPrefix);
        }

        // 2. Try manufacturer-specific paths
        if (manufacturer != null) {
            String[] mfgPaths = MANUFACTURER_PATHS.get(manufacturer.toUpperCase());
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
            logger.debug("Trying {} custom RTSP path pairs from configuration", customPaths.length / 2);

            // Process custom paths as pairs
            for (int i = 0; i < customPaths.length; i += 2) {
                String mainPath = customPaths[i];
                String subPath = customPaths[i + 1];

                logger.debug("Trying custom path pair: main={}, sub={}", mainPath, subPath);

                // Try main stream on each detected RTSP port
                for (int port : rtspPorts) {
                    String mainUrl = "rtsp://" + device.getIpAddress() + ":" + port + mainPath;
                    RTSPStream mainStream = testRtspUrl(mainUrl, username, password);

                    if (mainStream != null) {
                        streams.add(mainStream);
                        logger.info("Found working custom main stream: {}", mainUrl);

                        // Try paired sub stream on same port
                        String subUrl = "rtsp://" + device.getIpAddress() + ":" + port + subPath;
                        RTSPStream subStream = testRtspUrl(subUrl, username, password);
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
                RTSPStream stream = testRtspUrl(rtspUrl, username, password);

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
                            RTSPStream subStream = testRtspUrl(subUrl, username, password);
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
     * Get detected RTSP ports from device port scan results.
     * Defaults to [554] if no RTSP ports were detected.
     */
    private List<Integer> getDetectedRtspPorts(Device device) {
        List<Integer> ports = new ArrayList<>();

        // Add detected RTSP ports
        if (device.getOpenRtspPorts() != null && !device.getOpenRtspPorts().isEmpty()) {
            ports.addAll(device.getOpenRtspPorts());
            logger.info("Using detected RTSP ports for {}: {}", device.getIpAddress(), ports);
        }

        // If no RTSP ports detected, default to standard port 554
        if (ports.isEmpty()) {
            ports.add(554);
            logger.info("No RTSP ports detected for {}, using default port 554", device.getIpAddress());
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

            out.write(request.getBytes());
            out.flush();

            // Read response
            String line = in.readLine();
            if (line == null) {
                logger.debug("No response from RTSP URL: {}", rtspUrl);
                return null;
            }

            if (line.contains("401")) {
                // Unauthorized - need authentication
                logger.debug("RTSP URL requires authentication: {}", rtspUrl);
                return testRtspUrlWithAuth(rtspUrl, username, password, socket, in, out);
            } else if (line.contains("200")) {
                // Success - validate SDP content
                logger.debug("Got 200 OK from RTSP URL, validating SDP: {}", rtspUrl);

                // Read headers to get Content-Length
                int contentLength = 0;
                String headerLine;
                while ((headerLine = in.readLine()) != null) {
                    if (headerLine.isEmpty()) {
                        break; // End of headers
                    }
                    if (headerLine.startsWith("Content-Length:")) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(15).trim());
                        } catch (NumberFormatException e) {
                            logger.debug("Invalid Content-Length header");
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

                // Validate SDP content
                if (validateSdp(sdpBody.toString(), rtspUrl)) {
                    logger.info("RTSP URL validated with SDP: {}", rtspUrl);
                    RTSPStream stream = new RTSPStream("Main", rtspUrl);
                    return stream;
                } else {
                    logger.warn("RTSP URL returned 200 OK but invalid/missing SDP: {}", rtspUrl);
                    return null;
                }
            } else {
                logger.debug("RTSP URL returned non-success code: {} - {}", line, rtspUrl);
            }

        } catch (SocketTimeoutException e) {
            logger.debug("Timeout testing RTSP URL: {}", rtspUrl);
        } catch (Exception e) {
            logger.debug("Error testing RTSP URL {}: {}", rtspUrl, e.getMessage());
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
     * Test RTSP URL with Digest authentication.
     */
    private RTSPStream testRtspUrlWithAuth(String rtspUrl, String username, String password,
                                           Socket socket, BufferedReader in, OutputStream out) {
        try {
            // Read authentication challenge
            String realm = null;
            String nonce = null;
            String line;

            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("WWW-Authenticate:")) {
                    AuthUtils.DigestChallenge challenge = AuthUtils.parseDigestChallenge(line.substring(17));
                    if (challenge != null) {
                        realm = challenge.realm;
                        nonce = challenge.nonce;
                    }
                }
            }

            if (realm == null || nonce == null) {
                logger.debug("Missing realm or nonce in authentication challenge");
                return null;
            }

            // Send authenticated request
            String uri = extractUri(rtspUrl);
            String authHeader = AuthUtils.buildDigestAuthHeader(username, password, realm, nonce, uri, "DESCRIBE", null);

            String authRequest = "DESCRIBE " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: 2\r\n" +
                    "User-Agent: CCTV-Discovery/1.0\r\n" +
                    "Authorization: " + authHeader + "\r\n" +
                    "Accept: application/sdp\r\n" +
                    "\r\n";

            out.write(authRequest.getBytes());
            out.flush();

            String responseLine = in.readLine();
            if (responseLine != null && responseLine.contains("200")) {
                logger.debug("Got 200 OK with authentication, validating SDP: {}", rtspUrl);

                // Read headers to get Content-Length
                int contentLength = 0;
                String headerLine;
                while ((headerLine = in.readLine()) != null) {
                    if (headerLine.isEmpty()) {
                        break; // End of headers
                    }
                    if (headerLine.startsWith("Content-Length:")) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(15).trim());
                        } catch (NumberFormatException e) {
                            logger.debug("Invalid Content-Length header");
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

                // Validate SDP content
                if (validateSdp(sdpBody.toString(), rtspUrl)) {
                    logger.info("RTSP URL authenticated and validated with SDP: {}", rtspUrl);
                    RTSPStream stream = new RTSPStream("Main", rtspUrl);
                    return stream;
                } else {
                    logger.warn("RTSP URL authenticated but invalid/missing SDP: {}", rtspUrl);
                    return null;
                }
            } else {
                logger.debug("Authentication failed for RTSP URL: {}", rtspUrl);
            }

        } catch (Exception e) {
            logger.debug("Error during RTSP authentication for {}: {}", rtspUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Validate SDP (Session Description Protocol) content from RTSP DESCRIBE response.
     * Checks for presence of video media track to confirm stream is actually available.
     *
     * SDP format (RFC 4566):
     * v=0                               (version)
     * o=- 1234567890 1234567890 IN IP4 192.168.1.100  (origin)
     * s=Session                         (session name)
     * c=IN IP4 192.168.1.100           (connection info)
     * t=0 0                            (time)
     * m=video 0 RTP/AVP 96             (media - THIS IS CRITICAL)
     * a=rtpmap:96 H264/90000           (codec)
     * a=control:track1                 (control URL)
     *
     * @param sdpContent The SDP body from RTSP response
     * @param rtspUrl The RTSP URL being tested (for logging)
     * @return true if SDP contains valid video stream, false otherwise
     */
    private boolean validateSdp(String sdpContent, String rtspUrl) {
        if (sdpContent == null || sdpContent.trim().isEmpty()) {
            logger.debug("Empty SDP content for {}", rtspUrl);
            return false;
        }

        logger.debug("Validating SDP content for {} - Length: {} bytes", rtspUrl, sdpContent.length());

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
                logger.debug("SDP version line found: {}", line);
            }

            // Check for video media track (critical - proves video stream exists)
            if (line.startsWith("m=video")) {
                hasVideoMedia = true;
                logger.debug("SDP video media track found: {}", line);
            }

            // Check for codec info (nice to have - indicates proper stream configuration)
            if (line.startsWith("a=rtpmap:") &&
                (line.contains("H264") || line.contains("H265") || line.contains("HEVC") ||
                 line.contains("MPEG4") || line.contains("MJPEG") || line.contains("MP4V"))) {
                hasCodecInfo = true;
                logger.debug("SDP codec info found: {}", line);
            }
        }

        // Version is required by RFC 4566
        if (!hasVersion) {
            logger.warn("SDP missing version line (v=) for {}", rtspUrl);
            return false;
        }

        // Video media track is critical - without it, there's no video stream
        if (!hasVideoMedia) {
            logger.warn("SDP missing video media track (m=video) for {} - May be audio-only or invalid stream", rtspUrl);
            return false;
        }

        // Log codec info status (informational only - not required for validation)
        if (!hasCodecInfo) {
            logger.debug("SDP missing explicit codec info (a=rtpmap) for {} - Stream may still work", rtspUrl);
        }

        logger.info("SDP validation PASSED for {} - Version: {}, Video: {}, Codec: {}",
                    rtspUrl, hasVersion, hasVideoMedia, hasCodecInfo);

        return true;
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
        int rtspPort = rtspPorts.get(0); // Use first detected port
        logger.debug("Using RTSP port {} for NVR channel iteration", rtspPort);

        String manufacturer = device.getManufacturer();
        boolean isHikvision = manufacturer != null && manufacturer.toUpperCase().contains("HIKVISION");
        boolean isDahua = manufacturer != null && manufacturer.toUpperCase().contains("DAHUA");

        for (int channel = 1; channel <= maxChannels; channel++) {
            String mainPath = null;
            String subPath = null;

            if (isHikvision) {
                mainPath = "/Streaming/Channels/" + (channel * 100 + 1);
                subPath = "/Streaming/Channels/" + (channel * 100 + 2);
            } else if (isDahua) {
                mainPath = "/cam/realmonitor?channel=" + channel + "&subtype=0";
                subPath = "/cam/realmonitor?channel=" + channel + "&subtype=1";
            } else {
                mainPath = "/ch" + String.format("%02d", channel) + "/0";
                subPath = "/ch" + String.format("%02d", channel) + "/1";
            }

            String mainUrl = "rtsp://" + device.getIpAddress() + ":" + rtspPort + mainPath;
            RTSPStream mainStream = testRtspUrl(mainUrl, username, password);

            if (mainStream != null) {
                mainStream.setStreamName("CH" + channel + " Main");
                mainStream.setChannelName("Channel " + channel);
                streams.add(mainStream);
                consecutiveFailures = 0;

                // Try sub stream
                String subUrl = "rtsp://" + device.getIpAddress() + ":" + rtspPort + subPath;
                RTSPStream subStream = testRtspUrl(subUrl, username, password);
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
