package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamAnalyzerTest {

    @TempDir
    static Path settingsDir;

    @BeforeAll
    static void isolateSettings() {
        AppConfig.forTesting(settingsDir.resolve("user-settings.properties"));
    }

    private static RTSPStream stream(String name, int width, int height, String codec, String profile, int kbps) {
        RTSPStream s = new RTSPStream(name, "rtsp://10.0.0.1:554/" + name);
        s.setDimensions(width, height);
        s.setCodec(codec);
        s.setProfile(profile);
        s.setBitrateKbps(kbps);
        return s;
    }

    @Test
    @DisplayName("Main and sub are decided by resolution, not by profile name")
    void refinesRolesByResolution() {
        // The test camera names its profiles PROFILE_1 and PROFILE_2, which say
        // nothing about which is the sub-stream.
        RTSPStream first = stream("PROFILE_1", 1920, 1080, "H.264", "High 5.1", 2048);
        RTSPStream second = stream("PROFILE_2", 640, 360, "H.264", "Main 3.1", 400);

        StreamAnalyzer.refineRoles(List.of(first, second));

        assertEquals(RTSPStream.Role.MAIN, first.getRole());
        assertEquals(RTSPStream.Role.SUB, second.getRole());
    }

    @Test
    @DisplayName("Roles survive when the streams arrive in reverse order")
    void refinesRolesRegardlessOfOrder() {
        RTSPStream small = stream("A", 640, 480, "H.264", "Main", 300);
        RTSPStream large = stream("B", 2560, 1440, "H.264", "Main", 4000);

        StreamAnalyzer.refineRoles(List.of(small, large));

        assertEquals(RTSPStream.Role.SUB, small.getRole());
        assertEquals(RTSPStream.Role.MAIN, large.getRole());
    }

    @Test
    @DisplayName("Recorder channels keep the roles the channel walk assigned")
    void leavesRecorderChannelsAlone() {
        RTSPStream ch1 = stream("CH1 Main", 1920, 1080, "H.264", "Main", 2048);
        ch1.setChannelName("Channel 1");
        ch1.setRole(RTSPStream.Role.MAIN);
        RTSPStream ch2 = stream("CH2 Main", 1920, 1080, "H.264", "Main", 2048);
        ch2.setChannelName("Channel 2");
        ch2.setRole(RTSPStream.Role.MAIN);

        StreamAnalyzer.refineRoles(List.of(ch1, ch2));

        assertEquals(RTSPStream.Role.MAIN, ch1.getRole());
        assertEquals(RTSPStream.Role.MAIN, ch2.getRole());
    }

    @Test
    @DisplayName("High profile is flagged on any stream and raises a finding")
    void flagsHighProfile() {
        StreamAnalyzer analyzer = new StreamAnalyzer();
        Device device = new Device("10.0.0.1");
        RTSPStream main = stream("Main", 1920, 1080, "H.264", "High 5.1", 2048);
        main.setRole(RTSPStream.Role.MAIN);

        analyzer.checkCompliance(device, main);

        assertFalse(main.isCompliant());
        assertTrue(main.getComplianceIssues().contains("High profile"));
        assertTrue(device.getFindings().stream().anyMatch(f -> f.severity() == Finding.Severity.MEDIUM));
        analyzer.close();
    }

    @Test
    @DisplayName("A conforming main stream on Main profile passes")
    void passesCompliantMain() {
        StreamAnalyzer analyzer = new StreamAnalyzer();
        Device device = new Device("10.0.0.1");
        RTSPStream main = stream("Main", 1920, 1080, "H.264", "Main 4.1", 4096);
        main.setRole(RTSPStream.Role.MAIN);

        analyzer.checkCompliance(device, main);

        assertTrue(main.isCompliant(), main.getComplianceIssues());
        assertNull(main.getComplianceIssues());
        analyzer.close();
    }

    @Test
    @DisplayName("Sub-stream limits apply only to the sub-stream")
    void appliesSubStreamRules() {
        StreamAnalyzer analyzer = new StreamAnalyzer();
        Device device = new Device("10.0.0.1");

        RTSPStream goodSub = stream("Sub", 640, 360, "H.264", "Main 3.1", 400);
        goodSub.setRole(RTSPStream.Role.SUB);
        analyzer.checkCompliance(device, goodSub);
        assertTrue(goodSub.isCompliant(), goodSub.getComplianceIssues());

        RTSPStream tooBig = stream("Sub", 1920, 1080, "H.264", "Main 4.1", 2048);
        tooBig.setRole(RTSPStream.Role.SUB);
        analyzer.checkCompliance(device, tooBig);
        assertFalse(tooBig.isCompliant());
        assertTrue(tooBig.getComplianceIssues().contains("Resolution"));
        assertTrue(tooBig.getComplianceIssues().contains("Bitrate"));

        RTSPStream wrongCodec = stream("Sub", 640, 360, "H.265", "Main", 200);
        wrongCodec.setRole(RTSPStream.Role.SUB);
        analyzer.checkCompliance(device, wrongCodec);
        assertTrue(wrongCodec.getComplianceIssues().contains("not H.264"));

        // The same oversized stream as a main stream is fine.
        RTSPStream asMain = stream("Main", 1920, 1080, "H.264", "Main 4.1", 2048);
        asMain.setRole(RTSPStream.Role.MAIN);
        analyzer.checkCompliance(device, asMain);
        assertTrue(asMain.isCompliant(), asMain.getComplianceIssues());
        analyzer.close();
    }

    @Test
    @DisplayName("A stream that could not be analysed is reported, not silently passed")
    void reportsAnalysisFailure() {
        StreamAnalyzer analyzer = new StreamAnalyzer();
        Device device = new Device("10.0.0.1");
        RTSPStream broken = new RTSPStream("Main", "rtsp://10.0.0.1/live");
        broken.setAnalysisError("Timed out");

        analyzer.checkCompliance(device, broken);

        assertFalse(broken.isCompliant());
        assertTrue(broken.getComplianceIssues().contains("Timed out"));
        analyzer.close();
    }
}
