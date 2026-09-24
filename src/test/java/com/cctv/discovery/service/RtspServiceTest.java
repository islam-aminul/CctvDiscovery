package com.cctv.discovery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RtspServiceTest {

    @Test
    @DisplayName("Sub-stream paths are derived from the main path for each vendor style")
    void derivesSubstreamPaths() {
        assertEquals("/cam/realmonitor?channel=1&subtype=1",
                RtspService.substreamPath("/cam/realmonitor?channel=1&subtype=0"));
        assertEquals("/Streaming/Channels/102", RtspService.substreamPath("/Streaming/Channels/101"));
        assertEquals("/h264/ch1/sub/av_stream", RtspService.substreamPath("/h264/ch1/main/av_stream"));
        assertEquals("/live/channel1", RtspService.substreamPath("/live/channel0"));
        assertEquals("/live1s2.sdp", RtspService.substreamPath("/live1s1.sdp"));
        assertEquals("/live/ch00_1", RtspService.substreamPath("/live/ch00_0"));
        assertNull(RtspService.substreamPath("/videoMain"), "an unrecognised path has no derived pair");
        assertNull(RtspService.substreamPath(null));
    }

    @Test
    @DisplayName("Recorder path patterns expand each vendor's channel placeholders")
    void expandsChannelPatterns() {
        RtspService.NvrChannelPattern hikvision = new RtspService.NvrChannelPattern(
                "/Streaming/Channels/{channel*100+1}", "/Streaming/Channels/{channel*100+2}");
        assertEquals("/Streaming/Channels/101", hikvision.getMainPath(1));
        assertEquals("/Streaming/Channels/302", hikvision.getSubPath(3));
        assertEquals("/Streaming/Channels/1601", hikvision.getMainPath(16));

        RtspService.NvrChannelPattern dahua = new RtspService.NvrChannelPattern(
                "/cam/realmonitor?channel={channel}&subtype=0", "/cam/realmonitor?channel={channel}&subtype=1");
        assertEquals("/cam/realmonitor?channel=7&subtype=0", dahua.getMainPath(7));
        assertEquals("/cam/realmonitor?channel=7&subtype=1", dahua.getSubPath(7));

        RtspService.NvrChannelPattern generic = new RtspService.NvrChannelPattern("/ch{channel01}/0", "/ch{channel01}/1");
        assertEquals("/ch05/0", generic.getMainPath(5));
        assertEquals("/ch12/1", generic.getSubPath(12));

        RtspService.NvrChannelPattern uniview = new RtspService.NvrChannelPattern(
                "/media/video{channel}", "/media/video{channel+100}");
        assertEquals("/media/video2", uniview.getMainPath(2));
        assertEquals("/media/video102", uniview.getSubPath(2));
    }

    @Test
    @DisplayName("Deriving a sub path from a sub path gives nonsense, so only main paths are remembered")
    void substreamDerivationIsNotReversible() {
        // Caching a sub path meant it was offered first on the next run and
        // treated as the main stream; the pair derived from it did not exist,
        // so a repeat scan found one stream where the first had found two.
        String main = "/live/channel0";
        String sub = RtspService.substreamPath(main);
        assertEquals("/live/channel1", sub);

        String derivedFromSub = RtspService.substreamPath(sub);
        assertNotEquals(main, derivedFromSub, "deriving from a sub path must not round-trip to the main");
        assertEquals("/live/channel2", derivedFromSub, "it yields an address that does not exist");
    }

    @Test
    @DisplayName("Recorder patterns are matched by brand, with a generic fallback")
    void findsPatternsByBrand() {
        assertEquals("/Streaming/Channels/101", RtspService.findNvrPattern("Hikvision").getMainPath(1));
        assertEquals("/cam/realmonitor?channel=1&subtype=0", RtspService.findNvrPattern("CP Plus").getMainPath(1));
        assertEquals("/cam/realmonitor?channel=1&subtype=0", RtspService.findNvrPattern("Dahua").getMainPath(1));
        assertEquals("/ch01/0", RtspService.findNvrPattern("Some Unknown Brand").getMainPath(1));
        assertEquals("/ch01/0", RtspService.findNvrPattern(null).getMainPath(1));
        assertEquals("/ch01/0", RtspService.findNvrPattern("").getMainPath(1));
    }

    @Test
    @DisplayName("Validation methods carry sensible default timeouts")
    void hasDefaultTimeouts() {
        RtspService.RtspDiscoveryConfig config = new RtspService.RtspDiscoveryConfig();
        assertEquals(RtspService.RtspValidationMethod.FRAME_CAPTURE, config.getValidationMethod());
        assertEquals(RtspService.RtspValidationMethod.FRAME_CAPTURE.getDefaultTimeout(), config.getTimeout());

        config.setCustomTimeout(4321);
        assertEquals(4321, config.getTimeout(), "an explicit timeout overrides the default");

        config.setCustomTimeout(0);
        config.setValidationMethod(RtspService.RtspValidationMethod.SDP_ONLY);
        assertEquals(RtspService.RtspValidationMethod.SDP_ONLY.getDefaultTimeout(), config.getTimeout());

        config.setValidationMethod(null);
        assertEquals(RtspService.RtspValidationMethod.FRAME_CAPTURE, config.getValidationMethod());
    }
}
