package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX Application launcher.
 * This class extends Application and initializes the JavaFX runtime.
 */
public class Launcher extends Application {
    private static final Logger logger = LoggerFactory.getLogger(Launcher.class);

    private MainController controller;

    @Override
    public void start(Stage primaryStage) {
        try {
            AppConfig config = AppConfig.getInstance();
            String appName = config.getAppName();
            String appVersion = config.getAppVersion();

            logger.info("Starting {} v{}...", appName, appVersion);

            primaryStage.setTitle(appName + " v" + appVersion);

            // Set application icon
            try {
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    Image icon = new Image(iconStream);
                    primaryStage.getIcons().add(icon);
                    logger.info("Application icon loaded successfully");
                } else {
                    logger.warn("Icon resource not found: /icon.png");
                }
            } catch (Exception e) {
                logger.warn("Could not load application icon", e);
            }

            // Create main controller and scene
            logger.info("Creating main controller...");
            controller = new MainController(primaryStage);

            logger.info("Creating scene...");
            Scene scene = controller.createScene();

            logger.info("Setting up stage...");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1200);
            primaryStage.setMinHeight(700);

            // Handle window close
            primaryStage.setOnCloseRequest(event -> {
                logger.info("Application closing...");
                if (controller != null) {
                    controller.shutdown();
                }
                Platform.exit();
                System.exit(0);
            });

            logger.info("Showing primary stage...");
            primaryStage.show();

            logger.info("Application started successfully");

        } catch (Exception e) {
            logger.error("Failed to start application", e);
            e.printStackTrace(); // Print full stack trace to stderr
            throw new RuntimeException("Application startup failed: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
