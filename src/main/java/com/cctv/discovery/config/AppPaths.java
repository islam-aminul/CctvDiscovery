package com.cctv.discovery.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the application keeps everything it writes.
 *
 * <p>Settings, the RTSP path cache and the logs all live under one root, and
 * that root is chosen from the environment rather than from wherever the
 * program happens to be sitting. The same user gets the same folder whether the
 * tool was started from Program Files, a copy on the desktop, a memory stick or
 * the folder the self-extracting archive unpacks into, and two copies of the
 * executable share one set of settings rather than quietly diverging.
 *
 * <p>The root is {@code %LOCALAPPDATA%\CctvDiscovery} on Windows. Local rather
 * than roaming because the contents are specific to the machine: logs roll to
 * hundreds of megabytes, and the remembered stream paths describe the network
 * this computer is on. Neither belongs in a profile that follows the user to
 * other machines.
 *
 * <p>This class deliberately has no logger. Logback asks it for the log
 * directory while it is configuring itself, so reaching for SLF4J here would be
 * circular.
 */
public final class AppPaths {

    /** Moves everything: settings, cache and logs. Mainly for tests. */
    public static final String DATA_DIR_PROPERTY = "cctv.data.dir";
    /** Moves the logs alone, for when they need to go somewhere specific. */
    public static final String LOG_DIR_PROPERTY = "cctv.log.dir";

    private static final String WINDOWS_FOLDER = "CctvDiscovery";
    private static final String UNIX_FOLDER = ".cctv-discovery";

    private AppPaths() {
    }

    /** The one folder the application writes to, created if it is not there. */
    public static Path dataDirectory() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (isSet(override)) {
            return created(Path.of(override));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (isSet(localAppData)) {
            return created(Path.of(localAppData, WINDOWS_FOLDER));
        }
        String home = System.getProperty("user.home");
        if (isSet(home)) {
            return created(Path.of(home, UNIX_FOLDER));
        }
        return created(temporaryDirectory());
    }

    /** Log files, under the data directory unless pointed elsewhere. */
    public static Path logDirectory() {
        String override = System.getProperty(LOG_DIR_PROPERTY);
        if (isSet(override)) {
            return created(Path.of(override));
        }
        return created(dataDirectory().resolve("logs"));
    }

    /**
     * The roaming folder versions up to 2.0.0 used for settings and the path
     * cache, or null on a machine that has no such notion. Files are moved out
     * of it once; see {@link AppConfig}.
     */
    static Path previousDataDirectory() {
        String appData = System.getenv("APPDATA");
        return isSet(appData) ? Path.of(appData, WINDOWS_FOLDER) : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static Path temporaryDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", "."), WINDOWS_FOLDER);
    }

    /**
     * Create the directory, falling back to a temporary one when it cannot be
     * written. A read-only or missing profile should cost the user their
     * settings, not the ability to start the program.
     */
    private static Path created(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException | RuntimeException e) {
            Path temp = temporaryDirectory();
            if (temp.equals(dir)) {
                return dir;
            }
            try {
                Files.createDirectories(temp);
            } catch (IOException | RuntimeException ignored) {
                // Nothing left to try; hand back the path and let the caller
                // report the failure when it writes.
            }
            return temp;
        }
    }
}
