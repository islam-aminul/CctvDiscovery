package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Locale;

/**
 * Light or dark appearance.
 *
 * <p>The dark stylesheet only overrides colours, so it is layered on top of the
 * base one rather than replacing it, keeping layout in a single file.
 */
public enum Theme {

    LIGHT("Light"),
    DARK("Dark"),
    /** Follow whatever the operating system is set to. */
    SYSTEM("Match the system");

    private static final Logger logger = LoggerFactory.getLogger(Theme.class);

    private static final String BASE_CSS = "/css/app.css";
    private static final String DARK_CSS = "/css/dark.css";
    private static final String SETTING = "ui.theme";

    private final String label;

    Theme(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** The theme saved in settings, defaulting to following the system. */
    public static Theme current() {
        String value = AppConfig.getInstance().getProperty(SETTING);
        if (value == null) {
            return SYSTEM;
        }
        try {
            return Theme.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SYSTEM;
        }
    }

    public static void save(Theme theme) {
        AppConfig config = AppConfig.getInstance();
        config.setProperty(SETTING, theme.name());
        config.saveUserSettings();
    }

    /** Whether this theme paints dark, resolving {@link #SYSTEM} now. */
    public boolean isDark() {
        return this == DARK || (this == SYSTEM && systemPrefersDark());
    }

    /**
     * Whether the operating system is set to a dark appearance.
     *
     * <p>Windows keeps this in the registry. Anything unreadable, or any other
     * platform, is treated as light, which is the safer default: light styling
     * on a dark desktop is merely unfashionable, whereas the reverse can be
     * unreadable.
     */
    static boolean systemPrefersDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.Charset.defaultCharset());
            }
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            // AppsUseLightTheme is 0x0 when the user has chosen dark.
            int index = output.indexOf("0x");
            return index >= 0 && output.charAt(index + 2) == '0';
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("Cannot read the system theme: {}", e.getMessage());
            return false;
        }
    }

    /** Apply this theme's stylesheets to a scene, replacing any already set. */
    public void applyTo(Scene scene) {
        scene.getStylesheets().clear();
        addSheet(scene, BASE_CSS);
        if (isDark()) {
            addSheet(scene, DARK_CSS);
        }
    }

    private static void addSheet(Scene scene, String resource) {
        URL url = Theme.class.getResource(resource);
        if (url == null) {
            logger.warn("Stylesheet {} is missing", resource);
            return;
        }
        scene.getStylesheets().add(url.toExternalForm());
    }
}
