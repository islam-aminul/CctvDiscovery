package com.cctv.discovery.discovery;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.FFmpegSupport;
import com.cctv.discovery.util.RtspClient;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

/**
 * Measures each stream with FFmpeg: resolution, codec, profile, frame rate,
 * bitrate and keyframe interval, then checks them against the compliance rules.
 *
 * <p>Bitrate is measured from compressed packet sizes. Summing decoded frame
 * buffers, as the previous version did, reported roughly 1.27 million kbps for
 * a stream actually running at about 194 kbps.
 */
public final class StreamAnalyzer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StreamAnalyzer.class);

    private final AppConfig config = AppConfig.getInstance();
    private final Semaphore analysisPermits;
    private volatile boolean cancelled;

    public StreamAnalyzer() {
        FFmpegSupport.init();
        this.analysisPermits = new Semaphore(config.getStreamAnalysisMaxThreads());
    }

    public void cancel() {
        cancelled = true;
    }

    /** Analyse every stream of a device, bounded by the configured timeout. */
    public void analyzeDevice(Device device) {
        List<RTSPStream> streams = device.getRtspStreams();
        if (streams.isEmpty() || cancelled) {
            return;
        }

        Duration budget = Duration.ofMillis((long) config.getStreamAnalysisTimeout() * streams.size() + 5_000);
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("analyze-" + device.getIpAddress()).withTimeout(budget))) {
            for (RTSPStream stream : streams) {
                scope.fork(() -> {
                    analyzeStream(stream, device);
                    return null;
                });
            }
            scope.join();
        } catch (StructuredTaskScope.TimeoutException e) {
            logger.warn("Analysis budget elapsed for {}", device.getIpAddress());
            for (RTSPStream stream : streams) {
                if (!stream.isAnalyzed() && stream.getAnalysisError() == null) {
                    stream.setAnalysisError("Timed out");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        refineRoles(streams);
        for (RTSPStream stream : streams) {
            checkCompliance(device, stream);
        }
    }

    /** Analyse one stream; failures are recorded on the stream, not thrown. */
    public void analyzeStream(RTSPStream stream, Device device) {
        if (cancelled) {
            return;
        }
        int timeout = config.getStreamAnalysisTimeout();
        FFmpegFrameGrabber grabber = null;
        boolean permitHeld = false;
        try {
            analysisPermits.acquire();
            permitHeld = true;

            grabber = FFmpegSupport.grabber(stream.getRtspUrl(), device.getUsername(), device.getPassword(), timeout);
            grabber.start();
            populate(stream, grabber);

            logger.info("Analysed {}: {} {} {} {}kbps {}fps",
                    RtspClient.stripCredentials(stream.getRtspUrl()), stream.getResolution(), stream.getCodec(),
                    stream.getProfile() == null ? "" : stream.getProfile(),
                    stream.getBitrateKbps(), stream.getFps());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stream.setAnalysisError("Cancelled");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stream.setAnalysisError(FFmpegSupport.isUnauthorized(message) ? "Authentication rejected" : message);
            logger.debug("Analysis failed for {}: {}",
                    RtspClient.stripCredentials(stream.getRtspUrl()), message);
        } finally {
            FFmpegSupport.closeQuietly(grabber);
            if (permitHeld) {
                analysisPermits.release();
            }
        }
    }

    /**
     * Read everything measurable from a grabber that has already started.
     *
     * <p>Separate from {@link #analyzeStream} so the measurement can be
     * exercised against a recorded file: the rate calculation has been wrong in
     * both directions before, and only a manual comparison against ffprobe
     * caught it.
     */
    void populate(RTSPStream stream, FFmpegFrameGrabber grabber) {
        if (grabber.getImageWidth() > 0 && grabber.getImageHeight() > 0) {
            stream.setDimensions(grabber.getImageWidth(), grabber.getImageHeight());
        }
        String codec = FFmpegSupport.videoCodecName(grabber);
        if (codec != null) {
            stream.setCodec(codec);
        }
        String profile = FFmpegSupport.videoProfile(grabber);
        if (profile != null) {
            stream.setProfile(profile);
        }
        String audio = FFmpegSupport.audioCodecName(grabber);
        if (audio != null) {
            stream.setAudioCodec(audio);
        }

        Measurement measurement = measure(grabber, config.getStreamAnalysisDuration());
        if (measurement.videoPackets() > 0) {
            stream.setBitrateKbps((int) Math.round(measurement.kbps()));
            if (measurement.fps() > 0) {
                stream.setFps(round(measurement.fps(), 2));
            }
            if (measurement.keyframeIntervalSeconds() > 0) {
                stream.setKeyframeIntervalSeconds(round(measurement.keyframeIntervalSeconds(), 2));
            }
        }
        if (stream.getFps() == null && grabber.getFrameRate() > 0) {
            stream.setFps(round(grabber.getFrameRate(), 2));
        }
        if (stream.getWidth() == null && measurement.videoPackets() == 0) {
            // No packets and no dimensions: fall back to one decoded image.
            try {
                Frame image = grabber.grabImage();
                if (image != null && image.imageWidth > 0) {
                    stream.setDimensions(image.imageWidth, image.imageHeight);
                }
            } catch (Exception e) {
                logger.debug("Fallback image grab failed: {}", e.getMessage());
            }
        }
        stream.setAnalyzed(true);
    }

    /** Values measured over a sampling window. */
    record Measurement(long videoPackets, long videoBytes, double kbps, double fps,
                               double keyframeIntervalSeconds) {
    }

    /**
     * Sample the compressed stream for {@code seconds}, summing packet sizes and
     * counting frames and keyframes.
     *
     * <p>Rates are derived from the span of presentation timestamps rather than
     * from wall-clock time. A camera that delivers a backlog faster than real
     * time, or stalls on a busy link, otherwise yields a frame rate and bitrate
     * that describe the network rather than the encoder.
     */
    Measurement measure(FFmpegFrameGrabber grabber, int seconds) {
        long bytes = 0;
        long packets = 0;
        long keyframes = 0;
        long firstPts = Long.MIN_VALUE;
        long lastPts = Long.MIN_VALUE;
        long firstKeyPts = Long.MIN_VALUE;
        long lastKeyPts = Long.MIN_VALUE;

        int videoStream = grabber.getVideoStream();
        double timeBase = videoTimeBaseSeconds(grabber, videoStream);
        long start = System.nanoTime();
        double target = Math.max(1, seconds);
        // Sample until enough *stream* time has passed, capped by a wall-clock
        // limit for cameras that stall or deliver far slower than real time.
        // Stopping purely on wall clock let a burst of buffered frames, which
        // spans very little stream time, inflate the bitrate several-fold.
        long deadline = start + TimeUnit.SECONDS.toNanos((long) Math.ceil(target * 3));

        try {
            while (System.nanoTime() < deadline && !cancelled) {
                if (timeBase > 0 && firstPts != Long.MIN_VALUE && lastPts > firstPts
                        && (lastPts - firstPts) * timeBase >= target) {
                    break;
                }
                AVPacket packet = grabber.grabPacket();
                if (packet == null) {
                    break;
                }
                try {
                    if (packet.stream_index() != videoStream) {
                        continue;
                    }
                    packets++;
                    bytes += Math.max(0, packet.size());
                    long pts = packet.pts();
                    if (pts != avutil.AV_NOPTS_VALUE) {
                        if (firstPts == Long.MIN_VALUE) {
                            firstPts = pts;
                        }
                        lastPts = pts;
                    }
                    if ((packet.flags() & avcodec.AV_PKT_FLAG_KEY) != 0) {
                        keyframes++;
                        if (pts != avutil.AV_NOPTS_VALUE) {
                            if (firstKeyPts == Long.MIN_VALUE) {
                                firstKeyPts = pts;
                            }
                            lastKeyPts = pts;
                        }
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        } catch (Exception e) {
            logger.debug("Sampling ended early: {}", e.getMessage());
        }

        if (packets == 0) {
            return new Measurement(0, 0, 0, 0, 0);
        }

        double wallSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double streamSeconds = 0;
        if (timeBase > 0 && firstPts != Long.MIN_VALUE && lastPts > firstPts) {
            streamSeconds = (lastPts - firstPts) * timeBase;
        }
        // Fall back to wall clock when the device sends no usable timestamps.
        double span = streamSeconds > 0.5 ? streamSeconds : wallSeconds;
        if (span <= 0) {
            return new Measurement(packets, bytes, 0, 0, 0);
        }

        // With N frames spanning the interval between the first and last, the
        // rate is (N-1)/span when timestamps drive it.
        double frameCount = streamSeconds > 0.5 ? packets - 1 : packets;
        double kbps = (bytes * 8.0) / span / 1000.0;
        double fps = frameCount / span;

        double keyframeInterval = 0;
        if (keyframes > 1) {
            keyframeInterval = timeBase > 0 && lastKeyPts > firstKeyPts
                    ? (lastKeyPts - firstKeyPts) * timeBase / (keyframes - 1)
                    : span / (keyframes - 1);
        }
        return new Measurement(packets, bytes, kbps, fps, keyframeInterval);
    }

    /** Seconds per timestamp unit for the video stream, or 0 when unknown. */
    private static double videoTimeBaseSeconds(FFmpegFrameGrabber grabber, int videoStream) {
        try {
            if (videoStream < 0 || grabber.getFormatContext() == null) {
                return 0;
            }
            AVStream stream = grabber.getFormatContext().streams(videoStream);
            if (stream == null) {
                return 0;
            }
            AVRational timeBase = stream.time_base();
            if (timeBase == null || timeBase.den() == 0) {
                return 0;
            }
            return (double) timeBase.num() / timeBase.den();
        } catch (Exception e) {
            return 0;
        }
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /**
     * Decide which stream is main and which is sub from the measured pixel
     * counts, so compliance applies to the right one even when the device names
     * its profiles something opaque like PROFILE_1 and PROFILE_2.
     */
    static void refineRoles(List<RTSPStream> streams) {
        List<RTSPStream> byChannel = new ArrayList<>(streams);
        long distinctChannels = byChannel.stream()
                .map(s -> s.getChannelName() == null ? "" : s.getChannelName())
                .distinct().count();
        if (distinctChannels > 1) {
            // Recorder channels already carry explicit roles.
            return;
        }
        List<RTSPStream> measured = byChannel.stream().filter(s -> s.pixels() > 0).toList();
        if (measured.size() < 2) {
            return;
        }
        long maxPixels = measured.stream().mapToLong(RTSPStream::pixels).max().orElse(0);
        for (RTSPStream stream : measured) {
            stream.setRole(stream.pixels() == maxPixels ? RTSPStream.Role.MAIN : RTSPStream.Role.SUB);
        }
    }

    /**
     * Apply the configured rules. High profile is flagged on every stream
     * because browsers cannot play it over HLS without transcoding; resolution
     * and bitrate limits apply to sub-streams.
     */
    void checkCompliance(Device device, RTSPStream stream) {
        List<String> issues = new ArrayList<>();

        if (config.isHighProfileFlagged() && isHighProfile(stream.getProfile())) {
            issues.add("High profile (needs transcoding for browser playback)");
            device.addFinding(new Finding(Finding.Severity.MEDIUM, "Compliance",
                    "Stream uses H.264 High profile",
                    stream.getStreamName() + " reports " + stream.getProfile(),
                    "Set the encoder to Main or Baseline profile for browser-based viewing."));
        }

        if (stream.getRole() == RTSPStream.Role.SUB) {
            Integer height = stream.getHeight();
            if (height != null) {
                int min = config.getSubStreamMinHeight();
                int max = config.getSubStreamMaxHeight();
                if (height < min || height > max) {
                    issues.add("Resolution outside " + min + "p-" + max + "p");
                }
            }
            String codec = stream.getCodec();
            if (codec != null && !codec.contains("H.264")) {
                issues.add("Codec is " + codec + ", not H.264");
            }
            Integer bitrate = stream.getBitrateKbps();
            int maxKbps = config.getSubStreamMaxKbps();
            if (bitrate != null && bitrate >= maxKbps) {
                issues.add("Bitrate " + bitrate + " kbps is at or above " + maxKbps + " kbps");
            }
        }

        if (stream.getAnalysisError() != null) {
            issues.add("Not analysed: " + stream.getAnalysisError());
        }

        stream.setCompliant(issues.isEmpty());
        stream.setComplianceIssues(issues.isEmpty() ? null : String.join("; ", issues));
    }

    static boolean isHighProfile(String profile) {
        return profile != null && profile.toLowerCase(Locale.ROOT).contains("high");
    }

    public void shutdown() {
        cancel();
    }

    @Override
    public void close() {
        shutdown();
    }
}
