package com.cctv.discovery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Application configuration: defaults from {@code application.properties},
 * overridden by the per-user {@code user-settings.properties}.
 * <p>
 * Settings live in the one folder described by {@link AppPaths}, so they are
 * the same whichever copy of the executable is started and wherever it was
 * copied to.
 */
public final class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    private static final String DEFAULT_PROPERTIES = "/application.properties";
    private static final String USER_SETTINGS_FILE = "user-settings.properties";
    /** Files older versions left in the roaming profile. */
    private static final String[] MOVED_FROM_ROAMING = {USER_SETTINGS_FILE, "rtsp-paths.properties"};

    /*
     * The move out of the roaming profile runs on class initialisation rather
     * than in the constructor, because the path cache reads dataDirectory()
     * without ever asking for an AppConfig instance. Touching this class at all
     * is enough to be sure the old files have been brought across first.
     */
    static {
        migrateFromPreviousLocation();
    }

    private static volatile AppConfig instance;

    private final Properties defaultProps = new Properties();
    private final Properties userProps = new Properties();
    private final Path userSettingsFile;

    private AppConfig(Path userSettingsFile) {
        this.userSettingsFile = userSettingsFile;
        loadDefaultProperties();
        loadUserSettings();
    }

    public static AppConfig getInstance() {
        AppConfig local = instance;
        if (local == null) {
            synchronized (AppConfig.class) {
                local = instance;
                if (local == null) {
                    local = new AppConfig(dataDirectory().resolve(USER_SETTINGS_FILE));
                    instance = local;
                }
            }
        }
        return local;
    }

    /** For tests: configuration backed by an explicit settings file. */
    public static AppConfig forTesting(Path settingsFile) {
        AppConfig config = new AppConfig(settingsFile);
        instance = config;
        return config;
    }

    /** Per-user application data directory (created on demand). */
    public static Path dataDirectory() {
        return AppPaths.dataDirectory();
    }

    /** Directory for log files. */
    public static Path logDirectory() {
        return AppPaths.logDirectory();
    }

    public Path getUserSettingsFile() {
        return userSettingsFile;
    }

    private void loadDefaultProperties() {
        try (InputStream is = AppConfig.class.getResourceAsStream(DEFAULT_PROPERTIES)) {
            if (is != null) {
                defaultProps.load(is);
            } else {
                logger.warn("Default properties not found: {}", DEFAULT_PROPERTIES);
            }
        } catch (Exception e) {
            logger.error("Error loading default properties", e);
        }
    }

    /**
     * Bring settings and the path cache across from the roaming folder that
     * versions up to 2.0.0 used.
     *
     * <p>Copies rather than moves, so that an older build run afterwards still
     * finds its own files, and never overwrites: whatever is already in the new
     * location is what the current version has been using.
     *
     * <p>Older versions also looked for a settings file next to the jar. That
     * is deliberately not read any more. It made the settings depend on where
     * the program had been copied to, which is exactly what this folder exists
     * to avoid.
     */
    private static void migrateFromPreviousLocation() {
        migrate(AppPaths.previousDataDirectory(), AppPaths.dataDirectory());
    }

    /** Package-private so the copying rules can be tested against real files. */
    static void migrate(Path previous, Path current) {
        if (previous == null || current.equals(previous) || !Files.isDirectory(previous)) {
            return;
        }
        for (String name : MOVED_FROM_ROAMING) {
            Path from = previous.resolve(name);
            Path to = current.resolve(name);
            try {
                if (Files.isRegularFile(from) && !Files.exists(to)) {
                    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                    logger.info("Brought {} across from {}", name, previous);
                }
            } catch (Exception e) {
                logger.warn("Could not bring {} across from {}: {}", name, previous, e.getMessage());
            }
        }
    }

    private void loadUserSettings() {
        if (Files.isRegularFile(userSettingsFile)) {
            try (InputStream is = Files.newInputStream(userSettingsFile)) {
                userProps.load(is);
                logger.info("Loaded user settings from {}", userSettingsFile);
            } catch (Exception e) {
                logger.error("Error loading user settings", e);
            }
        }
    }

    /** Persist user overrides. Returns false (and logs) when the file cannot be written. */
    public synchronized boolean saveUserSettings() {
        try {
            Files.createDirectories(userSettingsFile.getParent());
            try (OutputStream os = Files.newOutputStream(userSettingsFile)) {
                userProps.store(os, "CCTV Discovery - user settings");
            }
            return true;
        } catch (Exception e) {
            logger.error("Error saving user settings to {}", userSettingsFile, e);
            return false;
        }
    }

    public synchronized String getProperty(String key) {
        String value = userProps.getProperty(key);
        if (value == null) {
            value = defaultProps.getProperty(key);
        }
        if (value != null && value.contains("${user.home}")) {
            value = value.replace("${user.home}", System.getProperty("user.home"));
        }
        return value;
    }

    public String getDefaultProperty(String key) {
        return defaultProps.getProperty(key);
    }

    public int getInt(String key, int defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer for {}: {}", key, value);
            }
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public synchronized void setProperty(String key, String value) {
        userProps.setProperty(key, value);
    }

    public synchronized void resetAllToDefaults() {
        userProps.clear();
        saveUserSettings();
    }

    /** Parse a comma-separated port list, ignoring invalid entries and duplicates. */
    public static int[] parsePorts(String value) {
        if (value == null) {
            return new int[0];
        }
        Set<Integer> ports = new LinkedHashSet<>();
        for (String part : value.split("[,;\\s]+")) {
            try {
                int p = Integer.parseInt(part.trim());
                if (p > 0 && p < 65536) {
                    ports.add(p);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }

    private int[] ports(String key) {
        int[] ports = parsePorts(getProperty(key));
        return ports.length > 0 ? ports : parsePorts(getDefaultProperty(key));
    }

    // Application info
    public String getAppName() {
        return getProperty("app.name");
    }

    public String getAppVersion() {
        return getProperty("app.version");
    }

    public String getAppOrganization() {
        return getProperty("app.organization");
    }

    // Discovery
    public boolean isWsDiscoveryEnabled() {
        return getBoolean("discovery.wsdiscovery.enabled", true);
    }

    public int getOnvifTimeout() {
        return getInt("discovery.onvif.timeout", 3000);
    }

    /** HTTP ports probed for ONVIF device services. */
    public int[] getHttpPorts() {
        return ports("discovery.http.ports");
    }

    /** Ports probed for RTSP servers (confirmed by protocol, not by number). */
    public int[] getRtspPorts() {
        return ports("discovery.rtsp.ports");
    }

    /** Vendor SDK / management ports recorded for identification only. */
    public int[] getOtherPorts() {
        return ports("discovery.other.ports");
    }

    public int getMaxTargets() {
        return getInt("discovery.max.targets", 65_536);
    }

    // Concurrency
    public int getPortScanConcurrency() {
        return Math.max(1, getInt("threads.port.scan.concurrency", 256));
    }

    public int getDeviceParallelism() {
        return Math.max(1, getInt("threads.devices.parallel", 8));
    }

    public int getStreamAnalysisMaxThreads() {
        return Math.max(1, getInt("threads.stream.analysis.max", 4));
    }

    // Timeouts
    public int getSocketConnectTimeout() {
        return getInt("timeout.socket.connect", 1000);
    }

    public int getSocketReadTimeout() {
        return getInt("timeout.socket.read", 3000);
    }

    public int getStreamAnalysisTimeout() {
        return getInt("timeout.stream.analysis", 25_000);
    }

    // Stream analysis
    public int getStreamAnalysisDuration() {
        return getInt("stream.analysis.duration", 8);
    }

    // RTSP
    public int getNvrMaxChannels() {
        return getInt("rtsp.nvr.max.channels", 64);
    }

    public int getNvrConsecutiveFailures() {
        return getInt("rtsp.nvr.consecutive.failures", 3);
    }

    public String[] getCustomRtspPaths() {
        String value = getProperty("rtsp.custom.paths");
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        List<String> paths = new ArrayList<>();
        for (String p : value.split(";")) {
            if (!p.isBlank()) {
                paths.add(p.trim());
            }
        }
        return paths.toArray(String[]::new);
    }

    public String getRtspValidationMethod() {
        return getProperty("rtsp.validation.method");
    }

    public void setRtspValidationMethod(String method) {
        setProperty("rtsp.validation.method", method);
    }

    public int getRtspValidationTimeout() {
        return getInt("rtsp.validation.timeout", 0);
    }

    public void setRtspValidationTimeout(int timeoutMs) {
        setProperty("rtsp.validation.timeout", String.valueOf(timeoutMs));
    }

    // Compliance rules
    public int getSubStreamMinHeight() {
        return getInt("compliance.sub.min.height", 360);
    }

    public int getSubStreamMaxHeight() {
        return getInt("compliance.sub.max.height", 480);
    }

    public int getSubStreamMaxKbps() {
        return getInt("compliance.sub.max.kbps", 512);
    }

    public boolean isHighProfileFlagged() {
        return getBoolean("compliance.flag.high.profile", true);
    }

    public int getMaxTimeDriftSeconds() {
        return getInt("compliance.max.time.drift", 5);
    }

    // Export
    public String getExportDefaultDirectory() {
        return getProperty("export.default.directory");
    }

    public boolean isExportEncryptionDefault() {
        return getBoolean("export.encrypt", true);
    }

    public boolean isExportIncludeCredentialsDefault() {
        return getBoolean("export.include.credentials", true);
    }

    // MAC resolution
    public boolean isMacResolutionEnabled() {
        return getBoolean("mac.resolution.enabled", true);
    }

    // UI
    public int getWindowMinWidth() {
        return getInt("ui.window.min.width", 1100);
    }

    public int getWindowMinHeight() {
        return getInt("ui.window.min.height", 680);
    }

    public int getMaxCredentials() {
        return Math.max(1, getInt("ui.credentials.max", 8));
    }

    @Override
    public String toString() {
        return "AppConfig{settings=" + userSettingsFile + ", http=" + Arrays.toString(getHttpPorts())
                + ", rtsp=" + Arrays.toString(getRtspPorts()) + '}';
    }
}
