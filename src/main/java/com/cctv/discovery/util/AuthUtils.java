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
     * Parse WWW-Authenticate header to extract realm and nonce.
     */
    public static DigestChallenge parseDigestChallenge(String wwwAuthenticate) {
        if (wwwAuthenticate == null || !wwwAuthenticate.startsWith("Digest ")) {
            return null;
        }

        DigestChallenge challenge = new DigestChallenge();
        String[] parts = wwwAuthenticate.substring(7).split(",");

        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("realm=")) {
                challenge.realm = extractQuotedValue(part);
            } else if (part.startsWith("nonce=")) {
                challenge.nonce = extractQuotedValue(part);
            } else if (part.startsWith("opaque=")) {
                challenge.opaque = extractQuotedValue(part);
            } else if (part.startsWith("qop=")) {
                challenge.qop = extractQuotedValue(part);
            }
        }

        return challenge;
    }

    private static String extractQuotedValue(String part) {
        int start = part.indexOf('"');
        int end = part.lastIndexOf('"');
        if (start != -1 && end != -1 && end > start) {
            return part.substring(start + 1, end);
        }
        return "";
    }

    /**
     * Digest challenge information.
     */
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
