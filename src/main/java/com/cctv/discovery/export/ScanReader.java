package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads back a scan written by {@link ReportWriter#writeJson}.
 *
 * <p>Deliberately a small hand-rolled parser rather than another dependency:
 * the only documents it has to read are ones this application wrote, and the
 * shape is fixed. Anything it does not recognise is reported rather than
 * guessed at.
 */
public final class ScanReader {

    private static final Logger logger = LoggerFactory.getLogger(ScanReader.class);

    /** A file that is not a scan this application wrote. */
    public static class NotAScanException extends IOException {
        public NotAScanException(String message) {
            super(message);
        }
    }

    /** A loaded scan. */
    public record Scan(String site, String generated, boolean includedCredentials, List<Device> devices) {
    }

    private ScanReader() {
    }

    public static Scan read(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Object parsed = new Json(json).parseValue();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new NotAScanException("The file does not contain a scan.");
        }
        Object format = root.get("format");
        if (!(format instanceof String text) || !text.startsWith("cctv-discovery/")) {
            throw new NotAScanException("The file was not written by this application.");
        }

        List<Device> devices = new ArrayList<>();
        Object list = root.get("devices");
        if (list instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map) {
                    devices.add(toDevice(map));
                }
            }
        }
        Scan scan = new Scan(string(root, "site"), string(root, "generated"),
                Boolean.TRUE.equals(root.get("includesCredentials")), devices);
        logger.info("Loaded a scan of {} with {} device(s)", scan.site(), devices.size());
        return scan;
    }

    private static Device toDevice(Map<?, ?> map) {
        Device device = new Device(string(map, "ip"));
        device.setDeviceName(string(map, "name"));
        device.setManufacturer(string(map, "manufacturer"));
        device.setModel(string(map, "model"));
        device.setFirmwareVersion(string(map, "firmware"));
        device.setSerialNumber(string(map, "serial"));
        device.setMacAddress(string(map, "mac"));
        device.setVendorFromMac(string(map, "macVendor"));
        device.setOnvifServiceUrl(string(map, "onvifService"));
        device.setDiscoverySource(string(map, "foundBy"));
        device.setErrorMessage(string(map, "note"));
        device.setUsername(string(map, "username"));
        device.setPassword(string(map, "password"));

        device.setStatus(enumOf(string(map, "status"), Device.DeviceStatus.class, Device.DeviceStatus.COMPLETED));
        device.setType(enumOf(string(map, "type"), Device.DeviceType.class, Device.DeviceType.UNKNOWN));
        device.setAuthFailed(device.getStatus() == Device.DeviceStatus.AUTH_FAILED
                && device.getRtspStreams().isEmpty()
                && "No credential was accepted".equals(device.getErrorMessage()));

        Double drift = number(map, "clockDriftSeconds");
        if (drift != null) {
            device.setTimeDifferenceSeconds(drift.longValue());
        }
        Object anonymous = map.get("anonymousRtsp");
        if (anonymous instanceof Boolean flag) {
            device.setRtspAnonymousAccess(flag);
        }
        if (map.get("openPorts") instanceof List<?> ports) {
            for (Object port : ports) {
                if (port instanceof Double value) {
                    device.getOpenSpecialPorts().add(value.intValue());
                }
            }
        }

        if (map.get("streams") instanceof List<?> streams) {
            for (Object entry : streams) {
                if (entry instanceof Map<?, ?> stream) {
                    device.addStream(toStream(stream));
                }
            }
        }
        if (map.get("findings") instanceof List<?> findings) {
            for (Object entry : findings) {
                if (entry instanceof Map<?, ?> finding) {
                    device.addFinding(new Finding(
                            enumOf(string(finding, "severity"), Finding.Severity.class, Finding.Severity.INFO),
                            string(finding, "category"),
                            string(finding, "title"),
                            string(finding, "detail"),
                            string(finding, "recommendation")));
                }
            }
        }
        return device;
    }

    private static RTSPStream toStream(Map<?, ?> map) {
        RTSPStream stream = new RTSPStream(string(map, "name"), string(map, "url"));
        stream.setSource(string(map, "source"));
        stream.setRole(enumOf(string(map, "role"), RTSPStream.Role.class, RTSPStream.Role.OTHER));
        stream.setCodec(string(map, "codec"));
        stream.setProfile(string(map, "profile"));
        stream.setAudioCodec(string(map, "audio"));
        stream.setComplianceIssues(string(map, "issues"));
        stream.setCompliant(!Boolean.FALSE.equals(map.get("compliant")));

        String resolution = string(map, "resolution");
        if (resolution != null && resolution.contains("x")) {
            try {
                String[] parts = resolution.split("x");
                stream.setDimensions(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (RuntimeException e) {
                logger.debug("Unreadable resolution '{}'", resolution);
            }
        }
        Double bitrate = number(map, "bitrateKbps");
        if (bitrate != null) {
            stream.setBitrateKbps(bitrate.intValue());
        }
        stream.setFps(number(map, "fps"));
        stream.setKeyframeIntervalSeconds(number(map, "keyframeSeconds"));
        stream.setAnalyzed(stream.getResolution() != null);
        return stream;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static Double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Double d ? d : null;
    }

    private static <E extends Enum<E>> E enumOf(String name, Class<E> type, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** A minimal recursive-descent JSON reader. */
    private static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object parseValue() throws IOException {
            skipSpace();
            if (at >= text.length()) {
                throw new NotAScanException("The file is empty.");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipSpace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipSpace();
                String key = parseString();
                skipSpace();
                expect(':');
                map.put(key, parseValue());
                skipSpace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new NotAScanException("Expected ',' or '}' at character " + at);
                }
            }
        }

        private List<Object> parseArray() throws IOException {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipSpace();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipSpace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new NotAScanException("Expected ',' or ']' at character " + at);
                }
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"', '\\', '/' -> sb.append(escape);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new NotAScanException("Unknown escape \\" + escape);
                }
            }
        }

        private Boolean parseBoolean() throws IOException {
            if (text.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            throw new NotAScanException("Expected true or false at character " + at);
        }

        private Object parseNull() throws IOException {
            if (!text.startsWith("null", at)) {
                throw new NotAScanException("Expected null at character " + at);
            }
            at += 4;
            return null;
        }

        private Double parseNumber() throws IOException {
            int start = at;
            while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            try {
                return Double.valueOf(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw new NotAScanException("Unreadable number at character " + start);
            }
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private char peek() throws IOException {
            if (at >= text.length()) {
                throw new NotAScanException("The file ends unexpectedly.");
            }
            return text.charAt(at);
        }

        private char next() throws IOException {
            char c = peek();
            at++;
            return c;
        }

        private void expect(char expected) throws IOException {
            skipSpace();
            char c = next();
            if (c != expected) {
                throw new NotAScanException("Expected '" + expected + "' at character " + (at - 1));
            }
        }
    }
}
