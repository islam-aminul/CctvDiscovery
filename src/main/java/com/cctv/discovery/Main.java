package com.cctv.discovery;

import com.cctv.discovery.ui.Launcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for CCTV Discovery application.
 *
 * This class serves as the standard Java entry point and delegates to the JavaFX
 * Launcher class. This architecture prevents "Runtime Components Missing" errors
 * on modern or headless Java environments.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("=== CCTV Discovery Tool v1.0.0 ===");
        logger.info("Starting application...");

        try {
            // Launch JavaFX application
            Launcher.main(args);
        } catch (Exception e) {
            logger.error("Fatal error during application startup", e);
            System.err.println("Failed to start CCTV Discovery Tool.");
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
