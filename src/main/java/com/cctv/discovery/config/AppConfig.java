package com.cctv.discovery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

/**
 * Application configuration manager.
 * Loads default properties from application.properties and user overrides from user-settings.properties.
 */
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private static final AppConfig INSTANCE = new AppConfig();

    private static final String DEFAULT_PROPERTIES = "/application.properties";
    private static final String USER_SETTINGS_FILE = "user-settings.properties";

    private final Properties defaultProps;
    private final Properties userProps;

    private AppConfig() {
        this.defaultProps = new Properties();
        this.userProps = new Properties();
        loadDefaultProperties();
        loadUserSettings();
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    private void loadDefaultProperties() {
        try (InputStream is = getClass().getResourceAsStream(DEFAULT_PROPERTIES)) {
            if (is != null) {
                defaultProps.load(is);
                logger.info("Loaded default application properties");
            } else {
                logger.warn("Default properties file not found: {}", DEFAULT_PROPERTIES);
            }
        } catch (Exception e) {
            logger.error("Error loading default properties", e);
        }
    }

    private void loadUserSettings() {
        File userFile = new File(USER_SETTINGS_FILE);
        if (userFile.exists()) {
            try (FileInputStream fis = new FileInputStream(userFile)) {
                userProps.load(fis);
                logger.info("Loaded user settings from: {}", userFile.getAbsolutePath());
            } catch (Exception e) {
                logger.error("Error loading user settings", e);
            }
        }
    }

    /**
     * Save user settings to file.
     */
    public void saveUserSettings() {
        File userFile = new File(USER_SETTINGS_FILE);
        try (FileOutputStream fos = new FileOutputStream(userFile)) {
            userProps.store(fos, "CCTV Discovery - User Settings (Auto-generated)");
            logger.info("Saved user settings to: {}", userFile.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Error saving user settings", e);
        }
    }

    /**
     * Get property value. User settings override defaults.
     */
    public String getProperty(String key) {
        String value = userProps.getProperty(key);
        if (value == null) {
            value = defaultProps.getProperty(key);
        }
        // Handle ${user.home} placeholder
        if (value != null && value.contains("${user.home}")) {
            value = value.replace("${user.home}", System.getProperty("user.home"));
        }
        return value;
    }

    /**
     * Get property as integer.
     */
    public int getInt(String key, int defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer value for {}: {}", key, value);
            }
        }
        return defaultValue;
    }

    /**
     * Get property as boolean.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    /**
     * Set user property override.
     */
    public void setProperty(String key, String value) {
        userProps.setProperty(key, value);
    }

    /**
     * Reset property to default (remove user override).
     */
    public void resetProperty(String key) {
        userProps.remove(key);
    }

    /**
     * Reset all settings to defaults.
     */
    public void resetAllToDefaults() {
        userProps.clear();
        saveUserSettings();
        logger.info("All settings reset to defaults");
    }

    // Application Info
    public String getAppName() {
        return getProperty("app.name");
    }

    public String getAppVersion() {
        return getProperty("app.version");
    }

    // Network Discovery
    public int getOnvifPort() {
        return getInt("discovery.onvif.port", 3702);
    }

    public String getOnvifMulticast() {
        return getProperty("discovery.onvif.multicast");
    }

    public int getOnvifTimeout() {
        return getInt("discovery.onvif.timeout", 5000);
    }

    public int[] getHttpPorts() {
        String value = getProperty("discovery.http.ports");
        if (value != null) {
            String[] parts = value.split(",");
            int[] ports = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                ports[i] = Integer.parseInt(parts[i].trim());
            }
            return ports;
        }
        return new int[]{80, 8080};
    }

    public int[] getRtspPorts() {
        String value = getProperty("discovery.rtsp.ports");
        if (value != null) {
            String[] parts = value.split(",");
            int[] ports = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                ports[i] = Integer.parseInt(parts[i].trim());
            }
            return ports;
        }
        return new int[]{554, 8554};
    }

    // Threading
    public int getPortScanThreadMultiplier() {
        return getInt("threads.port.scan.multiplier", 8);
    }

    public int getPortScanMaxThreads() {
        return getInt("threads.port.scan.max", 64);
    }

    public int getStreamAnalysisMaxThreads() {
        return getInt("threads.stream.analysis.max", 8);
    }

    // Timeouts
    public int getSocketConnectTimeout() {
        return getInt("timeout.socket.connect", 2000);
    }

    public int getSocketReadTimeout() {
        return getInt("timeout.socket.read", 5000);
    }

    public int getRtspConnectTimeout() {
        return getInt("timeout.rtsp.connect", 5000);
    }

    public int getStreamAnalysisTimeout() {
        return getInt("timeout.stream.analysis", 10000);
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
        if (value != null && !value.trim().isEmpty()) {
            String[] paths = value.split(";");
            // Trim each path
            for (int i = 0; i < paths.length; i++) {
                paths[i] = paths[i].trim();
            }
            return paths;
        }
        return new String[0];
    }

    // Stream Analysis
    public int getStreamAnalysisDuration() {
        return getInt("stream.analysis.duration", 10);
    }

    public int getStreamAnalysisFrameSamples() {
        return getInt("stream.analysis.frame.samples", 30);
    }

    // Export
    public String getExportDefaultDirectory() {
        return getProperty("export.default.directory");
    }

    public boolean isExcelPasswordEnabled() {
        return getBoolean("export.excel.password.enabled", true);
    }

    public String getExcelPasswordFixedCode() {
        return getProperty("export.excel.password.fixed.code");
    }

    // MAC Resolution
    public boolean isMacResolutionEnabled() {
        return getBoolean("mac.resolution.enabled", true);
    }

    public int getMacResolutionTimeout() {
        return getInt("mac.resolution.timeout", 2000);
    }

    // UI
    public int getWindowMinWidth() {
        return getInt("ui.window.min.width", 1200);
    }

    public int getWindowMinHeight() {
        return getInt("ui.window.min.height", 700);
    }

    public int getMaxCredentials() {
        return getInt("ui.credentials.max", 4);
    }
}
