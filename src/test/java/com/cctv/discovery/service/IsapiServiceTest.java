package com.cctv.discovery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hikvision's deviceInfo reply, in the shape two devices on a live site sent on
 * 2026-10-09. Serials and MACs are made up; the rest is as received.
 */
class IsapiServiceTest {

    private static final String RECORDER = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <DeviceInfo version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <deviceName>Network Video Recorder</deviceName>
            <deviceID>00000000-0000-0000-0000-000000000000</deviceID>
            <model>DS-7W08NI-Q1</model>
            <serialNumber>DS-7W08NI-Q10820200101AAAA00000000WCVU</serialNumber>
            <macAddress>ec:c8:9c:00:00:01</macAddress>
            <firmwareVersion>V3.4.106</firmwareVersion>
            <firmwareReleasedDate>build 191014</firmwareReleasedDate>
            <encoderVersion>V5.0</encoderVersion>
            <encoderReleasedDate>build 191009</encoderReleasedDate>
            <deviceType>IPC</deviceType>
            <telecontrolID>255</telecontrolID>
            </DeviceInfo>
            """;

    private static final String CAMERA = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <DeviceInfo version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <deviceName>IP CAMERA</deviceName>
            <model>DS-2CD1323G0E-I</model>
            <serialNumber>DS-2CD1323G0E-I20200101AAWRF00000000</serialNumber>
            <macAddress>ec:c8:9c:00:00:02</macAddress>
            <firmwareVersion>V5.5.82</firmwareVersion>
            <deviceType>IPCamera</deviceType>
            <telecontrolID>88</telecontrolID>
            </DeviceInfo>
            """;

    @Test
    @DisplayName("Model, firmware and serial are read from the reply")
    void readsTheDetails() {
        IsapiService.DeviceInfo camera = IsapiService.parse(CAMERA).orElseThrow();
        assertEquals("DS-2CD1323G0E-I", camera.model());
        assertEquals("V5.5.82", camera.firmwareVersion());
        assertEquals("DS-2CD1323G0E-I20200101AAWRF00000000", camera.serialNumber());
        assertEquals("ec:c8:9c:00:00:02", camera.macAddress());
    }

    /**
     * The recorder on that site reports deviceType "IPC", a camera. Trusting
     * that field would have kept it typed as a camera; the model family says
     * otherwise.
     */
    @Test
    @DisplayName("A recorder is told by its model, not by the type it claims")
    void recorderByModelNotClaimedType() {
        assertTrue(IsapiService.parse(RECORDER).orElseThrow().isRecorder());
        assertFalse(IsapiService.parse(CAMERA).orElseThrow().isRecorder());
    }

    @Test
    @DisplayName("Recorder families and names are recognised, cameras are not")
    void recorderFamilies() {
        assertTrue(info("DS-7608NI-K2", "NVR").isRecorder());
        assertTrue(info("DS-9616NI-I8", null).isRecorder());
        assertTrue(info("DS-8104HQHI-K8", null).isRecorder());
        assertTrue(info("iDS-7208HUHI-M1/S", null).isRecorder());
        assertTrue(info(null, "Embedded Net DVR").isRecorder());
        assertFalse(info("DS-2CD2043G2-I", "IP CAMERA").isRecorder());
        assertFalse(info("DS-2DE4425IW-DE", "PTZ").isRecorder());
    }

    @Test
    @DisplayName("Anything that is not device information is ignored")
    void ignoresOtherReplies() {
        assertTrue(IsapiService.parse("<html><body>401 Unauthorized</body></html>").isEmpty());
        assertTrue(IsapiService.parse("").isEmpty());
        assertTrue(IsapiService.parse(null).isEmpty());
        assertTrue(IsapiService.parse("<DeviceInfo><deviceName>x</deviceName></DeviceInfo>").isEmpty(),
                "a reply with neither model nor serial says nothing worth recording");
    }

    private static IsapiService.DeviceInfo info(String model, String name) {
        return new IsapiService.DeviceInfo(name, model, null, null, null);
    }
}
