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
}
