package com.cctv.discovery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Brands that ship another maker's firmware answer on that maker's paths.
 *
 * <p>Lookup used to be by exact name. Prama runs Hikvision firmware, but a
 * Prama camera reporting "PRAMA", or carrying a MAC from Prama's E4:28:A4
 * block, was never offered Hikvision's stream paths. The other two Prama blocks
 * only worked because they were mislabelled "Hikvision".
 */
class FirmwareFamilyTest {

    @Test
    @DisplayName("Every way Prama names itself maps to Hikvision firmware")
    void pramaIsHikvisionFirmware() {
        assertEquals("HIKVISION", RtspService.firmwareFamily("Prama"));
        assertEquals("HIKVISION", RtspService.firmwareFamily("PRAMA"));
        assertEquals("HIKVISION", RtspService.firmwareFamily("Prama Hikvision India Private Limited"));
        assertEquals("HIKVISION", RtspService.firmwareFamily("Prama India Private Limited"));
    }

    @Test
    @DisplayName("Pramac, a generator maker in the same registry, is not taken for Prama")
    void pramacIsNotPrama() {
        assertNull(RtspService.firmwareFamily("Pramac"));
        assertNull(RtspService.firmwareFamily("Pramac GmbH"));
    }

    @Test
    @DisplayName("The long names ONVIF reports find their own family")
    void longNamesFindTheirFamily() {
        assertEquals("HIKVISION", RtspService.firmwareFamily("Hangzhou Hikvision Digital Technology Co., Ltd."));
        assertEquals("HIKVISION", RtspService.firmwareFamily("EZVIZ (Hikvision)"));
        assertEquals("DAHUA", RtspService.firmwareFamily("Zhejiang Dahua Technology"));
        assertEquals("DAHUA", RtspService.firmwareFamily("Imou"));
        assertEquals("CP_PLUS", RtspService.firmwareFamily("CP Plus"));
        assertNull(RtspService.firmwareFamily("Acme Doorbells"));
        assertNull(RtspService.firmwareFamily(null));
    }

    @Test
    @DisplayName("A Prama recorder walks its channels the Hikvision way")
    void pramaRecordersUseHikvisionChannels() {
        RtspService.NvrChannelPattern hikvision = RtspService.findNvrPattern("Hikvision");
        assertSame(hikvision, RtspService.findNvrPattern("Prama"));
        assertSame(hikvision, RtspService.findNvrPattern("Prama India"));
        assertEquals("/Streaming/Channels/301", RtspService.findNvrPattern("PRAMA").getMainPath(3));
    }
}
