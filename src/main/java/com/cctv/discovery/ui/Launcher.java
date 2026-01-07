package com.cctv.discovery.ui;

import javafx.application.Application;
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
            logger.info("Starting CCTV Discovery application...");

            primaryStage.setTitle("CCTV Discovery & Audit Tool v1.0.0");

            // Set application icon
            try {
                Image icon = new Image(getClass().getResourceAsStream("/icon.png"));
                primaryStage.getIcons().add(icon);
            } catch (Exception e) {
                logger.warn("Could not load application icon", e);
            }

            // Create main controller and scene
            controller = new MainController(primaryStage);
            Scene scene = controller.createScene();

            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1200);
            primaryStage.setMinHeight(700);

            // Handle window close
            primaryStage.setOnCloseRequest(event -> {
                logger.info("Application closing...");
                if (controller != null) {
                    controller.shutdown();
                }
            });

            primaryStage.show();

            logger.info("Application started successfully");

        } catch (Exception e) {
            logger.error("Failed to start application", e);
            throw new RuntimeException("Application startup failed", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
