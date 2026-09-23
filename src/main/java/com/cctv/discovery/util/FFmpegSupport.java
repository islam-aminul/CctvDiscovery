package com.cctv.discovery.util;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Shared FFmpeg setup for stream probing and analysis.
 *
 * <p>Two details matter and were wrong before. The socket timeout option is
 * {@code timeout}: FFmpeg 6 removed {@code stimeout}, so a server that accepts
 * a connection and then goes silent blocked the caller indefinitely. And the
 * first frames of a camera with audio are audio frames, so a validity check
 * must ask for an image rather than take whatever frame arrives first.
 */
public final class FFmpegSupport {

    private static final Logger logger = LoggerFactory.getLogger(FFmpegSupport.class);

    static {
        // Route native FFmpeg output through SLF4J; logback keeps it out of the console.
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        FFmpegLogCallback.set();
    }

    private FFmpegSupport() {
    }

    /** Touching this class applies the native log configuration. */
    public static void init() {
        // static initialiser does the work
    }

    /**
     * Build a grabber for an RTSP URL. Credentials, when given, are placed in
     * the URL userinfo percent-encoded so that {@code @ : / ? #} in a password
     * cannot corrupt it.
     */
    public static FFmpegFrameGrabber grabber(String rtspUrl, String username, String password, int timeoutMs) {
        String url = username == null || username.isEmpty()
                ? rtspUrl
                : RtspClient.withCredentials(rtspUrl, username, password);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url);
        grabber.setFormat("rtsp");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        // Microseconds. 'timeout' replaced 'stimeout' in FFmpeg 6.
        String micros = String.valueOf(Math.max(1, (long) timeoutMs) * 1000L);
        grabber.setOption("timeout", micros);
        grabber.setOption("rw_timeout", micros);
        grabber.setOption("stimeout", micros); // harmless on older builds
        grabber.setOption("max_delay", "500000");
        grabber.setOption("reorder_queue_size", "0");
        grabber.setOption("fflags", "nobuffer");
        grabber.setOption("analyzeduration", "3000000");
        grabber.setOption("probesize", "1000000");
        grabber.setImageWidth(0);
        grabber.setImageHeight(0);
        return grabber;
    }

    /**
     * Codec profile and level from the decoder context, for example
     * {@code "High 5.1"}. The container metadata this was previously read from
     * is empty for live RTSP, which is why no profile was ever reported.
     */
    public static String videoProfile(FFmpegFrameGrabber grabber) {
        try {
            int index = grabber.getVideoStream();
            if (index < 0 || grabber.getFormatContext() == null) {
                return null;
            }
            AVStream stream = grabber.getFormatContext().streams(index);
            if (stream == null) {
                return null;
            }
            AVCodecParameters params = stream.codecpar();
            if (params == null) {
                return null;
            }
            int profile = params.profile();
            int level = params.level();
            if (profile == avcodec.AV_PROFILE_UNKNOWN) {
                return null;
            }
            String codec = grabber.getVideoCodecName();
            String name = profileName(codec, profile);
            return level > 0 ? name + " " + formatLevel(level) : name;
        } catch (Exception e) {
            logger.debug("Cannot read codec profile: {}", e.getMessage());
            return null;
        }
    }

    private static String profileName(String codecName, int profile) {
        String codec = codecName == null ? "" : codecName.toLowerCase(Locale.ROOT);
        if (codec.contains("hevc") || codec.contains("265")) {
            return SdpParser.h265ProfileName(profile);
        }
        // H.264 profile_idc values are carried directly in codecpar.profile.
        return SdpParser.h264ProfileName(profile, 0);
    }

    private static String formatLevel(int level) {
        return level % 10 == 0 ? String.valueOf(level / 10) : (level / 10) + "." + (level % 10);
    }

    /** Human-readable codec name for a grabber's video stream. */
    public static String videoCodecName(FFmpegFrameGrabber grabber) {
        String name = grabber.getVideoCodecName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "h264" -> "H.264";
            case "hevc", "h265" -> "H.265";
            case "mjpeg" -> "MJPEG";
            case "mpeg4" -> "MPEG-4";
            default -> name;
        };
    }

    /** Human-readable codec name for a grabber's audio stream, or null. */
    public static String audioCodecName(FFmpegFrameGrabber grabber) {
        if (grabber.getAudioStream() < 0) {
            return null;
        }
        String name = grabber.getAudioCodecName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "pcm_alaw" -> "G.711 A-law";
            case "pcm_mulaw" -> "G.711 u-law";
            case "aac" -> "AAC";
            default -> name;
        };
    }

    /** Stop and release a grabber, never throwing. */
    public static void closeQuietly(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.stop();
        } catch (Exception e) {
            logger.debug("Grabber stop failed: {}", e.getMessage());
        }
        try {
            grabber.release();
        } catch (Exception e) {
            logger.debug("Grabber release failed: {}", e.getMessage());
        }
    }

    /** True when an FFmpeg error message indicates 401 Unauthorized. */
    public static boolean isUnauthorized(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("401") || lower.contains("unauthorized") || message.contains("-825242872");
    }
}
