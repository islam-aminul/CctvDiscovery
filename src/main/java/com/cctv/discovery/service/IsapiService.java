package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.util.AuthUtils;
import com.cctv.discovery.util.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Device details from Hikvision's own web API, for the Hikvision firmware
 * family, Prama included.
 *
 * <p>ONVIF is off by default on current Hikvision firmware, and without it the
 * report had no model, firmware or serial number for any device. ISAPI answered
 * all three on a site where ONVIF did not: a DS-2CD1323G0E-I camera on V5.5.82
 * and a DS-7W08NI-Q1 recorder on V3.4.106, measured on 2026-10-09.
 *
 * <p>Asked only after RTSP has already accepted the password, so it never spends
 * a login attempt on a password the device has refused.
 */
public final class IsapiService {

    private static final Logger logger = LoggerFactory.getLogger(IsapiService.class);

    private static final String DEVICE_INFO = "/ISAPI/System/deviceInfo";

    /**
     * Hikvision recorder model families: DS-7 and DS-9 network and hybrid
     * recorders, DS-8 DVRs, and their iDS- variants. The device's own
     * {@code deviceType} cannot be used: the DS-7W08NI-Q1 recorder reports "IPC".
     */
    private static final Pattern RECORDER_MODEL = Pattern.compile("(?i)^I?DS-[789].*");

    /** What deviceInfo says about a device. */
    public record DeviceInfo(String deviceName, String model, String serialNumber, String firmwareVersion,
                             String macAddress) {

        /** A recorder by its model family or its name, never by its claimed type. */
        public boolean isRecorder() {
            if (model != null && RECORDER_MODEL.matcher(model.trim()).matches()) {
                return true;
            }
            String name = deviceName == null ? "" : deviceName.toLowerCase(Locale.ROOT);
            return name.contains("video recorder") || name.matches(".*\\b(nvr|dvr|hvr|xvr)\\b.*");
        }
    }

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public IsapiService() {
        AppConfig config = AppConfig.getInstance();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.max(500, config.getSocketConnectTimeout())))
                .build();
        this.requestTimeout = Duration.ofMillis(config.getSocketReadTimeout());
    }

    /**
     * Ask the first web port that answers for its device information.
     *
     * @param httpPorts the device's open HTTP ports; 8000, Hikvision's binary SDK
     *                  port, and 443 are skipped, and at most two are tried
     */
    public Optional<DeviceInfo> deviceInfo(String ip, List<Integer> httpPorts, String username, String password) {
        int tried = 0;
        for (int port : httpPorts) {
            if (port == 8000 || port == 443 || port == 8443) {
                continue;
            }
            if (tried++ >= 2) {
                break;
            }
            Optional<DeviceInfo> info = fetch("http://" + ip + (port == 80 ? "" : ":" + port) + DEVICE_INFO,
                    username, password);
            if (info.isPresent()) {
                return info;
            }
        }
        return Optional.empty();
    }

    private Optional<DeviceInfo> fetch(String url, String username, String password) {
        try {
            HttpResponse<String> first = get(url, null);
            HttpResponse<String> response = first;
            if (first.statusCode() == 401 && username != null && !username.isEmpty()) {
                List<AuthUtils.AuthChallenge> challenges =
                        AuthUtils.parseChallenges(first.headers().allValues("WWW-Authenticate"));
                if (challenges.isEmpty()) {
                    return Optional.empty();
                }
                AuthUtils.Authenticator authenticator =
                        new AuthUtils.Authenticator(challenges.getFirst(), username, password);
                response = get(url, authenticator.authorization("GET", DEVICE_INFO));
            }
            if (response.statusCode() != 200) {
                logger.debug("ISAPI {} answered {}", url, response.statusCode());
                return Optional.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("ISAPI {} failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private HttpResponse<String> get(String url, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", "CCTV-Discovery/2.0")
                .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Package-private so the parsing can be tested against recorded replies. */
    static Optional<DeviceInfo> parse(String xml) {
        if (xml == null || !xml.contains("DeviceInfo")) {
            return Optional.empty();
        }
        try {
            Document doc = XmlUtils.parse(xml);
            DeviceInfo info = new DeviceInfo(
                    blankToNull(XmlUtils.text(doc, "deviceName")),
                    blankToNull(XmlUtils.text(doc, "model")),
                    blankToNull(XmlUtils.text(doc, "serialNumber")),
                    blankToNull(XmlUtils.text(doc, "firmwareVersion")),
                    blankToNull(XmlUtils.text(doc, "macAddress")));
            return info.model() == null && info.serialNumber() == null ? Optional.empty() : Optional.of(info);
        } catch (Exception e) {
            logger.debug("ISAPI reply was not device information: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
