package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.discovery.NetworkScanner;
import com.cctv.discovery.discovery.StreamAnalyzer;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.HostAuditData;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.service.HostAuditService;
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

        // Export Section
        VBox exportSection = createExportSection();

        // Progress Section - AT BOTTOM
        VBox progressSection = createProgressSection();

        // Add all sections in numbered order (Progress at bottom)
        vbox.getChildren().addAll(
                networkSection,
                credentialSection,
                actionSection,
                exportSection,
                progressSection
        );

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
        lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
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
                textField.setPromptText("e.g., 192.168.1.1");

                // Real-time validation
                textField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal == null || newVal.trim().isEmpty()) {
                        textField.setStyle("");
                    } else if (NetworkUtils.isValidIP(newVal.trim())) {
                        textField.setStyle("");
                    } else {
                        textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                    textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                            warning.setContentText(String.format("New Range: %s - %s\nExisting Range: %s - %s\n\nThis is allowed but may cause redundant scanning.",
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
                        textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                    textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                            warning.setContentText(String.format("New Range: %s - %s\nExisting Range: %s - %s\n\nThis is allowed but may cause redundant scanning.",
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

        tvIpRanges.getColumns().addAll(colStartIp, colEndIp);

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
                    } else if (NetworkUtils.isValidCIDR(newVal.trim())) {
                        textField.setStyle("");
                    } else {
                        textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                    alert.setContentText(String.format("CIDR: %s\n\nPlease enter a different CIDR notation.", newValue));
                    alert.showAndWait();

                    // Apply error styling and revert to old value
                    textField.setStyle("-fx-border-color: #dc3545; -fx-border-width: 2px; -fx-background-color: #fff5f5;");
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
                lblAdvancedIpCount
        );

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
        lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
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

    private VBox createActionSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("3. Discovery");
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

        Label lblTitle = new Label("4. Export");
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
        // Custom cell factory for Status column with icons
        colStatus.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    Device device = getTableView().getItems().get(getIndex());
                    String icon = getStatusIcon(device);
                    setText(icon + " " + item);
                    setStyle("-fx-font-weight: bold;");
                }
            }
        });

        TableColumn<Device, String> colName = new TableColumn<>("Device Name");
        colName.setCellValueFactory(new PropertyValueFactory<>("deviceName"));

        TableColumn<Device, String> colManufacturer = new TableColumn<>("Manufacturer");
        colManufacturer.setCellValueFactory(new PropertyValueFactory<>("manufacturer"));

        TableColumn<Device, String> colStreams = new TableColumn<>("Streams");
        colStreams.setCellValueFactory(cellData -> {
            int count = cellData.getValue().getRtspStreams().size();
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(count));
        });
        // Custom cell factory for Streams column with color coding
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
                    if (count > 0) {
                        setStyle("-fx-text-fill: #28a745; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #6c757d;");
                    }
                }
            }
        });

        TableColumn<Device, String> colError = new TableColumn<>("Error");
        colError.setCellValueFactory(new PropertyValueFactory<>("errorMessage"));
        // Custom cell factory for Error column with color coding
        colError.setCellFactory(column -> new TableCell<Device, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.contains("Authentication failed")) {
                        setStyle("-fx-text-fill: #dc3545; -fx-font-weight: bold;");
                    } else if (item.contains("Unknown device type")) {
                        setStyle("-fx-text-fill: #fd7e14; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #6c757d;");
                    }
                }
            }
        });

        tvResults.getColumns().addAll(colIp, colStatus, colName, colManufacturer, colStreams, colError);

        // Add row factory for color-coded backgrounds
        tvResults.setRowFactory(tv -> {
            TableRow<Device> row = new TableRow<Device>() {
                @Override
                protected void updateItem(Device device, boolean empty) {
                    super.updateItem(device, empty);

                    if (empty || device == null) {
                        setStyle("");
                    } else {
                        String backgroundColor = getRowBackgroundColor(device);
                        String baseStyle = "-fx-background-color: " + backgroundColor + "; -fx-padding: 4px;";

                        // Add hover and selection styles
                        setStyle(baseStyle);

                        // Listen for selection changes
                        selectedProperty().addListener((obs, wasSelected, isNowSelected) -> {
                            if (isNowSelected) {
                                setStyle(baseStyle + " -fx-border-color: #0078d4; -fx-border-width: 2px;");
                            } else {
                                setStyle(baseStyle);
                            }
                        });
                    }
                }
            };
            return row;
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
            return "#d4edda"; // Light green - success
        } else if (device.getStatus() == Device.DeviceStatus.AUTHENTICATING) {
            return "#d1ecf1"; // Light blue - in progress
        } else if (device.getStatus() == Device.DeviceStatus.AUTH_FAILED) {
            if (device.isAuthFailed()) {
                return "#f8d7da"; // Light red - authentication failure
            } else {
                return "#fff3cd"; // Light yellow - unknown device type
            }
        } else {
            return "white"; // Default - discovered but not processed
        }
    }

    /**
     * Get status icon based on device status
     */
    private String getStatusIcon(Device device) {
        if (device.getStatus() == Device.DeviceStatus.COMPLETED) {
            return "✓"; // Success
        } else if (device.getStatus() == Device.DeviceStatus.AUTHENTICATING) {
            return "⏳"; // In progress
        } else if (device.getStatus() == Device.DeviceStatus.AUTH_FAILED) {
            if (device.isAuthFailed()) {
                return "✗"; // Authentication failed
            } else {
                return "⚠"; // Unknown device type
            }
        } else {
            return "○"; // Discovered
        }
    }

    private void populateNetworkInterfaces() {
        List<NetworkInterface> interfaces = NetworkUtils.getActiveNetworkInterfaces();
        for (NetworkInterface ni : interfaces) {
            try {
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof java.net.Inet4Address) {
                        String display = inetAddr.getHostAddress() + " - " + ni.getDisplayName();
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
        // Preserve previously selected interfaces
        List<String> selectedIps = new ArrayList<>();
        for (NetworkInterfaceItem item : networkInterfaces) {
            if (item.isSelected()) {
                selectedIps.add(item.getIpAddress());
            }
        }

        // Clear existing items to prevent duplication on repeated modal opens
        networkInterfaces.clear();

        List<NetworkInterface> interfaces = NetworkUtils.getActiveNetworkInterfaces();
        for (NetworkInterface ni : interfaces) {
            try {
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof java.net.Inet4Address) {
                        String display = ni.getDisplayName();
                        String ip = inetAddr.getHostAddress();
                        // Restore selection state if this IP was previously selected
                        boolean wasSelected = selectedIps.contains(ip);
                        networkInterfaces.add(new NetworkInterfaceItem(display, ip, wasSelected));
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
        ipRanges.add(new IpRangeItem("", ""));
        updateAdvancedIpCount();
    }

    private void addCidr() {
        cidrs.add(new CidrItem(""));
        updateAdvancedIpCount();
    }

    private boolean isDuplicateIpRange(String startIp, String endIp) {
        for (IpRangeItem existing : ipRanges) {
            if (existing.getStartIp().equals(startIp) && existing.getEndIp().equals(endIp)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateCidr(String cidr) {
        for (CidrItem existing : cidrs) {
            if (existing.getCidr().equals(cidr)) {
                return true;
            }
        }
        return false;
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
     * @param start1 Start IP of first range
     * @param end1 End IP of first range
     * @param start2 Start IP of second range
     * @param end2 End IP of second range
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

    private void authenticateAndDiscoverStreams(Device device) {
        logger.info("=== Starting authentication for device: {} ===", device.getIpAddress());
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        Platform.runLater(() -> tvResults.refresh());

        // PRIORITY 1: Try ONVIF Discovery
        boolean onvifSuccess = attemptOnvifAuthentication(device);

        // PRIORITY 2: Try RTSP URL Guessing (if ONVIF failed or no streams found)
        boolean rtspSuccess = false;
        if (!onvifSuccess || device.getRtspStreams().isEmpty()) {
            logger.info("Falling back to RTSP URL guessing for {}", device.getIpAddress());
            rtspSuccess = attemptRtspAuthentication(device);
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
            // Check if this is likely NOT a camera (router, printer, NAS, web server, etc.)
            // Use actual success indicators, not just port detection
            boolean hasWsDiscovery = device.getOnvifServiceUrl() != null;
            boolean hasRtspPorts = device.getOpenRtspPorts() != null && !device.getOpenRtspPorts().isEmpty();

            // Device is likely not a camera if:
            // - No WS-Discovery announcement AND
            // - No RTSP ports detected AND
            // - ONVIF didn't actually work (even if ports were detected)
            boolean isLikelyNotCamera = !hasWsDiscovery && !hasRtspPorts && !onvifSuccess;

            if (isLikelyNotCamera) {
                device.setStatus(Device.DeviceStatus.AUTH_FAILED);
                device.setAuthFailed(false); // Not an auth failure - just not a camera
                device.setErrorMessage("Unknown device type");
                logger.info("=== Device {} marked as UNKNOWN DEVICE TYPE (not a camera) ===", device.getIpAddress());
            } else {
                device.setStatus(Device.DeviceStatus.AUTH_FAILED);
                device.setAuthFailed(true);
                device.setErrorMessage("Authentication failed with all credentials");
                logger.warn("=== Device {} authentication FAILED ===", device.getIpAddress());
            }
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

        // Check if RTSP ports exist - if not, skip entirely
        if (device.getOpenRtspPorts() == null || device.getOpenRtspPorts().isEmpty()) {
            logger.info("No RTSP ports detected for {} - skipping RTSP authentication", device.getIpAddress());
            return false;
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

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.debug("Could not load icon for retry authentication dialog", ex);
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
                retryBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
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
            if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
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
                if (rbCIDR != null && rbCIDR.isSelected() && tfCIDR != null) {
                    ips = NetworkUtils.parseCIDR(tfCIDR.getText());
                } else if (rbManualRange != null && rbManualRange.isSelected() && tfStartIP != null && tfEndIP != null) {
                    ips = NetworkUtils.parseIPRange(tfStartIP.getText(), tfEndIP.getText());
                } else if (rbInterface != null && rbInterface.isSelected() && cbInterfaces != null) {
                    // Extract CIDR from interface - simplified to /24
                    // Format is now: IP - DisplayName (e.g., "192.168.1.5 - Ethernet")
                    String selected = cbInterfaces.getValue();
                    if (selected != null) {
                        String[] parts = selected.split(" - ");
                        if (parts.length >= 1) {
                            String ip = parts[0];  // IP is now first part
                            String[] octets = ip.split("\\.");
                            if (octets.length == 4) {
                                String network = octets[0] + "." + octets[1] + "." + octets[2] + ".0/24";
                                ips = NetworkUtils.parseCIDR(network);
                            }
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

        // Set window icon
        siteDialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) siteDialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.debug("Could not load icon for export dialog", ex);
            }
        });

        // Style buttons
        siteDialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) siteDialog.getDialogPane().lookupButton(ButtonType.OK);
            Button cancelBtn = (Button) siteDialog.getDialogPane().lookupButton(ButtonType.CANCEL);

            if (okBtn != null) {
                okBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

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
                excelExporter.exportToExcel(new ArrayList<>(devices), siteId.get(), null, null, file, generatedPassword, hostAuditData);

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

        // Set window icon
        dialog.setOnShown(e -> {
            try {
                javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
                java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.png");
                if (iconStream != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(iconStream));
                }
            } catch (Exception ex) {
                logger.debug("Could not load icon for help dialog", ex);
            }
        });

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

        // Style close button
        dialog.setOnShowing(dialogEvent -> {
            Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeButton);
            if (closeBtn != null) {
                closeBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
        });

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
        // Disable modal buttons and start button during discovery
        btnConfigureNetwork.setDisable(true);
        btnManageCredentials.setDisable(true);
        btnStart.setDisable(true);
    }

    private void enableInputs() {
        discoveryInProgress = false;
        btnConfigureNetwork.setDisable(false);
        btnManageCredentials.setDisable(false);
        updateStartButtonState();
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
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
                logger.debug("Could not load icon for network config dialog", ex);
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
                okBtn.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
            }
            if (cancelBtn != null) {
                cancelBtn.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
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

    private void updateNetworkSummary() {
        if (cbAdvancedMode != null && cbAdvancedMode.isSelected()) {
            // Advanced mode summary
            int sourceCount = 0;
            int totalIps = 0;

            long selectedInterfaces = networkInterfaces.stream().filter(NetworkInterfaceItem::isSelected).count();
            if (selectedInterfaces > 0) {
                sourceCount++;
                totalIps += selectedInterfaces * 254;
            }

            long validRanges = ipRanges.stream()
                    .filter(r -> NetworkUtils.isValidIP(r.getStartIp()) && NetworkUtils.isValidIP(r.getEndIp()))
                    .count();
            if (validRanges > 0) {
                sourceCount++;
                for (IpRangeItem range : ipRanges) {
                    if (NetworkUtils.isValidIP(range.getStartIp()) && NetworkUtils.isValidIP(range.getEndIp())) {
                        totalIps += NetworkUtils.countIPsInRange(range.getStartIp(), range.getEndIp());
                    }
                }
            }

            long validCidrs = cidrs.stream()
                    .filter(c -> NetworkUtils.isValidCIDR(c.getCidr()))
                    .count();
            if (validCidrs > 0) {
                sourceCount++;
                for (CidrItem cidr : cidrs) {
                    if (NetworkUtils.isValidCIDR(cidr.getCidr())) {
                        totalIps += NetworkUtils.countIPsInCIDR(cidr.getCidr());
                    }
                }
            }

            if (sourceCount > 0) {
                lblNetworkSummary.setText(String.format("Advanced: %d source(s), %d possible IPs", sourceCount, totalIps));
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #28a745;");
                networkConfigured = true;
            } else {
                lblNetworkSummary.setText("Advanced mode: No sources configured");
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #dc3545;");
                networkConfigured = false;
            }
        } else {
            // Simple mode summary
            if (rbInterface != null && rbInterface.isSelected() && cbInterfaces.getValue() != null) {
                lblNetworkSummary.setText("Interface: " + cbInterfaces.getValue());
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #28a745;");
                networkConfigured = true;
            } else if (rbManualRange != null && rbManualRange.isSelected() &&
                    NetworkUtils.isValidIP(tfStartIP.getText()) && NetworkUtils.isValidIP(tfEndIP.getText())) {
                int count = NetworkUtils.countIPsInRange(tfStartIP.getText(), tfEndIP.getText());
                lblNetworkSummary.setText(String.format("Range: %s - %s (%d IPs)", tfStartIP.getText(), tfEndIP.getText(), count));
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #28a745;");
                networkConfigured = true;
            } else if (rbCIDR != null && rbCIDR.isSelected() && NetworkUtils.isValidCIDR(tfCIDR.getText())) {
                int count = NetworkUtils.countIPsInCIDR(tfCIDR.getText());
                lblNetworkSummary.setText(String.format("CIDR: %s (%d IPs)", tfCIDR.getText(), count));
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #28a745;");
                networkConfigured = true;
            } else {
                lblNetworkSummary.setText("Not configured");
                lblNetworkSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
                networkConfigured = false;
            }
        }
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
                logger.debug("Could not load icon for credential management dialog", ex);
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
                lblList, lvCredentials
        );

        dialog.getDialogPane().setContent(content);

        // OK button
        ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(okButton);

        // Style button
        dialog.setOnShowing(dialogEvent -> {
            Button okBtn = (Button) dialog.getDialogPane().lookupButton(okButton);
            if (okBtn != null) {
                okBtn.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-weight: bold; -fx-pref-width: 80px; -fx-pref-height: 30px;");
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
            lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
        } else {
            lblCredentialSummary.setText(String.format("%d credential%s added", count, count > 1 ? "s" : ""));
            lblCredentialSummary.setStyle("-fx-font-style: italic; -fx-text-fill: #28a745;");
        }
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
            return ipAddress + " - " + displayName;
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
