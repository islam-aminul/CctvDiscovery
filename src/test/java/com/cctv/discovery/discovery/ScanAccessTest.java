package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspPathCache;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.testing.FakeRtspServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a scan concludes about signing in, computed from real RTSP answers.
 *
 * <p>ScanCoordinatorTest checks the final wording given what was observed.
 * This checks the observing, which is where both defects found on a live
 * Hikvision site were: the recorder was reported as having accepted a password
 * that was never sent, and a wrong password was tried against every candidate
 * path, enough failed logins to lock the account on every device.
 */
class ScanAccessTest {

    private static final String STREAM = "/Streaming/Channels/101";

    @TempDir
    Path tmp;

    private FakeRtspServer server;
    private ScanCoordinator scanner;
    private NetworkScanner networkScanner;
    private StreamAnalyzer streamAnalyzer;

    private static final ScanCoordinator.Listener QUIET = new ScanCoordinator.Listener() {
        @Override public void onProgress(double fraction, String message) { }
        @Override public void onDevicesFound(List<Device> devices) { }
        @Override public void onDeviceList(List<Device> devices) { }
        @Override public void onDeviceChanged(Device device) { }
        @Override public void onFinished(long seconds, boolean cancelled) { }
    };

    @BeforeEach
    void setUp() {
        // The channel walk reads its limits from settings; keep them the
        // defaults rather than whatever this machine's user has set.
        AppConfig.forTesting(tmp.resolve("user-settings.properties"));
        // Describing the stream is enough here; decoding a frame from a fake
        // server would test FFmpeg, not the decisions this class is about.
        RtspService.RtspDiscoveryConfig sdpOnly = new RtspService.RtspDiscoveryConfig();
        sdpOnly.setValidationMethod(RtspService.RtspValidationMethod.SDP_ONLY);
        RtspService rtsp = new RtspService(sdpOnly, new RtspPathCache(tmp.resolve("paths.properties")));
        networkScanner = new NetworkScanner();
        streamAnalyzer = new StreamAnalyzer();
        scanner = new ScanCoordinator(networkScanner, new OnvifService(), rtsp, streamAnalyzer,
                AppConfig.getInstance());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        scanner.shutdown();
    }

    private Device hikvisionAt(FakeRtspServer fake) {
        Device device = new Device("127.0.0.1");
        device.setManufacturer("Hikvision");
        device.getOpenRtspPorts().add(fake.port());
        return device;
    }

    private Device identify(FakeRtspServer.Style style, Set<String> streams, Credential... credentials)
            throws Exception {
        server = new FakeRtspServer(style, "admin", "right", streams);
        Device device = hikvisionAt(server);
        scanner.identify(device, List.of(credentials), QUIET);
        return device;
    }

    private static boolean hasFinding(Device device, String title) {
        return device.getFindings().stream().anyMatch(f -> f.title().equals(title));
    }

    // ------------------------------------------------------------- lockout --

    @Test
    @DisplayName("A wrong password costs one failed login per device, not one per path")
    void wrongPasswordIsTriedOnce() throws Exception {
        Device device = identify(FakeRtspServer.Style.CAMERA, Set.of(STREAM), new Credential("admin", "wrong"));

        assertEquals(1, server.rejectedLogins(),
                "Hikvision locks an account after about five failures; requests: " + server.requestLog());
        assertTrue(device.isAuthFailed());
        assertEquals("No credential was accepted", device.getErrorMessage());
    }

    @Test
    @DisplayName("The right password still gets its turn after wrong ones")
    void rightPasswordAfterWrongOnes() throws Exception {
        Device device = identify(FakeRtspServer.Style.CAMERA, Set.of(STREAM),
                new Credential("admin", "first-guess"),
                new Credential("admin", "second-guess"),
                new Credential("admin", "right"));

        assertEquals(2, server.rejectedLogins(), "one failure for each wrong password, and no more");
        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertFalse(device.getRtspStreams().isEmpty());
        assertEquals("right", device.getPassword());
    }

    // ---------------------------------------------------------- recorders ---

    @Test
    @DisplayName("A recorder that hides its challenge behind 404 on '/' still needs a password")
    void recorderWithoutPassword() throws Exception {
        Device device = identify(FakeRtspServer.Style.RECORDER, Set.of(STREAM));

        assertTrue(device.isAuthFailed(), "shown red as a sign-in problem, not grey as 'not a camera'");
        assertEquals("Needs a password; none was entered", device.getErrorMessage());
        assertTrue(hasFinding(device, "Sign-in details needed"));
        assertFalse(hasFinding(device, "Stream address unknown"),
                "must not claim a password was accepted when none was sent");
        assertEquals(0, server.rejectedLogins() + server.acceptedLogins(), "nothing carried credentials");
    }

    @Test
    @DisplayName("A recorder that refuses the password says so, rather than blaming the path")
    void recorderWithWrongPassword() throws Exception {
        Device device = identify(FakeRtspServer.Style.RECORDER, Set.of(STREAM), new Credential("admin", "wrong"));

        assertTrue(device.isAuthFailed());
        assertEquals("No credential was accepted", device.getErrorMessage());
        assertFalse(hasFinding(device, "Stream address unknown"));
        assertEquals(1, server.rejectedLogins());
    }

    // -------------------------------------------------- the honest branches --

    @Test
    @DisplayName("A good password on a device whose path we do not know is called accepted, truthfully")
    void rightPasswordUnknownPath() throws Exception {
        Device device = identify(FakeRtspServer.Style.CAMERA, Set.of("/a/path/nobody/ships"),
                new Credential("admin", "right"));

        assertFalse(device.isAuthFailed());
        assertEquals("No stream path matched", device.getErrorMessage());
        assertTrue(device.getFindings().stream().anyMatch(
                f -> f.detail().startsWith("A password was accepted")));
        assertEquals(0, server.rejectedLogins());
        assertTrue(server.acceptedLogins() > 0);
    }

    @Test
    @DisplayName("A device that asks for no password is not said to have accepted one")
    void openDeviceUnknownPath() throws Exception {
        Device device = identify(FakeRtspServer.Style.OPEN, Set.of("/a/path/nobody/ships"));

        assertFalse(device.isAuthFailed());
        assertEquals("No stream path matched", device.getErrorMessage());
        assertTrue(device.getFindings().stream().anyMatch(
                f -> f.detail().startsWith("The device answers on RTSP")));
        assertFalse(device.getFindings().stream().anyMatch(
                f -> f.detail().contains("password was accepted")));
    }

    // ------------------------------------------------------------ recorders --

    private static final Set<String> THREE_CHANNELS = Set.of(
            "/Streaming/Channels/101", "/Streaming/Channels/102",
            "/Streaming/Channels/201", "/Streaming/Channels/202",
            "/Streaming/Channels/301", "/Streaming/Channels/302");

    @Test
    @DisplayName("A recorder with ONVIF off is recognised by its second channel, and every channel is walked")
    void recognisesRecorderWithoutOnvif() throws Exception {
        Device device = identify(FakeRtspServer.Style.RECORDER, THREE_CHANNELS, new Credential("admin", "right"));

        assertEquals(Device.DeviceType.RECORDER, device.getType());
        assertEquals(6, device.getRtspStreams().size(),
                "three channels, main and sub each, and channel 1 not counted twice: "
                        + device.getRtspStreams().stream().map(s -> s.getStreamName()).toList());
        assertEquals(Set.of("Channel 1", "Channel 2", "Channel 3"), device.getRtspStreams().stream()
                .map(s -> s.getChannelName()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertEquals(0, server.rejectedLogins());
    }

    @Test
    @DisplayName("A camera, which refuses channel 2, stays a camera")
    void cameraIsNotMistakenForRecorder() throws Exception {
        Device device = identify(FakeRtspServer.Style.CAMERA,
                Set.of("/Streaming/Channels/101", "/Streaming/Channels/102"), new Credential("admin", "right"));

        assertFalse(device.isNvrDvr());
        assertEquals(2, device.getRtspStreams().size());
    }

    @Test
    @DisplayName("Video readable with no password is found, and flagged")
    void openDeviceWithVideoIsFlagged() throws Exception {
        Device device = identify(FakeRtspServer.Style.OPEN, Set.of(STREAM));

        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertEquals(Boolean.TRUE, device.getRtspAnonymousAccess());
        assertTrue(hasFinding(device, "Video stream readable without a password"));
    }
}
