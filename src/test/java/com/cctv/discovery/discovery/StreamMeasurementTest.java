package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.FFmpegSupport;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures a recorded clip whose properties are known exactly, so the rate
 * calculation cannot drift again without a test failing.
 *
 * <p>The fixture is six seconds of H.264 Main profile, level 3.0, 320x240,
 * 10 fps, one keyframe every 20 frames, encoded at a constant 200 kbps.
 * ffprobe reports 214 kbps for the video stream and 60 frames.
 */
class StreamMeasurementTest {

    private static final String FIXTURE = "/media/sample-320x240-10fps.mp4";

    private static final int EXPECTED_WIDTH = 320;
    private static final int EXPECTED_HEIGHT = 240;
    private static final double EXPECTED_FPS = 10.0;
    private static final double EXPECTED_KBPS = 214.0;
    private static final double EXPECTED_KEYFRAME_SECONDS = 2.0; // 20 frames at 10 fps

    @TempDir
    static Path tempDir;

    private static Path fixture;
    private StreamAnalyzer analyzer;

    @BeforeAll
    static void unpackFixture() throws Exception {
        AppConfig.forTesting(tempDir.resolve("user-settings.properties"));
        fixture = tempDir.resolve("sample.mp4");
        try (InputStream in = StreamMeasurementTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "test fixture " + FIXTURE + " is missing");
            Files.copy(in, fixture, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @BeforeEach
    void setUp() {
        analyzer = new StreamAnalyzer();
    }

    @AfterEach
    void tearDown() {
        analyzer.close();
    }

    private FFmpegFrameGrabber openFixture() throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fixture.toFile());
        grabber.start();
        return grabber;
    }

    @Test
    @DisplayName("Resolution, codec and profile are read from the stream itself")
    void readsStreamProperties() throws Exception {
        FFmpegFrameGrabber grabber = openFixture();
        try {
            RTSPStream stream = new RTSPStream("fixture", "rtsp://example/fixture");
            analyzer.populate(stream, grabber);

            assertEquals(EXPECTED_WIDTH, stream.getWidth());
            assertEquals(EXPECTED_HEIGHT, stream.getHeight());
            assertEquals("320x240", stream.getResolution());
            assertEquals("H.264", stream.getCodec());
            // Profile and level come from the codec context, not container
            // metadata, which is empty for a live stream.
            assertEquals("Main 3", stream.getProfile());
            assertTrue(stream.isAnalyzed());
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }
    }

    @Test
    @DisplayName("Bitrate is within 10% of what ffprobe reports for the same clip")
    void measuresBitrate() throws Exception {
        FFmpegFrameGrabber grabber = openFixture();
        try {
            RTSPStream stream = new RTSPStream("fixture", "rtsp://example/fixture");
            analyzer.populate(stream, grabber);

            Integer kbps = stream.getBitrateKbps();
            assertNotNull(kbps, "no bitrate was measured");
            double error = Math.abs(kbps - EXPECTED_KBPS) / EXPECTED_KBPS;
            assertTrue(error < 0.10,
                    "expected about " + EXPECTED_KBPS + " kbps, measured " + kbps);

            // Guards against the two ways this has been wrong before: summing
            // decoded buffers reported over a million kbps, and stopping on
            // wall-clock time reported several times the real rate.
            assertTrue(kbps < 1_000, "bitrate is implausibly high: " + kbps);
            assertTrue(kbps > 50, "bitrate is implausibly low: " + kbps);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }
    }

    @Test
    @DisplayName("Frame rate comes out at the encoded rate")
    void measuresFrameRate() throws Exception {
        FFmpegFrameGrabber grabber = openFixture();
        try {
            RTSPStream stream = new RTSPStream("fixture", "rtsp://example/fixture");
            analyzer.populate(stream, grabber);

            Double fps = stream.getFps();
            assertNotNull(fps, "no frame rate was measured");
            assertTrue(Math.abs(fps - EXPECTED_FPS) < 0.5,
                    "expected about " + EXPECTED_FPS + " fps, measured " + fps);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }
    }

    @Test
    @DisplayName("Keyframe interval matches the encoded GOP length")
    void measuresKeyframeInterval() throws Exception {
        FFmpegFrameGrabber grabber = openFixture();
        try {
            RTSPStream stream = new RTSPStream("fixture", "rtsp://example/fixture");
            analyzer.populate(stream, grabber);

            Double gop = stream.getKeyframeIntervalSeconds();
            assertNotNull(gop, "no keyframe interval was measured");
            assertTrue(Math.abs(gop - EXPECTED_KEYFRAME_SECONDS) < 0.5,
                    "expected about " + EXPECTED_KEYFRAME_SECONDS + "s, measured " + gop);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }
    }

    @Test
    @DisplayName("Rates are derived from stream time, so they do not change with read speed")
    void ratesAreIndependentOfReadSpeed() throws Exception {
        // Reading a local file runs far faster than real time, which is exactly
        // the condition that inflated the bitrate when wall-clock time was used.
        RTSPStream first = new RTSPStream("a", "rtsp://example/a");
        RTSPStream second = new RTSPStream("b", "rtsp://example/b");

        FFmpegFrameGrabber grabber = openFixture();
        try {
            analyzer.populate(first, grabber);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }

        grabber = openFixture();
        try {
            analyzer.populate(second, grabber);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }

        assertEquals(first.getBitrateKbps(), second.getBitrateKbps(), "repeat runs must agree");
        assertEquals(first.getFps(), second.getFps());
    }

    @Test
    @DisplayName("A measured stream is judged against the sub-stream rules by role")
    void appliesRulesToMeasuredValues() throws Exception {
        FFmpegFrameGrabber grabber = openFixture();
        try {
            RTSPStream sub = new RTSPStream("fixture", "rtsp://example/fixture");
            sub.setRole(RTSPStream.Role.SUB);
            analyzer.populate(sub, grabber);
            analyzer.checkCompliance(new com.cctv.discovery.model.Device("10.0.0.1"), sub);

            // 240p is below the 360p floor and 214 kbps is inside the limit, so
            // exactly one rule should be reported.
            assertNotNull(sub.getComplianceIssues());
            assertTrue(sub.getComplianceIssues().contains("Resolution"),
                    "240p should be flagged: " + sub.getComplianceIssues());
            assertTrue(!sub.getComplianceIssues().contains("Bitrate"),
                    "214 kbps is within the limit: " + sub.getComplianceIssues());
        } finally {
            FFmpegSupport.closeQuietly(grabber);
        }
    }
}
