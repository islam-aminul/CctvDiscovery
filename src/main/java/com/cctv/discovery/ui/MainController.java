package com.cctv.discovery.ui;

import com.cctv.discovery.discovery.NetworkScanner;
import com.cctv.discovery.discovery.StreamAnalyzer;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.util.NetworkUtils;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main UI controller for CCTV Discovery application.
 */
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    private Stage primaryStage;
    private Scene scene;

    // Network selection
    private RadioButton rbInterface;
    private RadioButton rbManualRange;
    private RadioButton rbCIDR;
    private ComboBox<String> cbInterfaces;
    private TextField tfStartIP;
    private TextField tfEndIP;
    private TextField tfCIDR;
    private Label lblIpCount;

    // Credentials
    private TextField tfUsername;
    private TextField tfPassword;
    private Button btnAddCredential;
    private ListView<Credential> lvCredentials;
    private ObservableList<Credential> credentials;

    // Actions
    private Button btnStart;
    private Button btnExport;

    // Progress
    private ProgressBar progressBar;
    private Label lblProgress;

    // Results table
    private TableView<Device> tvResults;
    private ObservableList<Device> devices;

    // Services
    private NetworkScanner networkScanner;
    private OnvifService onvifService;
    private RtspService rtspService;
    private StreamAnalyzer streamAnalyzer;
    private ExcelExporter excelExporter;
    private ExecutorService executorService;

    // State
    private boolean discoveryInProgress = false;

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.credentials = FXCollections.observableArrayList();
        this.devices = FXCollections.observableArrayList();

        // Initialize services
        this.networkScanner = new NetworkScanner();
        this.onvifService = new OnvifService();
        this.rtspService = new RtspService();
        this.streamAnalyzer = new StreamAnalyzer();
        this.excelExporter = new ExcelExporter();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public Scene createScene() {
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.35);

        // Left panel - Controls
        VBox leftPanel = createLeftPanel();
        ScrollPane leftScroll = new ScrollPane(leftPanel);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.getStyleClass().add("left-panel");

        // Right panel - Results
        VBox rightPanel = createRightPanel();

        splitPane.getItems().addAll(leftScroll, rightPanel);

        scene = new Scene(splitPane, 1400, 800);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        return scene;
    }

    private VBox createLeftPanel() {
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(15));
        vbox.getStyleClass().add("left-panel");

        // Header
        Label title = new Label("CCTV Discovery Tool");
        title.getStyleClass().add("header-title");

        Button btnHelp = new Button("User Manual");
        btnHelp.setOnAction(e -> showHelpManual());

        HBox header = new HBox(10, title, new Region(), btnHelp);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        // Network Section
        VBox networkSection = createNetworkSection();

        // Credential Section
        VBox credentialSection = createCredentialSection();

        // Action Section
        VBox actionSection = createActionSection();

        // Progress Section
        VBox progressSection = createProgressSection();

        // Export Section
        VBox exportSection = createExportSection();

        vbox.getChildren().addAll(
                header,
                new Separator(),
                networkSection,
                new Separator(),
                credentialSection,
                new Separator(),
                actionSection,
                new Separator(),
                progressSection,
                new Separator(),
                exportSection
        );

        return vbox;
    }

    private VBox createNetworkSection() {
        VBox vbox = new VBox(10);

        Label lblTitle = new Label("Network Selection");
        lblTitle.getStyleClass().add("section-title");

        // Radio buttons
        ToggleGroup tg = new ToggleGroup();
        rbInterface = new RadioButton("Network Interface");
        rbManualRange = new RadioButton("Manual IP Range");
        rbCIDR = new RadioButton("CIDR Notation");
        rbInterface.setToggleGroup(tg);
        rbManualRange.setToggleGroup(tg);
        rbCIDR.setToggleGroup(tg);
        rbInterface.setSelected(true);

        // Interface dropdown
        cbInterfaces = new ComboBox<>();
        populateNetworkInterfaces();
        cbInterfaces.setMaxWidth(Double.MAX_VALUE);

        // Manual range
        tfStartIP = new TextField();
        tfStartIP.setPromptText("Start IP (e.g., 192.168.1.1)");
        tfStartIP.setDisable(true);

        tfEndIP = new TextField();
        tfEndIP.setPromptText("End IP (e.g., 192.168.1.254)");
        tfEndIP.setDisable(true);

        // CIDR
        tfCIDR = new TextField();
        tfCIDR.setPromptText("CIDR (e.g., 192.168.1.0/24)");
        tfCIDR.setDisable(true);

        // IP count label
        lblIpCount = new Label("Possible IPs: 0");
        lblIpCount.getStyleClass().add("label-info");

        // Event handlers
        rbInterface.setOnAction(e -> updateNetworkMode());
        rbManualRange.setOnAction(e -> updateNetworkMode());
        rbCIDR.setOnAction(e -> updateNetworkMode());
        cbInterfaces.setOnAction(e -> updateIpCount());
        tfStartIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfEndIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfCIDR.textProperty().addListener((obs, old, val) -> updateIpCount());

        vbox.getChildren().addAll(
                lblTitle,
                rbInterface, cbInterfaces,
                rbManualRange, tfStartIP, tfEndIP,
                rbCIDR, tfCIDR,
                lblIpCount
        );

        updateIpCount();
        return vbox;
    }

    private VBox createCredentialSection() {
        VBox vbox = new VBox(10);

        Label lblTitle = new Label("Credentials (Max 4)");
        lblTitle.getStyleClass().add("section-title");

        tfUsername = new TextField("admin");
        tfUsername.setPromptText("Username");

        tfPassword = new TextField();
        tfPassword.setPromptText("Password");

        btnAddCredential = new Button("Add Credential");
        btnAddCredential.setMaxWidth(Double.MAX_VALUE);
        btnAddCredential.setOnAction(e -> addCredential());

        lvCredentials = new ListView<>(credentials);
        lvCredentials.setPrefHeight(120);
        lvCredentials.setCellFactory(param -> new CredentialListCell());
        lvCredentials.setContextMenu(createCredentialContextMenu());

        vbox.getChildren().addAll(lblTitle, tfUsername, tfPassword, btnAddCredential, lvCredentials);
        return vbox;
    }

    private VBox createActionSection() {
        VBox vbox = new VBox(10);

        Label lblTitle = new Label("Discovery");
        lblTitle.getStyleClass().add("section-title");

        btnStart = new Button("Start Discovery");
        btnStart.getStyleClass().add("button-success");
        btnStart.setMaxWidth(Double.MAX_VALUE);
        btnStart.setPrefHeight(40);
        btnStart.setDisable(true);
        btnStart.setOnAction(e -> startDiscovery());

        vbox.getChildren().addAll(lblTitle, btnStart);
        updateStartButtonState();
        return vbox;
    }

    private VBox createProgressSection() {
        VBox vbox = new VBox(10);

        Label lblTitle = new Label("Progress");
        lblTitle.getStyleClass().add("section-title");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(20);

        lblProgress = new Label("Ready");
        lblProgress.getStyleClass().add("label-info");

        vbox.getChildren().addAll(lblTitle, progressBar, lblProgress);
        return vbox;
    }

    private VBox createExportSection() {
        VBox vbox = new VBox(10);

        Label lblTitle = new Label("Export");
        lblTitle.getStyleClass().add("section-title");

        btnExport = new Button("Export to Excel");
        btnExport.setMaxWidth(Double.MAX_VALUE);
        btnExport.setPrefHeight(35);
        btnExport.setDisable(true);
        btnExport.setOnAction(e -> exportToExcel());

        vbox.getChildren().addAll(lblTitle, btnExport);
        return vbox;
    }

    private VBox createRightPanel() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(15));

        Label lblTitle = new Label("Discovered Devices");
        lblTitle.getStyleClass().add("section-title");

        tvResults = new TableView<>(devices);
        tvResults.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Device, String> colIp = new TableColumn<>("IP Address");
        colIp.setCellValueFactory(new PropertyValueFactory<>("ipAddress"));

        TableColumn<Device, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));

        TableColumn<Device, String> colName = new TableColumn<>("Device Name");
        colName.setCellValueFactory(new PropertyValueFactory<>("deviceName"));

        TableColumn<Device, String> colManufacturer = new TableColumn<>("Manufacturer");
        colManufacturer.setCellValueFactory(new PropertyValueFactory<>("manufacturer"));

        TableColumn<Device, String> colStreams = new TableColumn<>("Streams");
        colStreams.setCellValueFactory(cellData -> {
            int count = cellData.getValue().getRtspStreams().size();
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(count));
        });

        TableColumn<Device, String> colError = new TableColumn<>("Error");
        colError.setCellValueFactory(new PropertyValueFactory<>("errorMessage"));

        tvResults.getColumns().addAll(colIp, colStatus, colName, colManufacturer, colStreams, colError);

        VBox.setVgrow(tvResults, Priority.ALWAYS);

        vbox.getChildren().addAll(lblTitle, tvResults);
        return vbox;
    }

    private void populateNetworkInterfaces() {
        List<NetworkInterface> interfaces = NetworkUtils.getActiveNetworkInterfaces();
        for (NetworkInterface ni : interfaces) {
            try {
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof java.net.Inet4Address) {
                        String display = ni.getDisplayName() + " - " + inetAddr.getHostAddress();
                        cbInterfaces.getItems().add(display);
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing interface", e);
            }
        }
        if (!cbInterfaces.getItems().isEmpty()) {
            cbInterfaces.getSelectionModel().selectFirst();
        }
    }

    private void updateNetworkMode() {
        cbInterfaces.setDisable(!rbInterface.isSelected());
        tfStartIP.setDisable(!rbManualRange.isSelected());
        tfEndIP.setDisable(!rbManualRange.isSelected());
        tfCIDR.setDisable(!rbCIDR.isSelected());
        updateIpCount();
        updateStartButtonState();
    }

    private void updateIpCount() {
        int count = 0;
        if (rbInterface.isSelected() && cbInterfaces.getValue() != null) {
            // Estimate /24 network
            count = 254;
        } else if (rbManualRange.isSelected()) {
            String start = tfStartIP.getText();
            String end = tfEndIP.getText();
            if (NetworkUtils.isValidIP(start) && NetworkUtils.isValidIP(end)) {
                count = NetworkUtils.countIPsInRange(start, end);
            }
        } else if (rbCIDR.isSelected()) {
            String cidr = tfCIDR.getText();
            if (NetworkUtils.isValidCIDR(cidr)) {
                count = NetworkUtils.countIPsInCIDR(cidr);
            }
        }
        lblIpCount.setText("Possible IPs: " + count);
        updateStartButtonState();
    }

    private void addCredential() {
        if (credentials.size() >= 4) {
            showAlert("Maximum Credentials", "You can only add up to 4 credential sets.", Alert.AlertType.WARNING);
            return;
        }

        String username = tfUsername.getText().trim();
        String password = tfPassword.getText();

        if (username.isEmpty()) {
            showAlert("Invalid Input", "Username cannot be empty.", Alert.AlertType.WARNING);
            return;
        }

        Credential cred = new Credential(username, password);
        credentials.add(cred);
        tfPassword.clear();

        if (credentials.size() >= 4) {
            btnAddCredential.setDisable(true);
            tfUsername.setDisable(true);
            tfPassword.setDisable(true);
        }

        updateStartButtonState();
    }

    private ContextMenu createCredentialContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> {
            Credential selected = lvCredentials.getSelectionModel().getSelectedItem();
            if (selected != null) {
                credentials.remove(selected);
                if (credentials.size() < 4) {
                    btnAddCredential.setDisable(false);
                    tfUsername.setDisable(false);
                    tfPassword.setDisable(false);
                }
                updateStartButtonState();
            }
        });
        menu.getItems().add(deleteItem);
        return menu;
    }

    private void updateStartButtonState() {
        boolean validNetwork = (rbInterface.isSelected() && cbInterfaces.getValue() != null) ||
                (rbManualRange.isSelected() && NetworkUtils.isValidIP(tfStartIP.getText()) &&
                        NetworkUtils.isValidIP(tfEndIP.getText())) ||
                (rbCIDR.isSelected() && NetworkUtils.isValidCIDR(tfCIDR.getText()));

        boolean hasCredentials = !credentials.isEmpty();

        btnStart.setDisable(!validNetwork || !hasCredentials || discoveryInProgress);
    }

    private void startDiscovery() {
        // Lock UI
        discoveryInProgress = true;
        disableInputs();
        devices.clear();
        progressBar.setProgress(0);
        lblProgress.setText("Starting discovery...");

        executorService.submit(() -> {
            try {
                runDiscovery();
            } catch (Exception e) {
                logger.error("Discovery error", e);
                Platform.runLater(() -> {
                    showAlert("Discovery Error", "An error occurred: " + e.getMessage(), Alert.AlertType.ERROR);
                    enableInputs();
                });
            }
        });
    }

    private void runDiscovery() {
        // Phase 1: WS-Discovery
        Platform.runLater(() -> lblProgress.setText("Running WS-Discovery..."));
        List<Device> wsDevices = networkScanner.performWsDiscovery();

        // Ask for port scan
        boolean doPortScan = false;
        if (wsDevices.isEmpty()) {
            doPortScan = true;
            Platform.runLater(() -> lblProgress.setText("No ONVIF devices found. Running port scan..."));
        } else {
            // Ask user
            final boolean[] result = {false};
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("WS-Discovery Complete");
                alert.setHeaderText("Found " + wsDevices.size() + " ONVIF device(s).");
                alert.setContentText("Do you want to perform a deep port scan?");
                Optional<ButtonType> response = alert.showAndWait();
                synchronized (result) {
                    result[0] = response.isPresent() && response.get() == ButtonType.OK;
                    result.notifyAll();
                }
            });

            synchronized (result) {
                try {
                    result.wait();
                    doPortScan = result[0];
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }

        List<Device> finalDevices = new ArrayList<>(wsDevices);

        // Phase 2: Port scan
        if (doPortScan) {
            List<String> ips = getIPList();
            List<Device> portScanDevices = networkScanner.performPortScan(ips, (current, total) -> {
                double progress = (double) current / total * 0.3; // 30% of total
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    lblProgress.setText("Port scanning... " + current + " of " + total);
                });
            });

            finalDevices = networkScanner.mergeDeviceLists(wsDevices, portScanDevices);
        }

        // Add devices to table
        Platform.runLater(() -> devices.addAll(finalDevices));

        // Phase 3: Authentication & Stream Discovery
        int total = finalDevices.size();
        int[] current = {0};

        for (Device device : finalDevices) {
            authenticateAndDiscoverStreams(device);
            current[0]++;
            final int c = current[0];
            Platform.runLater(() -> {
                double progress = 0.3 + ((double) c / total * 0.5);
                progressBar.setProgress(progress);
                lblProgress.setText("Processing " + c + " of " + total + " devices...");
                tvResults.refresh();
            });
        }

        // Phase 4: Stream Analysis
        current[0] = 0;
        for (Device device : finalDevices) {
            if (!device.getRtspStreams().isEmpty()) {
                streamAnalyzer.analyzeDevice(device);
            }
            current[0]++;
            final int c = current[0];
            Platform.runLater(() -> {
                double progress = 0.8 + ((double) c / total * 0.2);
                progressBar.setProgress(progress);
                lblProgress.setText("Analyzing streams... " + c + " of " + total);
                tvResults.refresh();
            });
        }

        // Complete
        Platform.runLater(() -> {
            progressBar.setProgress(1.0);
            lblProgress.setText("Discovery complete! Found " + finalDevices.size() + " devices.");
            enableInputs();
            btnExport.setDisable(finalDevices.isEmpty());
        });
    }

    private void authenticateAndDiscoverStreams(Device device) {
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        Platform.runLater(() -> tvResults.refresh());

        boolean authenticated = false;

        // Try ONVIF first
        if (device.getOnvifServiceUrl() != null) {
            for (Credential cred : credentials) {
                if (onvifService.getDeviceInformation(device, cred.getUsername(), cred.getPassword())) {
                    authenticated = true;
                    List<String> videoSources = onvifService.getVideoSources(device);
                    if (videoSources.size() > 1) {
                        device.setNvrDvr(true);
                    }
                    break;
                }
            }
        }

        // Try RTSP
        if (!authenticated || device.getRtspStreams().isEmpty()) {
            for (Credential cred : credentials) {
                List<RTSPStream> streams = rtspService.discoverStreams(device, cred.getUsername(), cred.getPassword());
                if (!streams.isEmpty()) {
                    device.setUsername(cred.getUsername());
                    device.setPassword(cred.getPassword());
                    device.getRtspStreams().addAll(streams);
                    authenticated = true;
                    break;
                }
            }
        }

        // Check for NVR and iterate channels
        if (device.isNvrDvr() && authenticated) {
            List<RTSPStream> nvrStreams = rtspService.iterateNvrChannels(
                    device, device.getUsername(), device.getPassword(), 64);
            device.getRtspStreams().addAll(nvrStreams);
        }

        if (authenticated) {
            device.setStatus(Device.DeviceStatus.COMPLETED);
        } else {
            device.setStatus(Device.DeviceStatus.AUTH_FAILED);
            device.setAuthFailed(true);
            device.setErrorMessage("Authentication failed with all credentials");
        }
    }

    private List<String> getIPList() {
        List<String> ips = new ArrayList<>();
        try {
            if (rbCIDR.isSelected()) {
                ips = NetworkUtils.parseCIDR(tfCIDR.getText());
            } else if (rbManualRange.isSelected()) {
                ips = NetworkUtils.parseIPRange(tfStartIP.getText(), tfEndIP.getText());
            } else if (rbInterface.isSelected()) {
                // Extract CIDR from interface - simplified to /24
                String selected = cbInterfaces.getValue();
                if (selected != null) {
                    String[] parts = selected.split(" - ");
                    if (parts.length == 2) {
                        String ip = parts[1];
                        String[] octets = ip.split("\\.");
                        String network = octets[0] + "." + octets[1] + "." + octets[2] + ".0/24";
                        ips = NetworkUtils.parseCIDR(network);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error generating IP list", e);
        }
        return ips;
    }

    private void exportToExcel() {
        // Step 1: Get Site ID
        TextInputDialog siteDialog = new TextInputDialog();
        siteDialog.setTitle("Export Report");
        siteDialog.setHeaderText("Enter Report Details");
        siteDialog.setContentText("Site ID (required):");

        Optional<String> siteId = siteDialog.showAndWait();
        if (!siteId.isPresent() || siteId.get().trim().isEmpty()) {
            return;
        }

        // Step 2: Auto-generate protection password (numeric only, no delimiters)
        // Format: {DeviceCount}{YYYYMMDD}{FixedCode}
        // Example: 25 devices on 2026-01-07 → "252026010748275"
        int deviceCount = devices.size();
        String dateStr = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        String fixedCode = "482753";
        String generatedPassword = "" + deviceCount + dateStr + fixedCode;

        logger.info("Auto-generated protection password for {} devices on {}", deviceCount, dateStr);

        // Step 3: Choose file location with default to Documents folder
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Excel Report");
        fileChooser.setInitialFileName("CCTV_Audit_" + siteId.get() + ".xlsx");

        // Set initial directory to user's Documents folder
        String userHome = System.getProperty("user.home");
        File documentsDir = new File(userHome, "Documents");
        if (documentsDir.exists() && documentsDir.isDirectory()) {
            fileChooser.setInitialDirectory(documentsDir);
        } else {
            // Fallback to user home if Documents doesn't exist
            fileChooser.setInitialDirectory(new File(userHome));
        }

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = fileChooser.showSaveDialog(primaryStage);
        if (file != null) {
            try {
                // Export with auto-generated password protection
                excelExporter.exportToExcel(new ArrayList<>(devices), siteId.get(), null, null, file, generatedPassword);

                // Show success with password
                showAlert("Export Complete",
                    "Report exported successfully to:\n" + file.getAbsolutePath() +
                    "\n\n🔒 WORKSHEET PROTECTED" +
                    "\nPassword: " + generatedPassword +
                    "\n\nThis password has been auto-generated." +
                    "\nWrite it down if you need to access the report later.",
                    Alert.AlertType.INFORMATION);

                logger.info("Excel export completed with password protection");
            } catch (Exception e) {
                logger.error("Export error", e);
                showAlert("Export Error", "Failed to export: " + e.getMessage(), Alert.AlertType.ERROR);
            }
        }
    }

    private void showHelpManual() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("User Manual");
        alert.setHeaderText("CCTV Discovery Tool - Quick Guide");
        alert.setContentText(
                "1. Select your network range\n" +
                        "2. Add at least one credential (max 4)\n" +
                        "3. Click 'Start Discovery'\n" +
                        "4. Wait for the scan to complete\n" +
                        "5. Export results to Excel\n\n" +
                        "For detailed documentation, refer to the manual.");
        alert.showAndWait();
    }

    private void disableInputs() {
        rbInterface.setDisable(true);
        rbManualRange.setDisable(true);
        rbCIDR.setDisable(true);
        cbInterfaces.setDisable(true);
        tfStartIP.setDisable(true);
        tfEndIP.setDisable(true);
        tfCIDR.setDisable(true);
        tfUsername.setDisable(true);
        tfPassword.setDisable(true);
        btnAddCredential.setDisable(true);
        btnStart.setDisable(true);
    }

    private void enableInputs() {
        discoveryInProgress = false;
        updateNetworkMode();
        if (credentials.size() < 4) {
            tfUsername.setDisable(false);
            tfPassword.setDisable(false);
            btnAddCredential.setDisable(false);
        }
        updateStartButtonState();
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    public void shutdown() {
        if (networkScanner != null) {
            networkScanner.shutdown();
        }
        if (streamAnalyzer != null) {
            streamAnalyzer.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    // Custom ListCell for credentials
    private static class CredentialListCell extends ListCell<Credential> {
        @Override
        protected void updateItem(Credential item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.toDisplayString());
            }
        }
    }
}
