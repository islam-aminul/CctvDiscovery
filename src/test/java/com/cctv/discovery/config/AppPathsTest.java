package com.cctv.discovery.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The folder the application writes to has to be the same wherever the program
 * itself happens to be, which is the whole reason this class exists.
 */
class AppPathsTest {

    private final String dataDirBefore = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
    private final String logDirBefore = System.getProperty(AppPaths.LOG_DIR_PROPERTY);
    private final String userDirBefore = System.getProperty("user.dir");

    @AfterEach
    void restoreProperties() {
        restore(AppPaths.DATA_DIR_PROPERTY, dataDirBefore);
        restore(AppPaths.LOG_DIR_PROPERTY, logDirBefore);
        restore("user.dir", userDirBefore);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("The folder does not move when the program does")
    void isIndependentOfWhereTheProgramIs(@TempDir Path tmp) {
        System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
        System.clearProperty(AppPaths.LOG_DIR_PROPERTY);

        // Two copies of the executable, started from two different folders.
        System.setProperty("user.dir", tmp.resolve("Program Files").toString());
        Path fromOneCopy = AppPaths.dataDirectory();
        Path logsFromOneCopy = AppPaths.logDirectory();

        System.setProperty("user.dir", tmp.resolve("Desktop/CctvDiscovery portable").toString());
        Path fromAnotherCopy = AppPaths.dataDirectory();

        assertEquals(fromOneCopy, fromAnotherCopy,
                "settings must not follow the executable around");
        assertEquals(logsFromOneCopy, AppPaths.logDirectory());
        assertFalse(fromOneCopy.toString().contains("Program Files"),
                "the data directory must not be derived from the install location");
    }

    @Test
    @DisplayName("Logs sit under the data directory, so one override moves everything")
    void logsLiveUnderTheDataDirectory(@TempDir Path tmp) {
        Path root = tmp.resolve("everything");
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, root.toString());
        System.clearProperty(AppPaths.LOG_DIR_PROPERTY);

        assertEquals(root, AppPaths.dataDirectory());
        assertEquals(root.resolve("logs"), AppPaths.logDirectory());
        assertTrue(Files.isDirectory(root.resolve("logs")), "the log directory is created on demand");
    }

    @Test
    @DisplayName("Logs alone can be sent elsewhere without moving the settings")
    void logsCanBeOverriddenOnTheirOwn(@TempDir Path tmp) {
        Path data = tmp.resolve("data");
        Path logs = tmp.resolve("somewhere/else");
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, data.toString());
        System.setProperty(AppPaths.LOG_DIR_PROPERTY, logs.toString());

        assertEquals(data, AppPaths.dataDirectory());
        assertEquals(logs, AppPaths.logDirectory());
    }

    @Test
    @DisplayName("The directory is created rather than assumed")
    void createsTheDirectory(@TempDir Path tmp) {
        Path fresh = tmp.resolve("not/created/yet");
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, fresh.toString());

        assertFalse(Files.exists(fresh));
        assertEquals(fresh, AppPaths.dataDirectory());
        assertTrue(Files.isDirectory(fresh));
    }

    @Test
    @DisplayName("Settings are brought across from the folder older versions used")
    void bringsSettingsAcrossOnce(@TempDir Path tmp) throws Exception {
        Path previous = Files.createDirectories(tmp.resolve("Roaming/CctvDiscovery"));
        Path current = Files.createDirectories(tmp.resolve("Local/CctvDiscovery"));
        Files.writeString(previous.resolve("user-settings.properties"), "discovery.http.ports=8000\n");
        Files.writeString(previous.resolve("rtsp-paths.properties"), "2818FD=/live/channel0\n");

        AppConfig.migrate(previous, current);

        assertEquals("discovery.http.ports=8000\n",
                Files.readString(current.resolve("user-settings.properties")));
        assertEquals("2818FD=/live/channel0\n",
                Files.readString(current.resolve("rtsp-paths.properties")));
        assertTrue(Files.exists(previous.resolve("user-settings.properties")),
                "the old copy is left alone, so an older build still works");
    }

    @Test
    @DisplayName("Settings already in place are never overwritten by the old ones")
    void doesNotOverwriteWhatIsAlreadyThere(@TempDir Path tmp) throws Exception {
        Path previous = Files.createDirectories(tmp.resolve("Roaming/CctvDiscovery"));
        Path current = Files.createDirectories(tmp.resolve("Local/CctvDiscovery"));
        Files.writeString(previous.resolve("user-settings.properties"), "stale=yes\n");
        Files.writeString(current.resolve("user-settings.properties"), "current=yes\n");

        AppConfig.migrate(previous, current);

        assertEquals("current=yes\n", Files.readString(current.resolve("user-settings.properties")));
    }

    @Test
    @DisplayName("A missing old folder is not an error")
    void toleratesNothingToMigrate(@TempDir Path tmp) {
        AppConfig.migrate(tmp.resolve("never-existed"), tmp);
        AppConfig.migrate(null, tmp);
    }
}
