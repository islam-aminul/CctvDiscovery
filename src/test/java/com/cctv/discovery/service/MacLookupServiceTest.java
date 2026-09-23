package com.cctv.discovery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacLookupServiceTest {

    private final MacLookupService service = MacLookupService.getInstance();

    @Test
    @DisplayName("The bundled IEEE registry covers all three assignment sizes")
    void loadsFullRegistry() {
        assertTrue(service.size() > 50_000, "expected the full MA-L/MA-M/MA-S set, got " + service.size());
    }

    @Test
    @DisplayName("The test camera's OUI resolves to its brand")
    void resolvesTestCamera() {
        // 28:18:FD belongs to Aditya Infotech, who sell under the CP Plus brand.
        assertEquals("CP Plus", service.lookupManufacturer("28:18:FD:F1:E5:BE"));
        assertEquals("Aditya Infotech Ltd.", service.lookupOrganization("28:18:FD:F1:E5:BE"));
    }

    @Test
    @DisplayName("Separator style and case do not matter")
    void acceptsAnyMacFormat() {
        String expected = service.lookupManufacturer("28:18:FD:F1:E5:BE");
        assertEquals(expected, service.lookupManufacturer("28-18-fd-f1-e5-be"));
        assertEquals(expected, service.lookupManufacturer("2818fdf1e5be"));
    }

    @Test
    @DisplayName("Common CCTV vendors map to the names installers use")
    void mapsKnownVendors() {
        assertEquals("Hikvision", service.brandFor("Hangzhou Hikvision Digital Technology Co.,Ltd."));
        assertEquals("Dahua", service.brandFor("Zhejiang Dahua Technology Co., Ltd."));
        assertEquals("CP Plus", service.brandFor("CPPLUS"));
        assertEquals("Hanwha Vision", service.brandFor("Hanwha Techwin Security Vietnam"));
        assertEquals("Axis", service.brandFor("Axis Communications AB"));
    }

    @Test
    @DisplayName("An unmapped vendor keeps its name without the legal suffix")
    void cleansUnmappedNames() {
        assertEquals("Acme Cameras", service.brandFor("Acme Cameras Co., Ltd."));
        assertEquals("Widget Networks", service.brandFor("Widget Networks, Inc."));
    }

    @Test
    @DisplayName("Unregistered and malformed addresses report Unknown")
    void handlesUnknownMacs() {
        // 02:… is locally administered, so it is in no registry.
        assertEquals(MacLookupService.UNKNOWN, service.lookupManufacturer("02:00:00:00:00:01"));
        assertEquals(MacLookupService.UNKNOWN, service.lookupManufacturer("not-a-mac"));
        assertEquals(MacLookupService.UNKNOWN, service.lookupManufacturer(null));
        assertNull(service.lookupOrganization("02:00:00:00:00:01"));
    }

    @Test
    @DisplayName("A longer MA-S assignment wins over the MA-L block containing it")
    void prefersLongestPrefix() {
        // 8C:1F:64 is an MA-L block that IEEE subdivides into MA-S assignments.
        String specific = service.lookupOrganization("8C:1F:64:AF:A0:01");
        assertNotNull(specific);
        String other = service.lookupOrganization("8C:1F:64:00:00:01");
        assertNotNull(other);
    }
}
