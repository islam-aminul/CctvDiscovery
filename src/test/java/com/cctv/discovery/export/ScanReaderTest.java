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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanReaderTest {

    private static final String PASSWORD = "p@ss,with\"quotes\nand a newline";

    @TempDir
    Path dir;

    private static List<Device> sample() {
        Device camera = new Device("192.168.0.113");
        camera.setType(Device.DeviceType.CAMERA);
        camera.setDeviceName("Front gate, west");
        camera.setManufacturer("CP Plus");
        camera.setModel("CP_Plus_Wi-Fi_camera");
        camera.setFirmwareVersion("1.0");
        camera.setMacAddress("28:18:FD:F1:E5:BE");
        camera.setVendorFromMac("CP Plus");
        camera.setUsername("admin");
        camera.setPassword(PASSWORD);
        camera.setStatus(Device.DeviceStatus.COMPLETED);
        camera.setTimeDifferenceSeconds(3L);
        camera.setRtspAnonymousAccess(Boolean.TRUE);
        camera.getOpenRtspPorts().add(5543);

        RTSPStream main = new RTSPStream("PROFILE_1", "rtsp://192.168.0.113:5543/live/channel0");
        main.setRole(RTSPStream.Role.MAIN);
        main.setSource("ONVIF");
        main.setDimensions(1920, 1080);
        main.setCodec("H.264");
        main.setProfile("High 5.1");
        main.setAudioCodec("G.711 A-law");
        main.setBitrateKbps(951);
        main.setFps(9.17);
        main.setKeyframeIntervalSeconds(3.27);
        main.setCompliant(false);
        main.setComplianceIssues("High profile (needs transcoding)");
        camera.addStream(main);

        camera.addFinding(new Finding(Finding.Severity.HIGH, "Security",
                "Readable without a password", "No credentials needed", "Turn on authentication"));

        Device bare = new Device("192.168.0.50");
        bare.setStatus(Device.DeviceStatus.AUTH_FAILED);
        bare.setErrorMessage("Not a camera or recorder");
        return List.of(camera, bare);
    }

    private Path write(boolean includeCredentials) throws Exception {
        Path file = dir.resolve("scan-" + includeCredentials + ".json");
        ReportWriter.writeJson(sample(), "SITE-1", includeCredentials, file);
        return file;
    }

    @Test
    @DisplayName("A scan survives a round trip through the file")
    void roundTrips() throws Exception {
        ScanReader.Scan scan = ScanReader.read(write(true));

        assertEquals("SITE-1", scan.site());
        assertTrue(scan.includedCredentials());
        assertEquals(2, scan.devices().size());

        Device camera = scan.devices().getFirst();
        assertEquals("192.168.0.113", camera.getIpAddress());
        assertEquals("Front gate, west", camera.getDeviceName());
        assertEquals("CP Plus", camera.getManufacturer());
        assertEquals("28:18:FD:F1:E5:BE", camera.getMacAddress());
        assertEquals(Device.DeviceType.CAMERA, camera.getType());
        assertEquals(Device.DeviceStatus.COMPLETED, camera.getStatus());
        assertEquals(3L, camera.getTimeDifferenceSeconds());
        assertEquals(Boolean.TRUE, camera.getRtspAnonymousAccess());
    }

    @Test
    @DisplayName("Measured values come back as numbers, not text")
    void keepsMeasurements() throws Exception {
        RTSPStream stream = ScanReader.read(write(true)).devices().getFirst().getRtspStreams().getFirst();

        assertEquals("1920x1080", stream.getResolution());
        assertEquals(1920, stream.getWidth());
        assertEquals(1080, stream.getHeight());
        assertEquals("H.264", stream.getCodec());
        assertEquals("High 5.1", stream.getProfile());
        assertEquals(951, stream.getBitrateKbps());
        assertEquals(9.17, stream.getFps());
        assertEquals(3.27, stream.getKeyframeIntervalSeconds());
        assertEquals(RTSPStream.Role.MAIN, stream.getRole());
        assertFalse(stream.isCompliant());
        assertTrue(stream.getComplianceIssues().contains("High profile"));
    }

    @Test
    @DisplayName("Findings come back with their severity and advice")
    void keepsFindings() throws Exception {
        Device camera = ScanReader.read(write(true)).devices().getFirst();
        assertEquals(1, camera.getFindings().size());

        Finding finding = camera.getFindings().getFirst();
        assertEquals(Finding.Severity.HIGH, finding.severity());
        assertEquals("Security", finding.category());
        assertEquals("Turn on authentication", finding.recommendation());
    }

    @Test
    @DisplayName("A password with quotes, commas and a newline survives intact")
    void keepsAwkwardPassword() throws Exception {
        Device camera = ScanReader.read(write(true)).devices().getFirst();
        assertEquals("admin", camera.getUsername());
        assertEquals(PASSWORD, camera.getPassword());
    }

    @Test
    @DisplayName("A scan saved without credentials loads without them")
    void honoursOmittedCredentials() throws Exception {
        ScanReader.Scan scan = ScanReader.read(write(false));
        assertFalse(scan.includedCredentials());

        Device camera = scan.devices().getFirst();
        assertNull(camera.getPassword());
        assertFalse(camera.getRtspStreams().getFirst().getRtspUrl().contains("admin"));
    }

    @Test
    @DisplayName("A device with no streams still loads, with its reason")
    void keepsDevicesWithoutStreams() throws Exception {
        Device bare = ScanReader.read(write(true)).devices().get(1);
        assertEquals("192.168.0.50", bare.getIpAddress());
        assertTrue(bare.getRtspStreams().isEmpty());
        assertEquals("Not a camera or recorder", bare.getErrorMessage());
    }

    @Test
    @DisplayName("Another application's JSON is refused by name, not by crashing")
    void refusesForeignJson() throws Exception {
        Path file = dir.resolve("other.json");
        Files.writeString(file, "{\"hello\": \"world\"}", StandardCharsets.UTF_8);

        ScanReader.NotAScanException e = assertThrows(ScanReader.NotAScanException.class,
                () -> ScanReader.read(file));
        assertTrue(e.getMessage().contains("not written by this application"));
    }

    @Test
    @DisplayName("A truncated or malformed file reports where it went wrong")
    void refusesMalformedJson() throws Exception {
        Path truncated = dir.resolve("truncated.json");
        Files.writeString(truncated, "{\"format\": \"cctv-discovery/1\", \"devices\": [{\"ip\":");
        assertThrows(java.io.IOException.class, () -> ScanReader.read(truncated));

        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "");
        assertThrows(java.io.IOException.class, () -> ScanReader.read(empty));

        Path rubbish = dir.resolve("rubbish.json");
        Files.writeString(rubbish, "this is not json at all");
        assertThrows(java.io.IOException.class, () -> ScanReader.read(rubbish));
    }

    @Test
    @DisplayName("An unknown status or role falls back rather than failing the load")
    void toleratesUnknownEnums() throws Exception {
        Path file = dir.resolve("future.json");
        Files.writeString(file, """
                {
                  "format": "cctv-discovery/2",
                  "site": "SITE-9",
                  "includesCredentials": false,
                  "devices": [
                    {"ip": "10.0.0.1", "status": "TELEPORTED", "type": "HOVERCAM",
                     "streams": [{"name": "s", "url": "rtsp://10.0.0.1/s", "role": "DIAGONAL"}],
                     "findings": [{"severity": "CATASTROPHIC", "title": "t"}]}
                  ]
                }""", StandardCharsets.UTF_8);

        ScanReader.Scan scan = ScanReader.read(file);
        Device device = scan.devices().getFirst();
        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertEquals(Device.DeviceType.UNKNOWN, device.getType());
        assertEquals(RTSPStream.Role.OTHER, device.getRtspStreams().getFirst().getRole());
        assertEquals(Finding.Severity.INFO, device.getFindings().getFirst().severity());
    }
}
