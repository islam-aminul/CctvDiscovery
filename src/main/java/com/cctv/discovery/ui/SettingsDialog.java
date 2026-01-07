package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Settings dialog for configuring application properties.
 */
public class SettingsDialog extends Stage {
    private static final Logger logger = LoggerFactory.getLogger(SettingsDialog.class);

    private final AppConfig config = AppConfig.getInstance();

    // Network Discovery Fields
    private TextField tfOnvifPort;
    private TextField tfOnvifMulticast;
    private TextField tfOnvifTimeout;
    private TextField tfHttpPorts;
    private TextField tfRtspPorts;

    // Threading Fields
    private TextField tfThreadMultiplier;
    private TextField tfMaxThreads;
    private TextField tfStreamThreads;

    // Timeout Fields
    private TextField tfSocketConnect;
    private TextField tfSocketRead;
    private TextField tfRtspTimeout;
    private TextField tfStreamTimeout;

    // RTSP Fields
    private TextField tfNvrMaxChannels;
    private TextField tfNvrFailures;

    // Export Fields
    private TextField tfExportDir;
    private CheckBox cbPasswordEnabled;
    private TextField tfPasswordCode;

    // MAC Resolution
    private CheckBox cbMacEnabled;
    private TextField tfMacTimeout;

    public SettingsDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Application Settings");
        setResizable(true);

        VBox root = createContent();
        Scene scene = new Scene(root, 700, 650);

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
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));

        Label title = new Label("Application Configuration");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label subtitle = new Label("Configure ports, timeouts, and paths. Changes take effect after restart.");
        subtitle.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Create tabs
        Tab networkTab = new Tab("Network", createNetworkTab());
        Tab performanceTab = new Tab("Performance", createPerformanceTab());
        Tab timeoutsTab = new Tab("Timeouts", createTimeoutsTab());
        Tab exportTab = new Tab("Export", createExportTab());
        Tab advancedTab = new Tab("Advanced", createAdvancedTab());

        tabPane.getTabs().addAll(networkTab, performanceTab, timeoutsTab, exportTab, advancedTab);

        // Buttons
        HBox buttonBox = createButtonBox();

        vbox.getChildren().addAll(title, subtitle, new Separator(), tabPane, buttonBox);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        return vbox;
    }

    private VBox createNetworkTab() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));

        // ONVIF Settings
        Label lblOnvif = new Label("ONVIF Discovery");
        lblOnvif.setStyle("-fx-font-weight: bold;");

        GridPane onvifGrid = new GridPane();
        onvifGrid.setHgap(10);
        onvifGrid.setVgap(10);

        tfOnvifPort = new TextField();
        tfOnvifMulticast = new TextField();
        tfOnvifTimeout = new TextField();

        onvifGrid.add(new Label("ONVIF Port:"), 0, 0);
        onvifGrid.add(tfOnvifPort, 1, 0);
        onvifGrid.add(new Label("Multicast Address:"), 0, 1);
        onvifGrid.add(tfOnvifMulticast, 1, 1);
        onvifGrid.add(new Label("Discovery Timeout (ms):"), 0, 2);
        onvifGrid.add(tfOnvifTimeout, 1, 2);

        // Port Scanning
        Label lblPorts = new Label("Port Scanning");
        lblPorts.setStyle("-fx-font-weight: bold;");

        GridPane portsGrid = new GridPane();
        portsGrid.setHgap(10);
        portsGrid.setVgap(10);

        tfHttpPorts = new TextField();
        tfRtspPorts = new TextField();

        portsGrid.add(new Label("HTTP Ports (comma-separated):"), 0, 0);
        portsGrid.add(tfHttpPorts, 1, 0);
        portsGrid.add(new Label("RTSP Ports (comma-separated):"), 0, 1);
        portsGrid.add(tfRtspPorts, 1, 1);

        vbox.getChildren().addAll(
                lblOnvif, onvifGrid,
                new Separator(),
                lblPorts, portsGrid
        );

        return vbox;
    }

    private VBox createPerformanceTab() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));

        Label lblThreading = new Label("Threading Configuration");
        lblThreading.setStyle("-fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        tfThreadMultiplier = new TextField();
        tfMaxThreads = new TextField();
        tfStreamThreads = new TextField();

        grid.add(new Label("Port Scan Thread Multiplier:"), 0, 0);
        grid.add(tfThreadMultiplier, 1, 0);
        grid.add(new Label("(Threads = CPU cores × multiplier)"), 2, 0);

        grid.add(new Label("Max Port Scan Threads:"), 0, 1);
        grid.add(tfMaxThreads, 1, 1);

        grid.add(new Label("Max Stream Analysis Threads:"), 0, 2);
        grid.add(tfStreamThreads, 1, 2);

        Label note = new Label("Note: Higher thread counts improve speed but increase CPU usage.");
        note.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        vbox.getChildren().addAll(lblThreading, grid, note);

        return vbox;
    }

    private VBox createTimeoutsTab() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));

        Label lblTimeouts = new Label("Timeout Configuration (milliseconds)");
        lblTimeouts.setStyle("-fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        tfSocketConnect = new TextField();
        tfSocketRead = new TextField();
        tfRtspTimeout = new TextField();
        tfStreamTimeout = new TextField();

        grid.add(new Label("Socket Connect Timeout:"), 0, 0);
        grid.add(tfSocketConnect, 1, 0);

        grid.add(new Label("Socket Read Timeout:"), 0, 1);
        grid.add(tfSocketRead, 1, 1);

        grid.add(new Label("RTSP Connection Timeout:"), 0, 2);
        grid.add(tfRtspTimeout, 1, 2);

        grid.add(new Label("Stream Analysis Timeout:"), 0, 3);
        grid.add(tfStreamTimeout, 1, 3);

        Label note = new Label("Note: Lower timeouts speed up scanning but may miss slow devices.");
        note.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        vbox.getChildren().addAll(lblTimeouts, grid, note);

        return vbox;
    }

    private VBox createExportTab() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));

        Label lblExport = new Label("Export Settings");
        lblExport.setStyle("-fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        tfExportDir = new TextField();
        tfExportDir.setPrefWidth(350);
        Button btnBrowse = new Button("Browse...");
        btnBrowse.setOnAction(e -> browseExportDirectory());

        HBox exportDirBox = new HBox(10, tfExportDir, btnBrowse);
        exportDirBox.setAlignment(Pos.CENTER_LEFT);

        grid.add(new Label("Default Export Directory:"), 0, 0);
        grid.add(exportDirBox, 1, 0);

        // Password Protection
        Label lblPassword = new Label("Excel Password Protection");
        lblPassword.setStyle("-fx-font-weight: bold;");

        GridPane passwordGrid = new GridPane();
        passwordGrid.setHgap(10);
        passwordGrid.setVgap(10);

        cbPasswordEnabled = new CheckBox("Enable password protection");
        tfPasswordCode = new TextField();

        passwordGrid.add(cbPasswordEnabled, 0, 0, 2, 1);
        passwordGrid.add(new Label("Fixed Code (secret):"), 0, 1);
        passwordGrid.add(tfPasswordCode, 1, 1);

        Label note = new Label("Note: Password format is {DeviceCount}{YYYYMMDD}{FixedCode}");
        note.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        vbox.getChildren().addAll(
                lblExport, grid,
                new Separator(),
                lblPassword, passwordGrid, note
        );

        return vbox;
    }

    private VBox createAdvancedTab() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));

        Label lblRtsp = new Label("RTSP/NVR Settings");
        lblRtsp.setStyle("-fx-font-weight: bold;");

        GridPane rtspGrid = new GridPane();
        rtspGrid.setHgap(10);
        rtspGrid.setVgap(10);

        tfNvrMaxChannels = new TextField();
        tfNvrFailures = new TextField();

        rtspGrid.add(new Label("Max NVR Channels to Scan:"), 0, 0);
        rtspGrid.add(tfNvrMaxChannels, 1, 0);

        rtspGrid.add(new Label("Stop After Failures:"), 0, 1);
        rtspGrid.add(tfNvrFailures, 1, 1);

        // MAC Resolution
        Label lblMac = new Label("MAC Address Resolution");
        lblMac.setStyle("-fx-font-weight: bold;");

        GridPane macGrid = new GridPane();
        macGrid.setHgap(10);
        macGrid.setVgap(10);

        cbMacEnabled = new CheckBox("Enable MAC address resolution");
        tfMacTimeout = new TextField();

        macGrid.add(cbMacEnabled, 0, 0, 2, 1);
        macGrid.add(new Label("ARP Timeout (ms):"), 0, 1);
        macGrid.add(tfMacTimeout, 1, 1);

        vbox.getChildren().addAll(
                lblRtsp, rtspGrid,
                new Separator(),
                lblMac, macGrid
        );

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
        // Network
        tfOnvifPort.setText(String.valueOf(config.getOnvifPort()));
        tfOnvifMulticast.setText(config.getOnvifMulticast());
        tfOnvifTimeout.setText(String.valueOf(config.getOnvifTimeout()));
        tfHttpPorts.setText(arrayToString(config.getHttpPorts()));
        tfRtspPorts.setText(arrayToString(config.getRtspPorts()));

        // Performance
        tfThreadMultiplier.setText(String.valueOf(config.getPortScanThreadMultiplier()));
        tfMaxThreads.setText(String.valueOf(config.getPortScanMaxThreads()));
        tfStreamThreads.setText(String.valueOf(config.getStreamAnalysisMaxThreads()));

        // Timeouts
        tfSocketConnect.setText(String.valueOf(config.getSocketConnectTimeout()));
        tfSocketRead.setText(String.valueOf(config.getSocketReadTimeout()));
        tfRtspTimeout.setText(String.valueOf(config.getRtspConnectTimeout()));
        tfStreamTimeout.setText(String.valueOf(config.getStreamAnalysisTimeout()));

        // Export
        tfExportDir.setText(config.getExportDefaultDirectory());
        cbPasswordEnabled.setSelected(config.isExcelPasswordEnabled());
        tfPasswordCode.setText(config.getExcelPasswordFixedCode());

        // Advanced
        tfNvrMaxChannels.setText(String.valueOf(config.getNvrMaxChannels()));
        tfNvrFailures.setText(String.valueOf(config.getNvrConsecutiveFailures()));
        cbMacEnabled.setSelected(config.isMacResolutionEnabled());
        tfMacTimeout.setText(String.valueOf(config.getMacResolutionTimeout()));
    }

    private void saveSettings() {
        try {
            // Network
            config.setProperty("discovery.onvif.port", tfOnvifPort.getText());
            config.setProperty("discovery.onvif.multicast", tfOnvifMulticast.getText());
            config.setProperty("discovery.onvif.timeout", tfOnvifTimeout.getText());
            config.setProperty("discovery.http.ports", tfHttpPorts.getText());
            config.setProperty("discovery.rtsp.ports", tfRtspPorts.getText());

            // Performance
            config.setProperty("threads.port.scan.multiplier", tfThreadMultiplier.getText());
            config.setProperty("threads.port.scan.max", tfMaxThreads.getText());
            config.setProperty("threads.stream.analysis.max", tfStreamThreads.getText());

            // Timeouts
            config.setProperty("timeout.socket.connect", tfSocketConnect.getText());
            config.setProperty("timeout.socket.read", tfSocketRead.getText());
            config.setProperty("timeout.rtsp.connect", tfRtspTimeout.getText());
            config.setProperty("timeout.stream.analysis", tfStreamTimeout.getText());

            // Export
            config.setProperty("export.default.directory", tfExportDir.getText());
            config.setProperty("export.excel.password.enabled", String.valueOf(cbPasswordEnabled.isSelected()));
            config.setProperty("export.excel.password.fixed.code", tfPasswordCode.getText());

            // Advanced
            config.setProperty("rtsp.nvr.max.channels", tfNvrMaxChannels.getText());
            config.setProperty("rtsp.nvr.consecutive.failures", tfNvrFailures.getText());
            config.setProperty("mac.resolution.enabled", String.valueOf(cbMacEnabled.isSelected()));
            config.setProperty("mac.resolution.timeout", tfMacTimeout.getText());

            // Save to file
            config.saveUserSettings();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Settings Saved");
            alert.setHeaderText("Configuration Updated");
            alert.setContentText("Settings have been saved successfully.\nRestart the application for changes to take effect.");
            alert.showAndWait();

            close();

        } catch (Exception e) {
            logger.error("Error saving settings", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Failed to Save Settings");
            alert.setContentText("Error: " + e.getMessage());
            alert.showAndWait();
        }
    }

    private void resetToDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset to Defaults");
        confirm.setHeaderText("Reset All Settings?");
        confirm.setContentText("This will reset all settings to default values. Continue?");

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

    private void browseExportDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Default Export Directory");

        File currentDir = new File(tfExportDir.getText());
        if (currentDir.exists() && currentDir.isDirectory()) {
            chooser.setInitialDirectory(currentDir);
        }

        File selectedDir = chooser.showDialog(this);
        if (selectedDir != null) {
            tfExportDir.setText(selectedDir.getAbsolutePath());
        }
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
