package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {

    private static final String CAMERA_PASSWORD = "p@ss,with\"quotes";

    @TempDir
    Path dir;

    private static List<Device> sample() {
        Device camera = new Device("192.168.0.113");
        camera.setType(Device.DeviceType.CAMERA);
        camera.setDeviceName("Front gate, west");   // deliberate comma
        camera.setManufacturer("CP Plus");
        camera.setMacAddress("28:18:FD:F1:E5:BE");
        camera.setUsername("admin");
        camera.setPassword(CAMERA_PASSWORD);
        camera.setStatus(Device.DeviceStatus.COMPLETED);
        camera.getOpenRtspPorts().add(5543);

        RTSPStream main = new RTSPStream("PROFILE_1", "rtsp://192.168.0.113:5543/live/channel0");
        main.setRole(RTSPStream.Role.MAIN);
        main.setDimensions(1920, 1080);
        main.setCodec("H.264");
        main.setProfile("High 5.1");
        main.setBitrateKbps(951);
        main.setFps(9.17);
        main.setCompliant(false);
        main.setComplianceIssues("High profile (needs transcoding), see notes");
        camera.addStream(main);

        camera.addFinding(new Finding(Finding.Severity.HIGH, "Security",
                "Readable without a password", "No credentials needed", "Turn on authentication"));

        Device bare = new Device("192.168.0.50");
        bare.setStatus(Device.DeviceStatus.AUTH_FAILED);
        bare.setErrorMessage("Not a camera or recorder");
        return List.of(camera, bare);
    }

    // --------------------------------------------------------------------- CSV

    @Test
    @DisplayName("Every device appears, including one with no streams")
    void csvKeepsDevicesWithoutStreams() throws Exception {
        Path file = dir.resolve("report.csv");
        ReportWriter.writeCsv(sample(), false, file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "header plus one row per stream plus the streamless device");
        assertTrue(lines.get(0).startsWith("ip,status,type"));
        assertTrue(lines.get(2).startsWith("192.168.0.50,"));
    }

    @Test
    @DisplayName("Commas and quotes in values do not break the columns")
    void csvQuotesAwkwardValues() throws Exception {
        Path file = dir.resolve("quoting.csv");
        ReportWriter.writeCsv(sample(), true, file);
        String text = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(text.contains("\"Front gate, west\""), "a comma in a name is quoted");
        assertTrue(text.contains("\"High profile (needs transcoding), see notes\""));

        // Field-level rules, checked directly.
        assertEquals("plain", ReportWriter.csvField("plain"));
        assertEquals("\"a,b\"", ReportWriter.csvField("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ReportWriter.csvField("say \"hi\""));
        assertEquals("\"two\nlines\"", ReportWriter.csvField("two\nlines"));
        assertEquals("", ReportWriter.csvField(null));
    }

    @Test
    @DisplayName("Credentials are left out of CSV unless asked for")
    void csvHonoursCredentialChoice() throws Exception {
        Path without = dir.resolve("without.csv");
        ReportWriter.writeCsv(sample(), false, without);
        assertFalse(Files.readString(without).contains(CAMERA_PASSWORD));

        Path with = dir.resolve("with.csv");
        ReportWriter.writeCsv(sample(), true, with);
        assertTrue(Files.readString(with).contains("admin:"), "the URL carries the credentials");
    }

    // -------------------------------------------------------------------- JSON

    @Test
    @DisplayName("JSON is well formed and carries the measured values")
    void jsonIsWellFormed() throws Exception {
        Path file = dir.resolve("report.json");
        ReportWriter.writeJson(sample(), "SITE-1", false, file);
        String json = Files.readString(file, StandardCharsets.UTF_8);

        assertBalanced(json);
        assertTrue(json.contains("\"site\": \"SITE-1\""));
        assertTrue(json.contains("\"bitrateKbps\": 951"));
        assertTrue(json.contains("\"fps\": 9.17"));
        assertTrue(json.contains("\"severity\": \"HIGH\""));
        assertTrue(json.contains("\"compliant\": false"));
        assertTrue(json.contains("\"clockDriftSeconds\": null"), "absent numbers are null, not empty");
    }

    @Test
    @DisplayName("Quotes and control characters in values are escaped")
    void jsonEscapesValues() {
        assertEquals("\"say \\\"hi\\\"\"", ReportWriter.jsonString("say \"hi\""));
        assertEquals("\"back\\\\slash\"", ReportWriter.jsonString("back\\slash"));
        assertEquals("\"two\\nlines\"", ReportWriter.jsonString("two\nlines"));
        assertEquals("\"\\u0001\"", ReportWriter.jsonString("\u0001"));
        assertEquals("null", ReportWriter.jsonString(null));
    }

    @Test
    @DisplayName("A password with quotes and commas survives JSON escaping")
    void jsonHandlesAwkwardPassword() throws Exception {
        Path file = dir.resolve("credentials.json");
        ReportWriter.writeJson(sample(), "SITE-1", true, file);
        String json = Files.readString(file, StandardCharsets.UTF_8);

        assertBalanced(json);
        assertTrue(json.contains("\\\""), "the quote inside the password is escaped");
        assertFalse(json.contains("\"password\": \"p@ss,with\"quotes\""), "never emitted raw");
    }

    @Test
    @DisplayName("Credentials are left out of JSON unless asked for")
    void jsonHonoursCredentialChoice() throws Exception {
        Path file = dir.resolve("no-credentials.json");
        ReportWriter.writeJson(sample(), "SITE-1", false, file);
        String json = Files.readString(file);

        assertFalse(json.contains(CAMERA_PASSWORD));
        assertFalse(json.contains("\"password\""));
        assertTrue(json.contains("\"includesCredentials\": false"));
    }

    /** Braces and brackets balance, and quotes pair, outside string literals. */
    private static void assertBalanced(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                switch (c) {
                    case '{' -> braces++;
                    case '}' -> braces--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                    default -> { }
                }
            }
            assertTrue(braces >= 0 && brackets >= 0, "closed more than was opened at position " + i);
        }
        assertFalse(inString, "a string literal was left open");
        assertEquals(0, braces, "unbalanced braces");
        assertEquals(0, brackets, "unbalanced brackets");
    }
}
