package com.cctv.discovery.discovery;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Stream analyzer using JavaCV to extract codec, resolution, FPS, and bitrate information.
 */
public class StreamAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(StreamAnalyzer.class);

    private static final int MAX_PARALLEL_STREAMS = 8;
    private static final int ANALYSIS_DURATION_SECONDS = 10;
    private static final int FRAME_SAMPLE_COUNT = 30;

    private final ExecutorService executorService;

    public StreamAnalyzer() {
        this.executorService = Executors.newFixedThreadPool(MAX_PARALLEL_STREAMS);
        logger.info("StreamAnalyzer initialized with {} threads", MAX_PARALLEL_STREAMS);
    }

    /**
     * Analyze all streams for a device.
     */
    public void analyzeDevice(Device device) {
        List<RTSPStream> streams = device.getRtspStreams();
        if (streams.isEmpty()) {
            return;
        }

        List<Future<?>> futures = new ArrayList<>();

        for (RTSPStream stream : streams) {
            Future<?> future = executorService.submit(() -> analyzeStream(stream, device));
            futures.add(future);
        }

        // Wait for all analyses to complete
        for (Future<?> future : futures) {
            try {
                future.get(ANALYSIS_DURATION_SECONDS + 10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Stream analysis timeout");
                future.cancel(true);
            } catch (Exception e) {
                logger.error("Error during stream analysis", e);
            }
        }
    }

    /**
     * Analyze a single RTSP stream.
     */
    private void analyzeStream(RTSPStream stream, Device device) {
        FFmpegFrameGrabber grabber = null;
        try {
            String rtspUrl = stream.getRtspUrl();

            // Add credentials to URL if available
            if (device.getUsername() != null && device.getPassword() != null) {
                rtspUrl = rtspUrl.replace("rtsp://",
                        "rtsp://" + device.getUsername() + ":" + device.getPassword() + "@");
            }

            grabber = new FFmpegFrameGrabber(rtspUrl);
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("stimeout", "5000000"); // 5 seconds in microseconds
            grabber.setImageWidth(0);
            grabber.setImageHeight(0);

            logger.debug("Starting stream analysis for: {}", stream.getRtspUrl());
            grabber.start();

            // Extract basic metadata
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double frameRate = grabber.getFrameRate();
            int videoCodec = grabber.getVideoCodec();
            int videoBitrate = grabber.getVideoBitrate();

            // Set resolution
            if (width > 0 && height > 0) {
                stream.setResolution(width + "x" + height);
            }

            // Set FPS
            if (frameRate > 0) {
                stream.setFps(frameRate);
            }

            // Set codec
            String codecName = getCodecName(videoCodec);
            stream.setCodec(codecName);

            // Calculate bitrate by sampling frames
            long startTime = System.currentTimeMillis();
            long totalBytes = 0;
            int frameCount = 0;

            while (frameCount < FRAME_SAMPLE_COUNT &&
                    (System.currentTimeMillis() - startTime) < (ANALYSIS_DURATION_SECONDS * 1000)) {
                Frame frame = grabber.grabFrame();
                if (frame != null) {
                    frameCount++;
                    // Estimate frame size (this is approximate)
                    totalBytes += estimateFrameSize(frame);
                }
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            if (elapsedMs > 0 && totalBytes > 0) {
                // Calculate bitrate in kbps
                double bitrateKbps = (totalBytes * 8.0 / elapsedMs);
                stream.setBitrateKbps((int) bitrateKbps);
            } else if (videoBitrate > 0) {
                // Use FFmpeg reported bitrate
                stream.setBitrateKbps(videoBitrate / 1000);
            }

            // Check compliance
            checkCompliance(stream);

            logger.info("Stream analyzed: {} - {}@{}fps, {}, {}kbps",
                    stream.getRtspUrl(),
                    stream.getResolution(),
                    stream.getFps(),
                    stream.getCodec(),
                    stream.getBitrateKbps());

        } catch (Exception e) {
            logger.error("Error analyzing stream: {}", stream.getRtspUrl(), e);
            stream.setComplianceIssues("Analysis failed: " + e.getMessage());
        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    logger.debug("Error releasing grabber", e);
                }
            }
        }
    }

    /**
     * Get codec name from codec ID.
     */
    private String getCodecName(int codecId) {
        // Common codec IDs from FFmpeg
        switch (codecId) {
            case 27:
                return "H.264";
            case 173:
                return "H.265/HEVC";
            case 12:
                return "MPEG-4";
            case 7:
                return "MJPEG";
            default:
                return "Unknown (" + codecId + ")";
        }
    }

    /**
     * Estimate frame size in bytes (rough approximation).
     */
    private int estimateFrameSize(Frame frame) {
        if (frame.image != null && frame.image.length > 0) {
            int totalSize = 0;
            for (int i = 0; i < frame.image.length; i++) {
                if (frame.image[i] != null) {
                    totalSize += frame.image[i].capacity();
                }
            }
            return totalSize;
        }
        return 4096; // Default estimate
    }

    /**
     * Check stream compliance with requirements.
     * Sub-stream should be: 360p-480p, H.264, <256kbps
     */
    private void checkCompliance(RTSPStream stream) {
        List<String> issues = new ArrayList<>();

        // Only check sub-streams
        String streamName = stream.getStreamName();
        if (streamName == null || !streamName.toLowerCase().contains("sub")) {
            stream.setCompliant(true);
            return;
        }

        // Check resolution (360p-480p = 640x360 to 720x480)
        String resolution = stream.getResolution();
        if (resolution != null) {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                try {
                    int height = Integer.parseInt(parts[1]);
                    if (height < 360 || height > 480) {
                        issues.add("Resolution not in 360p-480p range");
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }

        // Check codec (should be H.264)
        String codec = stream.getCodec();
        if (codec != null && !codec.contains("H.264")) {
            issues.add("Codec is not H.264");
        }

        // Check bitrate (should be < 256kbps)
        Integer bitrate = stream.getBitrateKbps();
        if (bitrate != null && bitrate >= 256) {
            issues.add("Bitrate >= 256kbps");
        }

        if (!issues.isEmpty()) {
            stream.setCompliant(false);
            stream.setComplianceIssues(String.join(", ", issues));
        } else {
            stream.setCompliant(true);
        }
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }
}
