package com.cctv.discovery.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspPathCacheTest {

    private static final String HIKVISION_MAC = "44:47:CC:11:22:33";
    private static final String DAHUA_MAC = "08:ED:ED:AA:BB:CC";

    @TempDir
    Path dir;

    private RtspPathCache cache() {
        return new RtspPathCache(dir.resolve("rtsp-paths.properties"));
    }

    @Test
    @DisplayName("A path learned in one run is offered in the next")
    void survivesRestart() {
        RtspPathCache first = cache();
        first.remember(HIKVISION_MAC, "/Streaming/Channels/101");
        first.save();

        RtspPathCache second = cache();
        assertEquals(List.of("/Streaming/Channels/101"), second.pathsFor(HIKVISION_MAC));
    }

    @Test
    @DisplayName("Another device from the same vendor benefits from the same path")
    void appliesAcrossTheVendor() {
        RtspPathCache cache = cache();
        cache.remember(HIKVISION_MAC, "/Streaming/Channels/101");

        // Same OUI, different device.
        assertEquals(List.of("/Streaming/Channels/101"), cache.pathsFor("44:47:CC:99:88:77"));
        // A different vendor learns nothing from it.
        assertTrue(cache.pathsFor(DAHUA_MAC).isEmpty());
    }

    @Test
    @DisplayName("The most recent success is tried first")
    void ordersMostRecentFirst() {
        RtspPathCache cache = cache();
        cache.remember(DAHUA_MAC, "/cam/realmonitor?channel=1&subtype=0");
        cache.remember(DAHUA_MAC, "/live/ch00_0");

        assertEquals("/live/ch00_0", cache.pathsFor(DAHUA_MAC).getFirst());
        assertEquals(2, cache.pathsFor(DAHUA_MAC).size());
    }

    @Test
    @DisplayName("Repeating a path moves it up rather than duplicating it")
    void doesNotDuplicate() {
        RtspPathCache cache = cache();
        cache.remember(DAHUA_MAC, "/a");
        cache.remember(DAHUA_MAC, "/b");
        cache.remember(DAHUA_MAC, "/a");

        assertEquals(List.of("/a", "/b"), cache.pathsFor(DAHUA_MAC));
    }

    @Test
    @DisplayName("Only a bounded number of paths is kept per vendor")
    void boundsGrowth() {
        RtspPathCache cache = cache();
        for (int i = 0; i < 40; i++) {
            cache.remember(DAHUA_MAC, "/path" + i);
        }
        assertTrue(cache.pathsFor(DAHUA_MAC).size() <= 8,
                "kept " + cache.pathsFor(DAHUA_MAC).size());
        assertEquals("/path39", cache.pathsFor(DAHUA_MAC).getFirst());
    }

    @Test
    @DisplayName("A path containing a comma survives a save and load")
    void handlesCommasAndQuery() {
        String path = "/cam/realmonitor?channel=1&subtype=0,extra";
        RtspPathCache first = cache();
        first.remember(DAHUA_MAC, path);
        first.save();

        assertEquals(List.of(path), cache().pathsFor(DAHUA_MAC));
    }

    @Test
    @DisplayName("Devices with no usable MAC are ignored rather than sharing a bucket")
    void ignoresUnusableMacs() {
        RtspPathCache cache = cache();
        cache.remember(null, "/a");
        cache.remember("not-a-mac", "/b");
        cache.remember(HIKVISION_MAC, null);

        assertEquals(0, cache.vendorCount());
        assertTrue(cache.pathsFor(null).isEmpty());
        assertTrue(cache.pathsFor("not-a-mac").isEmpty());
    }

    @Test
    @DisplayName("A corrupt cache file is discarded instead of failing the scan")
    void toleratesCorruptFile() throws Exception {
        Path file = dir.resolve("rtsp-paths.properties");
        Files.write(file, "\u0000\u0000 not a properties file \\u123".getBytes(StandardCharsets.UTF_8));

        RtspPathCache cache = new RtspPathCache(file);
        assertTrue(cache.pathsFor(HIKVISION_MAC).isEmpty());

        // Still usable afterwards.
        cache.remember(HIKVISION_MAC, "/recovered");
        cache.save();
        assertEquals(List.of("/recovered"), new RtspPathCache(file).pathsFor(HIKVISION_MAC));
    }

    @Test
    @DisplayName("Nothing is written when nothing was learned")
    void skipsWriteWhenUnchanged() {
        Path file = dir.resolve("rtsp-paths.properties");
        new RtspPathCache(file).save();
        assertFalse(Files.exists(file), "an empty cache should not create a file");
    }

    @Test
    @DisplayName("Clearing forgets everything on disk too")
    void clears() {
        RtspPathCache cache = cache();
        cache.remember(HIKVISION_MAC, "/Streaming/Channels/101");
        cache.save();
        cache.clear();

        assertEquals(0, cache.vendorCount());
        assertTrue(cache().pathsFor(HIKVISION_MAC).isEmpty());
    }
}
