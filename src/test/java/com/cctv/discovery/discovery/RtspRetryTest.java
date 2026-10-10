package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.testing.FakeRtspServer;
import com.cctv.discovery.util.TargetParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A host whose web port answers but whose stream server is momentarily busy
 * gets its RTSP ports checked a second time.
 *
 * <p>On 2026-10-09 a Hikvision NVR answered on 80 and 8000 but not 554 for one
 * scan, and was ruled "Not a camera or recorder"; seconds later RTSP answered
 * normally. The earlier retry covered only hosts where nothing answered.
 */
class RtspRetryTest {

    @TempDir
    Path tmp;

    private FakeRtspServer rtsp;
    private ServerSocket web;
    private Thread webThread;
    private NetworkScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        rtsp = new FakeRtspServer(FakeRtspServer.Style.CAMERA, "admin", "right", Set.of());
        // A web port that accepts and hangs up, which is all a port scan asks of it.
        web = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        webThread = Thread.ofVirtual().start(() -> {
            while (!web.isClosed()) {
                try (Socket client = web.accept()) {
                    client.getInputStream().available();
                } catch (Exception ignored) {
                    return;
                }
            }
        });

        AppConfig config = AppConfig.forTesting(tmp.resolve("user-settings.properties"));
        config.setProperty("discovery.http.ports", String.valueOf(web.getLocalPort()));
        config.setProperty("discovery.rtsp.ports", String.valueOf(rtsp.port()));
        config.setProperty("discovery.other.ports", "");
        scanner = new NetworkScanner();
    }

    @AfterEach
    void tearDown() throws Exception {
        scanner.shutdown();
        rtsp.close();
        web.close();
        webThread.join(2_000);
    }

    @Test
    @DisplayName("A busy stream server is found on the second attempt")
    void busyRtspServerIsRetried() {
        // The first pass connects once to see the port is open and once to
        // confirm it speaks RTSP; both are dropped, as the busy NVR did.
        rtsp.dropNextConnections(2);

        List<Device> found = scanner.performPortScan(TargetParser.parse("127.0.0.1"), null);

        assertEquals(1, found.size());
        assertEquals(List.of(rtsp.port()), found.getFirst().getOpenRtspPorts(),
                "without the retry the host is left with no RTSP port and ruled not a camera");
    }

    @Test
    @DisplayName("A healthy stream server is found once, not twice")
    void healthyServerIsNotDuplicated() {
        List<Device> found = scanner.performPortScan(TargetParser.parse("127.0.0.1"), null);
        assertEquals(List.of(rtsp.port()), found.getFirst().getOpenRtspPorts());
    }
}
