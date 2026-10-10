package com.cctv.discovery.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetParserTest {

    @Test
    @DisplayName("A /24 excludes the network and broadcast addresses")
    void parsesCidr() {
        TargetParser.Targets targets = TargetParser.parse("192.168.1.0/24");
        assertEquals(254, targets.count());
        assertTrue(targets.contains("192.168.1.1"));
        assertTrue(targets.contains("192.168.1.254"));
        assertFalse(targets.contains("192.168.1.0"));
        assertFalse(targets.contains("192.168.1.255"));
    }

    @Test
    @DisplayName("A /32 scans the single host and a /31 scans both (RFC 3021)")
    void parsesSmallPrefixes() {
        assertEquals(1, TargetParser.parse("10.0.0.7/32").count());
        assertEquals(List.of("10.0.0.7"), TargetParser.parse("10.0.0.7/32").toList(10));
        assertEquals(2, TargetParser.parse("10.0.0.6/31").count());
    }

    @Test
    @DisplayName("Ranges accept a full address or a final octet")
    void parsesRanges() {
        assertEquals(41, TargetParser.parse("192.168.1.10-192.168.1.50").count());
        assertEquals(41, TargetParser.parse("192.168.1.10-50").count());
        assertEquals(1, TargetParser.parse("192.168.1.10-10").count());
        assertTrue(TargetParser.parse("192.168.1.50-192.168.1.10").isEmpty(), "reversed range is rejected");
    }

    @Test
    @DisplayName("Overlapping and adjacent sources are merged so nothing is scanned twice")
    void mergesOverlaps() {
        TargetParser.Targets targets = TargetParser.parse("192.168.1.1-192.168.1.20, 192.168.1.10-192.168.1.30");
        assertEquals(30, targets.count());
        assertEquals(1, targets.intervals().size());

        TargetParser.Targets adjacent = TargetParser.parse("10.0.0.1-10.0.0.5 10.0.0.6-10.0.0.9");
        assertEquals(1, adjacent.intervals().size(), "adjacent ranges join");
        assertEquals(9, adjacent.count());
    }

    @Test
    @DisplayName("Duplicate single addresses are counted once")
    void deduplicates() {
        assertEquals(2, TargetParser.parse("10.0.0.1, 10.0.0.1\n10.0.0.2").count());
    }

    @Test
    @DisplayName("Separators may be commas, semicolons, spaces or newlines")
    void acceptsMixedSeparators() {
        assertEquals(3, TargetParser.parse("10.0.0.1;10.0.0.2\t10.0.0.3").count());
    }

    @Test
    @DisplayName("Unparseable entries are reported rather than silently dropped")
    void reportsInvalidTokens() {
        TargetParser.Targets targets = TargetParser.parse("10.0.0.1, not-an-ip, 999.1.1.1, 10.0.0.2/33");
        assertEquals(1, targets.count());
        assertEquals(List.of("not-an-ip", "999.1.1.1", "10.0.0.2/33"), targets.invalidTokens());
    }

    @Test
    @DisplayName("Large ranges are produced lazily and can be capped")
    void iteratesLazily() {
        TargetParser.Targets targets = TargetParser.parse("10.0.0.0/8");
        assertEquals(16_777_214L, targets.count());
        List<String> first = targets.toList(3);
        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"), first);
    }

    @Test
    @DisplayName("Iteration walks every interval in order")
    void iteratesAcrossIntervals() {
        TargetParser.Targets targets = TargetParser.parse("10.0.0.1-10.0.0.2, 10.0.5.1");
        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.5.1"), targets.toList(10));
    }

    @Test
    @DisplayName("Empty input yields nothing")
    void handlesEmptyInput() {
        assertTrue(TargetParser.parse("").isEmpty());
        assertTrue(TargetParser.parse(null).isEmpty());
        assertEquals(0, TargetParser.parse("   ").count());
    }
}
