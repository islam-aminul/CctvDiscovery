package com.cctv.discovery;

import com.cctv.discovery.ui.Launcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for CCTV Discovery application.
 *
 * This class serves as the standard Java entry point and delegates to the
 * JavaFX
 * Launcher class. This architecture prevents "Runtime Components Missing"
 * errors
 * on modern or headless Java environments.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Always print to console for debugging (works even if logging fails)
        System.out.println("--- CCTV Discovery Tool ---");
        System.out.println("Starting application...");
        System.out.println("Java Version: " + System.getProperty("java.version"));

        try {
            logger.info("--- CCTV Discovery Tool ---");
            logger.info("Starting application...");
            logger.info("Java Version: " + System.getProperty("java.version"));

            System.out.println("Launching JavaFX application...");

            // Launch JavaFX application
            Launcher.main(args);

        } catch (Exception e) {
            logger.error("Fatal error during application startup", e);
            System.err.println("Failed to start CCTV Discovery Tool.");
            System.err.println("Error: " + e.getMessage());
            System.err.println("Full stack trace:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
