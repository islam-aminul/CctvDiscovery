package com.cctv.discovery.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Logback is configured by XML and cannot call Java, so logback.xml asks
 * {@link LogDirectoryDefiner} where the logs go rather than repeating the rules
 * in {@link AppPaths} and drifting from them.
 *
 * <p>Worth a test because the failure is quiet: a definer that cannot be loaded
 * leaves the property undefined and Logback writes to a folder literally named
 * LOG_DIR_IS_UNDEFINED, next to wherever the program was started.
 */
class LogDirectoryTest {

    private final String logDirBefore = System.getProperty(AppPaths.LOG_DIR_PROPERTY);

    @AfterEach
    void restore() throws Exception {
        if (logDirBefore == null) {
            System.clearProperty(AppPaths.LOG_DIR_PROPERTY);
        } else {
            System.setProperty(AppPaths.LOG_DIR_PROPERTY, logDirBefore);
        }
        configure();
    }

    private static LoggerContext configure() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        URL configuration = LogDirectoryTest.class.getResource("/logback.xml");
        assertNotNull(configuration, "logback.xml is missing from the resources");

        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(configuration);
        return context;
    }

    /**
     * Where the rolling file appender was actually pointed. Asserting on this
     * rather than on the LOG_DIR property, because {@code <define>} keeps its
     * value in the configurator's own scope: the property reads back as null
     * even when the definer ran perfectly well, so a test on it would be
     * measuring the wrong thing.
     */
    private static Path appenderFile() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = root.getAppender("FILE");
        assertNotNull(appender, "the FILE appender is missing from logback.xml");
        return Path.of(((ch.qos.logback.core.FileAppender<?>) appender).getFile());
    }

    @Test
    @DisplayName("logback.xml gets its directory from the application, not from its own copy of the rules")
    void logDirectoryComesFromAppPaths(@TempDir Path tmp) throws Exception {
        Path logs = tmp.resolve("logs-under-test");
        System.setProperty(AppPaths.LOG_DIR_PROPERTY, logs.toString());

        configure();
        Path file = appenderFile();

        assertFalse(file.toString().contains("UNDEFINED"),
                "the definer class could not be loaded, so logging went to " + file);
        assertEquals(logs, file.getParent());
        assertEquals(AppPaths.logDirectory(), file.getParent());
    }

    @Test
    @DisplayName("Moving the data directory moves the logs with it")
    void followsTheDataDirectory(@TempDir Path tmp) throws Exception {
        String dataDirBefore = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.clearProperty(AppPaths.LOG_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, tmp.toString());
        try {
            configure();
            assertEquals(tmp.resolve("logs"), appenderFile().getParent());
        } finally {
            if (dataDirBefore == null) {
                System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            } else {
                System.setProperty(AppPaths.DATA_DIR_PROPERTY, dataDirBefore);
            }
        }
    }
}
