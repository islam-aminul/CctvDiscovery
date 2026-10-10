package com.cctv.discovery.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthUtilsTest {

    @Test
    @DisplayName("Digest with qop matches the RFC 7616 worked example")
    void matchesRfc7616Vector() {
        AuthUtils.AuthChallenge challenge = new AuthUtils.AuthChallenge();
        challenge.type = AuthUtils.AuthType.DIGEST;
        challenge.realm = "http-auth@example.org";
        challenge.nonce = "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v";
        challenge.qop = "auth";
        challenge.algorithm = "MD5";

        String header = AuthUtils.digest(challenge, "Mufasa", "Circle of Life", "GET", "/dir/index.html",
                1, "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ");

        assertTrue(header.contains("response=\"8ca523f5e9506fed4657c9700eebdbec\""), header);
        assertTrue(header.contains("nc=00000001"));
        assertTrue(header.contains("qop=auth"));
    }

    @Test
    @DisplayName("Without an offered qop the legacy RFC 2069 response is sent")
    void omitsQopWhenNotOffered() {
        AuthUtils.AuthChallenge challenge = new AuthUtils.AuthChallenge();
        challenge.type = AuthUtils.AuthType.DIGEST;
        challenge.realm = "camera";
        challenge.nonce = "abc123";

        String header = AuthUtils.digest(challenge, "admin", "secret", "DESCRIBE", "rtsp://cam/live", 1, "cnonce");

        assertFalse(header.contains("qop"), "a server that did not offer qop must not receive one");
        assertFalse(header.contains("cnonce"));

        String ha1 = AuthUtils.hash("MD5", "admin:camera:secret");
        String ha2 = AuthUtils.hash("MD5", "DESCRIBE:rtsp://cam/live");
        String expected = AuthUtils.hash("MD5", ha1 + ":abc123:" + ha2);
        assertTrue(header.contains("response=\"" + expected + "\""), header);
    }

    @Test
    @DisplayName("The nonce count increases with every request on one challenge")
    void incrementsNonceCount() {
        AuthUtils.AuthChallenge challenge = new AuthUtils.AuthChallenge();
        challenge.type = AuthUtils.AuthType.DIGEST;
        challenge.realm = "camera";
        challenge.nonce = "n";
        challenge.qop = "auth";

        AuthUtils.Authenticator authenticator = new AuthUtils.Authenticator(challenge, "admin", "pw");
        String first = authenticator.authorization("DESCRIBE", "rtsp://cam/live");
        String second = authenticator.authorization("SETUP", "rtsp://cam/live/track0");

        assertTrue(first.contains("nc=00000001"));
        assertTrue(second.contains("nc=00000002"));
        assertNotEquals(first, second, "the hash covers the method and URI, so it must change");
    }

    @Test
    @DisplayName("Challenge parsing handles quoted commas, spacing and unknown parameters")
    void parsesChallenges() {
        AuthUtils.AuthChallenge digest = AuthUtils.parseAuthChallenge(
                "Digest realm=\"Surveillance, Main\", nonce=\"xyz\", qop=\"auth,auth-int\", algorithm=MD5, stale=FALSE");
        assertEquals(AuthUtils.AuthType.DIGEST, digest.type);
        assertEquals("Surveillance, Main", digest.realm);
        assertEquals("xyz", digest.nonce);
        assertTrue(digest.offersQopAuth());
        assertEquals("FALSE", digest.stale);

        AuthUtils.AuthChallenge basic = AuthUtils.parseAuthChallenge("Basic realm=\"camera\"");
        assertEquals(AuthUtils.AuthType.BASIC, basic.type);
        assertEquals("camera", basic.realm);

        assertNull(AuthUtils.parseAuthChallenge("Negotiate"), "unsupported schemes are ignored");
        assertNull(AuthUtils.parseAuthChallenge(null));
    }

    @Test
    @DisplayName("Digest challenges are preferred over Basic, strongest algorithm first")
    void ordersChallengesByStrength() {
        List<AuthUtils.AuthChallenge> challenges = AuthUtils.parseChallenges(List.of(
                "Basic realm=\"cam\"",
                "Digest realm=\"cam\", nonce=\"a\", algorithm=MD5",
                "Digest realm=\"cam\", nonce=\"b\", algorithm=SHA-256"));

        assertEquals(3, challenges.size());
        assertEquals("SHA-256", challenges.get(0).algorithm);
        assertEquals("MD5", challenges.get(1).algorithm);
        assertEquals(AuthUtils.AuthType.BASIC, challenges.get(2).type);
    }

    @Test
    @DisplayName("A Digest challenge without a nonce is discarded")
    void discardsUnusableChallenge() {
        assertTrue(AuthUtils.parseChallenges(List.of("Digest realm=\"cam\"")).isEmpty());
    }

    @Test
    @DisplayName("Basic encodes user:password as base64")
    void buildsBasic() {
        assertEquals("Basic YWRtaW46c2VjcmV0", AuthUtils.basic("admin", "secret"));
    }

    @Test
    @DisplayName("WS-Security digest follows Base64(SHA-1(nonce + created + password))")
    void buildsPasswordDigest() {
        byte[] nonce = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String created = "2026-09-24T10:00:00Z";
        assertEquals(AuthUtils.passwordDigest(nonce, created, "pw"),
                AuthUtils.passwordDigest(nonce, created, "pw"), "the digest is deterministic");
        assertNotEquals(AuthUtils.passwordDigest(nonce, created, "pw"),
                AuthUtils.passwordDigest(nonce, created, "other"));
    }

    @Test
    @DisplayName("The WS-Security header carries the device's own time, not this host's")
    void appliesClockOffset() {
        String ahead = AuthUtils.wsSecurityHeader("admin", "pw", 3_600_000L);
        String here = AuthUtils.wsSecurityHeader("admin", "pw", 0L);
        assertNotEquals(created(ahead), created(here));
        assertTrue(ahead.contains("<wsse:Username>admin</wsse:Username>"));
    }

    private static String created(String header) {
        int start = header.indexOf("<wsu:Created>") + 13;
        return header.substring(start, header.indexOf("</wsu:Created>"));
    }

    @Test
    @DisplayName("Usernames with XML characters are escaped")
    void escapesUsername() {
        String header = AuthUtils.wsSecurityHeader("ad<min&co", "pw", 0);
        assertTrue(header.contains("ad&lt;min&amp;co"));
    }

    @Test
    @DisplayName("Redaction removes secrets from protocol traces")
    void redactsSecrets() {
        String soap = "<s:Header><wsse:Security><wsse:Password>abc</wsse:Password></wsse:Security></s:Header><Body/>";
        assertFalse(AuthUtils.redact(soap).contains("abc"));

        assertFalse(AuthUtils.redact("Authorization: Digest username=\"a\", response=\"ff\"").contains("response"));
        assertEquals("rtsp://[redacted]@cam/live", AuthUtils.redact("rtsp://admin:pw@cam/live"));
    }
}
