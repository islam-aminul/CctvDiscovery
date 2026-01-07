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
        // Always print to console for debugging (works even if logging fails)
        System.out.println("=== CCTV Discovery Tool v1.0.0 ===");
        System.out.println("Starting application...");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("JavaFX Runtime: Checking...");

        try {
            logger.info("=== CCTV Discovery Tool v1.0.0 ===");
            logger.info("Starting application...");

            System.out.println("Launching JavaFX application...");

            // Launch JavaFX application
            Launcher.main(args);

        } catch (Exception e) {
            logger.error("Fatal error during application startup", e);
            System.err.println("\n========================================");
            System.err.println("Failed to start CCTV Discovery Tool.");
            System.err.println("========================================");
            System.err.println("Error: " + e.getMessage());
            System.err.println("\nFull stack trace:");
            e.printStackTrace();
            System.err.println("========================================");
            System.exit(1);
        }
    }
}
