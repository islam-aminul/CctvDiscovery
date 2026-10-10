package com.cctv.discovery.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * HTTP/RTSP Basic and Digest authentication (RFC 7617 / RFC 7616) and the
 * ONVIF WS-Security UsernameToken.
 *
 * <p>Nothing here writes to a log. Callers must keep Authorization headers and
 * Security elements out of log output; see {@link #redact(String)}.
 */
public final class AuthUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthUtils() {
    }

    public enum AuthType {BASIC, DIGEST}

    /** A parsed WWW-Authenticate challenge. */
    public static final class AuthChallenge {
        public AuthType type;
        public String realm;
        public String nonce;
        public String opaque;
        public String qop;
        public String algorithm;
        public String stale;

        public boolean isValid() {
            if (type == AuthType.BASIC) {
                return true;
            }
            return type == AuthType.DIGEST && realm != null && nonce != null && !nonce.isEmpty();
        }

        /** True when the server offered {@code qop=auth} (possibly among others). */
        public boolean offersQopAuth() {
            if (qop == null) {
                return false;
            }
            for (String q : qop.split(",")) {
                if ("auth".equalsIgnoreCase(q.trim())) {
                    return true;
                }
            }
            return false;
        }

        /** Safe for logs: describes the challenge without any secret. */
        @Override
        public String toString() {
            return type == AuthType.BASIC
                    ? "Basic realm=\"" + realm + '"'
                    : "Digest realm=\"" + realm + "\", algorithm=" + (algorithm == null ? "MD5" : algorithm)
                      + ", qop=" + qop;
        }
    }

    /**
     * Credentials bound to one server challenge. Produces a fresh Authorization
     * value per request, because the Digest hash covers the method and URI, and
     * increments the nonce count as RFC 7616 requires.
     */
    public static final class Authenticator {
        private final AuthChallenge challenge;
        private final String username;
        private final String password;
        private int nonceCount;

        public Authenticator(AuthChallenge challenge, String username, String password) {
            this.challenge = challenge;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        public AuthType type() {
            return challenge.type;
        }

        public AuthChallenge challenge() {
            return challenge;
        }

        public synchronized String authorization(String method, String uri) {
            if (challenge.type == AuthType.BASIC) {
                return basic(username, password);
            }
            nonceCount++;
            return digest(challenge, username, password, method, uri, nonceCount, newCnonce());
        }
    }

    public static String basic(String username, String password) {
        String raw = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Build a Digest Authorization value. {@code qop} is sent only when the
     * server offered it; RFC 2069-era devices get the legacy response, which the
     * previous implementation broke by always sending {@code qop=auth}.
     */
    public static String digest(AuthChallenge c, String username, String password, String method, String uri,
                                int nonceCount, String cnonce) {
        String algorithm = c.algorithm == null ? "MD5" : c.algorithm.trim();
        String upper = algorithm.toUpperCase(Locale.ROOT);
        boolean sess = upper.endsWith("-SESS");
        String hashName = upper.startsWith("SHA-256") ? "SHA-256" : "MD5";
        boolean useQop = c.offersQopAuth();
        String nc = String.format("%08x", nonceCount);

        String ha1 = hash(hashName, username + ":" + c.realm + ":" + password);
        if (sess) {
            ha1 = hash(hashName, ha1 + ":" + c.nonce + ":" + cnonce);
        }
        String ha2 = hash(hashName, method + ":" + uri);
        String response = useQop
                ? hash(hashName, ha1 + ":" + c.nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : hash(hashName, ha1 + ":" + c.nonce + ":" + ha2);

        StringBuilder h = new StringBuilder(220);
        h.append("Digest username=\"").append(quote(username)).append('"');
        h.append(", realm=\"").append(quote(c.realm)).append('"');
        h.append(", nonce=\"").append(quote(c.nonce)).append('"');
        h.append(", uri=\"").append(quote(uri)).append('"');
        if (c.algorithm != null) {
            h.append(", algorithm=").append(algorithm);
        }
        if (useQop) {
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        }
        h.append(", response=\"").append(response).append('"');
        if (c.opaque != null && !c.opaque.isEmpty()) {
            h.append(", opaque=\"").append(quote(c.opaque)).append('"');
        }
        return h.toString();
    }

    private static String quote(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String hash(String algorithm, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    public static String newCnonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Parse every challenge from one or more WWW-Authenticate values, strongest
     * first (SHA-256 Digest, then MD5 Digest, then Basic).
     */
    public static List<AuthChallenge> parseChallenges(List<String> headerValues) {
        List<AuthChallenge> result = new ArrayList<>();
        for (String value : headerValues) {
            AuthChallenge c = parseAuthChallenge(value);
            if (c != null && c.isValid()) {
                result.add(c);
            }
        }
        result.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        return result;
    }

    private static int rank(AuthChallenge c) {
        if (c.type == AuthType.BASIC) {
            return 0;
        }
        String alg = c.algorithm == null ? "MD5" : c.algorithm.toUpperCase(Locale.ROOT);
        return alg.startsWith("SHA-256") ? 3 : 2;
    }

    /** Parse a single WWW-Authenticate value; null when the scheme is unsupported. */
    public static AuthChallenge parseAuthChallenge(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        AuthChallenge c = new AuthChallenge();
        String params;
        if (lower.startsWith("basic")) {
            c.type = AuthType.BASIC;
            params = trimmed.substring(5);
        } else if (lower.startsWith("digest")) {
            c.type = AuthType.DIGEST;
            params = trimmed.substring(6);
        } else {
            return null;
        }
        for (String part : splitRespectingQuotes(params)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = unquote(part.substring(eq + 1).trim());
            switch (key) {
                case "realm" -> c.realm = value;
                case "nonce" -> c.nonce = value;
                case "opaque" -> c.opaque = value;
                case "qop" -> c.qop = value;
                case "algorithm" -> c.algorithm = value;
                case "stale" -> c.stale = value;
                default -> { /* ignore unknown parameters */ }
            }
        }
        if (c.realm == null) {
            c.realm = "";
        }
        return c;
    }

    private static List<String> splitRespectingQuotes(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\\' && inQuotes && i + 1 < input.length()) {
                current.append(ch).append(input.charAt(++i));
            } else if (ch == '"') {
                inQuotes = !inQuotes;
                current.append(ch);
            } else if (ch == ',' && !inQuotes) {
                if (!current.isEmpty()) {
                    parts.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    // ------------------------------------------------------------ WS-Security

    /**
     * ONVIF WS-Security UsernameToken with PasswordDigest.
     *
     * @param clockOffsetMillis device clock minus host clock, so the Created
     *                          stamp is in the device's own time and cameras
     *                          with a skewed clock still accept the token
     */
    public static String wsSecurityHeader(String username, String password, long clockOffsetMillis) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String created = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plusMillis(clockOffsetMillis).truncatedTo(ChronoUnit.SECONDS));
        String digest = passwordDigest(nonce, created, password == null ? "" : password);
        return "<wsse:Security s:mustUnderstand=\"1\" "
                + "xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" "
                + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + "<wsse:UsernameToken>"
                + "<wsse:Username>" + XmlUtils.escape(username) + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/"
                + "oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digest + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/"
                + "oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce>"
                + "<wsu:Created>" + created + "</wsu:Created>"
                + "</wsse:UsernameToken></wsse:Security>";
    }

    /** PasswordDigest = Base64(SHA-1(nonce + created + password)). */
    public static String passwordDigest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public static String uuidUrn() {
        return "urn:uuid:" + UUID.randomUUID();
    }

    /**
     * Replace the contents of any Security element or Authorization header in
     * {@code text} with a placeholder, so protocol traces can be logged safely.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replaceAll("(?is)<([a-z0-9]+:)?Security\\b.*?</([a-z0-9]+:)?Security>", "<Security>[redacted]</Security>")
                // Everything after the scheme is secret, so mask to end of line.
                .replaceAll("(?i)(Authorization:\\s*)(Basic|Digest)\\b.*", "$1$2 [redacted]")
                .replaceAll("(?i)(rtsps?://)[^/@\\s]+@", "$1[redacted]@");
    }
}
