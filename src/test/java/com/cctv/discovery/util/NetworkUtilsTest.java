package com.cctv.discovery.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUtilsTest {

    @Test
    void subnetRangeCoversTheWholeNetwork() {
        assertArrayEquals(new long[]{NetworkUtils.ipToLong("192.168.1.0"), NetworkUtils.ipToLong("192.168.1.255")},
                NetworkUtils.subnetRange("192.168.1.114", 24));
        assertArrayEquals(new long[]{NetworkUtils.ipToLong("10.0.0.5"), NetworkUtils.ipToLong("10.0.0.5")},
                NetworkUtils.subnetRange("10.0.0.5", 32));
        assertArrayEquals(new long[]{0L, 0xFFFFFFFFL}, NetworkUtils.subnetRange("10.0.0.5", 0));
    }

    @Test
    void subnetRangeRefusesNonsense() {
        assertNull(NetworkUtils.subnetRange("not an address", 24));
        assertNull(NetworkUtils.subnetRange("192.168.1.1", 33));
    }

    @Test
    void onSubnetChecksEverySubnet() {
        List<long[]> subnets = List.of(NetworkUtils.subnetRange("192.168.1.114", 24),
                NetworkUtils.subnetRange("172.16.4.1", 30));
        assertTrue(NetworkUtils.onSubnet("192.168.1.200", subnets));
        assertTrue(NetworkUtils.onSubnet("172.16.4.3", subnets));
        assertFalse(NetworkUtils.onSubnet("172.16.4.4", subnets));
        assertFalse(NetworkUtils.onSubnet("192.168.2.1", subnets));
        assertFalse(NetworkUtils.onSubnet("garbage", subnets));
        assertFalse(NetworkUtils.onSubnet("192.168.1.200", List.of()));
    }

    @Test
    void virtualAdaptersAreRecognised() {
        assertTrue(NetworkUtils.isVirtualAdapter("eth5", "Hyper-V Virtual Ethernet Adapter"));
        assertTrue(NetworkUtils.isVirtualAdapter("eth6", "vEthernet (WSL (Hyper-V firewall))"));
        assertTrue(NetworkUtils.isVirtualAdapter("eth2", "VMware Virtual Ethernet Adapter for VMnet8"));
        assertTrue(NetworkUtils.isVirtualAdapter("eth3", "VirtualBox Host-Only Ethernet Adapter"));
        assertTrue(NetworkUtils.isVirtualAdapter("net9", "TAP-Windows Adapter V9"));
        assertTrue(NetworkUtils.isVirtualAdapter("wsl", "WSL"));
        assertTrue(NetworkUtils.isVirtualAdapter("tap0", "tap device"));
        assertFalse(NetworkUtils.isVirtualAdapter("eth7", "Laptop desktop Wi-Fi"));
        assertFalse(NetworkUtils.isVirtualAdapter("wlan1", "Qualcomm FastConnect 6900 Wi-Fi 6E Dual Band Simultaneous (DBS) WiFiCx Network Adapter"));
        assertFalse(NetworkUtils.isVirtualAdapter("eth0", "Realtek PCIe GbE Family Controller"));
        assertFalse(NetworkUtils.isVirtualAdapter("eth1", "Intel(R) Ethernet Connection (7) I219-V"));
    }
}
