package com.cctv.discovery.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SdpParserTest {

    /** Exactly what the CP Plus test camera returns for DESCRIBE. */
    private static final String CAMERA_SDP = """
            v=0
            o=- 1790185644 36319 IN IP4 0.0.0.0
            s=gvminirtsp
            c=IN IP4 0.0.0.0
            t=0 0
            a=range:npt=now-
            a=recvonly
            a=control:*
            m=video 0 RTP/AVP 98
            a=rtpmap:98 H264/90000
            a=fmtp:98 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=J2QAM60AzoB4AiflmoCAgPgAAAMACAAAAwFBiYACSfAANu6//4FA,KO48sA==
            a=control:track0
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000/1
            a=control:track1
            """;

    @Test
    @DisplayName("Reads the session name, tracks and codecs of a real camera")
    void parsesCameraSdp() {
        SdpParser.Sdp sdp = SdpParser.parse(CAMERA_SDP);
        assertNotNull(sdp);
        assertEquals("gvminirtsp", sdp.sessionName());
        assertEquals(2, sdp.media().size());

        SdpParser.Media video = sdp.firstVideo();
        assertNotNull(video);
        assertEquals("H.264", video.codecName());
        assertEquals(90000, video.clockRate());
        assertEquals("track0", video.control());

        SdpParser.Media audio = sdp.firstAudio();
        assertNotNull(audio);
        assertEquals("G.711 A-law", audio.codecName());
    }

    @Test
    @DisplayName("Decodes profile and level from profile-level-id")
    void decodesH264Profile() {
        SdpParser.Sdp sdp = SdpParser.parse(CAMERA_SDP);
        assertEquals("High 5.1", sdp.firstVideo().profile());

        assertEquals("Constrained Baseline 3", SdpParser.h264Profile("42c01e"));
        assertEquals("Main 4", SdpParser.h264Profile("4d0028"));
        assertEquals("High 4.1", SdpParser.h264Profile("640029"));
        assertNull(SdpParser.h264Profile("bogus"));
        assertNull(SdpParser.h264Profile(null));
    }

    @Test
    @DisplayName("Control URLs resolve per RFC 2326: absolute, relative and '*'")
    void resolvesControlUrls() {
        String base = "rtsp://192.168.0.113:5543/live/channel0";
        assertEquals(base, SdpParser.resolveControl(base, "*"));
        assertEquals(base, SdpParser.resolveControl(base, null));
        assertEquals(base + "/track0", SdpParser.resolveControl(base, "track0"));
        assertEquals(base + "/track0", SdpParser.resolveControl(base, "/track0"));
        assertEquals("rtsp://10.0.0.1/other", SdpParser.resolveControl(base, "rtsp://10.0.0.1/other"));
        assertEquals("rtsp://host/live/track1",
                SdpParser.resolveControl("rtsp://host/live/", "track1"), "a trailing slash is not doubled");
    }

    @Test
    @DisplayName("A body that is not SDP is rejected")
    void rejectsNonSdp() {
        assertNull(SdpParser.parse("<html>404</html>"));
        assertNull(SdpParser.parse(""));
        assertNull(SdpParser.parse(null));
    }

    @Test
    @DisplayName("A session name of '-' counts as no name")
    void ignoresPlaceholderSessionName() {
        SdpParser.Sdp sdp = SdpParser.parse("v=0\r\ns=-\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\n");
        assertNotNull(sdp);
        assertNull(sdp.sessionName());
        assertEquals("H.265", sdp.firstVideo().codecName());
    }

    @Test
    @DisplayName("Static payload type 26 is MJPEG without an rtpmap line")
    void handlesStaticPayloadTypes() {
        SdpParser.Sdp sdp = SdpParser.parse("v=0\r\ns=cam\r\nm=video 0 RTP/AVP 26\r\na=control:track1\r\n");
        assertNotNull(sdp);
        assertEquals("MJPEG", sdp.firstVideo().codecName());
    }
}
