package com.cctv.discovery.service;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which RTSP paths worked for each vendor, keyed by the MAC prefix.
 *
 * <p>Path probing is the slowest part of a scan for devices that do not publish
 * their stream addresses over ONVIF. Keeping this between runs means a second
 * survey of the same site tries the path that worked last time first. The cache
 * holds no credentials, only paths such as {@code /cam/realmonitor?channel=1}.
 */
public final class RtspPathCache {

    private static final Logger logger = LoggerFactory.getLogger(RtspPathCache.class);

    private static final String FILE_NAME = "rtsp-paths.properties";
    /** Paths kept per vendor; the most recently successful come first. */
    private static final int MAX_PATHS_PER_VENDOR = 8;
    private static final int MAX_VENDORS = 500;

    private static volatile RtspPathCache shared;

    private final Path file;
    private final Map<String, List<String>> byVendor = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public RtspPathCache(Path file) {
        this.file = file;
        load();
    }

    /** The cache stored in the per-user data directory. */
    public static RtspPathCache shared() {
        RtspPathCache local = shared;
        if (local == null) {
            synchronized (RtspPathCache.class) {
                local = shared;
                if (local == null) {
                    local = new RtspPathCache(AppConfig.dataDirectory().resolve(FILE_NAME));
                    shared = local;
                }
            }
        }
        return local;
    }

    /** Replace the shared instance; for tests. */
    public static void useForTesting(RtspPathCache cache) {
        shared = cache;
    }

    /** The vendor key for a MAC address, or null when there is no usable MAC. */
    static String vendorKey(String macAddress) {
        String mac = NetworkUtils.normalizeMac(macAddress);
        return mac == null ? null : mac.substring(0, 8);
    }

    /** Paths that previously worked for this MAC's vendor, best first. */
    public List<String> pathsFor(String macAddress) {
        String key = vendorKey(macAddress);
        if (key == null) {
            return List.of();
        }
        List<String> paths = byVendor.get(key);
        return paths == null ? List.of() : List.copyOf(paths);
    }

    /** Record a path that returned video, moving it to the front. */
    public void remember(String macAddress, String path) {
        String key = vendorKey(macAddress);
        if (key == null || path == null || path.isBlank()) {
            return;
        }
        byVendor.compute(key, (k, existing) -> {
            List<String> updated = new ArrayList<>();
            updated.add(path);
            if (existing != null) {
                for (String previous : existing) {
                    if (!previous.equals(path) && updated.size() < MAX_PATHS_PER_VENDOR) {
                        updated.add(previous);
                    }
                }
            }
            return updated;
        });
        dirty = true;
    }

    public int vendorCount() {
        return byVendor.size();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                Set<String> paths = new LinkedHashSet<>();
                for (String path : value.split("\\|")) {
                    String trimmed = path.trim();
                    if (!trimmed.isEmpty() && paths.size() < MAX_PATHS_PER_VENDOR) {
                        paths.add(trimmed);
                    }
                }
                if (!paths.isEmpty()) {
                    byVendor.put(key, new ArrayList<>(paths));
                }
            }
            logger.info("Loaded remembered RTSP paths for {} vendor(s)", byVendor.size());
        } catch (Exception e) {
            // A corrupt cache is not worth failing a scan over; start empty.
            logger.warn("Ignoring unreadable RTSP path cache at {}: {}", file, e.getMessage());
            byVendor.clear();
        }
    }

    /** Write the cache if anything changed. Failure is logged, not thrown. */
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            int written = 0;
            for (Map.Entry<String, List<String>> entry : byVendor.entrySet()) {
                if (written++ >= MAX_VENDORS) {
                    break;
                }
                // '|' separates paths, because a path may contain a comma
                // (for example /cam/realmonitor?channel=1&subtype=0).
                props.setProperty(entry.getKey(), String.join("|", entry.getValue()));
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "RTSP paths that returned video, by MAC vendor prefix");
            }
            dirty = false;
            logger.info("Saved remembered RTSP paths for {} vendor(s)", written);
        } catch (Exception e) {
            logger.warn("Could not save the RTSP path cache to {}: {}", file, e.getMessage());
        }
    }

    /** Forget everything, on disk and in memory. */
    public synchronized void clear() {
        byVendor.clear();
        dirty = true;
        save();
    }
}
