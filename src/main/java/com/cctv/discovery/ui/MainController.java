package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.discovery.NetworkScanner;
import com.cctv.discovery.discovery.StreamAnalyzer;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.HostAuditData;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.service.HostAuditService;
import com.cctv.discovery.service.MacLookupService;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.util.NetworkUtils;
import com.cctv.discovery.util.RtspClient;
import com.cctv.discovery.util.TargetParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private RadioButton rbIpList;
    private ComboBox<String> cbInterfaces;
    private TextField tfStartIP;
    private TextField tfEndIP;
    private TextField tfCIDR;
    private TextArea taIpList;
    private Label lblIpCount;

    // Network selection - Advanced mode
    private CheckBox cbAdvancedMode;
    private ListView<NetworkInterfaceItem> lvNetworkInterfaces;
    private ObservableList<NetworkInterfaceItem> networkInterfaces;
    private TableView<IpRangeItem> tvIpRanges;
    private ObservableList<IpRangeItem> ipRanges;
    private TableView<CidrItem> tvCidrs;
    private ObservableList<CidrItem> cidrs;
    private Label lblAdvancedIpCount;

    // Network summary in left panel
    private Label lblNetworkSummary;
    private Button btnConfigureNetwork;

    // Credentials
    private TextField tfUsername;
    private TextField tfPassword;
    private Button btnAddCredential;
    private ListView<Credential> lvCredentials;
    private ObservableList<Credential> credentials;

    // Credential summary in left panel
    private Label lblCredentialSummary;
    private Button btnManageCredentials;

    // Actions
    private Button btnStart;
    private Button btnExport;

    // Verification Method (left panel)
    private Button btnVerificationMethod;
    private Label lblVerificationSummary;

    // RTSP Validation Method (used in verification modal and settings)
    private String selectedValidationMethod = "FRAME_CAPTURE";

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
    private HostAuditService hostAuditService;
    private ExecutorService executorService;

    // State
    private boolean discoveryInProgress = false;
    private boolean discoveryCompleted = false;
    private boolean networkConfigured = false;
    private HostAuditData hostAuditData; // Collected at startup

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.credentials = FXCollections.observableArrayList();
        this.devices = FXCollections.observableArrayList();
        this.networkInterfaces = FXCollections.observableArrayList();
        this.ipRanges = FXCollections.observableArrayList();
        this.cidrs = FXCollections.observableArrayList();

        // Initialize advanced mode checkbox (used in modals)
        this.cbAdvancedMode = new CheckBox();
        this.cbAdvancedMode.setSelected(false);

        // Initialize services
        this.networkScanner = new NetworkScanner();
        this.onvifService = new OnvifService();
        this.rtspService = new RtspService();
        this.streamAnalyzer = new StreamAnalyzer();
        this.excelExporter = new ExcelExporter();
        this.hostAuditService = new HostAuditService();
        this.executorService = Executors.newSingleThreadExecutor();

        // Collect host audit data in background (don't block UI startup)
        executorService.submit(() -> {
            logger.info("Collecting host audit data in background...");
            hostAuditData = hostAuditService.collectHostAudit();
            logger.info("Host audit data collection completed");
        });
    }

    public Scene createScene() {
        // Create full-width header at the top
        HBox header = createHeaderPanel();

        // Create split pane for left and right panels
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.25); // Left panel occupies 1/4 of horizontal span

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

        scene = new Scene(mainLayout, 1024, 768);

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
        AppConfig config = AppConfig.getInstance();

        // Organization name in regular font above the tool name
        Label orgLabel = new Label(config.getAppOrganization());
        orgLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");

        // Tool name in bold title style
        Label title = new Label(config.getAppName());
        title.getStyleClass().add("header-title");

        // Stack org name above tool name
        VBox titleBlock = new VBox(0, orgLabel, title);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

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
        header.getChildren().addAll(titleBlock, spacer, buttonBox);

        return header;
    }

    private VBox createLeftPanel() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(10));
        vbox.getStyleClass().add("left-panel");

        // 1. Network Section
        VBox networkSection = createNetworkSection();

        // 2. Credential Section
        VBox credentialSection = createCredentialSection();

        // 3. Verification Method Section
        VBox verificationSection = createVerificationMethodSection();

        // 4. Start Discovery Section
        VBox discoverySection = createDiscoverySection();

        // 5. Export Section
        VBox exportSection = createExportSection();

        // Progress Section - AT BOTTOM
        VBox progressSection = createProgressSection();

        // Add all sections in numbered order (Progress at bottom)
        vbox.getChildren().addAll(
                networkSection,
                credentialSection,
                verificationSection,
                discoverySection,
                exportSection,
                progressSection);

        return vbox;
    }

    private VBox createNetworkSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("1. Network Selection");
        lblTitle.getStyleClass().add("section-title");

        btnConfigureNetwork = new Button("Select Network");
        btnConfigureNetwork.setMaxWidth(Double.MAX_VALUE);
        btnConfigureNetwork.setPrefHeight(35);
        btnConfigureNetwork.setOnAction(e -> showNetworkConfigDialog());

        lblNetworkSummary = new Label("Not configured");
        lblNetworkSummary.getStyleClass().add("label-info");
        lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #0078d4;");
        lblNetworkSummary.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, btnConfigureNetwork, lblNetworkSummary);
        return vbox;
    }

    private VBox createSimpleNetworkBox() {
        VBox vbox = new VBox(6);

        // Radio buttons
        ToggleGroup tg = new ToggleGroup();
        rbInterface = new RadioButton("Network Interface");
        rbManualRange = new RadioButton("Manual IP Range");
        rbCIDR = new RadioButton("CIDR Notation");
        rbIpList = new RadioButton("IP Address List");
        rbInterface.setToggleGroup(tg);
        rbManualRange.setToggleGroup(tg);
        rbCIDR.setToggleGroup(tg);
        rbIpList.setToggleGroup(tg);
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

        // IP Address List - accepts multiple IPs separated by commas, spaces, or newlines
        taIpList = new TextArea();
        taIpList.setPromptText("IP addresses separated by commas, spaces, or newlines\n(e.g., 192.168.1.10, 192.168.1.20\n192.168.1.30)");
        taIpList.setPrefRowCount(4);
        taIpList.setWrapText(true);
        taIpList.setDisable(true);

        // IP count label - center aligned, bold and colored
        lblIpCount = new Label("Possible IPs: 0");
        lblIpCount.getStyleClass().add("label-info");
        lblIpCount.setAlignment(Pos.CENTER);
        lblIpCount.setMaxWidth(Double.MAX_VALUE);
        lblIpCount.setStyle("-fx-font-weight: bold; -fx-text-fill: #0078d4;");

        // Event handlers
        rbInterface.setOnAction(e -> updateNetworkMode());
        rbManualRange.setOnAction(e -> updateNetworkMode());
        rbCIDR.setOnAction(e -> updateNetworkMode());
        rbIpList.setOnAction(e -> updateNetworkMode());
        cbInterfaces.setOnAction(e -> updateIpCount());
        tfStartIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfEndIP.textProperty().addListener((obs, old, val) -> updateIpCount());
        tfCIDR.textProperty().addListener((obs, old, val) -> updateIpCount());
        taIpList.textProperty().addListener((obs, old, val) -> updateIpCount());

        vbox.getChildren().addAll(
                rbInterface, cbInterfaces,
                rbManualRange, ipRangeBox,
                rbCIDR, tfCIDR,
                rbIpList, taIpList,
                lblIpCount);

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
                textField.setPromptText("e.g., 192.168.1.1");

                // Real-time validation
                textField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal == null || newVal.trim().isEmpty()) {
                        textField.setStyle("");
                    } else if (NetworkUtils.isValidIP(newVal.trim())) {
                        textField.setStyle("");
                    } else {
                        textField.setStyle(
                                "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    }
                });

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
                IpRangeItem range = getTableView().getItems().get(getIndex());
                String oldValue = range.getStartIp();

                // Validate for duplicates (excluding current item)
                boolean isDuplicate = false;
                for (int i = 0; i < getTableView().getItems().size(); i++) {
                    if (i != getIndex()) {
                        IpRangeItem other = getTableView().getItems().get(i);
                        if (other.getStartIp().equals(newValue) && other.getEndIp().equals(range.getEndIp())) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }

                if (isDuplicate) {
                    // Show error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate IP Range");
                    alert.setHeaderText("This IP range already exists");
                    alert.setContentText(String.format("IP Range: %s - %s\n\nPlease enter a different IP range.",
                            newValue, range.getEndIp()));
                    alert.showAndWait();

                    // Apply error styling and revert to old value
                    textField.setStyle(
                            "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    textField.setText(oldValue);
                    cancelEdit();
                    return;
                }

                // Check for overlapping ranges (warn but allow)
                for (int i = 0; i < getTableView().getItems().size(); i++) {
                    if (i != getIndex()) {
                        IpRangeItem other = getTableView().getItems().get(i);
                        if (isOverlappingIpRange(newValue, range.getEndIp(), other.getStartIp(), other.getEndIp())) {
                            Alert warning = new Alert(Alert.AlertType.WARNING);
                            warning.setTitle("Overlapping IP Range");
                            warning.setHeaderText("This IP range overlaps with an existing range");
                            warning.setContentText(String.format(
                                    "New Range: %s - %s\nExisting Range: %s - %s\n\nThis is allowed but may cause redundant scanning.",
                                    newValue, range.getEndIp(), other.getStartIp(), other.getEndIp()));
                            warning.showAndWait();
                            break; // Only show warning once
                        }
                    }
                }

                super.commitEdit(newValue);
                range.setStartIp(newValue);
                updateAdvancedIpCount();
            }
        });

        TableColumn<IpRangeItem, String> colEndIp = new TableColumn<>("End IP");
        colEndIp.setCellValueFactory(new PropertyValueFactory<>("endIp"));
        colEndIp.setCellFactory(col -> new TableCell<IpRangeItem, String>() {
            private final TextField textField = new TextField();

            {
                textField.setPromptText("e.g., 192.168.1.254");

                // Real-time validation
                textField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal == null || newVal.trim().isEmpty()) {
                        textField.setStyle("");
                    } else if (NetworkUtils.isValidIP(newVal.trim())) {
                        textField.setStyle("");
                    } else {
                        textField.setStyle(
                                "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    }
                });

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
                IpRangeItem range = getTableView().getItems().get(getIndex());
                String oldValue = range.getEndIp();

                // Validate for duplicates (excluding current item)
                boolean isDuplicate = false;
                for (int i = 0; i < getTableView().getItems().size(); i++) {
                    if (i != getIndex()) {
                        IpRangeItem other = getTableView().getItems().get(i);
                        if (other.getStartIp().equals(range.getStartIp()) && other.getEndIp().equals(newValue)) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }

                if (isDuplicate) {
                    // Show error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate IP Range");
                    alert.setHeaderText("This IP range already exists");
                    alert.setContentText(String.format("IP Range: %s - %s\n\nPlease enter a different IP range.",
                            range.getStartIp(), newValue));
                    alert.showAndWait();

                    // Apply error styling and revert to old value
                    textField.setStyle(
                            "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    textField.setText(oldValue);
                    cancelEdit();
                    return;
                }

                // Check for overlapping ranges (warn but allow)
                for (int i = 0; i < getTableView().getItems().size(); i++) {
                    if (i != getIndex()) {
                        IpRangeItem other = getTableView().getItems().get(i);
                        if (isOverlappingIpRange(range.getStartIp(), newValue, other.getStartIp(), other.getEndIp())) {
                            Alert warning = new Alert(Alert.AlertType.WARNING);
                            warning.setTitle("Overlapping IP Range");
                            warning.setHeaderText("This IP range overlaps with an existing range");
                            warning.setContentText(String.format(
                                    "New Range: %s - %s\nExisting Range: %s - %s\n\nThis is allowed but may cause redundant scanning.",
                                    range.getStartIp(), newValue, other.getStartIp(), other.getEndIp()));
                            warning.showAndWait();
                            break; // Only show warning once
                        }
                    }
                }

                super.commitEdit(newValue);
                range.setEndIp(newValue);
                updateAdvancedIpCount();
            }
        });

        tvIpRanges.getColumns().add(colStartIp);
        tvIpRanges.getColumns().add(colEndIp);

        Button btnAddRange = new Button("Add IP Range");
        btnAddRange.setPrefWidth(120);
        btnAddRange.setPrefHeight(30);
        btnAddRange.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold;");
        btnAddRange.setOnAction(e -> addIpRange());

        Button btnRemoveRange = new Button("Remove Selected");
        btnRemoveRange.setPrefWidth(120);
        btnRemoveRange.setPrefHeight(30);
        btnRemoveRange.setStyle("-fx-background-color: #ffc107; -fx-text-fill: black; -fx-font-weight: bold;");
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
                textField.setPromptText("e.g., 192.168.1.0/24");

                // Real-time validation
                textField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal == null || newVal.trim().isEmpty()) {
                        textField.setStyle("");
                    } else if (isValidCidr(newVal.trim())) {
                        textField.setStyle("");
                    } else {
                        textField.setStyle(
                                "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    }
                });

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
                CidrItem cidr = getTableView().getItems().get(getIndex());
                String oldValue = cidr.getCidr();

                // Validate for duplicates (excluding current item)
                boolean isDuplicate = false;
                for (int i = 0; i < getTableView().getItems().size(); i++) {
                    if (i != getIndex()) {
                        CidrItem other = getTableView().getItems().get(i);
                        if (other.getCidr().equals(newValue)) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }

                if (isDuplicate) {
                    // Show error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate CIDR");
                    alert.setHeaderText("This CIDR notation already exists");
                    alert.setContentText(
                            String.format("CIDR: %s\n\nPlease enter a different CIDR notation.", newValue));
                    alert.showAndWait();

                    // Apply error styling and revert to old value
                    textField.setStyle(
                            "-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
                    textField.setText(oldValue);
                    cancelEdit();
                    return;
                }

                super.commitEdit(newValue);
                cidr.setCidr(newValue);
                updateAdvancedIpCount();
            }
        });

        tvCidrs.getColumns().add(colCidr);

        Button btnAddCidr = new Button("Add CIDR");
        btnAddCidr.setPrefWidth(120);
        btnAddCidr.setPrefHeight(30);
        btnAddCidr.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold;");
        btnAddCidr.setOnAction(e -> addCidr());

        Button btnRemoveCidr = new Button("Remove Selected");
        btnRemoveCidr.setPrefWidth(120);
        btnRemoveCidr.setPrefHeight(30);
        btnRemoveCidr.setStyle("-fx-background-color: #ffc107; -fx-text-fill: black; -fx-font-weight: bold;");
        btnRemoveCidr.setOnAction(e -> {
            CidrItem selected = tvCidrs.getSelectionModel().getSelectedItem();
            if (selected != null) {
                cidrs.remove(selected);
                updateAdvancedIpCount();
            }
        });
        HBox cidrButtons = new HBox(8, btnAddCidr, btnRemoveCidr);

        // IP count label - bold and colored
        lblAdvancedIpCount = new Label("Total Possible IPs: 0");
        lblAdvancedIpCount.getStyleClass().add("label-info");
        lblAdvancedIpCount.setAlignment(Pos.CENTER);
        lblAdvancedIpCount.setMaxWidth(Double.MAX_VALUE);
        lblAdvancedIpCount.setStyle("-fx-font-weight: bold; -fx-text-fill: #0078d4;");

        vbox.getChildren().addAll(
                lblInterfaces, lvNetworkInterfaces,
                lblRanges, tvIpRanges, rangeButtons,
                lblCidrs, tvCidrs, cidrButtons,
                lblAdvancedIpCount);

        return vbox;
    }

    private VBox createCredentialSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("2. Credentials");
        lblTitle.getStyleClass().add("section-title");

        btnManageCredentials = new Button("Set Credentials");
        btnManageCredentials.setMaxWidth(Double.MAX_VALUE);
        btnManageCredentials.setPrefHeight(35);
        btnManageCredentials.setOnAction(e -> showCredentialManagementDialog());

        lblCredentialSummary = new Label("No credentials added");
        lblCredentialSummary.getStyleClass().add("label-info");
        lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #0078d4;");
        lblCredentialSummary.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, btnManageCredentials, lblCredentialSummary);
        return vbox;
    }

    private VBox createProgressSection() {
        VBox vbox = new VBox(6);
        vbox.setPadding(new Insets(10));

        Label lblTitle = new Label("Progress");
        lblTitle.getStyleClass().add("section-title");
        lblTitle.setAlignment(Pos.CENTER);
        lblTitle.setMaxWidth(Double.MAX_VALUE);
        lblTitle.setStyle("-fx-font-weight: bold;");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(18);
        progressBar.setStyle("-fx-accent: #008080;");

        // Progress label - center aligned and italic with teal text
        lblProgress = new Label("Ready");
        lblProgress.getStyleClass().add("label-info");
        lblProgress.setAlignment(Pos.CENTER);
        lblProgress.setMaxWidth(Double.MAX_VALUE);
        lblProgress.setStyle("-fx-font-style: italic; -fx-text-fill: #008080;");

        vbox.getChildren().addAll(lblTitle, progressBar, lblProgress);
        return vbox;
    }

    private VBox createVerificationMethodSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("3. Verification Method");
        lblTitle.getStyleClass().add("section-title");

        btnVerificationMethod = new Button("Set Verification Method");
        btnVerificationMethod.setMaxWidth(Double.MAX_VALUE);
        btnVerificationMethod.setPrefHeight(35);
        btnVerificationMethod.setOnAction(e -> showVerificationMethodDialog());

        // Load saved preference
        String savedMethod = config.getRtspValidationMethod();
        if ("SDP_ONLY".equals(savedMethod)) {
            selectedValidationMethod = "SDP_ONLY";
        } else if ("RTP_PACKET".equals(savedMethod)) {
            selectedValidationMethod = "RTP_PACKET";
        } else {
            selectedValidationMethod = "FRAME_CAPTURE";
        }

        lblVerificationSummary = new Label(getVerificationSummaryText(selectedValidationMethod));
        lblVerificationSummary.getStyleClass().add("label-info");
        lblVerificationSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #0078d4;");
        lblVerificationSummary.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, btnVerificationMethod, lblVerificationSummary);
        return vbox;
    }

    private String getVerificationSummaryText(String method) {
        switch (method) {
            case "SDP_ONLY":
                return "Quick Check \u2014 fastest, basic protocol check (~60% accuracy)";
            case "RTP_PACKET":
                return "Stream Test \u2014 balanced, verifies live data streaming (~90% accuracy)";
            case "FRAME_CAPTURE":
            default:
                return "Video Capture \u2014 most reliable, verifies actual video frames (~98% accuracy)";
        }
    }

    private void showVerificationMethodDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Verification Method");
        dialog.setHeaderText("Choose Camera Verification Method");
        dialog.getDialogPane().setPrefWidth(520);

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for verification method dialog", ex);
            }
        });

        VBox content = new VBox(12);
        content.setPadding(new Insets(15));

        Label lblIntro = new Label(
                "Choose how the tool confirms a camera is working.\nMore thorough methods take longer but give more reliable results.");
        lblIntro.setWrapText(true);
        lblIntro.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        ToggleGroup validationGroup = new ToggleGroup();

        // Card 1: Quick Check (SDP_ONLY)
        VBox card1 = createVerificationCard(
                validationGroup,
                "Quick Check",
                "Fastest option \u2014 checks if camera responds to\nconnection requests only. Some cameras may appear\nworking even if the video feed has issues.",
                "~3 seconds per camera  |  ~60% accuracy",
                false);
        RadioButton rb1 = (RadioButton) card1.getUserData();

        // Card 2: Stream Test (RTP_PACKET)
        VBox card2 = createVerificationCard(
                validationGroup,
                "Stream Test",
                "Balanced option \u2014 verifies the camera is actively\nsending video data. Good for quick audits where\nsome uncertainty is acceptable.",
                "~5 seconds per camera  |  ~90% accuracy",
                false);
        RadioButton rb2 = (RadioButton) card2.getUserData();

        // Card 3: Video Capture (FRAME_CAPTURE) - Recommended
        VBox card3 = createVerificationCard(
                validationGroup,
                "Video Capture",
                "Most reliable \u2014 actually captures a video frame to\nconfirm the camera is fully operational. Best for\nofficial audits and compliance reports.",
                "~10 seconds per camera  |  ~98% accuracy",
                true);
        RadioButton rb3 = (RadioButton) card3.getUserData();

        // Select current method
        if ("SDP_ONLY".equals(selectedValidationMethod)) {
            rb1.setSelected(true);
            card1.setStyle(getSelectedCardStyle());
        } else if ("RTP_PACKET".equals(selectedValidationMethod)) {
            rb2.setSelected(true);
            card2.setStyle(getSelectedCardStyle());
        } else {
            rb3.setSelected(true);
            card3.setStyle(getSelectedCardStyle());
        }

        // Update card styles on selection change
        validationGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            card1.setStyle(rb1.isSelected() ? getSelectedCardStyle() : getUnselectedCardStyle());
            card2.setStyle(rb2.isSelected() ? getSelectedCardStyle() : getUnselectedCardStyle());
            card3.setStyle(rb3.isSelected() ? getSelectedCardStyle() : getUnselectedCardStyle());
        });

        content.getChildren().addAll(lblIntro, card1, card2, card3);
        dialog.getDialogPane().setContent(content);

        // Buttons
        ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

        // Style buttons
        dialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) dialog.getDialogPane().lookupButton(okButton);
            Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelButton);
            if (okBtn != null) {
                okBtn.setStyle(
                        "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle(
                        "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == okButton) {
            // Save selection
            if (rb1.isSelected()) {
                selectedValidationMethod = "SDP_ONLY";
            } else if (rb2.isSelected()) {
                selectedValidationMethod = "RTP_PACKET";
            } else {
                selectedValidationMethod = "FRAME_CAPTURE";
            }
            lblVerificationSummary.setText(getVerificationSummaryText(selectedValidationMethod));
            logger.info("Verification method changed to: {}", selectedValidationMethod);
        }
    }

    private VBox createVerificationCard(ToggleGroup group, String title, String description, String stats,
            boolean recommended) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10));
        card.setStyle(getUnselectedCardStyle());

        RadioButton rb = new RadioButton();
        rb.setToggleGroup(group);

        // Title row with radio button
        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().addAll(rb, lblTitle);

        if (recommended) {
            Label badge = new Label("Recommended");
            badge.setStyle(
                    "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 6; -fx-background-radius: 3;");
            titleRow.getChildren().add(badge);
        }

        Label lblDesc = new Label(description);
        lblDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        lblDesc.setPadding(new Insets(0, 0, 0, 24));

        Label lblStats = new Label(stats);
        lblStats.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-font-style: italic;");
        lblStats.setPadding(new Insets(0, 0, 0, 24));

        card.getChildren().addAll(titleRow, lblDesc, lblStats);

        // Store radio button reference on the card
        card.setUserData(rb);

        // Click anywhere on card to select
        card.setOnMouseClicked(e -> rb.setSelected(true));

        return card;
    }

    private String getSelectedCardStyle() {
        return "-fx-border-color: #0078d4; -fx-border-width: 2; -fx-border-radius: 5; -fx-background-color: #e8f0fe; -fx-background-radius: 5;";
    }

    private String getUnselectedCardStyle() {
        return "-fx-border-color: #cccccc; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #ffffff; -fx-background-radius: 5;";
    }

    private VBox createDiscoverySection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("4. Start Discovery");
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

    private VBox createExportSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("5. Export");
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
        colIp.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    Device device = getTableView().getItems().get(getIndex());
                    String textColor = getRowTextColor(device);
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        TableColumn<Device, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(cellData -> {
            Device.DeviceStatus status = cellData.getValue().getStatus();
            return new javafx.beans.property.SimpleStringProperty(status != null ? status.toString() : "");
        });
        colStatus.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    Device device = getTableView().getItems().get(getIndex());
                    String textColor = getRowTextColor(device);
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        TableColumn<Device, String> colName = new TableColumn<>("Device Name");
        colName.setCellValueFactory(new PropertyValueFactory<>("deviceName"));
        colName.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    Device device = getTableView().getItems().get(getIndex());
                    String textColor = getRowTextColor(device);
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        TableColumn<Device, String> colManufacturer = new TableColumn<>("Manufacturer");
        colManufacturer.setCellValueFactory(new PropertyValueFactory<>("manufacturer"));
        colManufacturer.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    Device device = getTableView().getItems().get(getIndex());
                    String textColor = getRowTextColor(device);
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        TableColumn<Device, String> colStreams = new TableColumn<>("Streams");
        colStreams.setCellValueFactory(cellData -> {
            int count = cellData.getValue().getRtspStreams().size();
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(count));
        });
        colStreams.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    int count = Integer.parseInt(item);
                    // Streams > 0: green, Streams = 0: red
                    String textColor = count > 0 ? "#155724" : "#A94442";
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        TableColumn<Device, String> colError = new TableColumn<>("Error");
        colError.setCellValueFactory(new PropertyValueFactory<>("errorMessage"));
        colError.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    Device device = getTableView().getItems().get(getIndex());
                    String textColor = getRowTextColor(device);
                    setStyle("-fx-text-fill: " + textColor + ";");
                }
            }
        });

        tvResults.getColumns().add(colIp);
        tvResults.getColumns().add(colStatus);
        tvResults.getColumns().add(colName);
        tvResults.getColumns().add(colManufacturer);
        tvResults.getColumns().add(colStreams);
        tvResults.getColumns().add(colError);

        // Add row factory for background colors only
        tvResults.setRowFactory(tv -> new TableRow<Device>() {
            @Override
            protected void updateItem(Device device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setStyle("");
                } else {
                    String backgroundColor = getRowBackgroundColor(device);
                    setStyle("-fx-background-color: " + backgroundColor + ";");
                }
            }
        });

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

    /**
     * Get background color for table row based on device status
     */
    private String getRowBackgroundColor(Device device) {
        if (device.getStatus() == Device.DeviceStatus.COMPLETED) {
            return "#D4EDDA"; // Green - success
        } else if (device.getStatus() == Device.DeviceStatus.AUTHENTICATING) {
            return "#FFF3CD"; // Yellow - in progress
        } else if (device.getStatus() == Device.DeviceStatus.AUTH_FAILED) {
            if (device.isAuthFailed()) {
                return "#F8D7DA"; // Red - authentication failure
            } else {
                return "#E7E8EA"; // Gray - unknown device type
            }
        } else {
            return "#D1ECF1"; // Blue - discovered but not processed
        }
    }

    /**
     * Get text color for table row based on device status
     */
    private String getRowTextColor(Device device) {
        if (device.getStatus() == Device.DeviceStatus.COMPLETED) {
            return "#155724"; // Dark green
        } else if (device.getStatus() == Device.DeviceStatus.AUTHENTICATING) {
            return "#856404"; // Dark amber
        } else if (device.getStatus() == Device.DeviceStatus.AUTH_FAILED) {
            if (device.isAuthFailed()) {
                return "#A94442"; // Dark red
            } else {
                return "#383D41"; // Dark gray
            }
        } else {
            return "#0C5460"; // Dark blue
        }
    }

    /** True when the text is a CIDR block this tool can scan. */
    private static boolean isValidCidr(String text) {
        return text != null && text.contains("/") && TargetParser.parseToken(text.trim()) != null;
    }

    /** Address count for one token (single IP, range or CIDR); 0 when invalid. */
    private static long countIps(String token) {
        TargetParser.Interval interval = token == null ? null : TargetParser.parseToken(token.trim());
        return interval == null ? 0 : interval.size();
    }

    /** Address count for a start-end pair. */
    private static long countRange(String startIp, String endIp) {
        if (!NetworkUtils.isValidIP(startIp) || !NetworkUtils.isValidIP(endIp)) {
            return 0;
        }
        return countIps(startIp.trim() + "-" + endIp.trim());
    }

    /** Valid, de-duplicated addresses from a free-form list. */
    private static List<String> parseIpList(String text) {
        TargetParser.Targets targets = TargetParser.parse(text);
        return targets.toList(AppConfig.getInstance().getMaxTargets());
    }

    private void populateNetworkInterfaces() {
        cbInterfaces.getItems().clear();
        for (NetworkUtils.LocalInterface li : NetworkUtils.getLocalInterfaces()) {
            cbInterfaces.getItems().add(li.toString());
        }
        if (!cbInterfaces.getItems().isEmpty()) {
            cbInterfaces.getSelectionModel().selectFirst();
        }
    }

    private void populateAdvancedNetworkInterfaces() {
        List<String> selectedIps = new ArrayList<>();
        for (NetworkInterfaceItem item : networkInterfaces) {
            if (item.isSelected()) {
                selectedIps.add(item.getIpAddress());
            }
        }
        networkInterfaces.clear();
        for (NetworkUtils.LocalInterface li : NetworkUtils.getLocalInterfaces()) {
            networkInterfaces.add(new NetworkInterfaceItem(li, selectedIps.contains(li.address())));
        }
    }

    private void addIpRange() {
        ipRanges.add(new IpRangeItem("", ""));
        updateAdvancedIpCount();
    }

    private void addCidr() {
        cidrs.add(new CidrItem(""));
        updateAdvancedIpCount();
    }

    /**
     * Convert an IP address string to a long value for comparison
     */
    private long ipToLong(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            return 0;
        }
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (Long.parseLong(octets[i]) << (24 - (8 * i)));
        }
        return result;
    }

    /**
     * Check if two IP ranges overlap
     * 
     * @param start1 Start IP of first range
     * @param end1   End IP of first range
     * @param start2 Start IP of second range
     * @param end2   End IP of second range
     * @return true if ranges overlap
     */
    private boolean isOverlappingIpRange(String start1, String end1, String start2, String end2) {
        // Validate all IPs first
        if (!NetworkUtils.isValidIP(start1) || !NetworkUtils.isValidIP(end1) ||
                !NetworkUtils.isValidIP(start2) || !NetworkUtils.isValidIP(end2)) {
            return false;
        }

        long s1 = ipToLong(start1);
        long e1 = ipToLong(end1);
        long s2 = ipToLong(start2);
        long e2 = ipToLong(end2);

        // Ensure start <= end for both ranges
        if (s1 > e1) {
            long temp = s1;
            s1 = e1;
            e1 = temp;
        }
        if (s2 > e2) {
            long temp = s2;
            s2 = e2;
            e2 = temp;
        }

        // Check for overlap: ranges overlap if one starts before the other ends
        return (s1 <= e2 && e1 >= s2);
    }

    private void updateAdvancedIpCount() {
        // Counting through the parser means overlapping sources are not counted
        // twice, so the figure matches what the scan will actually do.
        lblAdvancedIpCount.setText("Total addresses: " + TargetParser.parse(advancedTargetText()).count());
        updateStartButtonState();
    }

    /** Every advanced-mode source as one target expression. */
    private String advancedTargetText() {
        StringBuilder sb = new StringBuilder();
        for (NetworkInterfaceItem item : networkInterfaces) {
            if (item.isSelected()) {
                sb.append(item.getNetworkCidr()).append(' ');
            }
        }
        for (IpRangeItem range : ipRanges) {
            if (NetworkUtils.isValidIP(range.getStartIp()) && NetworkUtils.isValidIP(range.getEndIp())) {
                sb.append(range.getStartIp().trim()).append('-').append(range.getEndIp().trim()).append(' ');
            }
        }
        for (CidrItem cidr : cidrs) {
            if (isValidCidr(cidr.getCidr())) {
                sb.append(cidr.getCidr().trim()).append(' ');
            }
        }
        return sb.toString();
    }

    private void updateNetworkMode() {
        cbInterfaces.setDisable(!rbInterface.isSelected());
        tfStartIP.setDisable(!rbManualRange.isSelected());
        tfEndIP.setDisable(!rbManualRange.isSelected());
        tfCIDR.setDisable(!rbCIDR.isSelected());
        taIpList.setDisable(!rbIpList.isSelected());
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
        long count = 0;
        if (rbInterface.isSelected() && cbInterfaces.getValue() != null) {
            count = getSelectedInterfaceIpCount(cbInterfaces.getValue());
        } else if (rbManualRange.isSelected()) {
            count = countRange(tfStartIP.getText(), tfEndIP.getText());
        } else if (rbCIDR.isSelected()) {
            count = countIps(tfCIDR.getText());
        } else if (rbIpList.isSelected()) {
            count = TargetParser.parse(taIpList.getText()).count();
        }
        lblIpCount.setText("Possible IPs: " + count);
        updateStartButtonState();
    }

    /**
     * Get the IP count for a selected interface from the combo box.
     * Parses the display string to extract IP, finds the NetworkInterface,
     * and calculates actual subnet size based on prefix length.
     */
    /** Usable addresses on the subnet of the interface chosen in the combo box. */
    private long getSelectedInterfaceIpCount(String displayString) {
        return findSelectedInterface(displayString)
                .map(NetworkUtils.LocalInterface::hostCount)
                .orElse(0L);
    }

    /** The interface behind a combo-box entry, matched on its address. */
    private Optional<NetworkUtils.LocalInterface> findSelectedInterface(String displayString) {
        if (displayString == null) {
            return Optional.empty();
        }
        String address = displayString.split("[/ ]")[0].trim();
        return NetworkUtils.getLocalInterfaces().stream()
                .filter(li -> li.address().equals(address))
                .findFirst();
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

        // Check for duplicate credentials
        for (Credential existing : credentials) {
            if (existing.getUsername().equals(username) && existing.getPassword().equals(password)) {
                showAlert("Duplicate Credential", "This credential already exists.", Alert.AlertType.WARNING);
                return;
            }
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
        updateCredentialSummary();
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

        boolean hasCredentials = !credentials.isEmpty();

        btnStart.setDisable(!networkConfigured || !hasCredentials || discoveryInProgress);

        // After a completed discovery, restyle to blue and rename
        if (discoveryCompleted && !discoveryInProgress) {
            btnStart.setText("Restart Discovery");
            btnStart.getStyleClass().remove("button-success");
            btnStart.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold;");
        }
    }

    private void startDiscovery() {
        // Lock UI
        discoveryInProgress = true;
        disableInputs();
        devices.clear();
        progressBar.setProgress(0);
        lblProgress.setText("Starting discovery...");

        // Configure RTSP validation method before discovery
        configureRtspValidation();

        // Clear any stale shutdown/probe state from a previous run so a
        // "Restart Discovery" does not abort prematurely.
        rtspService.reset();

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

    private void configureRtspValidation() {
        try {
            // Get selected validation method from stored selection
            RtspService.RtspValidationMethod method;
            if ("SDP_ONLY".equals(selectedValidationMethod)) {
                method = RtspService.RtspValidationMethod.SDP_ONLY;
            } else if ("RTP_PACKET".equals(selectedValidationMethod)) {
                method = RtspService.RtspValidationMethod.RTP_PACKET;
            } else {
                method = RtspService.RtspValidationMethod.FRAME_CAPTURE; // Default
            }

            // Get custom timeout (0 = use default)
            int customTimeout = config.getRtspValidationTimeout();

            // Configure RtspService
            RtspService.RtspDiscoveryConfig discoveryConfig = new RtspService.RtspDiscoveryConfig();
            discoveryConfig.setValidationMethod(method);
            discoveryConfig.setCustomTimeout(customTimeout);
            RtspService.setDiscoveryConfig(discoveryConfig);

            logger.info("RTSP validation configured: method={}, timeout={}ms (0=default)",
                    method, customTimeout);

        } catch (Exception e) {
            logger.error("Error configuring RTSP validation, using defaults", e);
        }
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
            final boolean[] result = { false };
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
                                "Do you want to perform port scan?");
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
        int[] current = { 0 };

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
            long successCount = finalDevices.stream()
                    .filter(d -> d.getStatus() == Device.DeviceStatus.COMPLETED)
                    .count();
            lblProgress.setText("Discovery complete! Found " + successCount + " devices.");
            discoveryCompleted = true;
            enableInputs();
            btnExport.setDisable(finalDevices.isEmpty());
            updateExportButtonColor();
        });
    }

    /**
     * Update export button color based on discovery results:
     * - Green if any devices succeeded
     * - Amber if all devices failed
     */
    private void updateExportButtonColor() {
        boolean anySuccess = devices.stream()
                .anyMatch(d -> !d.getRtspStreams().isEmpty());

        if (anySuccess) {
            // Green if any devices have streams
            btnExport.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold;");
        } else {
            // Amber if all devices failed
            btnExport.setStyle("-fx-background-color: #ffc107; -fx-text-fill: black; -fx-font-weight: bold;");
        }
    }

    /**
     * Identify one device and collect its streams: ONVIF first, because it
     * publishes the real stream URLs, then RTSP path probing only when ONVIF
     * gave us nothing.
     */
    private void authenticateAndDiscoverStreams(Device device) {
        logger.info("Identifying {}", device.getIpAddress());
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        Platform.runLater(() -> tvResults.refresh());

        boolean onvifAuthenticated = authenticateOnvif(device);

        if (onvifAuthenticated) {
            int sources = onvifService.getVideoSourceCount(device);
            device.setVideoSourceCount(sources);
            onvifService.getHostname(device);
            device.setType(onvifService.classifyType(device, sources));
            if (device.getMacAddress() == null) {
                onvifService.getMacAddress(device).ifPresent(mac -> networkScanner.applyMac(device, mac));
            }
            for (RTSPStream stream : onvifService.getStreamUris(device)) {
                device.addStream(stream);
            }
        }

        boolean rtspChallenged = false;
        if (device.getRtspStreams().isEmpty() && !device.getOpenRtspPorts().isEmpty()) {
            logger.info("No ONVIF stream URLs for {}; probing RTSP paths", device.getIpAddress());
            rtspChallenged = discoverStreamsByPath(device);
        }

        if (device.isNvrDvr() && device.getUsername() != null) {
            for (RTSPStream stream : rtspService.iterateNvrChannels(
                    device, device.getUsername(), device.getPassword(), config.getNvrMaxChannels())) {
                device.addStream(stream);
            }
        }

        checkAnonymousAccess(device);
        recordClockFinding(device);
        finalizeStatus(device, onvifAuthenticated, rtspChallenged);
    }

    /**
     * Try each credential against the device's ONVIF service, discovering the
     * service address first when the port scan has not already found one.
     */
    private boolean authenticateOnvif(Device device) {
        List<String> serviceUrls = new ArrayList<>();
        if (device.getOnvifServiceUrl() != null) {
            serviceUrls.add(device.getOnvifServiceUrl());
        }
        for (int port : device.getOpenHttpPorts()) {
            onvifService.findDeviceService(device.getIpAddress(), port)
                    .filter(url -> !serviceUrls.contains(url))
                    .ifPresent(serviceUrls::add);
        }
        if (serviceUrls.isEmpty()) {
            return false;
        }

        for (String serviceUrl : serviceUrls) {
            for (Credential credential : credentials) {
                if (onvifService.getDeviceInformation(device, serviceUrl,
                        credential.getUsername(), credential.getPassword())) {
                    logger.info("ONVIF accepted a credential on {}", serviceUrl);
                    return true;
                }
            }
        }
        logger.info("No credential accepted by ONVIF on {}", device.getIpAddress());
        return false;
    }

    /**
     * Probe RTSP paths with each credential.
     *
     * @return true when an RTSP server asked for credentials, which separates a
     *         wrong password from an unknown stream path
     */
    private boolean discoverStreamsByPath(Device device) {
        for (Credential credential : credentials) {
            List<RTSPStream> streams = rtspService.discoverStreams(
                    device, credential.getUsername(), credential.getPassword());
            if (!streams.isEmpty()) {
                if (device.getUsername() == null) {
                    device.setUsername(credential.getUsername());
                    device.setPassword(credential.getPassword());
                }
                for (RTSPStream stream : streams) {
                    device.addStream(stream);
                    if (device.getDeviceName() == null && stream.getSdpSessionName() != null) {
                        device.setDeviceName(stream.getSdpSessionName());
                    }
                }
                return false;
            }
        }
        for (int port : device.getOpenRtspPorts()) {
            RtspService.ProbeResult probe = rtspService.probe(
                    RtspClient.url(device.getIpAddress(), port, "/"), null, null);
            if (probe.authRequired()) {
                return true;
            }
        }
        return false;
    }

    /** Record whether the video is readable with no credentials at all. */
    private void checkAnonymousAccess(Device device) {
        if (device.getRtspStreams().isEmpty()) {
            return;
        }
        RTSPStream first = device.getRtspStreams().getFirst();
        RtspService.ProbeResult probe = rtspService.probe(first.getRtspUrl(), null, null);
        device.setRtspAnonymousAccess(probe.valid() && probe.anonymous());
        if (Boolean.TRUE.equals(device.getRtspAnonymousAccess())) {
            device.addFinding(new Finding(Finding.Severity.HIGH, "Security",
                    "Video stream readable without a password",
                    "RTSP DESCRIBE succeeded on " + RtspClient.stripCredentials(first.getRtspUrl())
                            + " with no credentials supplied.",
                    "Enable RTSP authentication on the device so the live feed cannot be viewed by anyone "
                            + "who can reach it on the network."));
            logger.warn("{} serves RTSP without authentication", device.getIpAddress());
        }
    }

    /** Flag a device clock that disagrees with this computer. */
    private void recordClockFinding(Device device) {
        Long drift = device.getTimeDifferenceSeconds();
        if (drift == null || Math.abs(drift) <= config.getMaxTimeDriftSeconds()) {
            return;
        }
        device.addFinding(new Finding(Finding.Severity.MEDIUM, "Configuration",
                "Device clock is out of step",
                "The device clock differs from this computer by " + drift + " seconds.",
                "Point the device at an NTP server so recordings carry accurate timestamps."));
    }

    /** Set the final row status and, when there are no streams, say why. */
    private void finalizeStatus(Device device, boolean onvifAuthenticated, boolean rtspChallenged) {
        if (!device.getRtspStreams().isEmpty()) {
            device.setStatus(Device.DeviceStatus.COMPLETED);
            device.setAuthFailed(false);
            device.setErrorMessage(null);
            logger.info("{} completed with {} stream(s)", device.getIpAddress(), device.getRtspStreams().size());
            return;
        }

        boolean videoDevice = onvifAuthenticated
                || !device.getOpenOnvifPorts().isEmpty()
                || !device.getOpenRtspPorts().isEmpty();

        device.setStatus(Device.DeviceStatus.AUTH_FAILED);
        if (!videoDevice) {
            device.setAuthFailed(false);
            device.setType(Device.DeviceType.UNKNOWN);
            device.setErrorMessage("Not a camera or recorder");
        } else if (rtspChallenged || (!onvifAuthenticated && !device.getOpenOnvifPorts().isEmpty())) {
            device.setAuthFailed(true);
            device.setErrorMessage("No credential was accepted");
            device.addFinding(new Finding(Finding.Severity.INFO, "Access", "Could not sign in",
                    "The device rejected every credential supplied.",
                    "Add the correct credentials and retry this device from its context menu."));
        } else {
            device.setAuthFailed(false);
            device.setErrorMessage("No stream path matched");
            device.addFinding(new Finding(Finding.Severity.INFO, "Access", "Stream address unknown",
                    "Credentials were accepted but no known RTSP path returned video.",
                    "Add this model's stream path under Settings, RTSP paths."));
        }
    }

    /**
     * Show dialog to retry authentication with a different credential.
     */
    private void showRetryCredentialDialog(Device device) {
        logger.info("User requested retry for device: {}", device.getIpAddress());

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Retry Authentication");
        dialog.setHeaderText("Retry authentication for " + device.getIpAddress());

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for retry authentication dialog", ex);
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField tfRetryUsername = new TextField("admin");
        tfRetryUsername.setPromptText("Username");

        TextField tfRetryPassword = new TextField();
        tfRetryPassword.setPromptText("Password");

        grid.add(new Label("Username:"), 0, 0);
        grid.add(tfRetryUsername, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(tfRetryPassword, 1, 1);

        dialog.getDialogPane().setContent(grid);

        ButtonType retryButton = new ButtonType("Retry", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = ButtonType.CANCEL;
        dialog.getDialogPane().getButtonTypes().addAll(retryButton, cancelButton);

        // Style buttons
        dialog.setOnShowing(dialogEvent -> {
            Button retryBtn = (Button) dialog.getDialogPane().lookupButton(retryButton);
            Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelButton);

            if (retryBtn != null) {
                retryBtn.setStyle(
                        "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle(
                        "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == retryButton) {
            String username = tfRetryUsername.getText().trim();
            String password = tfRetryPassword.getText().trim();

            if (!username.isEmpty() && !password.isEmpty()) {
                // Check if this credential was already used in discovery
                boolean alreadyUsed = credentials.stream()
                        .anyMatch(c -> c.getUsername().equals(username) && c.getPassword().equals(password));

                if (alreadyUsed) {
                    showAlert("Credential Already Used",
                            "This credential was already tried during discovery and failed.\nPlease enter a different username or password.",
                            Alert.AlertType.WARNING);
                    return;
                }

                // Add to credentials list
                Credential newCred = new Credential(username, password);
                credentials.add(newCred);
                logger.info("Added new credential to list: {}", username);

                // Retry authentication in background
                executorService.submit(() -> {
                    logger.info("Starting RETRY authentication for device: {}", device.getIpAddress());

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

    /** The addresses the scan will cover, de-duplicated across all sources. */
    private TargetParser.Targets getTargets() {
        String text;
        if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
            text = advancedTargetText();
        } else if (rbIpList != null && rbIpList.isSelected() && taIpList != null) {
            text = taIpList.getText();
        } else if (rbCIDR != null && rbCIDR.isSelected() && tfCIDR != null) {
            text = tfCIDR.getText();
        } else if (rbManualRange != null && rbManualRange.isSelected() && tfStartIP != null && tfEndIP != null) {
            text = tfStartIP.getText().trim() + "-" + tfEndIP.getText().trim();
        } else if (rbInterface != null && rbInterface.isSelected() && cbInterfaces != null) {
            // The interface's real subnet, not an assumed /24.
            text = findSelectedInterface(cbInterfaces.getValue())
                    .map(NetworkUtils.LocalInterface::networkCidr)
                    .orElse("");
        } else {
            text = "";
        }
        TargetParser.Targets targets = TargetParser.parse(text);
        if (!targets.invalidTokens().isEmpty()) {
            logger.warn("Ignoring {} unparseable target(s): {}",
                    targets.invalidTokens().size(), targets.invalidTokens());
        }
        return targets;
    }

    private List<String> getIPList() {
        return getTargets().toList(config.getMaxTargets());
    }

    private void exportToExcel() {
        // Step 1: Get Site ID
        TextInputDialog siteDialog = new TextInputDialog();
        siteDialog.setTitle("Export Report");
        siteDialog.setHeaderText("Enter Report Details");
        siteDialog.setContentText("Site ID (required):");

        // Set window icon
        siteDialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) siteDialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for export dialog", ex);
            }
        });

        // Style buttons
        siteDialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) siteDialog.getDialogPane().lookupButton(ButtonType.OK);
            Button cancelBtn = (Button) siteDialog.getDialogPane().lookupButton(ButtonType.CANCEL);

            if (okBtn != null) {
                okBtn.setStyle(
                        "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle(
                        "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

        Optional<String> siteId = siteDialog.showAndWait();
        if (!siteId.isPresent() || siteId.get().trim().isEmpty()) {
            return;
        }

        // Step 2: ask for the password that will encrypt the workbook. The old
        // build derived one from the device count, the date and a fixed code
        // shipped in the application, which anyone with a copy could reproduce.
        String workbookPassword = null;
        if (config.isExportEncryptionDefault()) {
            workbookPassword = promptForExportPassword();
            if (workbookPassword == null) {
                return; // cancelled
            }
            if (workbookPassword.isEmpty()) {
                workbookPassword = null; // export unencrypted by choice
            }
        }
        final String generatedPassword = workbookPassword;

        // Step 3: Choose file location with default from config
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Excel Report");

        // Format: cctv-discovery-report-{SITE ID}-YYYYMMDD-HHMM.xlsx
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(new java.util.Date());
        fileChooser.setInitialFileName("cctv-discovery-report-" + siteId.get() + "-" + timestamp + ".xlsx");

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
                // Export with auto-generated password protection and host audit data
                excelExporter.exportToExcel(new ArrayList<>(devices), siteId.get(), null, null, file, generatedPassword,
                        hostAuditData);

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

    /**
     * Ask for the workbook password.
     *
     * @return the password, an empty string to export unencrypted, or null when
     *         the user cancels
     */
    private String promptForExportPassword() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Protect Report");
        dialog.setHeaderText("Set a password for the exported workbook");
        applyDialogIcon(dialog);

        PasswordField pfPassword = new PasswordField();
        pfPassword.setPromptText("Password");
        PasswordField pfConfirm = new PasswordField();
        pfConfirm.setPromptText("Repeat password");

        Label hint = new Label("""
                The report contains camera addresses and passwords. It is encrypted \
                with this password, so anyone opening it must have it. Leave both \
                boxes empty to save the report without encryption.""");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        Label mismatch = new Label();
        mismatch.setStyle("-fx-text-fill: #A94442; -fx-font-size: 11px;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        grid.add(hint, 0, 0, 2, 1);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(pfPassword, 1, 1);
        grid.add(new Label("Confirm:"), 0, 2);
        grid.add(pfConfirm, 1, 2);
        grid.add(mismatch, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        ButtonType okType = new ButtonType("Save Report", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        Node okButton = dialog.getDialogPane().lookupButton(okType);
        Runnable validate = () -> {
            boolean matched = pfPassword.getText().equals(pfConfirm.getText());
            okButton.setDisable(!matched);
            mismatch.setText(matched ? "" : "The two passwords do not match.");
        };
        pfPassword.textProperty().addListener((obs, old, val) -> validate.run());
        pfConfirm.textProperty().addListener((obs, old, val) -> validate.run());
        Platform.runLater(pfPassword::requestFocus);

        dialog.setResultConverter(button -> button == okType ? pfPassword.getText() : null);
        return dialog.showAndWait().orElse(null);
    }

    /** Give a dialog the application icon. */
    private void applyDialogIcon(Dialog<?> dialog) {
        dialog.setOnShown(e -> {
            try {
                Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
                InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.debug("Could not load the dialog icon", ex);
            }
        });
    }

    private void showSettings() {
        logger.info("Opening settings dialog");
        SettingsDialog settingsDialog = new SettingsDialog(primaryStage);
        settingsDialog.showAndWait();
    }

    private void showHelpManual() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Help");
        dialog.setHeaderText(AppConfig.getInstance().getAppName() + " - Quick Guide");

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for help dialog", ex);
            }
        });

        // Content
        VBox content = new VBox(8);
        content.setPadding(new Insets(12));
        content.setPrefWidth(500);

        Label quickGuide = new Label(
                "Quick Start Guide:\n" +
                        "1. Network Selection:\n" +
                        "   • Simple Mode: Network interface, manual IP range, CIDR, or IP address list\n" +
                        "   • Advanced Mode: Enable to select multiple sources\n" +
                        "2. Add Credentials (Required - Max 4):\n" +
                        "   • Default username 'admin' is pre-filled\n" +
                        "   • Enter password and click 'Add Credential'\n" +
                        "   • Right-click credentials to Edit or Delete\n" +
                        "3. Verification Method:\n" +
                        "   • Quick Check: Fast (~3s), ~60% accurate\n" +
                        "   • Stream Test: Medium (~5s), ~90% accurate\n" +
                        "   • Video Capture: Thorough (~10s), ~98% accurate (Default)\n" +
                        "4. Configure Settings (Optional):\n" +
                        "   • Click 'Settings' to configure custom ports and RTSP paths\n" +
                        "5. Start Discovery:\n" +
                        "   • Click 'Start Discovery' and monitor progress\n" +
                        "6. View Results:\n" +
                        "   • Color-coded: Green (success), Yellow (in progress),\n" +
                        "     Red (failed), Gray (not camera), Blue (discovered)\n" +
                        "   • Right-click failed devices to retry with different credentials\n" +
                        "7. Export Results:\n" +
                        "   • Enter Site ID and click 'Export to Excel'");
        quickGuide.setWrapText(true);
        quickGuide.setStyle("-fx-font-size: 10px;");

        // User Manual and Close buttons - horizontally aligned
        Button btnUserManual = new Button("Open User Manual");
        btnUserManual.setStyle(
                "-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-height: 30px;");
        btnUserManual.setOnAction(e -> openUserManual());

        Button btnCloseHelp = new Button("Close");
        btnCloseHelp.setStyle(
                "-fx-background-color: #ffc107; -fx-text-fill: black; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
        btnCloseHelp.setOnAction(e -> {
            dialog.setResult(null);
            dialog.close();
        });

        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);

        HBox buttonBox = new HBox(10, btnUserManual, buttonSpacer, btnCloseHelp);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        content.getChildren().addAll(quickGuide, new Separator(), buttonBox);
        dialog.getDialogPane().setContent(content);

        // Add a hidden button type so the dialog can close (required by JavaFX Dialog)
        ButtonType hiddenClose = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(hiddenClose);

        // Hide the default button bar since we have custom buttons
        dialog.getDialogPane().lookupButton(hiddenClose).setVisible(false);
        dialog.getDialogPane().lookupButton(hiddenClose).setManaged(false);

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

            // Extract all images dynamically from image-list.txt
            List<String> imageFiles = new ArrayList<>();
            try (InputStream listStream = getClass().getResourceAsStream("/help/images/image-list.txt");
                    BufferedReader reader = listStream != null ? new BufferedReader(new InputStreamReader(listStream))
                            : null) {
                if (reader != null) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            imageFiles.add(line);
                        }
                    }
                    logger.info("Found {} images to extract from image-list.txt", imageFiles.size());
                } else {
                    logger.warn("image-list.txt not found, no images will be extracted");
                }
            } catch (Exception e) {
                logger.warn("Error reading image-list.txt: {}", e.getMessage());
            }

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
            logger.info("Extracted resource: {} to {}", resourcePath, targetFile.getAbsolutePath());
        }

        return targetFile;
    }

    private void disableInputs() {
        // Disable modal buttons and start button during discovery
        btnConfigureNetwork.setDisable(true);
        btnManageCredentials.setDisable(true);
        btnVerificationMethod.setDisable(true);
        btnStart.setDisable(true);
    }

    private void enableInputs() {
        discoveryInProgress = false;
        btnConfigureNetwork.setDisable(false);
        btnManageCredentials.setDisable(false);
        btnVerificationMethod.setDisable(false);
        updateStartButtonState();
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);

        // Apply application icon to all alerts
        try {
            javafx.stage.Stage stage = (javafx.stage.Stage) alert.getDialogPane().getScene().getWindow();
            java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new javafx.scene.image.Image(iconStream));
            }
        } catch (Exception e) {
            logger.info("Could not load icon for alert dialog", e);
        }

        alert.showAndWait();
    }

    private void showNetworkConfigDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Network Configuration");
        dialog.setHeaderText("Configure Network Selection");
        dialog.getDialogPane().setPrefWidth(600);

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for network config dialog", ex);
            }
        });

        // Create tab pane
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Tab 1: Simple Mode
        Tab simpleTab = new Tab("Simple Mode");
        VBox simpleContent = createSimpleNetworkBox();
        simpleContent.setPadding(new Insets(15));
        simpleTab.setContent(simpleContent);

        // Tab 2: Advanced Mode
        Tab advancedTab = new Tab("Advanced Mode");
        VBox advancedContent = createAdvancedNetworkBox();
        advancedContent.setPadding(new Insets(15));
        advancedTab.setContent(advancedContent);

        // Add tabs
        tabPane.getTabs().addAll(simpleTab, advancedTab);

        // Set active tab based on current mode
        if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
            tabPane.getSelectionModel().select(advancedTab);
        }

        // Update IP counts after modal is shown
        Platform.runLater(() -> {
            updateIpCount();
            updateAdvancedIpCount();
        });

        dialog.getDialogPane().setContent(tabPane);

        // Buttons
        ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

        // Style buttons
        dialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) dialog.getDialogPane().lookupButton(okButton);
            Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelButton);

            if (okBtn != null) {
                okBtn.setStyle(
                        "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle(
                        "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == okButton) {
            // User clicked OK - determine which tab was active
            boolean isAdvanced = tabPane.getSelectionModel().getSelectedItem() == advancedTab;

            if (cbAdvancedMode == null) {
                cbAdvancedMode = new CheckBox();
            }
            cbAdvancedMode.setSelected(isAdvanced);

            // Update network summary
            updateNetworkSummary();
            updateStartButtonState();
        }
    }

    /**
     * Describe the current selection in one line. Counting through the parser
     * means overlapping sources are reported once, matching what will be scanned.
     */
    private void updateNetworkSummary() {
        String style = "-fx-font-style: italic; -fx-text-fill: #0078d4;";
        TargetParser.Targets targets = getTargets();
        long count = targets.count();
        networkConfigured = count > 0;

        String description;
        if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
            long sources = networkInterfaces.stream().filter(NetworkInterfaceItem::isSelected).count()
                    + ipRanges.stream()
                            .filter(r -> NetworkUtils.isValidIP(r.getStartIp()) && NetworkUtils.isValidIP(r.getEndIp()))
                            .count()
                    + cidrs.stream().filter(c -> isValidCidr(c.getCidr())).count();
            description = sources == 0
                    ? "No sources selected"
                    : String.format("%d source%s, %s", sources, sources == 1 ? "" : "s", plural(count));
        } else if (rbInterface != null && rbInterface.isSelected() && cbInterfaces.getValue() != null) {
            String network = findSelectedInterface(cbInterfaces.getValue())
                    .map(NetworkUtils.LocalInterface::networkCidr).orElse("unknown");
            description = String.format("%s, %s", network, plural(count));
        } else if (rbManualRange != null && rbManualRange.isSelected()) {
            description = count == 0
                    ? "Enter a valid start and end address"
                    : String.format("%s to %s, %s", tfStartIP.getText().trim(), tfEndIP.getText().trim(), plural(count));
        } else if (rbCIDR != null && rbCIDR.isSelected()) {
            description = count == 0
                    ? "Enter a valid CIDR block"
                    : String.format("%s, %s", tfCIDR.getText().trim(), plural(count));
        } else if (rbIpList != null && rbIpList.isSelected()) {
            description = count == 0 ? "Enter at least one address" : plural(count);
        } else {
            description = "Not configured";
        }

        if (!targets.invalidTokens().isEmpty()) {
            description += String.format(" (%d entry ignored)", targets.invalidTokens().size());
        }
        lblNetworkSummary.setText(description);
        lblNetworkSummary.setStyle(style);
    }

    private static String plural(long count) {
        return count + (count == 1 ? " address" : " addresses");
    }

    private void showCredentialManagementDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Credential Management");
        dialog.setHeaderText("Add and Manage Credentials (Max 4)");
        dialog.getDialogPane().setPrefWidth(500);
        dialog.getDialogPane().setPrefHeight(400);

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.info("Could not load icon for credential management dialog", ex);
            }
        });

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));

        // Username and Password fields
        Label lblUsername = new Label("Username:");
        tfUsername = new TextField("admin");
        tfUsername.setPromptText("Username");

        Label lblPassword = new Label("Password:");
        tfPassword = new TextField();
        tfPassword.setPromptText("Password");

        btnAddCredential = new Button("Add Credential");
        btnAddCredential.setMaxWidth(Double.MAX_VALUE);
        btnAddCredential.setPrefHeight(30);
        btnAddCredential.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold;");
        btnAddCredential.setOnAction(e -> addCredential());

        // Credentials list
        Label lblList = new Label("Added Credentials:");
        lvCredentials = new ListView<>(credentials);
        lvCredentials.setPrefHeight(150);
        lvCredentials.setCellFactory(param -> new CredentialListCell());
        lvCredentials.setContextMenu(createCredentialContextMenu());

        content.getChildren().addAll(
                lblUsername, tfUsername,
                lblPassword, tfPassword,
                btnAddCredential,
                new Separator(),
                lblList, lvCredentials);

        dialog.getDialogPane().setContent(content);

        // OK button
        ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(okButton);

        // Style button
        dialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) dialog.getDialogPane().lookupButton(okButton);
            if (okBtn != null) {
                okBtn.setStyle(
                        "-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

        dialog.showAndWait();

        // Update summary after dialog closes
        updateCredentialSummary();
        updateStartButtonState();
    }

    private void updateCredentialSummary() {
        int count = credentials.size();
        if (count == 0) {
            lblCredentialSummary.setText("No credentials added");
            lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #0078d4;");
        } else {
            lblCredentialSummary.setText(String.format("%d credential%s added", count, count > 1 ? "s" : ""));
            lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #0078d4;");
        }
    }

    public void shutdown() {
        if (rtspService != null) {
            rtspService.shutdown();
        }
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
        private final NetworkUtils.LocalInterface localInterface;
        private boolean selected;

        public NetworkInterfaceItem(NetworkUtils.LocalInterface localInterface, boolean selected) {
            this.localInterface = localInterface;
            this.selected = selected;
        }

        public String getDisplayName() {
            return localInterface.displayName();
        }

        public String getIpAddress() {
            return localInterface.address();
        }

        public NetworkUtils.LocalInterface getLocalInterface() {
            return localInterface;
        }

        /** The network this interface is attached to, e.g. 192.168.0.0/24. */
        public String getNetworkCidr() {
            return localInterface.networkCidr();
        }

        public long getHostCount() {
            return localInterface.hostCount();
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public String toString() {
            return localInterface.toString();
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
