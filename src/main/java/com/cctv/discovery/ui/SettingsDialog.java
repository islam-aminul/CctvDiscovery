package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Custom RTSP Paths
    private TextArea taCustomRtspPaths;

    public SettingsDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Settings");
        setResizable(false);

        VBox root = createContent();
        Scene scene = new Scene(root, 550, 500);

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

        Label lblTitle = new Label("Custom RTSP Paths");
        lblTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label lblHelp = new Label("Add custom RTSP paths if default paths don't work (one path per line)");
        lblHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        lblHelp.setWrapText(true);

        taCustomRtspPaths = new TextArea();
        taCustomRtspPaths.setPromptText("Examples:\n/live\n/stream1\n/h264/ch1/main/av_stream\n/cam/realmonitor?channel=1&subtype=0");
        taCustomRtspPaths.setPrefRowCount(8);
        taCustomRtspPaths.setWrapText(false);
        taCustomRtspPaths.setStyle("-fx-font-family: 'Courier New', monospace;");

        Label lblExample = new Label("These paths will be tried in addition to the built-in manufacturer paths");
        lblExample.setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-style: italic;");
        lblExample.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, lblHelp, taCustomRtspPaths, lblExample);

        return vbox;
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

        // Custom RTSP paths
        String customPaths = config.getProperty("rtsp.custom.paths");
        if (customPaths != null && !customPaths.isEmpty()) {
            // Convert semicolon-separated to line-separated
            taCustomRtspPaths.setText(customPaths.replace(";", "\n"));
        } else {
            taCustomRtspPaths.setText("");
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

            // Save custom RTSP paths (convert line-separated to semicolon-separated)
            String customPaths = taCustomRtspPaths.getText().trim();
            if (!customPaths.isEmpty()) {
                // Clean up: remove empty lines, trim each line
                String[] lines = customPaths.split("\n");
                StringBuilder cleaned = new StringBuilder();
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        if (cleaned.length() > 0) {
                            cleaned.append(";");
                        }
                        cleaned.append(line);
                    }
                }
                config.setProperty("rtsp.custom.paths", cleaned.toString());
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
