package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.RtspClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a scan as CSV, for spreadsheets and analysis tools, or as JSON, which
 * is also the format a saved scan is reloaded from.
 *
 * <p>Neither format is encrypted, so credentials are included only when the
 * caller asks for them.
 */
public final class ReportWriter {

    private static final Logger logger = LoggerFactory.getLogger(ReportWriter.class);

    private ReportWriter() {
    }

    // -------------------------------------------------------------------- CSV

    private static final List<String> CSV_HEADERS = List.of(
            "ip", "status", "type", "name", "manufacturer", "model", "firmware", "serial",
            "mac", "mac_vendor", "open_ports", "onvif_service", "clock_drift_seconds",
            "anonymous_rtsp", "found_by", "note",
            "stream", "role", "source", "rtsp_url", "resolution", "codec", "profile",
            "bitrate_kbps", "fps", "keyframe_seconds", "audio", "compliant", "issues");

    /**
     * One row per stream, and one row for each device that has none, so the
     * file can be filtered or pivoted without losing devices.
     */
    public static void writeCsv(List<Device> devices, boolean includeCredentials, Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(String.join(",", CSV_HEADERS));
            out.newLine();

            for (Device device : devices) {
                if (device.getRtspStreams().isEmpty()) {
                    out.write(csvRow(device, null, includeCredentials));
                    out.newLine();
                } else {
                    for (RTSPStream stream : device.getRtspStreams()) {
                        out.write(csvRow(device, stream, includeCredentials));
                        out.newLine();
                    }
                }
            }
        }
        logger.info("Wrote CSV for {} device(s) to {}", devices.size(), file);
    }

    private static String csvRow(Device device, RTSPStream stream, boolean includeCredentials) {
        List<String> values = new ArrayList<>(CSV_HEADERS.size());
        values.add(device.getIpAddress());
        values.add(device.getStatus().label());
        values.add(device.getDeviceType());
        values.add(device.getDeviceName());
        values.add(device.getManufacturer());
        values.add(device.getModel());
        values.add(device.getFirmwareVersion());
        values.add(device.getSerialNumber());
        values.add(device.getMacAddress());
        values.add(device.getVendorFromMac());
        values.add(device.getAllOpenPorts().toString().replace("[", "").replace("]", ""));
        values.add(device.getOnvifServiceUrl());
        values.add(device.getTimeDifferenceSeconds() == null ? "" : device.getTimeDifferenceSeconds().toString());
        values.add(device.getRtspAnonymousAccess() == null ? ""
                : (device.getRtspAnonymousAccess() ? "yes" : "no"));
        values.add(device.getDiscoverySource());
        values.add(device.getErrorMessage());

        if (stream == null) {
            for (int i = values.size(); i < CSV_HEADERS.size(); i++) {
                values.add("");
            }
        } else {
            values.add(stream.getStreamName());
            values.add(stream.getRole().label());
            values.add(stream.getSource());
            values.add(includeCredentials
                    ? RtspClient.withCredentials(stream.getRtspUrl(), device.getUsername(), device.getPassword())
                    : RtspClient.stripCredentials(stream.getRtspUrl()));
            values.add(stream.getResolution());
            values.add(stream.getCodec());
            values.add(stream.getProfile());
            values.add(stream.getBitrateKbps() == null ? "" : stream.getBitrateKbps().toString());
            values.add(stream.getFps() == null ? "" : String.format("%.2f", stream.getFps()));
            values.add(stream.getKeyframeIntervalSeconds() == null ? ""
                    : String.format("%.2f", stream.getKeyframeIntervalSeconds()));
            values.add(stream.getAudioCodec());
            values.add(stream.isCompliant() ? "yes" : "no");
            values.add(stream.getComplianceIssues());
        }

        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csvField(values.get(i)));
        }
        return row.toString();
    }

    /** Quote a field when it contains a comma, quote or line break (RFC 4180). */
    static String csvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------- JSON

    /** Write the scan as JSON. */
    public static void writeJson(List<Device> devices, String siteId, boolean includeCredentials, Path file)
            throws IOException {
        String json = toJson(devices, siteId, includeCredentials);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        logger.info("Wrote JSON for {} device(s) to {}", devices.size(), file);
    }

    static String toJson(List<Device> devices, String siteId, boolean includeCredentials) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"format\": \"cctv-discovery/1\",\n");
        sb.append("  \"site\": ").append(jsonString(siteId)).append(",\n");
        sb.append("  \"generated\": ").append(jsonString(Instant.now().toString())).append(",\n");
        sb.append("  \"includesCredentials\": ").append(includeCredentials).append(",\n");
        sb.append("  \"devices\": [\n");

        for (int i = 0; i < devices.size(); i++) {
            appendDevice(sb, devices.get(i), includeCredentials);
            sb.append(i < devices.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static void appendDevice(StringBuilder sb, Device device, boolean includeCredentials) {
        sb.append("    {\n");
        field(sb, "ip", device.getIpAddress(), true);
        field(sb, "status", device.getStatus().name(), true);
        field(sb, "type", device.getType().name(), true);
        field(sb, "name", device.getDeviceName(), true);
        field(sb, "manufacturer", device.getManufacturer(), true);
        field(sb, "model", device.getModel(), true);
        field(sb, "firmware", device.getFirmwareVersion(), true);
        field(sb, "serial", device.getSerialNumber(), true);
        field(sb, "mac", device.getMacAddress(), true);
        field(sb, "macVendor", device.getVendorFromMac(), true);
        field(sb, "onvifService", device.getOnvifServiceUrl(), true);
        field(sb, "foundBy", device.getDiscoverySource(), true);
        field(sb, "note", device.getErrorMessage(), true);
        if (includeCredentials) {
            field(sb, "username", device.getUsername(), true);
            field(sb, "password", device.getPassword(), true);
        }
        sb.append("      \"clockDriftSeconds\": ")
                .append(device.getTimeDifferenceSeconds() == null ? "null" : device.getTimeDifferenceSeconds())
                .append(",\n");
        sb.append("      \"anonymousRtsp\": ")
                .append(device.getRtspAnonymousAccess() == null ? "null" : device.getRtspAnonymousAccess())
                .append(",\n");
        sb.append("      \"openPorts\": ").append(device.getAllOpenPorts()).append(",\n");

        sb.append("      \"streams\": [");
        List<RTSPStream> streams = device.getRtspStreams();
        for (int i = 0; i < streams.size(); i++) {
            RTSPStream stream = streams.get(i);
            sb.append(i == 0 ? "\n" : "");
            sb.append("        {");
            sb.append("\"name\": ").append(jsonString(stream.getStreamName()));
            sb.append(", \"role\": ").append(jsonString(stream.getRole().name()));
            sb.append(", \"source\": ").append(jsonString(stream.getSource()));
            sb.append(", \"url\": ").append(jsonString(includeCredentials
                    ? RtspClient.withCredentials(stream.getRtspUrl(), device.getUsername(), device.getPassword())
                    : RtspClient.stripCredentials(stream.getRtspUrl())));
            sb.append(", \"resolution\": ").append(jsonString(stream.getResolution()));
            sb.append(", \"codec\": ").append(jsonString(stream.getCodec()));
            sb.append(", \"profile\": ").append(jsonString(stream.getProfile()));
            sb.append(", \"bitrateKbps\": ").append(stream.getBitrateKbps() == null ? "null" : stream.getBitrateKbps());
            sb.append(", \"fps\": ").append(stream.getFps() == null ? "null" : stream.getFps());
            sb.append(", \"keyframeSeconds\": ").append(stream.getKeyframeIntervalSeconds() == null
                    ? "null" : stream.getKeyframeIntervalSeconds());
            sb.append(", \"audio\": ").append(jsonString(stream.getAudioCodec()));
            sb.append(", \"compliant\": ").append(stream.isCompliant());
            sb.append(", \"issues\": ").append(jsonString(stream.getComplianceIssues()));
            sb.append('}').append(i < streams.size() - 1 ? ",\n" : "\n      ");
        }
        sb.append("],\n");

        sb.append("      \"findings\": [");
        List<Finding> findings = device.getFindings();
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            sb.append(i == 0 ? "\n" : "");
            sb.append("        {");
            sb.append("\"severity\": ").append(jsonString(finding.severity().name()));
            sb.append(", \"category\": ").append(jsonString(finding.category()));
            sb.append(", \"title\": ").append(jsonString(finding.title()));
            sb.append(", \"detail\": ").append(jsonString(finding.detail()));
            sb.append(", \"recommendation\": ").append(jsonString(finding.recommendation()));
            sb.append('}').append(i < findings.size() - 1 ? ",\n" : "\n      ");
        }
        sb.append("]\n");
        sb.append("    }");
    }

    private static void field(StringBuilder sb, String name, String value, boolean comma) {
        sb.append("      \"").append(name).append("\": ").append(jsonString(value));
        sb.append(comma ? ",\n" : "\n");
    }

    /** JSON string literal, or {@code null} when the value is absent. */
    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
