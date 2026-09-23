package com.cctv.discovery.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspClientTest {

    @Test
    @DisplayName("Host and port are read from the URL, defaulting to 554")
    void parsesHostAndPort() {
        assertEquals("192.168.0.113", RtspClient.hostOf("rtsp://192.168.0.113:5543/live/channel0"));
        assertEquals(5543, RtspClient.portOf("rtsp://192.168.0.113:5543/live/channel0"));

        assertEquals("10.0.0.5", RtspClient.hostOf("rtsp://10.0.0.5/live"));
        assertEquals(554, RtspClient.portOf("rtsp://10.0.0.5/live"));
    }

    @Test
    @DisplayName("Credentials in the URL do not confuse host parsing")
    void ignoresUserInfoWhenParsing() {
        String url = "rtsp://admin:p%40ss@192.168.0.113:5543/live";
        assertEquals("192.168.0.113", RtspClient.hostOf(url));
        assertEquals(5543, RtspClient.portOf(url));
    }

    @Test
    @DisplayName("Credentials are stripped for display and logging")
    void stripsCredentials() {
        assertEquals("rtsp://192.168.0.113:5543/live",
                RtspClient.stripCredentials("rtsp://admin:secret@192.168.0.113:5543/live"));
        assertEquals("rtsp://192.168.0.113:5543/live",
                RtspClient.stripCredentials("rtsp://192.168.0.113:5543/live"));
    }

    @Test
    @DisplayName("Reserved characters in credentials are percent-encoded")
    void encodesCredentials() {
        String url = RtspClient.withCredentials("rtsp://cam:554/live", "admin", "p@ss:w/rd#1");
        assertEquals("rtsp://admin:p%40ss%3Aw%2Frd%231@cam:554/live", url);
        assertEquals("cam", RtspClient.hostOf(url));
        assertEquals(554, RtspClient.portOf(url));
    }

    @Test
    @DisplayName("Existing credentials are replaced rather than duplicated")
    void replacesExistingCredentials() {
        String url = RtspClient.withCredentials("rtsp://old:pw@cam/live", "new", "pw2");
        assertEquals("rtsp://new:pw2@cam/live", url);
        assertEquals(1, url.chars().filter(c -> c == '@').count());
    }

    @Test
    @DisplayName("A path is normalised into a full URL")
    void buildsUrls() {
        assertEquals("rtsp://10.0.0.1:554/live", RtspClient.url("10.0.0.1", 554, "live"));
        assertEquals("rtsp://10.0.0.1:554/live", RtspClient.url("10.0.0.1", 554, "/live"));
        assertEquals("rtsp://10.0.0.1:554/", RtspClient.url("10.0.0.1", 554, ""));
        assertEquals("rtsp://10.0.0.1:554/cam/realmonitor?channel=1&subtype=0",
                RtspClient.url("10.0.0.1", 554, "/cam/realmonitor?channel=1&subtype=0"));
    }

    @Test
    @DisplayName("An RTP packet is recognised by its version field")
    void detectsRtpPackets() {
        byte[] rtp = new byte[12];
        rtp[0] = (byte) 0x80; // version 2
        assertTrue(new RtspClient.InterleavedPacket(0, rtp).isRtp());

        byte[] tooShort = new byte[4];
        tooShort[0] = (byte) 0x80;
        assertFalse(new RtspClient.InterleavedPacket(0, tooShort).isRtp());

        byte[] wrongVersion = new byte[12];
        assertFalse(new RtspClient.InterleavedPacket(0, wrongVersion).isRtp());
    }
}
