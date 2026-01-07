package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplified settings dialog for non-technical users.
 * Only allows configuration of ports and custom RTSP paths.
 */
public class SettingsDialog extends Stage {
    private static final Logger logger = LoggerFactory.getLogger(SettingsDialog.class);

    private final AppConfig config = AppConfig.getInstance();

    // Port Fields
    private TextField tfHttpPorts;
    private TextField tfRtspPorts;

    // Custom RTSP Path Pairs
    private TextField tfMainPath;
    private TextField tfSubPath;
    private ListView<String> lvPathPairs;
    private ObservableList<String> pathPairs;

    public SettingsDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Settings");
        setResizable(false);

        // Initialize path pairs list
        this.pathPairs = FXCollections.observableArrayList();

        VBox root = createContent();
        Scene scene = new Scene(root, 650, 600);

        try {
            java.net.URL cssResource = getClass().getResource("/css/app.css");
            if (cssResource != null) {
                scene.getStylesheets().add(cssResource.toExternalForm());
            }
        } catch (Exception e) {
            logger.warn("Could not load CSS for settings dialog", e);
        }

        setScene(scene);
        loadCurrentSettings();
    }

    private VBox createContent() {
        VBox vbox = new VBox(20);
        vbox.setPadding(new Insets(20));

        // Title
        Label title = new Label("Application Settings");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label subtitle = new Label("Configure ports and RTSP paths for cameras with custom settings");
        subtitle.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        // Port Settings Section
        VBox portSection = createPortSection();

        // RTSP Paths Section
        VBox rtspSection = createRtspPathsSection();

        // Buttons
        HBox buttonBox = createButtonBox();

        vbox.getChildren().addAll(
                title,
                subtitle,
                new Separator(),
                portSection,
                new Separator(),
                rtspSection,
                buttonBox
        );

        return vbox;
    }

    private VBox createPortSection() {
        VBox vbox = new VBox(12);

        Label lblTitle = new Label("Port Configuration");
        lblTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label lblHelp = new Label("Only change these if your cameras use non-standard ports");
        lblHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(12);

        // HTTP Ports
        Label lblHttp = new Label("HTTP Ports:");
        lblHttp.setMinWidth(100);
        tfHttpPorts = new TextField();
        tfHttpPorts.setPromptText("e.g., 80,8080,8000");
        tfHttpPorts.setPrefWidth(300);
        Label lblHttpHelp = new Label("(Separate multiple ports with commas)");
        lblHttpHelp.setStyle("-fx-text-fill: #888; -fx-font-size: 10px;");

        // RTSP Ports
        Label lblRtsp = new Label("RTSP Ports:");
        lblRtsp.setMinWidth(100);
        tfRtspPorts = new TextField();
        tfRtspPorts.setPromptText("e.g., 554,8554");
        tfRtspPorts.setPrefWidth(300);
        Label lblRtspHelp = new Label("(Separate multiple ports with commas)");
        lblRtspHelp.setStyle("-fx-text-fill: #888; -fx-font-size: 10px;");

        grid.add(lblHttp, 0, 0);
        grid.add(tfHttpPorts, 1, 0);
        grid.add(lblHttpHelp, 1, 1);

        grid.add(lblRtsp, 0, 2);
        grid.add(tfRtspPorts, 1, 2);
        grid.add(lblRtspHelp, 1, 3);

        vbox.getChildren().addAll(lblTitle, lblHelp, grid);

        return vbox;
    }

    private VBox createRtspPathsSection() {
        VBox vbox = new VBox(12);

        Label lblTitle = new Label("Custom RTSP Path Pairs");
        lblTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label lblHelp = new Label("Add custom stream path pairs for cameras with non-standard configurations");
        lblHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        lblHelp.setWrapText(true);

        // Input fields for new path pair
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 10;");

        Label lblMain = new Label("Main Stream Path:");
        lblMain.setMinWidth(120);
        tfMainPath = new TextField();
        tfMainPath.setPromptText("/h264/ch1/main/av_stream");
        tfMainPath.setPrefWidth(350);

        Label lblSub = new Label("Sub Stream Path:");
        lblSub.setMinWidth(120);
        tfSubPath = new TextField();
        tfSubPath.setPromptText("/h264/ch1/sub/av_stream");
        tfSubPath.setPrefWidth(350);

        Button btnAdd = new Button("Add Path Pair");
        btnAdd.getStyleClass().add("button-success");
        btnAdd.setPrefWidth(120);
        btnAdd.setOnAction(e -> addPathPair());

        inputGrid.add(lblMain, 0, 0);
        inputGrid.add(tfMainPath, 1, 0);
        inputGrid.add(lblSub, 0, 1);
        inputGrid.add(tfSubPath, 1, 1);
        inputGrid.add(btnAdd, 1, 2);

        // List view for existing path pairs
        Label lblPairs = new Label("Configured Path Pairs:");
        lblPairs.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        lvPathPairs = new ListView<>(pathPairs);
        lvPathPairs.setPrefHeight(120);
        lvPathPairs.setPlaceholder(new Label("No custom paths configured\nClick 'Add Path Pair' to add"));

        // Remove button
        Button btnRemove = new Button("Remove Selected");
        btnRemove.setPrefWidth(120);
        btnRemove.setOnAction(e -> removeSelectedPair());

        HBox removeBox = new HBox(10);
        removeBox.setAlignment(Pos.CENTER_LEFT);
        removeBox.getChildren().add(btnRemove);

        Label lblNote = new Label("💡 Tip: Main stream is usually high quality, Sub stream is lower quality for bandwidth saving");
        lblNote.setStyle("-fx-text-fill: #0066cc; -fx-font-size: 10px; -fx-font-style: italic;");
        lblNote.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, lblHelp, inputGrid, lblPairs, lvPathPairs, removeBox, lblNote);

        return vbox;
    }

    private void addPathPair() {
        String mainPath = tfMainPath.getText().trim();
        String subPath = tfSubPath.getText().trim();

        // Validate inputs
        if (mainPath.isEmpty() || subPath.isEmpty()) {
            showError("Missing Information", "Please enter both Main Stream Path and Sub Stream Path");
            return;
        }

        if (!mainPath.startsWith("/")) {
            showError("Invalid Main Path", "Path must start with '/' (e.g., /h264/ch1/main/av_stream)");
            return;
        }

        if (!subPath.startsWith("/")) {
            showError("Invalid Sub Path", "Path must start with '/' (e.g., /h264/ch1/sub/av_stream)");
            return;
        }

        // Add to list (display format: "Main: /path | Sub: /path")
        String pairDisplay = String.format("Main: %s | Sub: %s", mainPath, subPath);
        pathPairs.add(pairDisplay);

        // Clear input fields
        tfMainPath.clear();
        tfSubPath.clear();

        logger.info("Added custom RTSP path pair: main={}, sub={}", mainPath, subPath);
    }

    private void removeSelectedPair() {
        int selectedIndex = lvPathPairs.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            pathPairs.remove(selectedIndex);
            logger.info("Removed path pair at index {}", selectedIndex);
        } else {
            showError("No Selection", "Please select a path pair to remove");
        }
    }

    private HBox createButtonBox() {
        HBox hbox = new HBox(10);
        hbox.setAlignment(Pos.CENTER_RIGHT);
        hbox.setPadding(new Insets(10, 0, 0, 0));

        Button btnSave = new Button("Save");
        btnSave.getStyleClass().add("button-success");
        btnSave.setPrefWidth(100);
        btnSave.setOnAction(e -> saveSettings());

        Button btnReset = new Button("Reset to Defaults");
        btnReset.setPrefWidth(130);
        btnReset.setOnAction(e -> resetToDefaults());

        Button btnCancel = new Button("Cancel");
        btnCancel.setPrefWidth(100);
        btnCancel.setOnAction(e -> close());

        hbox.getChildren().addAll(btnReset, btnSave, btnCancel);

        return hbox;
    }

    private void loadCurrentSettings() {
        // Ports
        tfHttpPorts.setText(arrayToString(config.getHttpPorts()));
        tfRtspPorts.setText(arrayToString(config.getRtspPorts()));

        // Custom RTSP path pairs
        String customPaths = config.getProperty("rtsp.custom.paths");
        if (customPaths != null && !customPaths.isEmpty()) {
            String[] paths = customPaths.split(";");
            // Process paths in pairs
            for (int i = 0; i < paths.length - 1; i += 2) {
                String mainPath = paths[i].trim();
                String subPath = paths[i + 1].trim();
                String pairDisplay = String.format("Main: %s | Sub: %s", mainPath, subPath);
                pathPairs.add(pairDisplay);
            }
        }
    }

    private void saveSettings() {
        try {
            // Validate port inputs
            String httpPorts = tfHttpPorts.getText().trim();
            String rtspPorts = tfRtspPorts.getText().trim();

            if (!validatePortList(httpPorts)) {
                showError("Invalid HTTP Ports", "Please enter valid port numbers separated by commas (e.g., 80,8080)");
                return;
            }

            if (!validatePortList(rtspPorts)) {
                showError("Invalid RTSP Ports", "Please enter valid port numbers separated by commas (e.g., 554,8554)");
                return;
            }

            // Save ports
            config.setProperty("discovery.http.ports", httpPorts);
            config.setProperty("discovery.rtsp.ports", rtspPorts);

            // Save custom RTSP path pairs
            if (!pathPairs.isEmpty()) {
                StringBuilder pathsBuilder = new StringBuilder();

                for (String pairDisplay : pathPairs) {
                    // Parse the display format "Main: /path | Sub: /path"
                    String[] parts = pairDisplay.split("\\|");
                    if (parts.length == 2) {
                        String mainPath = parts[0].replace("Main:", "").trim();
                        String subPath = parts[1].replace("Sub:", "").trim();

                        if (pathsBuilder.length() > 0) {
                            pathsBuilder.append(";");
                        }
                        pathsBuilder.append(mainPath).append(";").append(subPath);
                    }
                }

                config.setProperty("rtsp.custom.paths", pathsBuilder.toString());
                logger.info("Saved {} custom RTSP path pairs", pathPairs.size());
            } else {
                config.setProperty("rtsp.custom.paths", "");
            }

            // Save to file
            config.saveUserSettings();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Settings Saved");
            alert.setHeaderText("Configuration Updated");
            alert.setContentText("Settings saved successfully.\n\nRestart the application for changes to take effect.");
            alert.showAndWait();

            close();

        } catch (Exception e) {
            logger.error("Error saving settings", e);
            showError("Save Error", "Failed to save settings: " + e.getMessage());
        }
    }

    private void resetToDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset to Defaults");
        confirm.setHeaderText("Reset All Settings?");
        confirm.setContentText("This will reset all custom settings to defaults.\nAre you sure?");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                config.resetAllToDefaults();

                // Clear path pairs
                pathPairs.clear();

                // Reload settings
                loadCurrentSettings();

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Settings Reset");
                alert.setHeaderText("Defaults Restored");
                alert.setContentText("All settings have been reset to default values.");
                alert.showAndWait();
            }
        });
    }

    private boolean validatePortList(String portList) {
        if (portList.isEmpty()) {
            return false;
        }

        String[] parts = portList.split(",");
        for (String part : parts) {
            try {
                int port = Integer.parseInt(part.trim());
                if (port < 1 || port > 65535) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String arrayToString(int[] array) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(",");
            }
        }
        return sb.toString();
    }
}
