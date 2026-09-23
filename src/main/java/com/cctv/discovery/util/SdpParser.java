package com.cctv.discovery.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal SDP (RFC 8866) parser for RTSP DESCRIBE responses.
 */
public final class SdpParser {

    private SdpParser() {
    }

    /** One m= section. */
    public record Media(String type, int payloadType, String encoding, int clockRate,
                        Map<String, String> fmtp, String control) {

        /** Human-readable codec name, e.g. "H.264". */
        public String codecName() {
            if (encoding == null) {
                return null;
            }
            return switch (encoding.toUpperCase(Locale.ROOT)) {
                case "H264" -> "H.264";
                case "H265", "HEVC" -> "H.265";
                case "JPEG" -> "MJPEG";
                case "MP4V-ES" -> "MPEG-4";
                case "PCMA" -> "G.711 A-law";
                case "PCMU" -> "G.711 µ-law";
                case "MPEG4-GENERIC", "MP4A-LATM" -> "AAC";
                default -> encoding;
            };
        }

        /** Profile and level advertised in fmtp, e.g. "High 5.1"; null if not present. */
        public String profile() {
            String enc = encoding == null ? "" : encoding.toUpperCase(Locale.ROOT);
            if (enc.equals("H264")) {
                return h264Profile(fmtp.get("profile-level-id"));
            }
            if (enc.equals("H265") || enc.equals("HEVC")) {
                return h265Profile(fmtp.get("profile-id"), fmtp.get("level-id"));
            }
            return null;
        }
    }

    /** Parsed session description. */
    public record Sdp(String sessionName, String sessionControl, List<Media> media) {
        public Media firstVideo() {
            return media.stream().filter(m -> "video".equals(m.type())).findFirst().orElse(null);
        }

        public Media firstAudio() {
            return media.stream().filter(m -> "audio".equals(m.type())).findFirst().orElse(null);
        }
    }

    /**
     * Parse SDP text. Returns null when the body is not SDP (no v= line).
     */
    public static Sdp parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] lines = body.split("\r?\n|\r");
        boolean sawVersion = false;
        String sessionName = null;
        String sessionControl = null;
        List<Media> media = new ArrayList<>();

        String mType = null;
        int mPt = -1;
        String mEnc = null;
        int mRate = 0;
        Map<String, String> mFmtp = new LinkedHashMap<>();
        String mControl = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() < 2 || line.charAt(1) != '=') {
                continue;
            }
            char k = line.charAt(0);
            String v = line.substring(2);
            if (k == 'v') {
                sawVersion = true;
            } else if (k == 's' && mType == null) {
                String s = v.trim();
                sessionName = (s.isEmpty() || s.equals("-")) ? null : s;
            } else if (k == 'm') {
                if (mType != null) {
                    media.add(new Media(mType, mPt, mEnc, mRate, mFmtp, mControl));
                }
                String[] parts = v.split("\\s+");
                mType = parts[0];
                mPt = parts.length > 3 ? parseIntSafe(parts[3]) : -1;
                mEnc = staticEncoding(mType, mPt);
                mRate = 0;
                mFmtp = new LinkedHashMap<>();
                mControl = null;
            } else if (k == 'a') {
                if (v.startsWith("control:")) {
                    String c = v.substring(8).trim();
                    if (mType == null) {
                        sessionControl = c;
                    } else {
                        mControl = c;
                    }
                } else if (v.startsWith("rtpmap:") && mType != null) {
                    // a=rtpmap:<pt> <encoding>/<clock>[/<channels>]
                    String[] parts = v.substring(7).trim().split("\\s+", 2);
                    if (parts.length == 2 && parseIntSafe(parts[0]) == mPt) {
                        String[] enc = parts[1].split("/");
                        mEnc = enc[0];
                        mRate = enc.length > 1 ? parseIntSafe(enc[1]) : 0;
                    }
                } else if (v.startsWith("fmtp:") && mType != null) {
                    String[] parts = v.substring(5).trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        for (String kv : parts[1].split(";")) {
                            int eq = kv.indexOf('=');
                            if (eq > 0) {
                                mFmtp.put(kv.substring(0, eq).trim().toLowerCase(Locale.ROOT), kv.substring(eq + 1).trim());
                            }
                        }
                    }
                }
            }
        }
        if (mType != null) {
            media.add(new Media(mType, mPt, mEnc, mRate, mFmtp, mControl));
        }
        return sawVersion ? new Sdp(sessionName, sessionControl, List.copyOf(media)) : null;
    }

    /**
     * Resolve a media control attribute against the presentation base
     * (RFC 2326 C.1.1): absolute URLs are used as-is, "*" means the base
     * itself, anything else is relative to the base.
     */
    public static String resolveControl(String base, String control) {
        if (control == null || control.isEmpty() || control.equals("*")) {
            return base;
        }
        if (control.regionMatches(true, 0, "rtsp://", 0, 7) || control.regionMatches(true, 0, "rtsps://", 0, 8)) {
            return control;
        }
        String b = base.endsWith("/") ? base : base + "/";
        return control.startsWith("/") ? b + control.substring(1) : b + control;
    }

    private static String staticEncoding(String ignoredType, int pt) {
        return switch (pt) {
            case 0 -> "PCMU";
            case 8 -> "PCMA";
            case 26 -> "JPEG";
            default -> null; // dynamic payload types are named by a=rtpmap
        };
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Decode an H.264 profile-level-id (hex "PPCCLL") into e.g. "High 4.1". */
    public static String h264Profile(String profileLevelId) {
        if (profileLevelId == null || profileLevelId.length() != 6) {
            return null;
        }
        try {
            int profileIdc = Integer.parseInt(profileLevelId.substring(0, 2), 16);
            int constraints = Integer.parseInt(profileLevelId.substring(2, 4), 16);
            int levelIdc = Integer.parseInt(profileLevelId.substring(4, 6), 16);
            String name = h264ProfileName(profileIdc, constraints);
            return levelIdc > 0 ? name + " " + formatLevel(levelIdc) : name;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String h264ProfileName(int profileIdc, int constraintFlags) {
        return switch (profileIdc) {
            case 66 -> (constraintFlags & 0x40) != 0 ? "Constrained Baseline" : "Baseline";
            case 77 -> "Main";
            case 88 -> "Extended";
            case 100 -> "High";
            case 110 -> "High 10";
            case 122 -> "High 4:2:2";
            case 244 -> "High 4:4:4";
            case 44 -> "CAVLC 4:4:4";
            default -> "Profile " + profileIdc;
        };
    }

    private static String formatLevel(int levelIdc) {
        return levelIdc % 10 == 0 ? String.valueOf(levelIdc / 10) : (levelIdc / 10) + "." + (levelIdc % 10);
    }

    static String h265Profile(String profileId, String levelId) {
        if (profileId == null) {
            return null;
        }
        int id = parseIntSafe(profileId);
        String name = h265ProfileName(id);
        int level = levelId == null ? -1 : parseIntSafe(levelId);
        if (level > 0) {
            double l = level / 30.0;
            name += " " + (l == Math.floor(l) ? String.valueOf((int) l) : String.format(Locale.ROOT, "%.1f", l));
        }
        return name;
    }

    public static String h265ProfileName(int id) {
        return switch (id) {
            case 1 -> "Main";
            case 2 -> "Main 10";
            case 3 -> "Main Still Picture";
            case 4 -> "Range Extensions";
            default -> "Profile " + id;
        };
    }
}
