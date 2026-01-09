package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private final AppConfig config = AppConfig.getInstance();

    private Stage primaryStage;
    private Scene scene;

    // Network selection - Simple mode
    private RadioButton rbInterface;
    private RadioButton rbManualRange;
    private RadioButton rbCIDR;
    private ComboBox<String> cbInterfaces;
    private TextField tfStartIP;
    private TextField tfEndIP;
    private TextField tfCIDR;
    private Label lblIpCount;

    // Network selection - Advanced mode
    private CheckBox cbAdvancedMode;
    private VBox simpleNetworkBox;
    private VBox advancedNetworkBox;
    private ListView<NetworkInterfaceItem> lvNetworkInterfaces;
    private ObservableList<NetworkInterfaceItem> networkInterfaces;
    private TableView<IpRangeItem> tvIpRanges;
    private ObservableList<IpRangeItem> ipRanges;
    private TableView<CidrItem> tvCidrs;
    private ObservableList<CidrItem> cidrs;
    private Label lblAdvancedIpCount;

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
        this.networkInterfaces = FXCollections.observableArrayList();
        this.ipRanges = FXCollections.observableArrayList();
        this.cidrs = FXCollections.observableArrayList();

        // Initialize services
        this.networkScanner = new NetworkScanner();
        this.onvifService = new OnvifService();
        this.rtspService = new RtspService();
        this.streamAnalyzer = new StreamAnalyzer();
        this.excelExporter = new ExcelExporter();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public Scene createScene() {
        // Create full-width header at the top
        HBox header = createHeaderPanel();

        // Create split pane for left and right panels
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

        // Main layout with header at top and split pane below
        BorderPane mainLayout = new BorderPane();
        mainLayout.setTop(header);
        mainLayout.setCenter(splitPane);

        scene = new Scene(mainLayout, 1400, 800);

        // Load CSS with null check to prevent startup crashes
        try {
            java.net.URL cssResource = getClass().getResource("/css/app.css");
            if (cssResource != null) {
                scene.getStylesheets().add(cssResource.toExternalForm());
                logger.info("CSS loaded successfully");
            } else {
                logger.warn("CSS file not found: /css/app.css - using default styling");
            }
        } catch (Exception e) {
            logger.error("Failed to load CSS", e);
        }

        return scene;
    }

    private HBox createHeaderPanel() {
        // Header Panel - Full width spanning entire application
        Label title = new Label("CCTV Discovery Tool");
        title.getStyleClass().add("header-title");

        Button btnSettings = new Button("Settings");
        btnSettings.setOnAction(e -> showSettings());
        btnSettings.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold;");

        Button btnHelp = new Button("Help");
        btnHelp.setOnAction(e -> showHelpManual());
        btnHelp.setStyle("-fx-background-color: #17a2b8; -fx-text-fill: white; -fx-font-weight: bold;");

        // Spacer to push buttons to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Right side button container
        HBox buttonBox = new HBox(10, btnSettings, btnHelp);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        // Main header container
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 15, 10, 15));
        header.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
        header.getChildren().addAll(title, spacer, buttonBox);

        return header;
    }

    private VBox createLeftPanel() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(10));
        vbox.getStyleClass().add("left-panel");

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

        // Add all sections without separators
        vbox.getChildren().addAll(
                networkSection,
                credentialSection,
                actionSection,
                progressSection,
                exportSection
        );

        return vbox;
    }

    private VBox createNetworkSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("Network Selection");
        lblTitle.getStyleClass().add("section-title");

        // Advanced mode toggle
        cbAdvancedMode = new CheckBox("Advanced Mode (Multiple Networks)");
        cbAdvancedMode.setOnAction(e -> toggleNetworkMode());

        // Simple network box
        simpleNetworkBox = createSimpleNetworkBox();

        // Advanced network box
        advancedNetworkBox = createAdvancedNetworkBox();
        advancedNetworkBox.setVisible(false);
        advancedNetworkBox.setManaged(false);

        vbox.getChildren().addAll(lblTitle, cbAdvancedMode, simpleNetworkBox, advancedNetworkBox);

        updateIpCount();
        return vbox;
    }

    private VBox createSimpleNetworkBox() {
        VBox vbox = new VBox(6);

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

        // Manual range - side by side
        tfStartIP = new TextField();
        tfStartIP.setPromptText("Start IP (e.g., 192.168.1.1)");
        tfStartIP.setDisable(true);
        addIPValidation(tfStartIP);

        tfEndIP = new TextField();
        tfEndIP.setPromptText("End IP (e.g., 192.168.1.254)");
        tfEndIP.setDisable(true);
        addIPValidation(tfEndIP);

        HBox ipRangeBox = new HBox(8, tfStartIP, tfEndIP);
        HBox.setHgrow(tfStartIP, Priority.ALWAYS);
        HBox.setHgrow(tfEndIP, Priority.ALWAYS);

        // CIDR
        tfCIDR = new TextField();
        tfCIDR.setPromptText("CIDR (e.g., 192.168.1.0/24)");
        tfCIDR.setDisable(true);

        // IP count label - center aligned and italic
        lblIpCount = new Label("Possible IPs: 0");
        lblIpCount.getStyleClass().add("label-info");
        lblIpCount.setAlignment(Pos.CENTER);
        lblIpCount.setMaxWidth(Double.MAX_VALUE);
        lblIpCount.setStyle("-fx-font-style: italic;");

        // Event handlers
        rbInterface.setOnAction(e -> updateNetworkMode());
        rbManualRange.setOnAction(e -> updateNetworkMode());
        rbCIDR.setOnAction(e -> updateNetworkMode());
        cbInterfaces.setOnAction(e -> updateIpCount());
        tfStartIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfEndIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfCIDR.textProperty().addListener((obs, old, val) -> updateIpCount());

        vbox.getChildren().addAll(
                rbInterface, cbInterfaces,
                rbManualRange, ipRangeBox,
                rbCIDR, tfCIDR,
                lblIpCount
        );

        return vbox;
    }

    private VBox createAdvancedNetworkBox() {
        VBox vbox = new VBox(8);

        // Section 1: Network Interfaces
        Label lblInterfaces = new Label("Network Interfaces:");
        lblInterfaces.setStyle("-fx-font-weight: bold;");

        lvNetworkInterfaces = new ListView<>(networkInterfaces);
        lvNetworkInterfaces.setPrefHeight(100);
        lvNetworkInterfaces.setCellFactory(param -> new ListCell<NetworkInterfaceItem>() {
            @Override
            protected void updateItem(NetworkInterfaceItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    CheckBox checkBox = new CheckBox(item.toString());
                    checkBox.setSelected(item.isSelected());
                    checkBox.setOnAction(e -> {
                        item.setSelected(checkBox.isSelected());
                        updateAdvancedIpCount();
                    });
                    setGraphic(checkBox);
                }
            }
        });
        populateAdvancedNetworkInterfaces();

        // Section 2: IP Ranges
        Label lblRanges = new Label("IP Ranges:");
        lblRanges.setStyle("-fx-font-weight: bold;");

        tvIpRanges = new TableView<>(ipRanges);
        tvIpRanges.setPrefHeight(100);
        tvIpRanges.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<IpRangeItem, String> colStartIp = new TableColumn<>("Start IP");
        colStartIp.setCellValueFactory(new PropertyValueFactory<>("startIp"));
        colStartIp.setCellFactory(col -> new TableCell<IpRangeItem, String>() {
            private final TextField textField = new TextField();

            {
                textField.setOnAction(e -> commitEdit(textField.getText()));
                textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                    if (!isNowFocused) {
                        commitEdit(textField.getText());
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    textField.setText(item);
                    setGraphic(textField);
                }
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                IpRangeItem range = getTableView().getItems().get(getIndex());
                range.setStartIp(newValue);
                updateAdvancedIpCount();
            }
        });

        TableColumn<IpRangeItem, String> colEndIp = new TableColumn<>("End IP");
        colEndIp.setCellValueFactory(new PropertyValueFactory<>("endIp"));
        colEndIp.setCellFactory(col -> new TableCell<IpRangeItem, String>() {
            private final TextField textField = new TextField();

            {
                textField.setOnAction(e -> commitEdit(textField.getText()));
                textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                    if (!isNowFocused) {
                        commitEdit(textField.getText());
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    textField.setText(item);
                    setGraphic(textField);
                }
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                IpRangeItem range = getTableView().getItems().get(getIndex());
                range.setEndIp(newValue);
                updateAdvancedIpCount();
            }
        });

        tvIpRanges.getColumns().addAll(colStartIp, colEndIp);

        Button btnAddRange = new Button("Add IP Range");
        btnAddRange.setOnAction(e -> addIpRange());
        Button btnRemoveRange = new Button("Remove Selected");
        btnRemoveRange.setOnAction(e -> {
            IpRangeItem selected = tvIpRanges.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ipRanges.remove(selected);
                updateAdvancedIpCount();
            }
        });
        HBox rangeButtons = new HBox(8, btnAddRange, btnRemoveRange);

        // Section 3: CIDR Notations
        Label lblCidrs = new Label("CIDR Notations:");
        lblCidrs.setStyle("-fx-font-weight: bold;");

        tvCidrs = new TableView<>(cidrs);
        tvCidrs.setPrefHeight(100);
        tvCidrs.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<CidrItem, String> colCidr = new TableColumn<>("CIDR");
        colCidr.setCellValueFactory(new PropertyValueFactory<>("cidr"));
        colCidr.setCellFactory(col -> new TableCell<CidrItem, String>() {
            private final TextField textField = new TextField();

            {
                textField.setOnAction(e -> commitEdit(textField.getText()));
                textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                    if (!isNowFocused) {
                        commitEdit(textField.getText());
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    textField.setText(item);
                    setGraphic(textField);
                }
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                CidrItem cidr = getTableView().getItems().get(getIndex());
                cidr.setCidr(newValue);
                updateAdvancedIpCount();
            }
        });

        tvCidrs.getColumns().add(colCidr);

        Button btnAddCidr = new Button("Add CIDR");
        btnAddCidr.setOnAction(e -> addCidr());
        Button btnRemoveCidr = new Button("Remove Selected");
        btnRemoveCidr.setOnAction(e -> {
            CidrItem selected = tvCidrs.getSelectionModel().getSelectedItem();
            if (selected != null) {
                cidrs.remove(selected);
                updateAdvancedIpCount();
            }
        });
        HBox cidrButtons = new HBox(8, btnAddCidr, btnRemoveCidr);

        // IP count label
        lblAdvancedIpCount = new Label("Total Possible IPs: 0");
        lblAdvancedIpCount.getStyleClass().add("label-info");
        lblAdvancedIpCount.setAlignment(Pos.CENTER);
        lblAdvancedIpCount.setMaxWidth(Double.MAX_VALUE);
        lblAdvancedIpCount.setStyle("-fx-font-style: italic;");

        vbox.getChildren().addAll(
                lblInterfaces, lvNetworkInterfaces,
                lblRanges, tvIpRanges, rangeButtons,
                lblCidrs, tvCidrs, cidrButtons,
                lblAdvancedIpCount
        );

        return vbox;
    }

    private VBox createCredentialSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("Credentials (Max 4)");
        lblTitle.getStyleClass().add("section-title");

        // Username and Password side by side
        tfUsername = new TextField("admin");
        tfUsername.setPromptText("Username");

        tfPassword = new TextField();
        tfPassword.setPromptText("Password");

        HBox credentialBox = new HBox(8, tfUsername, tfPassword);
        HBox.setHgrow(tfUsername, Priority.ALWAYS);
        HBox.setHgrow(tfPassword, Priority.ALWAYS);

        btnAddCredential = new Button("Add Credential");
        btnAddCredential.setMaxWidth(Double.MAX_VALUE);
        btnAddCredential.setOnAction(e -> addCredential());

        lvCredentials = new ListView<>(credentials);
        lvCredentials.setPrefHeight(100);
        lvCredentials.setCellFactory(param -> new CredentialListCell());
        lvCredentials.setContextMenu(createCredentialContextMenu());

        vbox.getChildren().addAll(lblTitle, credentialBox, btnAddCredential, lvCredentials);
        return vbox;
    }

    private VBox createActionSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("Discovery");
        lblTitle.getStyleClass().add("section-title");

        btnStart = new Button("Start Discovery");
        btnStart.getStyleClass().add("button-success");
        btnStart.setMaxWidth(Double.MAX_VALUE);
        btnStart.setPrefHeight(35);
        btnStart.setDisable(true);
        btnStart.setOnAction(e -> startDiscovery());

        vbox.getChildren().addAll(lblTitle, btnStart);
        updateStartButtonState();
        return vbox;
    }

    private VBox createProgressSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("Progress");
        lblTitle.getStyleClass().add("section-title");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(18);

        // Progress label - center aligned and italic
        lblProgress = new Label("Ready");
        lblProgress.getStyleClass().add("label-info");
        lblProgress.setAlignment(Pos.CENTER);
        lblProgress.setMaxWidth(Double.MAX_VALUE);
        lblProgress.setStyle("-fx-font-style: italic;");

        vbox.getChildren().addAll(lblTitle, progressBar, lblProgress);
        return vbox;
    }

    private VBox createExportSection() {
        VBox vbox = new VBox(6);

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

        // Add context menu for device retry
        ContextMenu deviceContextMenu = new ContextMenu();

        MenuItem retryMenuItem = new MenuItem("Retry with Different Credential");
        retryMenuItem.setOnAction(e -> {
            Device selected = tvResults.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showRetryCredentialDialog(selected);
            }
        });

        deviceContextMenu.getItems().add(retryMenuItem);
        tvResults.setContextMenu(deviceContextMenu);

        // Only enable if device is selected
        tvResults.setOnContextMenuRequested(event -> {
            Device selected = tvResults.getSelectionModel().getSelectedItem();
            retryMenuItem.setDisable(selected == null);
        });

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

    private void populateAdvancedNetworkInterfaces() {
        List<NetworkInterface> interfaces = NetworkUtils.getActiveNetworkInterfaces();
        for (NetworkInterface ni : interfaces) {
            try {
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof java.net.Inet4Address) {
                        String display = ni.getDisplayName();
                        String ip = inetAddr.getHostAddress();
                        networkInterfaces.add(new NetworkInterfaceItem(display, ip, false));
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing interface", e);
            }
        }
    }

    private void toggleNetworkMode() {
        boolean advanced = cbAdvancedMode.isSelected();
        simpleNetworkBox.setVisible(!advanced);
        simpleNetworkBox.setManaged(!advanced);
        advancedNetworkBox.setVisible(advanced);
        advancedNetworkBox.setManaged(advanced);

        if (advanced) {
            updateAdvancedIpCount();
        } else {
            updateIpCount();
        }
        updateStartButtonState();
    }

    private void addIpRange() {
        ipRanges.add(new IpRangeItem("192.168.1.1", "192.168.1.254"));
        updateAdvancedIpCount();
    }

    private void addCidr() {
        cidrs.add(new CidrItem("192.168.1.0/24"));
        updateAdvancedIpCount();
    }

    private void updateAdvancedIpCount() {
        int count = 0;

        // Count selected network interfaces
        for (NetworkInterfaceItem item : networkInterfaces) {
            if (item.isSelected()) {
                count += 254; // Assume /24 network
            }
        }

        // Count IP ranges
        for (IpRangeItem range : ipRanges) {
            if (NetworkUtils.isValidIP(range.getStartIp()) && NetworkUtils.isValidIP(range.getEndIp())) {
                count += NetworkUtils.countIPsInRange(range.getStartIp(), range.getEndIp());
            }
        }

        // Count CIDRs
        for (CidrItem cidr : cidrs) {
            if (NetworkUtils.isValidCIDR(cidr.getCidr())) {
                count += NetworkUtils.countIPsInCIDR(cidr.getCidr());
            }
        }

        lblAdvancedIpCount.setText("Total Possible IPs: " + count);
        updateStartButtonState();
    }

    private void updateNetworkMode() {
        cbInterfaces.setDisable(!rbInterface.isSelected());
        tfStartIP.setDisable(!rbManualRange.isSelected());
        tfEndIP.setDisable(!rbManualRange.isSelected());
        tfCIDR.setDisable(!rbCIDR.isSelected());
        updateIpCount();
        updateStartButtonState();
    }

    /**
     * Adds real-time IP validation to a text field.
     * Shows red border and background when IP is invalid.
     */
    private void addIPValidation(TextField textField) {
        textField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.trim().isEmpty()) {
                // Empty field - remove styling
                textField.setStyle("");
            } else if (NetworkUtils.isValidIP(newValue.trim())) {
                // Valid IP - remove error styling
                textField.setStyle("");
            } else {
                // Invalid IP - show red styling
                textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
            }
        });
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

        MenuItem editItem = new MenuItem("Edit");
        editItem.setOnAction(e -> {
            Credential selected = lvCredentials.getSelectionModel().getSelectedItem();
            if (selected != null) {
                // Populate fields with selected credential
                tfUsername.setText(selected.getUsername());
                tfPassword.setText(selected.getPassword());

                // Remove from list temporarily for editing
                credentials.remove(selected);

                // Enable input fields
                btnAddCredential.setDisable(false);
                tfUsername.setDisable(false);
                tfPassword.setDisable(false);

                updateStartButtonState();

                logger.info("Editing credential: {}", selected.getUsername());
            }
        });

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
                logger.info("Deleted credential: {}", selected.getUsername());
            }
        });

        menu.getItems().addAll(editItem, deleteItem);
        return menu;
    }

    private void updateStartButtonState() {
        // Skip if button hasn't been created yet (during initialization)
        if (btnStart == null) {
            return;
        }

        boolean validNetwork;
        if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
            // Advanced mode: require at least one source with valid data
            boolean hasSelectedInterface = networkInterfaces.stream().anyMatch(NetworkInterfaceItem::isSelected);
            boolean hasValidRange = ipRanges.stream().anyMatch(range ->
                    NetworkUtils.isValidIP(range.getStartIp()) && NetworkUtils.isValidIP(range.getEndIp()));
            boolean hasValidCidr = cidrs.stream().anyMatch(cidr ->
                    NetworkUtils.isValidCIDR(cidr.getCidr()));

            validNetwork = hasSelectedInterface || hasValidRange || hasValidCidr;
        } else {
            // Simple mode: original validation
            validNetwork = (rbInterface.isSelected() && cbInterfaces.getValue() != null) ||
                    (rbManualRange.isSelected() && NetworkUtils.isValidIP(tfStartIP.getText()) &&
                            NetworkUtils.isValidIP(tfEndIP.getText())) ||
                    (rbCIDR.isSelected() && NetworkUtils.isValidCIDR(tfCIDR.getText()));
        }

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
            logger.info("WS-Discovery found 0 devices (IGMP may be blocked), automatically starting port scan");
            Platform.runLater(() -> lblProgress.setText("No ONVIF devices found via multicast. Running port scan..."));
        } else {
            // Ask user
            final boolean[] result = {false};
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("WS-Discovery Complete");
                alert.setHeaderText("Found " + wsDevices.size() + " device(s) via ONVIF WS-Discovery");
                alert.setContentText(
                        "Expected more devices?\n\n" +
                        "Port scanning can find:\n" +
                        "• Devices without ONVIF support\n" +
                        "• Devices where IGMP/multicast is blocked\n" +
                        "• Devices on non-standard ports\n\n" +
                        "Do you want to perform port scan?"
                );
                Optional<ButtonType> response = alert.showAndWait();
                synchronized (result) {
                    result[0] = response.isPresent() && response.get() == ButtonType.OK;
                    logger.info("User chose {} for port scan", result[0] ? "YES" : "NO");
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

        // Phase 2: Port scan
        final List<Device> finalDevices;
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
        } else {
            finalDevices = new ArrayList<>(wsDevices);
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
        logger.info("=== Starting authentication for device: {} ===", device.getIpAddress());
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        Platform.runLater(() -> tvResults.refresh());

        // PRIORITY 1: Try ONVIF Discovery
        boolean onvifSuccess = attemptOnvifAuthentication(device);

        // PRIORITY 2: Try RTSP URL Guessing (if ONVIF failed or no streams found)
        if (!onvifSuccess || device.getRtspStreams().isEmpty()) {
            logger.info("Falling back to RTSP URL guessing for {}", device.getIpAddress());
            attemptRtspAuthentication(device);
        }

        // PRIORITY 3: NVR/DVR Channel Iteration (if applicable and authenticated)
        if (device.isNvrDvr() && device.getUsername() != null) {
            logger.info("Device {} detected as NVR/DVR, iterating channels", device.getIpAddress());
            List<RTSPStream> nvrStreams = rtspService.iterateNvrChannels(
                    device, device.getUsername(), device.getPassword(), 64);
            device.getRtspStreams().addAll(nvrStreams);
            logger.info("Found {} additional NVR/DVR streams", nvrStreams.size());
        }

        // Set final status
        if (device.getUsername() != null && !device.getRtspStreams().isEmpty()) {
            device.setStatus(Device.DeviceStatus.COMPLETED);
            logger.info("=== Device {} authentication COMPLETED - {} streams found ===",
                    device.getIpAddress(), device.getRtspStreams().size());
        } else {
            device.setStatus(Device.DeviceStatus.AUTH_FAILED);
            device.setAuthFailed(true);
            device.setErrorMessage("Authentication failed with all credentials");
            logger.warn("=== Device {} authentication FAILED ===", device.getIpAddress());
        }
    }

    /**
     * Attempt ONVIF authentication with all credentials.
     * Tries service URL from WS-Discovery first, then constructs URLs from detected ports.
     */
    private boolean attemptOnvifAuthentication(Device device) {
        logger.info("--- Attempting ONVIF authentication for {} ---", device.getIpAddress());

        // Case 1: Service URL from WS-Discovery
        if (device.getOnvifServiceUrl() != null) {
            logger.info("Using ONVIF service URL from WS-Discovery: {}", device.getOnvifServiceUrl());

            for (Credential cred : credentials) {
                logger.debug("Trying ONVIF with credential: {}", cred.getUsername());

                if (onvifService.getDeviceInformation(device, cred.getUsername(), cred.getPassword())) {
                    device.setUsername(cred.getUsername());
                    device.setPassword(cred.getPassword());
                    logger.info("ONVIF authentication successful with user: {}", cred.getUsername());

                    List<String> videoSources = onvifService.getVideoSources(device);
                    if (videoSources.size() > 1) {
                        device.setNvrDvr(true);
                        logger.info("Multiple video sources detected - marking as NVR/DVR");
                    }
                    return true;
                }
            }
            logger.warn("All credentials failed for WS-Discovery ONVIF URL");
        }

        // Case 2: Construct URLs from detected ONVIF ports
        if (!device.getOpenOnvifPorts().isEmpty()) {
            logger.info("No service URL or WS-Discovery auth failed. Constructing ONVIF URLs from {} detected ports",
                    device.getOpenOnvifPorts().size());

            for (int port : device.getOpenOnvifPorts()) {
                logger.info("Trying ONVIF on port: {}", port);

                for (Credential cred : credentials) {
                    logger.debug("Trying constructed ONVIF URL with credential: {}", cred.getUsername());

                    if (onvifService.discoverDeviceByPort(device, port, cred.getUsername(), cred.getPassword())) {
                        device.setUsername(cred.getUsername());
                        device.setPassword(cred.getPassword());
                        logger.info("ONVIF authentication successful via constructed URL on port {} with user: {}",
                                port, cred.getUsername());

                        List<String> videoSources = onvifService.getVideoSources(device);
                        if (videoSources.size() > 1) {
                            device.setNvrDvr(true);
                            logger.info("Multiple video sources detected - marking as NVR/DVR");
                        }
                        return true;
                    }
                }
            }
            logger.warn("All ONVIF port/credential combinations failed");
        } else {
            logger.info("No ONVIF ports detected for {}, skipping ONVIF", device.getIpAddress());
        }

        return false;
    }

    /**
     * Attempt RTSP URL guessing with all credentials.
     */
    private boolean attemptRtspAuthentication(Device device) {
        logger.info("--- Attempting RTSP URL guessing for {} ---", device.getIpAddress());

        if (device.getOpenRtspPorts().isEmpty()) {
            logger.warn("No RTSP ports detected for {}, using default 554", device.getIpAddress());
        }

        for (Credential cred : credentials) {
            logger.debug("Trying RTSP discovery with credential: {}", cred.getUsername());

            List<RTSPStream> streams = rtspService.discoverStreams(device, cred.getUsername(), cred.getPassword());
            if (!streams.isEmpty()) {
                device.setUsername(cred.getUsername());
                device.setPassword(cred.getPassword());
                device.getRtspStreams().addAll(streams);
                logger.info("RTSP discovery successful with user: {} - found {} streams",
                        cred.getUsername(), streams.size());
                return true;
            }
        }

        logger.warn("All RTSP credential combinations failed");
        return false;
    }

    /**
     * Show dialog to retry authentication with a different credential.
     */
    private void showRetryCredentialDialog(Device device) {
        logger.info("User requested retry for device: {}", device.getIpAddress());

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Retry Authentication");
        dialog.setHeaderText("Retry authentication for " + device.getIpAddress());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField tfRetryUsername = new TextField();
        tfRetryUsername.setPromptText("Username");

        TextField tfRetryPassword = new TextField();
        tfRetryPassword.setPromptText("Password");

        grid.add(new Label("Username:"), 0, 0);
        grid.add(tfRetryUsername, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(tfRetryPassword, 1, 1);

        dialog.getDialogPane().setContent(grid);

        ButtonType retryButton = new ButtonType("Retry", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(retryButton, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == retryButton) {
            String username = tfRetryUsername.getText().trim();
            String password = tfRetryPassword.getText().trim();

            if (!username.isEmpty() && !password.isEmpty()) {
                // Add to credentials list if not already present
                Credential newCred = new Credential(username, password);
                boolean exists = credentials.stream()
                        .anyMatch(c -> c.getUsername().equals(username) && c.getPassword().equals(password));

                if (!exists) {
                    credentials.add(newCred);
                    logger.info("Added new credential to list: {}", username);
                }

                // Retry authentication in background
                executorService.submit(() -> {
                    logger.info("=== Starting RETRY authentication for device: {} ===", device.getIpAddress());

                    // Reset device state
                    device.getRtspStreams().clear();
                    device.setUsername(null);
                    device.setPassword(null);
                    device.setAuthFailed(false);
                    device.setErrorMessage(null);

                    Platform.runLater(() -> {
                        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
                        tvResults.refresh();
                    });

                    // Run authentication again
                    authenticateAndDiscoverStreams(device);

                    Platform.runLater(() -> tvResults.refresh());
                });

                showAlert("Retry Started",
                        "Retrying authentication for " + device.getIpAddress() + " with new credential.",
                        Alert.AlertType.INFORMATION);
            } else {
                showAlert("Invalid Input", "Please enter both username and password.", Alert.AlertType.WARNING);
            }
        }
    }

    private List<String> getIPList() {
        List<String> ips = new ArrayList<>();
        try {
            if (cbAdvancedMode.isSelected()) {
                // Advanced mode: combine all sources

                // Add IPs from selected network interfaces
                for (NetworkInterfaceItem item : networkInterfaces) {
                    if (item.isSelected()) {
                        String ip = item.getIpAddress();
                        String[] octets = ip.split("\\.");
                        if (octets.length == 4) {
                            String network = octets[0] + "." + octets[1] + "." + octets[2] + ".0/24";
                            ips.addAll(NetworkUtils.parseCIDR(network));
                        }
                    }
                }

                // Add IPs from IP ranges
                for (IpRangeItem range : ipRanges) {
                    if (NetworkUtils.isValidIP(range.getStartIp()) && NetworkUtils.isValidIP(range.getEndIp())) {
                        ips.addAll(NetworkUtils.parseIPRange(range.getStartIp(), range.getEndIp()));
                    }
                }

                // Add IPs from CIDRs
                for (CidrItem cidr : cidrs) {
                    if (NetworkUtils.isValidCIDR(cidr.getCidr())) {
                        ips.addAll(NetworkUtils.parseCIDR(cidr.getCidr()));
                    }
                }

                logger.info("Advanced mode: generated {} IPs from {} interfaces, {} ranges, {} CIDRs",
                        ips.size(),
                        networkInterfaces.stream().filter(NetworkInterfaceItem::isSelected).count(),
                        ipRanges.size(),
                        cidrs.size());

            } else {
                // Simple mode: single source
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
        String generatedPassword = null;
        if (config.isExcelPasswordEnabled()) {
            int deviceCount = devices.size();
            String dateStr = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            String fixedCode = config.getExcelPasswordFixedCode();
            generatedPassword = "" + deviceCount + dateStr + fixedCode;
            // Note: Password is NOT logged or displayed to prevent user tampering
        }

        // Step 3: Choose file location with default from config
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Excel Report");
        fileChooser.setInitialFileName("CCTV_Audit_" + siteId.get() + ".xlsx");

        // Set initial directory from config
        String exportDir = config.getExportDefaultDirectory();
        File initialDir = new File(exportDir);
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        } else {
            // Fallback to user home if configured directory doesn't exist
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));

        File file = fileChooser.showSaveDialog(primaryStage);
        if (file != null) {
            try {
                // Export with auto-generated password protection
                excelExporter.exportToExcel(new ArrayList<>(devices), siteId.get(), null, null, file, generatedPassword);

                // Show success WITHOUT password (authority will derive it)
                showAlert("Export Complete",
                    "Report exported successfully to:\n" + file.getAbsolutePath() +
                    "\n\n🔒 WORKSHEET PROTECTED" +
                    "\n\nThe worksheet has been password-protected." +
                    "\nYour supervisor can access the file using the standard procedure." +
                    "\n\nSubmit this report to your authority.",
                    Alert.AlertType.INFORMATION);

                logger.info("Excel export completed successfully for site: {}", siteId.get());
            } catch (Exception e) {
                logger.error("Export error", e);
                showAlert("Export Error", "Failed to export: " + e.getMessage(), Alert.AlertType.ERROR);
            }
        }
    }

    private void showSettings() {
        logger.info("Opening settings dialog");
        SettingsDialog settingsDialog = new SettingsDialog(primaryStage);
        settingsDialog.showAndWait();
    }

    private void showHelpManual() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Help");
        dialog.setHeaderText("CCTV Discovery Tool - Quick Guide");

        // Content
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(500);

        Label quickGuide = new Label(
                "Quick Start Guide:\n\n" +
                "1. Select Network Range:\n" +
                "   • Choose network interface, manual IP range, or CIDR notation\n\n" +
                "2. Add Credentials (Required - Max 4):\n" +
                "   • Enter username and password\n" +
                "   • Click 'Add Credential'\n" +
                "   • Right-click to Edit or Delete credentials\n\n" +
                "3. Configure Settings (Optional):\n" +
                "   • Click 'Settings' button to configure custom ports and RTSP paths\n\n" +
                "4. Start Discovery:\n" +
                "   • Click 'Start Discovery' button\n" +
                "   • Monitor progress in the progress section\n\n" +
                "5. Export Results:\n" +
                "   • After discovery completes, enter Site ID\n" +
                "   • Click 'Export to Excel'\n" +
                "   • Password-protected Excel file will be generated"
        );
        quickGuide.setWrapText(true);
        quickGuide.setStyle("-fx-font-size: 12px;");

        // User Manual button
        Button btnUserManual = new Button("Open Full User Manual");
        btnUserManual.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold;");
        btnUserManual.setPrefWidth(200);
        btnUserManual.setOnAction(e -> openUserManual());

        HBox buttonBox = new HBox(btnUserManual);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        content.getChildren().addAll(quickGuide, new Separator(), buttonBox);
        dialog.getDialogPane().setContent(content);

        // Close button
        ButtonType closeButton = new ButtonType("Close", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(closeButton);

        dialog.showAndWait();
    }

    private void openUserManual() {
        try {
            // Extract manual.html and images to temp directory
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "cctv-discovery-help");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // Extract manual.html
            File manualFile = extractResource("/help/manual.html", tempDir, "manual.html");

            // Extract images folder
            File imagesDir = new File(tempDir, "images");
            if (!imagesDir.exists()) {
                imagesDir.mkdirs();
            }

            // Extract all images
            String[] imageFiles = {
                "main-window.png",
                "header-buttons.png",
                "network-selection.png",
                "add-credentials.png",
                "edit-credentials.png",
                "settings-dialog.png",
                "settings-ports.png",
                "settings-rtsp-paths.png",
                "discovery-progress.png",
                "results-table.png",
                "export-dialog.png"
            };

            for (String imageFile : imageFiles) {
                extractResource("/help/images/" + imageFile, imagesDir, imageFile);
            }

            // Open in default browser
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(manualFile.toURI());
                logger.info("User manual opened in browser: {}", manualFile.getAbsolutePath());
            } else {
                showAlert("Cannot Open Manual",
                    "Unable to open browser. Please manually open:\n" + manualFile.getAbsolutePath(),
                    Alert.AlertType.WARNING);
            }

        } catch (Exception e) {
            logger.error("Error opening user manual", e);
            showAlert("Error", "Failed to open user manual: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private File extractResource(String resourcePath, File targetDir, String fileName) throws IOException {
        File targetFile = new File(targetDir, fileName);

        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.warn("Resource not found: {}", resourcePath);
                return targetFile; // Return file even if resource doesn't exist yet
            }

            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            logger.debug("Extracted resource: {} to {}", resourcePath, targetFile.getAbsolutePath());
        }

        return targetFile;
    }

    private void disableInputs() {
        // Simple mode controls
        rbInterface.setDisable(true);
        rbManualRange.setDisable(true);
        rbCIDR.setDisable(true);
        cbInterfaces.setDisable(true);
        tfStartIP.setDisable(true);
        tfEndIP.setDisable(true);
        tfCIDR.setDisable(true);

        // Advanced mode controls
        cbAdvancedMode.setDisable(true);
        lvNetworkInterfaces.setDisable(true);
        tvIpRanges.setDisable(true);
        tvCidrs.setDisable(true);

        // Credential controls
        tfUsername.setDisable(true);
        tfPassword.setDisable(true);
        btnAddCredential.setDisable(true);
        btnStart.setDisable(true);
    }

    private void enableInputs() {
        discoveryInProgress = false;

        // Advanced mode toggle
        cbAdvancedMode.setDisable(false);

        // Enable appropriate mode controls
        if (cbAdvancedMode.isSelected()) {
            lvNetworkInterfaces.setDisable(false);
            tvIpRanges.setDisable(false);
            tvCidrs.setDisable(false);
        } else {
            updateNetworkMode();
        }

        // Credential controls
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

    // Inner classes for advanced network selection
    public static class NetworkInterfaceItem {
        private final String displayName;
        private final String ipAddress;
        private boolean selected;

        public NetworkInterfaceItem(String displayName, String ipAddress, boolean selected) {
            this.displayName = displayName;
            this.ipAddress = ipAddress;
            this.selected = selected;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public String toString() {
            return displayName + " - " + ipAddress;
        }
    }

    public static class IpRangeItem {
        private String startIp;
        private String endIp;

        public IpRangeItem(String startIp, String endIp) {
            this.startIp = startIp;
            this.endIp = endIp;
        }

        public String getStartIp() {
            return startIp;
        }

        public void setStartIp(String startIp) {
            this.startIp = startIp;
        }

        public String getEndIp() {
            return endIp;
        }

        public void setEndIp(String endIp) {
            this.endIp = endIp;
        }
    }

    public static class CidrItem {
        private String cidr;

        public CidrItem(String cidr) {
            this.cidr = cidr;
        }

        public String getCidr() {
            return cidr;
        }

        public void setCidr(String cidr) {
            this.cidr = cidr;
        }
    }
}
