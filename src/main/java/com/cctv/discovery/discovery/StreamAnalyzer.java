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

            // Extract H.264 profile from FFmpeg metadata
            String profileName = extractProfile(grabber);
            if (profileName != null) {
                stream.setProfile(profileName);
                logger.debug("Detected profile: {} for {}", profileName, stream.getRtspUrl());
            }

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

            logger.info("Stream analyzed: {} - {}@{}fps, {} ({}), {}kbps",
                    stream.getRtspUrl(),
                    stream.getResolution(),
                    stream.getFps(),
                    stream.getCodec(),
                    stream.getProfile() != null ? stream.getProfile() : "N/A",
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
     * Extract H.264 profile from FFmpegFrameGrabber.
     * Uses the video codec context's profile field.
     *
     * H.264 profile IDs (from FFmpeg avcodec.h):
     *   66 = Baseline, 77 = Main, 88 = Extended,
     *   100 = High, 110 = High 10, 122 = High 4:2:2, 244 = High 4:4:4 Predictive,
     *   44 = CAVLC 4:4:4, 83 = Scalable Baseline, 86 = Scalable High,
     *   118 = Multiview High, 128 = Stereo High, 138 = Multiview Depth High
     */
    private String extractProfile(FFmpegFrameGrabber grabber) {
        try {
            // JavaCV exposes the video codec context; getVideoCodecName gives codec string
            String videoCodecName = grabber.getVideoCodecName();
            if (videoCodecName == null) {
                return null;
            }

            // Try to get profile from FFmpeg metadata
            String metadata = grabber.getVideoMetadata("profile");
            if (metadata != null && !metadata.isEmpty()) {
                return metadata;
            }

            // Fallback: parse profile from SPS via format context metadata
            // FFmpeg sets profile in the codec context; access via getVideoSideData or metadata
            String formatMeta = grabber.getMetadata("profile");
            if (formatMeta != null && !formatMeta.isEmpty()) {
                return formatMeta;
            }

            // Fallback: use pixel format as a proxy hint
            // High 10 uses yuv420p10le, High 4:2:2 uses yuv422p, High 4:4:4 uses yuv444p
            int pixFmt = grabber.getPixelFormat();
            if (videoCodecName.contains("h264") || videoCodecName.contains("H264")
                    || videoCodecName.contains("264")) {
                // avutil pixel format IDs (common):
                // 0 = yuv420p (Baseline/Main/High), 64 = yuv420p10le (High 10)
                // 4 = yuv422p (High 4:2:2), 5 = yuv444p (High 4:4:4)
                if (pixFmt == 64 || pixFmt == 68) {
                    return "High 10";
                } else if (pixFmt == 4) {
                    return "High 4:2:2";
                } else if (pixFmt == 5) {
                    return "High 4:4:4";
                }
            }

            return null;
        } catch (Exception e) {
            logger.debug("Could not extract profile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if a profile is considered "High" and requires transcoding for browser HLS playback.
     * Baseline and Main profiles are directly playable. High and above require transcoding.
     */
    private boolean isHighProfile(String profile) {
        if (profile == null) {
            return false;
        }
        String lower = profile.toLowerCase();
        return lower.contains("high");
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
     * Sub-stream: 360p-480p, H.264, <512kbps, Baseline/Main profile (not High).
     * All streams: High profile flagged (requires transcoding for browser HLS).
     */
    private void checkCompliance(RTSPStream stream) {
        List<String> issues = new ArrayList<>();

        // Check H.264 profile on ALL streams (High profiles need transcoding for HLS)
        String profile = stream.getProfile();
        if (isHighProfile(profile)) {
            issues.add("High profile (requires transcoding for browser HLS)");
        }

        // Sub-stream specific checks
        String streamName = stream.getStreamName();
        boolean isSub = streamName != null && streamName.toLowerCase().contains("sub");

        if (isSub) {
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

            // Check bitrate (should be < 512kbps)
            Integer bitrate = stream.getBitrateKbps();
            if (bitrate != null && bitrate >= 512) {
                issues.add("Bitrate >= 512kbps");
            }
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
