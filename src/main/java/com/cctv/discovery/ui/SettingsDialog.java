package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ports, extra stream paths and how thoroughly each stream is checked.
 *
 * <p>Built through {@link Modals} like every other modal. It used to be a bare
 * {@link Stage} with its own title block and its own row of buttons, which is
 * why it never looked like the rest of them.
 */
public class SettingsDialog {
    private static final Logger logger = LoggerFactory.getLogger(SettingsDialog.class);

    private final AppConfig config = AppConfig.getInstance();
    private final Dialog<ButtonType> dialog;
    private final ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType resetType = new ButtonType("Reset to Defaults", ButtonBar.ButtonData.LEFT);
    private final ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

    // Port Fields
    private TextField tfHttpPorts;
    private TextField tfRtspPorts;

    // Custom RTSP Path Pairs
    private TextField tfMainPath;
    private TextField tfSubPath;
    private ListView<String> lvPathPairs;
    private ObservableList<String> pathPairs;

    // RTSP Validation Method
    private RadioButton rbSdpOnly;
    private RadioButton rbRtpPacket;
    private RadioButton rbFrameCapture;
    private TextField tfCustomTimeout;

    public SettingsDialog(Stage owner) {
        this.pathPairs = FXCollections.observableArrayList();

        dialog = Modals.dialog("Settings", "Application settings",
                "Ports to scan, extra stream paths, and how thoroughly to check each stream.");
        dialog.getDialogPane().setContent(createContent());
        dialog.getDialogPane().setPrefSize(660, 540);

        dialog.getDialogPane().getButtonTypes().addAll(resetType, cancelType, saveType);
        Modals.primary(dialog, saveType);
        Modals.secondary(dialog, cancelType);
        Modals.danger(dialog, resetType);

        // Save validates first, and Reset asks before it wipes anything, so both
        // consume the event and close the dialog themselves only when done.
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.addEventFilter(ActionEvent.ACTION, e -> {
            if (!saveSettings()) {
                e.consume();
            }
        });
        Button reset = (Button) dialog.getDialogPane().lookupButton(resetType);
        reset.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            resetToDefaults();
        });

        loadCurrentSettings();
    }

    /** Show the dialog and wait for it to close. */
    public void showAndWait() {
        dialog.showAndWait();
    }

    private VBox createContent() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Tab portsTab = new Tab("Ports");
        portsTab.setContent(padded(createPortSection()));

        Tab pathsTab = new Tab("Stream Paths");
        pathsTab.setContent(padded(createRtspPathsSection()));

        Tab validationTab = new Tab("Verification");
        validationTab.setContent(padded(createRtspValidationSection()));

        tabPane.getTabs().addAll(portsTab, pathsTab, validationTab);

        // Where the choices are kept, so they can be backed up or copied to
        // another machine. The folder is the same whichever copy of the
        // program is running, which is the point worth showing.
        //
        // The folder rather than the file: a path has no spaces to wrap at, so
        // a long one is elided to "Saved in ..." and says nothing at all. The
        // full path is on the tooltip.
        Label location = new Label("Saved in " + config.getUserSettingsFile().getParent());
        location.getStyleClass().add("dialog-note");
        location.setTooltip(new Tooltip(config.getUserSettingsFile().toString()));

        return Modals.content(tabPane, location);
    }

    private static VBox padded(VBox section) {
        section.setPadding(new Insets(14));
        return section;
    }

    private VBox createPortSection() {
        VBox vbox = new VBox(8);

        Label lblTitle = new Label("Port Configuration");
        lblTitle.getStyleClass().add("dialog-section-title");

        Label lblHelp = new Label(
                "Only change these if your camera uses non-standard ports. Enter a single port or multiple ports separated by commas.");
        lblHelp.getStyleClass().add("dialog-note");
        lblHelp.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        // HTTP Ports
        Label lblHttp = new Label("HTTP Ports:");
        lblHttp.setMinWidth(100);
        tfHttpPorts = new TextField();
        tfHttpPorts.setPromptText("e.g., 80,8080,8000 or single: 8000");
        tfHttpPorts.setPrefWidth(300);

        // RTSP Ports
        Label lblRtsp = new Label("RTSP Ports:");
        lblRtsp.setMinWidth(100);
        tfRtspPorts = new TextField();
        tfRtspPorts.setPromptText("e.g., 554,8554 or single: 554");
        tfRtspPorts.setPrefWidth(300);

        grid.add(lblHttp, 0, 0);
        grid.add(tfHttpPorts, 1, 0);

        grid.add(lblRtsp, 0, 1);
        grid.add(tfRtspPorts, 1, 1);

        vbox.getChildren().addAll(lblTitle, lblHelp, grid);

        return vbox;
    }

    private VBox createRtspPathsSection() {
        VBox vbox = new VBox(8);

        Label lblTitle = new Label("Custom RTSP Path Pairs");
        lblTitle.getStyleClass().add("dialog-section-title");

        Label lblHelp = new Label("Add custom stream path pairs for cameras with non-standard configurations");
        lblHelp.getStyleClass().add("dialog-note");
        lblHelp.setWrapText(true);

        // Input fields for new path pair
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(8);
        inputGrid.setVgap(6);
        inputGrid.getStyleClass().add("input-group");

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
        btnAdd.setPrefWidth(350);
        btnAdd.setMaxWidth(350);
        btnAdd.setOnAction(e -> addPathPair());

        inputGrid.add(lblMain, 0, 0);
        inputGrid.add(tfMainPath, 1, 0);
        inputGrid.add(lblSub, 0, 1);
        inputGrid.add(tfSubPath, 1, 1);
        inputGrid.add(btnAdd, 1, 2);

        // List view for existing path pairs
        Label lblPairs = new Label("Configured Path Pairs:");
        lblPairs.getStyleClass().add("dialog-section-title");

        lvPathPairs = new ListView<>(pathPairs);
        lvPathPairs.setPrefHeight(100);
        lvPathPairs.setPlaceholder(new Label("No custom paths configured\nClick 'Add Path Pair' to add"));

        // Remove button - center aligned
        Button btnRemove = new Button("Remove Selected");
        btnRemove.setPrefWidth(150);
        btnRemove.getStyleClass().add("button-secondary");
        btnRemove.setOnAction(e -> removeSelectedPair());

        HBox removeBox = new HBox(8);
        removeBox.setAlignment(Pos.CENTER);
        removeBox.getChildren().add(btnRemove);

        Label lblNote = new Label(
                "💡 Tip: Main stream is usually high quality, Sub stream is lower quality for bandwidth saving");
        lblNote.getStyleClass().add("hint-label");
        lblNote.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, lblHelp, inputGrid, lblPairs, lvPathPairs, removeBox, lblNote);

        return vbox;
    }

    private VBox createRtspValidationSection() {
        VBox vbox = new VBox(8);

        Label lblTitle = new Label("RTSP Validation Method");
        lblTitle.getStyleClass().add("dialog-section-title");

        Label lblHelp = new Label(
                "Choose how RTSP stream URLs are validated during discovery. Higher accuracy takes more time.");
        lblHelp.getStyleClass().add("dialog-note");
        lblHelp.setWrapText(true);

        // Radio buttons for validation method
        ToggleGroup validationGroup = new ToggleGroup();

        rbSdpOnly = new RadioButton("SDP Only - Fast (3s per URL), ~60% accurate");
        rbSdpOnly.setToggleGroup(validationGroup);

        rbRtpPacket = new RadioButton("RTP Packet - Medium (5s per URL), ~90% accurate");
        rbRtpPacket.setToggleGroup(validationGroup);

        rbFrameCapture = new RadioButton("Frame Capture - Slow (10s per URL), ~98% accurate (Recommended)");
        rbFrameCapture.setToggleGroup(validationGroup);

        VBox radioBox = new VBox(4);
        radioBox.getChildren().addAll(rbSdpOnly, rbRtpPacket, rbFrameCapture);
        radioBox.setStyle("-fx-padding: 5 0 5 10;");

        // Custom timeout field (optional)
        Label lblTimeout = new Label("Custom Timeout (optional):");
        lblTimeout.getStyleClass().add("dialog-section-title");

        HBox timeoutBox = new HBox(8);
        timeoutBox.setAlignment(Pos.CENTER_LEFT);

        tfCustomTimeout = new TextField();
        tfCustomTimeout.setPromptText("0 = use default for method");
        tfCustomTimeout.setPrefWidth(200);

        Label lblMs = new Label("milliseconds");
        lblMs.getStyleClass().add("dialog-note");

        timeoutBox.getChildren().addAll(tfCustomTimeout, lblMs);

        Label lblNote = new Label(
                "💡 Tip: Frame Capture is recommended for accurate production audits. Use SDP Only for quick preliminary scans.");
        lblNote.getStyleClass().add("hint-label");
        lblNote.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, lblHelp, radioBox, lblTimeout, timeoutBox, lblNote);

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

        // Case 1: Warn if main path equals sub path
        if (mainPath.equals(subPath)) {
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("Same Paths");
            warn.setHeaderText("Main and Sub paths are identical");
            warn.setContentText("Main: " + mainPath + "\nSub: " + subPath + "\n\nThis is unusual but will be allowed.");
            warn.showAndWait();
        }

        // Case 2: Check for duplicate (main, sub) pair
        String pairDisplay = String.format("Main: %s | Sub: %s", mainPath, subPath);
        if (pathPairs.contains(pairDisplay)) {
            showError("Duplicate Path Pair",
                    "This exact path pair already exists:\n\nMain: " + mainPath + "\nSub: " + subPath);
            return;
        }

        // Add to list (display format: "Main: /path | Sub: /path")
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

        // RTSP Validation Method
        String validationMethod = config.getRtspValidationMethod();
        if ("SDP_ONLY".equals(validationMethod)) {
            rbSdpOnly.setSelected(true);
        } else if ("RTP_PACKET".equals(validationMethod)) {
            rbRtpPacket.setSelected(true);
        } else {
            rbFrameCapture.setSelected(true); // Default
        }

        // Custom timeout
        int timeout = config.getRtspValidationTimeout();
        tfCustomTimeout.setText(timeout > 0 ? String.valueOf(timeout) : "0");
    }

    /** @return true when the settings were written and the dialog may close. */
    private boolean saveSettings() {
        try {
            // Validate port inputs
            String httpPorts = tfHttpPorts.getText().trim();
            String rtspPorts = tfRtspPorts.getText().trim();

            if (!validatePortList(httpPorts)) {
                showError("Invalid HTTP Ports", "Enter port numbers separated by commas, for example 80,8080.");
                return false;
            }

            if (!validatePortList(rtspPorts)) {
                showError("Invalid RTSP Ports", "Enter port numbers separated by commas, for example 554,8554.");
                return false;
            }

            // Deduplicate ports
            String[] httpResult = deduplicatePorts(httpPorts);
            String deduplicatedHttpPorts = httpResult[0];
            boolean httpHadDuplicates = Boolean.parseBoolean(httpResult[1]);

            String[] rtspResult = deduplicatePorts(rtspPorts);
            String deduplicatedRtspPorts = rtspResult[0];
            boolean rtspHadDuplicates = Boolean.parseBoolean(rtspResult[1]);

            // Show info message if duplicates were found
            if (httpHadDuplicates || rtspHadDuplicates) {
                StringBuilder message = new StringBuilder("Duplicate ports were removed.\n\n");
                if (httpHadDuplicates) {
                    message.append("HTTP Ports: ").append(httpPorts).append(" → ").append(deduplicatedHttpPorts)
                            .append("\n");
                }
                if (rtspHadDuplicates) {
                    message.append("RTSP Ports: ").append(rtspPorts).append(" → ").append(deduplicatedRtspPorts)
                            .append("\n");
                }

                Modals.inform("Ports", "Duplicate ports removed", message.toString());

                // Update text fields to show deduplicated values
                tfHttpPorts.setText(deduplicatedHttpPorts);
                tfRtspPorts.setText(deduplicatedRtspPorts);
            }

            // Save ports
            config.setProperty("discovery.http.ports", deduplicatedHttpPorts);
            config.setProperty("discovery.rtsp.ports", deduplicatedRtspPorts);

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

            // Save RTSP validation method
            String validationMethod = "FRAME_CAPTURE"; // default
            if (rbSdpOnly.isSelected()) {
                validationMethod = "SDP_ONLY";
            } else if (rbRtpPacket.isSelected()) {
                validationMethod = "RTP_PACKET";
            }
            config.setRtspValidationMethod(validationMethod);

            // Save custom timeout (validate first)
            String timeoutStr = tfCustomTimeout.getText().trim();
            if (!timeoutStr.isEmpty() && !timeoutStr.equals("0")) {
                try {
                    int timeout = Integer.parseInt(timeoutStr);
                    if (timeout < 0 || timeout > 300000) { // Max 5 minutes
                        showError("Invalid Timeout", "Enter between 0 and 300000 milliseconds, which is five minutes.");
                        return false;
                    }
                    config.setRtspValidationTimeout(timeout);
                } catch (NumberFormatException e) {
                    showError("Invalid Timeout", "Enter the timeout as a number of milliseconds.");
                    return false;
                }
            } else {
                config.setRtspValidationTimeout(0); // Use default
            }

            logger.info("Saved RTSP validation: method={}, timeout={}", validationMethod, timeoutStr);

            if (!config.saveUserSettings()) {
                showError("Save Error", "The settings could not be written to "
                        + config.getUserSettingsFile() + ".");
                return false;
            }

            Modals.inform("Settings", "Settings saved",
                    "Saved to " + config.getUserSettingsFile()
                            + ". Restart the application for the changes to take effect.");
            return true;

        } catch (Exception e) {
            logger.error("Error saving settings", e);
            showError("Save Error", "The settings could not be saved: " + e.getMessage());
            return false;
        }
    }

    private void resetToDefaults() {
        boolean reset = Modals.confirm("Reset to Defaults", "Reset every setting?",
                "Ports, stream paths and the verification method go back to how they shipped. "
                        + "This cannot be undone.",
                "Reset");
        if (!reset) {
            return;
        }
        config.resetAllToDefaults();
        pathPairs.clear();
        loadCurrentSettings();
        Modals.inform("Reset to Defaults", "Defaults restored", "Every setting is back to how it shipped.");
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
        Modals.error(title, title, message);
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

    /**
     * Deduplicates a comma-separated list of ports while preserving order
     * 
     * @param portList comma-separated port list
     * @return String array: [0] = deduplicated ports, [1] = "true" if duplicates
     *         found, "false" otherwise
     */
    private String[] deduplicatePorts(String portList) {
        String[] parts = portList.split(",");
        java.util.LinkedHashSet<String> uniquePorts = new java.util.LinkedHashSet<>();

        int originalCount = parts.length;
        for (String part : parts) {
            uniquePorts.add(part.trim());
        }

        boolean hadDuplicates = uniquePorts.size() < originalCount;

        StringBuilder result = new StringBuilder();
        for (String port : uniquePorts) {
            if (result.length() > 0) {
                result.append(",");
            }
            result.append(port);
        }

        return new String[] { result.toString(), String.valueOf(hadDuplicates) };
    }
}
