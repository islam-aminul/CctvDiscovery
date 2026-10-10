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

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("A Prama camera with ONVIF off is found on Hikvision's paths")
    void pramaCameraUsesHikvisionPaths() throws Exception {
        server = new FakeRtspServer(FakeRtspServer.Style.CAMERA, "admin", "right",
                Set.of("/Streaming/Channels/101", "/Streaming/Channels/102"));
        Device device = new Device("127.0.0.1");
        device.setManufacturer("Prama");
        device.getOpenRtspPorts().add(server.port());

        scanner.identify(device, List.of(new Credential("admin", "right")), QUIET);

        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus(),
                "requests: " + server.requestLog());
        assertTrue(device.getRtspStreams().stream()
                .anyMatch(s -> s.getRtspUrl().endsWith("/Streaming/Channels/101")));
    }

    @Test
    @DisplayName("Video readable with no password is found, and flagged")
    void openDeviceWithVideoIsFlagged() throws Exception {
        Device device = identify(FakeRtspServer.Style.OPEN, Set.of(STREAM));

        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertEquals(Boolean.TRUE, device.getRtspAnonymousAccess());
        assertTrue(hasFinding(device, "Video stream readable without a password"));
    }

    // ---------------------------------------------------------------- ONVIF --

    private static final String DEVICE_INFO = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Body>
              <tds:GetDeviceInformationResponse>
                <tds:Manufacturer>CP Plus</tds:Manufacturer><tds:Model>CP-TEST-1</tds:Model>
                <tds:FirmwareVersion>1.2.3</tds:FirmwareVersion><tds:SerialNumber>SN1</tds:SerialNumber>
                <tds:HardwareId>HW1</tds:HardwareId>
              </tds:GetDeviceInformationResponse></s:Body></s:Envelope>""";

    /**
     * A device service that answers GetDeviceInformation, either to anyone or
     * only to a request carrying a WS-Security header. Every other request
     * gets a 400, which the scan treats as the device not offering it.
     */
    private static HttpServer onvifDevice(boolean open) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/onvif/device_service", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean signed = body.contains("UsernameToken");
            int status;
            byte[] reply = new byte[0];
            if (!body.contains("GetDeviceInformation")) {
                status = 400;
            } else if (open || signed) {
                status = 200;
                reply = DEVICE_INFO.getBytes(StandardCharsets.UTF_8);
            } else {
                status = 401;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/soap+xml");
            exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) {
                exchange.getResponseBody().write(reply);
            }
            exchange.close();
        });
        http.start();
        return http;
    }

    private Device onvifOnly(HttpServer http) {
        int port = http.getAddress().getPort();
        Device device = new Device("127.0.0.1");
        device.setOnvifServiceUrl("http://127.0.0.1:" + port + "/onvif/device_service");
        device.getOpenOnvifPorts().add(port);
        return device;
    }

    @Test
    @DisplayName("With no credentials entered, ONVIF is still asked once, and an open one is flagged")
    void openOnvifIsAskedWithoutCredentials() throws Exception {
        HttpServer http = onvifDevice(true);
        try {
            Device device = onvifOnly(http);
            scanner.identify(device, List.of(), QUIET);

            assertEquals("CP-TEST-1", device.getModel());
            assertEquals("1.2.3", device.getFirmwareVersion());
            assertEquals(Device.OnvifAuthMethod.NONE, device.getOnvifAuthMethod());
            assertTrue(hasFinding(device, "Device details readable without a password"));
            assertFalse(hasFinding(device, "Sign-in details needed"));
            assertFalse(device.isAuthFailed(), "nothing asked for a password");
            assertNull(device.getUsername(), "no credential worked, because none was sent");
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("ONVIF that wants a password, with none entered, asks for sign-in details")
    void lockedOnvifWithoutCredentials() throws Exception {
        HttpServer http = onvifDevice(false);
        try {
            Device device = onvifOnly(http);
            scanner.identify(device, List.of(), QUIET);

            assertNull(device.getModel());
            assertTrue(device.isAuthFailed());
            assertTrue(hasFinding(device, "Sign-in details needed"));
            assertFalse(hasFinding(device, "Device details readable without a password"));
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("ONVIF that takes the entered password is not reported as open")
    void lockedOnvifWithPassword() throws Exception {
        HttpServer http = onvifDevice(false);
        try {
            Device device = onvifOnly(http);
            scanner.identify(device, List.of(new Credential("admin", "right")), QUIET);

            assertEquals("CP-TEST-1", device.getModel());
            assertFalse(hasFinding(device, "Device details readable without a password"));
            assertEquals(Device.OnvifAuthMethod.WS_SECURITY, device.getOnvifAuthMethod());
        } finally {
            http.stop(0);
        }
    }

    /** Video open to anyone over RTSP, while ONVIF wants a password. */
    private Device openVideoLockedOnvif(HttpServer http, Credential... credentials) throws Exception {
        server = new FakeRtspServer(FakeRtspServer.Style.OPEN, "admin", "right", Set.of(STREAM));
        Device device = onvifOnly(http);
        device.setManufacturer("Hikvision");
        device.getOpenRtspPorts().add(server.port());
        scanner.identify(device, List.of(credentials), QUIET);
        return device;
    }

    @Test
    @DisplayName("Video found but ONVIF refused the password: the report says so")
    void refusedOnvifPasswordIsReported() throws Exception {
        HttpServer http = onvifDevice(false);
        try {
            // The fake takes any signed request, so refuse by sending none:
            // a wrong password is modelled by a device that refuses them all.
            http.removeContext("/onvif/device_service");
            http.createContext("/onvif/device_service", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            Device device = openVideoLockedOnvif(http, new Credential("admin", "wrong"));

            assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
            assertTrue(hasFinding(device, "ONVIF refused the credentials"));
            assertFalse(hasFinding(device, "ONVIF needs a password"));
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("Video found but ONVIF wants a password none was entered for: the report says so")
    void lockedOnvifNoPasswordIsReported() throws Exception {
        HttpServer http = onvifDevice(false);
        try {
            Device device = openVideoLockedOnvif(http);

            assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
            assertTrue(hasFinding(device, "ONVIF needs a password"));
            assertFalse(hasFinding(device, "ONVIF refused the credentials"));
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("ONVIF that took the password adds no access finding")
    void acceptedOnvifAddsNoAccessFinding() throws Exception {
        HttpServer http = onvifDevice(false);
        try {
            Device device = openVideoLockedOnvif(http, new Credential("admin", "right"));

            assertFalse(hasFinding(device, "ONVIF refused the credentials"));
            assertFalse(hasFinding(device, "ONVIF needs a password"));
        } finally {
            http.stop(0);
        }
    }
}
