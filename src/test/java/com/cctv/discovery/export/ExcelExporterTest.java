package com.cctv.discovery.export;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelExporterTest {

    private static final String CAMERA_PASSWORD = "camera-secret-42";
    private static final String FILE_PASSWORD = "workbook-secret-99";

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void isolateSettings() {
        AppConfig.forTesting(tempDir.resolve("user-settings.properties"));
    }

    private static List<Device> sampleDevices() {
        Device camera = new Device("192.168.0.113");
        camera.setType(Device.DeviceType.CAMERA);
        camera.setDeviceName("Front gate");
        camera.setManufacturer("CP Plus");
        camera.setModel("CP_Plus_Wi-Fi_camera");
        camera.setMacAddress("28:18:FD:F1:E5:BE");
        camera.setVendorFromMac("CP Plus");
        camera.setUsername("admin");
        camera.setPassword(CAMERA_PASSWORD);
        camera.setStatus(Device.DeviceStatus.COMPLETED);
        camera.setTimeDifferenceSeconds(3L);
        camera.setRtspAnonymousAccess(Boolean.TRUE);
        camera.getOpenRtspPorts().add(5543);
        camera.getOpenOnvifPorts().add(8000);

        RTSPStream main = new RTSPStream("PROFILE_1", "rtsp://192.168.0.113:5543/live/channel0");
        main.setRole(RTSPStream.Role.MAIN);
        main.setDimensions(1920, 1080);
        main.setCodec("H.264");
        main.setProfile("High 5.1");
        main.setBitrateKbps(115);
        main.setFps(9.17);
        main.setCompliant(false);
        main.setComplianceIssues("High profile (needs transcoding for browser playback)");
        camera.addStream(main);

        camera.addFinding(new Finding(Finding.Severity.HIGH, "Security",
                "Video stream readable without a password",
                "RTSP DESCRIBE succeeded with no credentials.",
                "Enable RTSP authentication on the device."));

        Device unknown = new Device("192.168.0.50");
        unknown.setStatus(Device.DeviceStatus.AUTH_FAILED);
        unknown.setErrorMessage("Not a camera or recorder");

        return List.of(camera, unknown);
    }

    private static XSSFWorkbook decrypt(File file, String password) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
            EncryptionInfo info = new EncryptionInfo(fs);
            Decryptor decryptor = Decryptor.getInstance(info);
            if (!decryptor.verifyPassword(password)) {
                throw new IllegalArgumentException("wrong password");
            }
            try (InputStream in = decryptor.getDataStream(fs)) {
                return new XSSFWorkbook(in);
            }
        }
    }

    @Test
    @DisplayName("An encrypted workbook opens with the password and not without it")
    void encryptsWorkbook() throws Exception {
        File file = tempDir.resolve("encrypted.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null,
                new ExcelExporter.ReportOptions("SITE-1", null, null, true, FILE_PASSWORD), file);

        assertTrue(file.length() > 0);

        try (XSSFWorkbook workbook = decrypt(file, FILE_PASSWORD)) {
            assertNotNull(workbook.getSheet("Devices"));
        }

        assertThrows(IllegalArgumentException.class, () -> decrypt(file, "not-the-password"));

        // Plain unzipping is how the old sheet-protected file was read.
        assertThrows(Exception.class, () -> {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                zip.size();
            }
        }, "an encrypted workbook is not a readable zip");
    }

    @Test
    @DisplayName("Camera passwords do not appear in the bytes of an encrypted file")
    void encryptedFileHidesCredentials() throws Exception {
        File file = tempDir.resolve("encrypted-credentials.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null,
                new ExcelExporter.ReportOptions("SITE-1", null, null, true, FILE_PASSWORD), file);

        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(CAMERA_PASSWORD), "the camera password must not be readable in the file");

        // It is present once decrypted, because the report is meant to carry it.
        try (XSSFWorkbook workbook = decrypt(file, FILE_PASSWORD)) {
            assertTrue(sheetText(workbook.getSheet("Devices")).contains(CAMERA_PASSWORD));
        }
    }

    @Test
    @DisplayName("Leaving credentials out keeps them out of the file entirely")
    void omitsCredentialsWhenAsked() throws Exception {
        File file = tempDir.resolve("no-credentials.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null,
                new ExcelExporter.ReportOptions("SITE-1", null, null, false, null), file);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
            String devices = sheetText(workbook.getSheet("Devices"));
            String streams = sheetText(workbook.getSheet("Streams"));
            assertFalse(devices.contains(CAMERA_PASSWORD));
            assertFalse(streams.contains(CAMERA_PASSWORD));
            assertFalse(streams.contains("admin@"), "stream URLs must not embed credentials");
            assertTrue(streams.contains("rtsp://192.168.0.113:5543/live/channel0"));
        }
    }

    @Test
    @DisplayName("Without a password the workbook is a normal, readable xlsx")
    void writesUnencryptedWhenNoPassword() throws Exception {
        File file = tempDir.resolve("plain.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null,
                ExcelExporter.ReportOptions.of("SITE-1"), file);

        assertDoesNotThrow(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                assertNotNull(workbook.getSheet("Summary"));
            }
        });
    }

    @Test
    @DisplayName("The report has a sheet for each audience and one row per stream")
    void buildsExpectedSheets() throws Exception {
        File file = tempDir.resolve("sheets.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null, ExcelExporter.ReportOptions.of("SITE-1"), file);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
            assertNotNull(workbook.getSheet("Summary"));
            assertNotNull(workbook.getSheet("Devices"));
            assertNotNull(workbook.getSheet("Streams"));
            assertNotNull(workbook.getSheet("Findings"));

            // Header plus two devices.
            assertEquals(2, workbook.getSheet("Devices").getLastRowNum());
            // Header plus the one stream.
            assertEquals(1, workbook.getSheet("Streams").getLastRowNum());

            String summary = sheetText(workbook.getSheet("Summary"));
            assertTrue(summary.contains("SITE-1"));
            assertTrue(summary.contains("Streams readable without a password"));

            String findings = sheetText(workbook.getSheet("Findings"));
            assertTrue(findings.contains("High"));
            assertTrue(findings.contains("Enable RTSP authentication"));
        }
    }

    @Test
    @DisplayName("Non-compliant values are marked so a reader can see them")
    void marksNonCompliance() throws Exception {
        File file = tempDir.resolve("compliance.xlsx").toFile();
        new ExcelExporter().export(sampleDevices(), null, ExcelExporter.ReportOptions.of("SITE-1"), file);

        try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
            String streams = sheetText(workbook.getSheet("Streams"));
            assertTrue(streams.contains("High 5.1"));
            assertTrue(streams.contains("No"), "the compliant column reads No");
            assertTrue(streams.contains("needs transcoding"));
        }
    }

    private static String sheetText(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        for (Row row : sheet) {
            for (Cell cell : row) {
                sb.append(switch (cell.getCellType()) {
                    case STRING -> cell.getStringCellValue();
                    case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                    case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                    default -> "";
                }).append('\u0001');
            }
        }
        return sb.toString();
    }
}
