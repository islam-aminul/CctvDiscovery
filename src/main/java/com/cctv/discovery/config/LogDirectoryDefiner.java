package com.cctv.discovery.config;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Tells Logback where the log directory is, by asking {@link AppPaths}.
 *
 * <p>Logback cannot call Java, so the alternative is an expression in
 * logback.xml that repeats the rules in {@link AppPaths} and then drifts from
 * them. This keeps one definition. {@code LogDirectoryTest} checks that the
 * configuration actually reaches this class, because a definer that fails to
 * load leaves Logback writing to a folder named LOG_DIR_IS_UNDEFINED rather
 * than failing outright.
 */
public class LogDirectoryDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return AppPaths.logDirectory().toString();
    }
}
