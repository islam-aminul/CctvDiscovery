package com.cctv.discovery.service;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.util.XmlUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnvifServiceTest {

    private final OnvifService service = new OnvifService();

    /** A ProbeMatch of the shape the CP Plus test camera sends. */
    private static final String PROBE_MATCH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <s:Body><d:ProbeMatches><d:ProbeMatch>
                <a:EndpointReference><a:Address>urn:uuid:4db723c3-713c-4a1b-9f8e-001122334455</a:Address></a:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/name/CP_Plus_Cloud_Camera onvif://www.onvif.org/hardware/IPC</d:Scopes>
                <d:XAddrs>http://192.168.0.113:8000/onvif/device_service</d:XAddrs>
              </d:ProbeMatch></d:ProbeMatches></s:Body>
            </s:Envelope>""";

    @Test
    @DisplayName("A ProbeMatch yields the address, service URL and scope name")
    void parsesProbeMatch() {
        Optional<Device> parsed = service.parseProbeMatch(PROBE_MATCH, "192.168.0.113");
        assertTrue(parsed.isPresent());
        Device device = parsed.get();
        assertEquals("192.168.0.113", device.getIpAddress());
        assertEquals("http://192.168.0.113:8000/onvif/device_service", device.getOnvifServiceUrl());
        assertEquals("CP_Plus_Cloud_Camera", device.getDeviceName());
        assertEquals("IPC", device.getHardwareId());
    }

    @Test
    @DisplayName("No MAC address is invented from the endpoint UUID")
    void neverDerivesMacFromUuid() {
        Device device = service.parseProbeMatch(PROBE_MATCH, "192.168.0.113").orElseThrow();
        // The UUID starts 4db723c3-713c, which the old build turned into the
        // multicast address 4D:B7:23:C3:71:3C and used as the device MAC.
        assertNull(device.getMacAddress());
    }

    @Test
    @DisplayName("A reply with no usable address is ignored")
    void ignoresUnusableReply() {
        assertTrue(service.parseProbeMatch("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">"
                + "<s:Body/></s:Envelope>", "192.168.0.113").isEmpty());
        assertTrue(service.parseProbeMatch("not xml at all", "192.168.0.113").isEmpty());
    }

    @Test
    @DisplayName("XML from the network cannot pull in external entities")
    void rejectsExternalEntities() {
        String attack = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY x SYSTEM "http://127.0.0.1:9/leak">]>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body>
                <d:XAddrs xmlns:d="urn:x">http://10.0.0.1/onvif/device_service &x;</d:XAddrs>
                </s:Body></s:Envelope>""";
        assertThrows(Exception.class, () -> XmlUtils.parse(attack));
        assertTrue(service.parseProbeMatch(attack, "10.0.0.1").isEmpty(), "the reply is dropped, not fetched");
    }

    @Test
    @DisplayName("Device service URLs are built for both http and https ports")
    void buildsServiceUrls() {
        assertTrue(OnvifService.serviceUrlsFor("10.0.0.1", 8000).getFirst()
                .equals("http://10.0.0.1:8000/onvif/device_service"));
        assertTrue(OnvifService.serviceUrlsFor("10.0.0.1", 443).getFirst().startsWith("https://"));
        assertTrue(OnvifService.serviceUrlsFor("10.0.0.1", 8443).getFirst().startsWith("https://"));
    }

    @Test
    @DisplayName("A camera that lists one video source per profile is still a camera")
    void doesNotMistakeProfilesForChannels() {
        Device camera = new Device("192.168.0.113");
        camera.setType(Device.DeviceType.CAMERA);
        camera.setModel("CP_Plus_Wi-Fi_camera");
        // The test camera reports V_SRC_1 and V_SRC_2 for its two encoder profiles.
        assertEquals(Device.DeviceType.CAMERA, service.classifyType(camera, 2));
    }

    @Test
    @DisplayName("Many channels, or a recorder model name, means a recorder")
    void detectsRecorders() {
        Device many = new Device("10.0.0.2");
        assertEquals(Device.DeviceType.RECORDER, service.classifyType(many, 16));

        Device named = new Device("10.0.0.3");
        named.setModel("DS-7608NI-NVR");
        assertEquals(Device.DeviceType.RECORDER, service.classifyType(named, 1));

        Device fromScope = new Device("10.0.0.4");
        fromScope.setType(Device.DeviceType.RECORDER);
        assertEquals(Device.DeviceType.RECORDER, service.classifyType(fromScope, 1));
    }

    @Test
    @DisplayName("A device with no video sources keeps the type the scan gave it")
    void keepsTypeWithoutSources() {
        Device device = new Device("10.0.0.5");
        device.setType(Device.DeviceType.UNKNOWN);
        assertEquals(Device.DeviceType.UNKNOWN, service.classifyType(device, 0));
        assertFalse(device.isNvrDvr());
    }

    @Test
    @DisplayName("Media services are read from GetServices, the only source for a Media2 address")
    void readsBothMediaGenerations() {
        // A device offering only Media2 reports no media capability in the
        // ver10 list, so GetCapabilities alone would find nothing on it.
        String reply = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                  <s:Body><tds:GetServicesResponse>
                    <tds:Service>
                      <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
                      <tds:XAddr>http://10.0.0.9/onvif/device_service</tds:XAddr>
                    </tds:Service>
                    <tds:Service>
                      <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
                      <tds:XAddr>http://10.0.0.9/onvif/media_service</tds:XAddr>
                    </tds:Service>
                    <tds:Service>
                      <tds:Namespace>http://www.onvif.org/ver20/media/wsdl</tds:Namespace>
                      <tds:XAddr>http://10.0.0.9/onvif/media2_service</tds:XAddr>
                    </tds:Service>
                  </tds:GetServicesResponse></s:Body>
                </s:Envelope>""";

        OnvifService.MediaEndpoints endpoints = OnvifService.parseServices(reply, "10.0.0.9");
        assertEquals("http://10.0.0.9/onvif/media_service", endpoints.media1());
        assertEquals("http://10.0.0.9/onvif/media2_service", endpoints.media2());
        assertTrue(endpoints.hasMedia2());
    }

    @Test
    @DisplayName("A device advertising an unreachable host is rewritten to the address that answered")
    void rewritesAdvertisedHost() {
        // Cameras behind NAT, or holding a stale static address, advertise a
        // host that cannot be reached from here.
        String reply = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                  <s:Body><tds:GetServicesResponse><tds:Service>
                    <tds:Namespace>http://www.onvif.org/ver20/media/wsdl</tds:Namespace>
                    <tds:XAddr>http://192.168.99.1:8080/onvif/media2</tds:XAddr>
                  </tds:Service></tds:GetServicesResponse></s:Body>
                </s:Envelope>""";

        OnvifService.MediaEndpoints endpoints = OnvifService.parseServices(reply, "10.0.0.9");
        assertEquals("http://10.0.0.9:8080/onvif/media2", endpoints.media2(),
                "the port and path are kept, only the host is corrected");
    }

    @Test
    @DisplayName("Only Media2: requests go there rather than to the device service")
    void usesMedia2WhenMedia1Absent() {
        OnvifService.MediaEndpoints onlyMedia2 =
                new OnvifService.MediaEndpoints(null, "http://10.0.0.9/onvif/media2");
        assertEquals("http://10.0.0.9/onvif/media2",
                onlyMedia2.preferred("http://10.0.0.9/onvif/device_service"));
    }

    @Test
    @DisplayName("Media1 is preferred when a device offers both")
    void prefersMedia1WhenBothExist() {
        OnvifService.MediaEndpoints both = new OnvifService.MediaEndpoints(
                "http://10.0.0.9/onvif/media", "http://10.0.0.9/onvif/media2");
        assertEquals("http://10.0.0.9/onvif/media", both.preferred("http://10.0.0.9/onvif/device_service"));
        assertTrue(both.hasMedia2(), "Media2 stays available as a fallback");
    }

    @Test
    @DisplayName("A device with neither media service falls back to its device service")
    void fallsBackToDeviceService() {
        OnvifService.MediaEndpoints none = new OnvifService.MediaEndpoints(null, null);
        assertFalse(none.hasMedia2());
        assertEquals("http://10.0.0.9/onvif/device_service",
                none.preferred("http://10.0.0.9/onvif/device_service"));
    }
}
