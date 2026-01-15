package com.cctv.discovery.util;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Authentication utility class for HTTP Digest, Basic Auth, and WS-Security.
 */
public class AuthUtils {

    /**
     * Generate HTTP Basic Authentication header value.
     */
    public static String generateBasicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.encodeBase64String(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate HTTP Digest Authentication response.
     */
    public static String generateDigestResponse(String username, String password,
                                                 String realm, String nonce, String uri,
                                                 String method, String qop, String nc, String cnonce) {
        String ha1 = DigestUtils.md5Hex(username + ":" + realm + ":" + password);
        String ha2 = DigestUtils.md5Hex(method + ":" + uri);

        String response;
        if (qop != null && !qop.isEmpty()) {
            response = DigestUtils.md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            response = DigestUtils.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }

        return response;
    }

    /**
     * Build Digest Authorization header.
     */
    public static String buildDigestAuthHeader(String username, String password,
                                                String realm, String nonce, String uri,
                                                String method, String opaque) {
        String qop = "auth";
        String nc = "00000001";
        String cnonce = generateCnonce();

        String response = generateDigestResponse(username, password, realm, nonce, uri, method, qop, nc, cnonce);

        StringBuilder header = new StringBuilder("Digest ");
        header.append("username=\"").append(username).append("\", ");
        header.append("realm=\"").append(realm).append("\", ");
        header.append("nonce=\"").append(nonce).append("\", ");
        header.append("uri=\"").append(uri).append("\", ");
        header.append("qop=").append(qop).append(", ");
        header.append("nc=").append(nc).append(", ");
        header.append("cnonce=\"").append(cnonce).append("\", ");
        header.append("response=\"").append(response).append("\"");

        if (opaque != null && !opaque.isEmpty()) {
            header.append(", opaque=\"").append(opaque).append("\"");
        }

        return header.toString();
    }

    /**
     * Generate client nonce for Digest auth.
     */
    public static String generateCnonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return DigestUtils.md5Hex(bytes);
    }

    /**
     * Generate WS-Security UsernameToken nonce.
     */
    public static String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.encodeBase64String(bytes);
    }

    /**
     * Generate WS-Security timestamp in ISO 8601 format.
     */
    public static String generateTimestamp() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat.format(new Date());
    }

    /**
     * Generate WS-Security password digest.
     * PasswordDigest = Base64(SHA1(nonce + created + password))
     */
    public static String generatePasswordDigest(String nonce, String created, String password) {
        try {
            byte[] nonceBytes = Base64.decodeBase64(nonce);
            byte[] createdBytes = created.getBytes(StandardCharsets.UTF_8);
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(nonceBytes);
            digest.update(createdBytes);
            digest.update(passwordBytes);

            return Base64.encodeBase64String(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Error generating password digest", e);
        }
    }

    /**
     * Generate WS-Security UsernameToken XML element.
     */
    public static String generateWsSecurityHeader(String username, String password) {
        String nonce = generateNonce();
        String created = generateTimestamp();
        String passwordDigest = generatePasswordDigest(nonce, created, password);

        StringBuilder xml = new StringBuilder();
        xml.append("<Security xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" ");
        xml.append("xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">");
        xml.append("<UsernameToken>");
        xml.append("<Username>").append(escapeXml(username)).append("</Username>");
        xml.append("<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">");
        xml.append(passwordDigest).append("</Password>");
        xml.append("<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">");
        xml.append(nonce).append("</Nonce>");
        xml.append("<wsu:Created>").append(created).append("</wsu:Created>");
        xml.append("</UsernameToken>");
        xml.append("</Security>");

        return xml.toString();
    }

    /**
     * Generate UUID for WS-Discovery messages.
     */
    public static String generateUUID() {
        return "uuid:" + UUID.randomUUID().toString();
    }

    /**
     * Escape XML special characters.
     */
    private static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Parse WWW-Authenticate header to extract authentication challenge.
     * Supports both Digest and Basic authentication.
     */
    public static AuthChallenge parseAuthChallenge(String wwwAuthenticate) {
        if (wwwAuthenticate == null || wwwAuthenticate.trim().isEmpty()) {
            return null;
        }

        wwwAuthenticate = wwwAuthenticate.trim();

        // Check for Basic authentication
        if (wwwAuthenticate.toLowerCase().startsWith("basic")) {
            return parseBasicChallenge(wwwAuthenticate);
        }

        // Check for Digest authentication
        if (wwwAuthenticate.toLowerCase().startsWith("digest")) {
            return parseDigestChallenge(wwwAuthenticate);
        }

        return null;
    }

    /**
     * Parse Basic authentication challenge.
     */
    private static AuthChallenge parseBasicChallenge(String wwwAuthenticate) {
        AuthChallenge challenge = new AuthChallenge();
        challenge.type = AuthType.BASIC;

        // Extract realm from Basic challenge
        // Format: Basic realm="some realm"
        String afterBasic = wwwAuthenticate.substring(5).trim();
        if (afterBasic.toLowerCase().startsWith("realm=")) {
            challenge.realm = extractValue(afterBasic.substring(6));
        } else {
            // Some servers send just "Basic" without realm
            challenge.realm = "Camera";
        }

        return challenge;
    }

    /**
     * Parse Digest authentication challenge with comprehensive format support.
     */
    private static AuthChallenge parseDigestChallenge(String wwwAuthenticate) {
        AuthChallenge challenge = new AuthChallenge();
        challenge.type = AuthType.DIGEST;

        String afterDigest = wwwAuthenticate.substring(6).trim();

        // Split by comma, but be careful about commas inside quoted values
        String[] parts = splitRespectingQuotes(afterDigest);

        for (String part : parts) {
            part = part.trim();

            if (part.toLowerCase().startsWith("realm=")) {
                challenge.realm = extractValue(part.substring(6));
            } else if (part.toLowerCase().startsWith("nonce=")) {
                challenge.nonce = extractValue(part.substring(6));
            } else if (part.toLowerCase().startsWith("opaque=")) {
                challenge.opaque = extractValue(part.substring(7));
            } else if (part.toLowerCase().startsWith("qop=")) {
                challenge.qop = extractValue(part.substring(4));
            } else if (part.toLowerCase().startsWith("algorithm=")) {
                challenge.algorithm = extractValue(part.substring(10));
            } else if (part.toLowerCase().startsWith("stale=")) {
                challenge.stale = extractValue(part.substring(6));
            }
        }

        return challenge;
    }

    /**
     * Split authentication header by comma while respecting quoted values.
     * Example: realm="test,value", nonce="123" -> ["realm=\"test,value\"", "nonce=\"123\""]
     */
    private static String[] splitRespectingQuotes(String input) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                current.append(c);
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }

            if (c == ',' && !inQuotes) {
                // Split here
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
                continue;
            }

            current.append(c);
        }

        // Add the last part
        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    /**
     * Extract value from authentication parameter.
     * Handles both quoted and unquoted values.
     * Examples:
     *   "value"    -> value
     *   value      -> value
     *   \"value\"  -> value (escaped quotes)
     */
    private static String extractValue(String part) {
        if (part == null || part.isEmpty()) {
            return null;
        }

        part = part.trim();

        // Handle quoted values
        if (part.startsWith("\"") && part.endsWith("\"") && part.length() > 1) {
            return part.substring(1, part.length() - 1);
        }

        // Handle escaped quotes: \"value\"
        if (part.startsWith("\\\"") && part.endsWith("\\\"") && part.length() > 3) {
            return part.substring(2, part.length() - 2);
        }

        // Find first and last quote if they exist
        int firstQuote = part.indexOf('"');
        int lastQuote = part.lastIndexOf('"');

        if (firstQuote != -1 && lastQuote != -1 && lastQuote > firstQuote) {
            return part.substring(firstQuote + 1, lastQuote);
        }

        // Unquoted value - take until space, comma, or semicolon
        int endIdx = part.length();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == ' ' || c == ',' || c == ';') {
                endIdx = i;
                break;
            }
        }

        String value = part.substring(0, endIdx).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use parseAuthChallenge instead
     */
    @Deprecated
    public static DigestChallenge parseDigestChallenge(String wwwAuthenticate) {
        AuthChallenge challenge = parseAuthChallenge(wwwAuthenticate);
        if (challenge == null || challenge.type != AuthType.DIGEST) {
            return null;
        }

        DigestChallenge digestChallenge = new DigestChallenge();
        digestChallenge.realm = challenge.realm;
        digestChallenge.nonce = challenge.nonce;
        digestChallenge.opaque = challenge.opaque;
        digestChallenge.qop = challenge.qop;
        return digestChallenge;
    }

    /**
     * Check if authentication challenge is valid.
     */
    public static boolean isValidChallenge(AuthChallenge challenge) {
        if (challenge == null) {
            return false;
        }

        if (challenge.type == AuthType.BASIC) {
            // Basic auth is always valid if we have the type
            return true;
        }

        if (challenge.type == AuthType.DIGEST) {
            // Digest auth requires realm and nonce
            return challenge.realm != null && !challenge.realm.isEmpty() &&
                   challenge.nonce != null && !challenge.nonce.isEmpty();
        }

        return false;
    }

    /**
     * Authentication type enum.
     */
    public enum AuthType {
        BASIC,
        DIGEST
    }

    /**
     * Authentication challenge information.
     * Supports both Basic and Digest authentication.
     */
    public static class AuthChallenge {
        public AuthType type;
        public String realm;
        public String nonce;
        public String opaque;
        public String qop;
        public String algorithm;
        public String stale;

        @Override
        public String toString() {
            if (type == AuthType.BASIC) {
                return "AuthChallenge{type=BASIC, realm='" + realm + "'}";
            } else if (type == AuthType.DIGEST) {
                return "AuthChallenge{type=DIGEST, realm='" + realm + "', nonce='" + nonce + "', qop='" + qop + "'}";
            }
            return "AuthChallenge{type=" + type + "}";
        }

        public boolean isValid() {
            return AuthUtils.isValidChallenge(this);
        }
    }

    /**
     * Digest challenge information.
     * @deprecated Use AuthChallenge instead
     */
    @Deprecated
    public static class DigestChallenge {
        public String realm;
        public String nonce;
        public String opaque;
        public String qop;

        @Override
        public String toString() {
            return "DigestChallenge{realm='" + realm + "', nonce='" + nonce + "'}";
        }
    }
}
