package com.cctv.discovery.service;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.util.NetworkUtils;
import com.cctv.discovery.util.TargetParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cameras that announce themselves are kept only when they are inside the
 * network the operator chose.
 *
 * <p>Every announcement used to be kept, identified, and sent the operator's
 * credentials, whatever range had been entered, so a survey of one range
 * reported, and tried passwords against, devices on any network the computer
 * happened to be attached to.
 */
class DiscoveryScopeTest {

    private static Device at(String ip) {
        return new Device(ip);
    }

    @Test
    @DisplayName("Announcements from outside a range are left out, and counted")
    void keepsOnlyTheChosenRange() {
        TargetParser.Targets range = TargetParser.parse("192.168.1.200-192.168.1.206");

        OnvifService.Discovery discovery = OnvifService.keepWithin(List.of(
                at("192.168.1.201"), at("192.168.1.150"), at("192.168.68.40"), at("192.168.1.206")), range);

        assertEquals(List.of("192.168.1.201", "192.168.1.206"),
                discovery.devices().stream().map(Device::getIpAddress).toList());
        assertEquals(List.of("192.168.1.150", "192.168.68.40"), discovery.leftOut());
    }

    @Test
    @DisplayName("A list of addresses keeps exactly those addresses")
    void keepsOnlyListedAddresses() {
        TargetParser.Targets list = TargetParser.parse("192.168.1.201, 192.168.1.204");

        OnvifService.Discovery discovery = OnvifService.keepWithin(List.of(
                at("192.168.1.201"), at("192.168.1.202"), at("192.168.1.204")), list);

        assertEquals(2, discovery.devices().size());
        assertEquals(List.of("192.168.1.202"), discovery.leftOut());
    }

    @Test
    @DisplayName("Nothing chosen means nothing is kept")
    void emptySelectionKeepsNothing() {
        OnvifService.Discovery discovery =
                OnvifService.keepWithin(List.of(at("192.168.1.201")), TargetParser.parse(""));
        assertTrue(discovery.devices().isEmpty());
    }

    // ------------------------------------------------- which adapters probe --

    @Test
    @DisplayName("An adapter probes only when its subnet overlaps the chosen network")
    void adaptersOnTheChosenNetwork() {
        TargetParser.Targets site = TargetParser.parse("192.168.1.200-192.168.1.206");

        // The Wi-Fi on the camera network, and what else this computer had on 2026-10-09.
        assertTrue(NetworkUtils.subnetOverlaps("192.168.1.114", 24, site));
        assertFalse(NetworkUtils.subnetOverlaps("192.168.68.51", 22, site), "the other site, on the cable");
        assertFalse(NetworkUtils.subnetOverlaps("172.29.48.1", 20, site), "the Hyper-V switch");
        assertFalse(NetworkUtils.subnetOverlaps("172.28.96.1", 20, site), "the WSL switch");
    }

    @Test
    @DisplayName("Overlap is judged on the whole subnet, not the adapter's own address")
    void overlapUsesTheWholeSubnet() {
        // A /22 spans 192.168.68.0 to 192.168.71.255.
        assertTrue(NetworkUtils.subnetOverlaps("192.168.68.51", 22, TargetParser.parse("192.168.71.20")));
        assertFalse(NetworkUtils.subnetOverlaps("192.168.68.51", 22, TargetParser.parse("192.168.72.1")));
        assertTrue(NetworkUtils.subnetOverlaps("10.0.0.5", 8, TargetParser.parse("10.200.1.0/24")));
        assertFalse(NetworkUtils.subnetOverlaps("not an address", 24, TargetParser.parse("10.0.0.1")));
        assertFalse(NetworkUtils.subnetOverlaps("10.0.0.5", 33, TargetParser.parse("10.0.0.1")));
    }
}
