package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.discovery.NetworkScanner;
import com.cctv.discovery.discovery.StreamAnalyzer;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.export.ReportWriter;
import com.cctv.discovery.export.ScanReader;
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
import javafx.event.ActionEvent;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.shape.SVGPath;
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
    private PasswordField tfPassword;
    private Button btnAddCredential;
    private ListView<Credential> lvCredentials;
    private ObservableList<Credential> credentials;

    // Credential summary in left panel
    private Label lblCredentialSummary;
    private Button btnManageCredentials;

    // Actions
    private Button btnStart;
    private Button btnStop;
    private Label lblResultSummary;
    private TableView<RTSPStream> tvStreams;
    private ListView<String> lvFindings;
    private Label lblDeviceDetail;
    private volatile boolean cancelRequested;
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
        // Every modal opens over this window and inherits its appearance.
        Modals.setOwner(primaryStage);
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

    /**
     * A tooltip that appears promptly, wraps, and stays long enough to read a
     * full sentence. JavaFX defaults hide after a few seconds.
     */
    private static Tooltip tip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(javafx.util.Duration.millis(350));
        tooltip.setShowDuration(javafx.util.Duration.seconds(30));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(340);
        return tooltip;
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

        Theme.current().applyTo(scene);
        return scene;
    }

    private HBox createHeaderPanel() {
        // Header Panel - Full width spanning entire application
        AppConfig config = AppConfig.getInstance();

        // Organization name in regular font above the tool name
        Label orgLabel = new Label(config.getAppOrganization());
        orgLabel.getStyleClass().add("header-organisation");

        // Tool name in bold title style
        Label title = new Label(config.getAppName());
        title.getStyleClass().add("header-title");

        // Stack org name above tool name
        VBox titleBlock = new VBox(0, orgLabel, title);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        Button btnSettings = new Button("Settings");
        btnSettings.setOnAction(e -> showSettings());
        btnSettings.setTooltip(tip("Ports to scan, extra stream paths and check timings."));
        btnSettings.getStyleClass().add("header-button");

        Button btnHelp = new Button("Help");
        btnHelp.setOnAction(e -> showHelpManual());
        btnHelp.setTooltip(tip("A short guide to running a survey."));
        btnHelp.getStyleClass().addAll("header-button", "header-button-secondary");

        // Spacer to push buttons to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnTheme = createThemeToggle();

        // Right side button container
        HBox buttonBox = new HBox(10, btnTheme, btnSettings, btnHelp);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        // Main header container
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 15, 10, 15));
        header.getStyleClass().add("app-header");
        header.getChildren().addAll(titleBlock, spacer, buttonBox);

        return header;
    }

    /**
     * The appearance control: one icon that cycles light, dark, and following
     * the system.
     *
     * <p>A cycling button rather than a switch because there are three states,
     * not two, and dropping "match the system" to fit a switch would lose the
     * setting most people want. The icon shows the mode now in force and the
     * tooltip names both that and what a click will do, since an icon alone
     * cannot say "currently automatic".
     */
    private Button createThemeToggle() {
        Button button = new Button();
        button.getStyleClass().add("icon-button");
        button.setMinSize(34, 30);
        button.setPrefSize(34, 30);

        applyThemeToggleState(button, Theme.current());
        button.setOnAction(e -> {
            Theme[] order = Theme.values();
            Theme next = order[(Theme.current().ordinal() + 1) % order.length];
            Theme.save(next);
            if (scene != null) {
                next.applyTo(scene);
            }
            applyThemeToggleState(button, next);
            logger.info("Appearance set to {}", next);
        });
        return button;
    }

    /** Point the toggle at a mode: icon, tooltip and the name read aloud. */
    private void applyThemeToggleState(Button button, Theme theme) {
        SVGPath icon = new SVGPath();
        icon.setContent(themeIconPath(theme));
        icon.getStyleClass().add("theme-icon");
        // The glyphs are drawn on a 24-unit grid; scale to fit the button.
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        button.setGraphic(icon);

        Theme next = Theme.values()[(theme.ordinal() + 1) % Theme.values().length];
        String state = switch (theme) {
            case LIGHT -> "Light";
            case DARK -> "Dark";
            case SYSTEM -> "Matching the system, currently "
                    + (Theme.SYSTEM.isDark() ? "dark" : "light");
        };
        button.setTooltip(tip(state + ". Click for " + next.toString().toLowerCase(java.util.Locale.ROOT) + "."));
        // Icon-only controls need a name for anyone using a screen reader.
        button.setAccessibleText("Appearance: " + state);
    }

    /**
     * Glyphs on a 24-unit grid: a sun, a crescent, and a display for the
     * setting that follows the computer.
     */
    private static String themeIconPath(Theme theme) {
        return switch (theme) {
            case LIGHT -> "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z"
                    + "M11 1h2v3.2h-2z M11 19.8h2V23h-2z"
                    + "M1 11h3.2v2H1z M19.8 11H23v2h-3.2z"
                    + "M3.9 5.3l1.4-1.4 2.3 2.3-1.4 1.4z"
                    + "M16.4 17.8l1.4-1.4 2.3 2.3-1.4 1.4z"
                    + "M18.7 3.9l1.4 1.4-2.3 2.3-1.4-1.4z"
                    + "M6.2 16.4l1.4 1.4-2.3 2.3-1.4-1.4z";
            case DARK -> "M12.5 3a9 9 0 1 0 8.5 11.9A7.2 7.2 0 0 1 12.5 3z";
            case SYSTEM -> "M20 3H4a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h5l-1 2v1h8v-1l-1-2h5"
                    + "a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 13H4V5h16v11z";
        };
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

        // Steps in order, with progress pinned underneath.
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        vbox.getChildren().addAll(
                networkSection,
                new Separator(),
                credentialSection,
                new Separator(),
                verificationSection,
                new Separator(),
                discoverySection,
                exportSection,
                spacer,
                progressSection);

        return vbox;
    }

    private VBox createNetworkSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("1. Where to look");
        lblTitle.getStyleClass().add("section-title");

        btnConfigureNetwork = new Button("Choose network...");
        btnConfigureNetwork.setMaxWidth(Double.MAX_VALUE);
        btnConfigureNetwork.setPrefHeight(34);
        btnConfigureNetwork.setOnAction(e -> showNetworkConfigDialog());
        btnConfigureNetwork.setTooltip(tip("""
                Pick the network to scan: an adapter on this computer, an address \
                range, a CIDR block, or a list of addresses."""));

        lblNetworkSummary = new Label("Not configured");
        lblNetworkSummary.getStyleClass().add("summary-label");
        lblNetworkSummary.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, btnConfigureNetwork, lblNetworkSummary);
        return vbox;
    }

    private VBox createSimpleNetworkBox() {
        VBox vbox = new VBox(6);

        // Radio buttons
        ToggleGroup tg = new ToggleGroup();
        rbInterface = new RadioButton("An adapter on this computer");
        rbInterface.setTooltip(tip("Scan the network one of this computer's adapters is on."));
        rbManualRange = new RadioButton("An address range");
        rbManualRange.setTooltip(tip("Scan every address between a first and last address."));
        rbCIDR = new RadioButton("A CIDR block");
        rbCIDR.setTooltip(tip("Scan a whole subnet, for example 192.168.1.0/24."));
        rbIpList = new RadioButton("A list of addresses");
        rbIpList.setTooltip(tip("Scan named addresses only. Ranges and CIDR blocks are accepted here too."));
        rbInterface.setToggleGroup(tg);
        rbManualRange.setToggleGroup(tg);
        rbCIDR.setToggleGroup(tg);
        rbIpList.setToggleGroup(tg);
        rbInterface.setSelected(true);

        // Interface dropdown
        cbInterfaces = new ComboBox<>();
        cbInterfaces.setTooltip(tip("Each entry shows the address and the size of its network."));
        populateNetworkInterfaces();
        cbInterfaces.setMaxWidth(Double.MAX_VALUE);

        // Manual range - side by side
        tfStartIP = new TextField();
        tfStartIP.setPromptText("First address");
        tfStartIP.setDisable(true);
        addIPValidation(tfStartIP);

        tfEndIP = new TextField();
        tfEndIP.setPromptText("Last address");
        tfEndIP.setDisable(true);
        addIPValidation(tfEndIP);

        HBox ipRangeBox = new HBox(8, tfStartIP, tfEndIP);
        HBox.setHgrow(tfStartIP, Priority.ALWAYS);
        HBox.setHgrow(tfEndIP, Priority.ALWAYS);

        // CIDR
        tfCIDR = new TextField();
        tfCIDR.setPromptText("192.168.1.0/24");
        tfCIDR.setTooltip(tip("Network address and prefix length."));
        tfCIDR.setDisable(true);

        // IP Address List - accepts multiple IPs separated by commas, spaces, or newlines
        taIpList = new TextArea();
        taIpList.setPromptText("""
                192.168.1.10, 192.168.1.20
                192.168.1.30-192.168.1.60
                192.168.2.0/24""");
        taIpList.setTooltip(tip("""
                One or more addresses, ranges (10.0.0.1-10.0.0.50 or 10.0.0.1-50) \
                and CIDR blocks, separated by commas, spaces or new lines. \
                Overlapping entries are scanned once."""));
        taIpList.setPrefRowCount(4);
        taIpList.setWrapText(true);
        taIpList.setDisable(true);

        // IP count label - center aligned, bold and colored
        lblIpCount = new Label("Possible IPs: 0");
        lblIpCount.getStyleClass().add("label-info");
        lblIpCount.setAlignment(Pos.CENTER);
        lblIpCount.setMaxWidth(Double.MAX_VALUE);
        lblIpCount.getStyleClass().add("count-label");

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
                        textField.getStyleClass().remove("field-invalid");
                    } else if (NetworkUtils.isValidIP(newVal.trim())) {
                        textField.getStyleClass().remove("field-invalid");
                    } else {
                        textField.getStyleClass().add("field-invalid");
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
                    Modals.error("Duplicate IP Range", "This range is already listed",
                            String.format("%s to %s is already in the list. Enter a different range.",
                                    newValue, range.getEndIp()));

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
                            Modals.warn("Overlapping IP Range", "This range overlaps another",
                                    String.format("%s to %s overlaps %s to %s. That is allowed; "
                                            + "the overlap is scanned once.",
                                            newValue, range.getEndIp(), other.getStartIp(), other.getEndIp()));
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
                        textField.getStyleClass().remove("field-invalid");
                    } else if (NetworkUtils.isValidIP(newVal.trim())) {
                        textField.getStyleClass().remove("field-invalid");
                    } else {
                        textField.getStyleClass().add("field-invalid");
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
                    Modals.error("Duplicate IP Range", "This range is already listed",
                            String.format("%s to %s is already in the list. Enter a different range.",
                                    range.getStartIp(), newValue));

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
                            Modals.warn("Overlapping IP Range", "This range overlaps another",
                                    String.format("%s to %s overlaps %s to %s. That is allowed; "
                                            + "the overlap is scanned once.",
                                            range.getStartIp(), newValue, other.getStartIp(), other.getEndIp()));
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
        btnAddRange.getStyleClass().add("button-success");
        btnAddRange.setOnAction(e -> addIpRange());

        Button btnRemoveRange = new Button("Remove Selected");
        btnRemoveRange.setPrefWidth(120);
        btnRemoveRange.setPrefHeight(30);
        btnRemoveRange.getStyleClass().add("button-secondary");
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
                        textField.getStyleClass().remove("field-invalid");
                    } else if (isValidCidr(newVal.trim())) {
                        textField.getStyleClass().remove("field-invalid");
                    } else {
                        textField.getStyleClass().add("field-invalid");
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
                    Modals.error("Duplicate Block", "This block is already listed",
                            String.format("%s is already in the list. Enter a different block.", newValue));

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
        btnAddCidr.getStyleClass().add("button-success");
        btnAddCidr.setOnAction(e -> addCidr());

        Button btnRemoveCidr = new Button("Remove Selected");
        btnRemoveCidr.setPrefWidth(120);
        btnRemoveCidr.setPrefHeight(30);
        btnRemoveCidr.getStyleClass().add("button-secondary");
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
        lblAdvancedIpCount.getStyleClass().add("count-label");

        vbox.getChildren().addAll(
                lblInterfaces, lvNetworkInterfaces,
                lblRanges, tvIpRanges, rangeButtons,
                lblCidrs, tvCidrs, cidrButtons,
                lblAdvancedIpCount);

        return vbox;
    }

    private VBox createCredentialSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("2. Sign-in details");
        lblTitle.getStyleClass().add("section-title");

        btnManageCredentials = new Button("Add credentials...");
        btnManageCredentials.setMaxWidth(Double.MAX_VALUE);
        btnManageCredentials.setPrefHeight(34);
        btnManageCredentials.setOnAction(e -> showCredentialManagementDialog());
        btnManageCredentials.setTooltip(tip("""
                Usernames and passwords to try on each device. Every credential is \
                tried in turn until one is accepted. Optional: devices that need no \
                password are still found without any."""));

        lblCredentialSummary = new Label("None added (optional)");
        lblCredentialSummary.getStyleClass().add("summary-label");
        lblCredentialSummary.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, btnManageCredentials, lblCredentialSummary);
        return vbox;
    }

    private VBox createProgressSection() {
        VBox vbox = new VBox(6);
        vbox.setPadding(new Insets(10, 0, 0, 0));

        Label lblTitle = new Label("Progress");
        lblTitle.getStyleClass().add("section-title");

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(16);

        lblProgress = new Label("Ready");
        lblProgress.getStyleClass().add("summary-label");
        lblProgress.setMaxWidth(Double.MAX_VALUE);
        lblProgress.setWrapText(true);

        vbox.getChildren().addAll(lblTitle, progressBar, lblProgress);
        return vbox;
    }

    private VBox createVerificationMethodSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("3. How thoroughly to check");
        lblTitle.getStyleClass().add("section-title");

        btnVerificationMethod = new Button("Choose how to check...");
        btnVerificationMethod.setMaxWidth(Double.MAX_VALUE);
        btnVerificationMethod.setPrefHeight(35);
        btnVerificationMethod.setOnAction(e -> showVerificationMethodDialog());
        btnVerificationMethod.setTooltip(tip("""
                How thoroughly each stream is checked. A more thorough check \
                takes longer but is less likely to report a stream that does \
                not actually play."""));

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
        Dialog<ButtonType> dialog = Modals.dialog("Verification Method", "How thoroughly to check",
                "A more thorough check takes longer but gives a more reliable answer.");
        dialog.getDialogPane().setPrefWidth(540);

        VBox content = Modals.content();
        content.setSpacing(12);

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
        } else if ("RTP_PACKET".equals(selectedValidationMethod)) {
            rb2.setSelected(true);
        } else {
            rb3.setSelected(true);
        }

        Runnable markSelection = () -> {
            setCardSelected(card1, rb1.isSelected());
            setCardSelected(card2, rb2.isSelected());
            setCardSelected(card3, rb3.isSelected());
        };
        markSelection.run();
        validationGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> markSelection.run());

        content.getChildren().addAll(card1, card2, card3);
        dialog.getDialogPane().setContent(content);

        ButtonType okButton = new ButtonType("Use this method", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
        Modals.primary(dialog, okButton);
        Modals.secondary(dialog, cancelButton);

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
        card.getStyleClass().add("choice-card");

        RadioButton rb = new RadioButton();
        rb.setToggleGroup(group);

        // Title row with radio button
        Label lblTitle = new Label(title);
        lblTitle.getStyleClass().add("choice-card-title");

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().addAll(rb, lblTitle);

        if (recommended) {
            Label badge = new Label("Recommended");
            badge.getStyleClass().add("badge");
            titleRow.getChildren().add(badge);
        }

        Label lblDesc = new Label(description);
        lblDesc.getStyleClass().add("choice-card-detail");
        lblDesc.setPadding(new Insets(0, 0, 0, 24));

        Label lblStats = new Label(stats);
        lblStats.getStyleClass().add("choice-card-cost");
        lblStats.setPadding(new Insets(0, 0, 0, 24));

        card.getChildren().addAll(titleRow, lblDesc, lblStats);

        // Store radio button reference on the card
        card.setUserData(rb);

        // Click anywhere on card to select
        card.setOnMouseClicked(e -> rb.setSelected(true));

        return card;
    }

    /**
     * Mark a card as the chosen one. A style class rather than an inline style,
     * so the selected look follows the theme instead of being a fixed blue that
     * belongs to neither palette.
     */
    private static void setCardSelected(VBox card, boolean selected) {
        card.getStyleClass().remove("choice-card-selected");
        if (selected) {
            card.getStyleClass().add("choice-card-selected");
        }
    }

    private VBox createDiscoverySection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("4. Run the scan");
        lblTitle.getStyleClass().add("section-title");

        btnStart = new Button("Start scan");
        btnStart.getStyleClass().add("button-success");
        btnStart.setMaxWidth(Double.MAX_VALUE);
        btnStart.setPrefHeight(38);
        btnStart.setDisable(true);
        btnStart.setOnAction(e -> startDiscovery());

        btnStop = new Button("Stop");
        btnStop.getStyleClass().add("button-danger");
        btnStop.setMaxWidth(Double.MAX_VALUE);
        btnStop.setPrefHeight(30);
        btnStop.setVisible(false);
        btnStop.setManaged(false);
        btnStop.setOnAction(e -> stopDiscovery());
        btnStop.setTooltip(tip("Stop the scan. Everything found so far is kept and can be exported."));

        vbox.getChildren().addAll(lblTitle, btnStart, btnStop);
        updateStartButtonState();
        return vbox;
    }

    private VBox createExportSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("5. Report");
        lblTitle.getStyleClass().add("section-title");

        btnExport = new Button("Export report...");
        btnExport.setMaxWidth(Double.MAX_VALUE);
        btnExport.setPrefHeight(34);
        btnExport.setDisable(true);
        btnExport.setOnAction(e -> exportToExcel());

        Button btnOpen = new Button("Open a saved scan...");
        btnOpen.getStyleClass().add("button-secondary");
        btnOpen.setMaxWidth(Double.MAX_VALUE);
        btnOpen.setPrefHeight(30);
        btnOpen.setOnAction(e -> openSavedScan());
        btnOpen.setTooltip(tip("""
                Load a scan previously exported as JSON, to review it or compare                 it with what is on site now."""));
        btnExport.setTooltip(tip("""
                Write the findings to an Excel workbook. You choose whether to \
                include camera passwords and whether to encrypt the file."""));

        vbox.getChildren().addAll(lblTitle, btnExport, btnOpen);
        return vbox;
    }

    private VBox createRightPanel() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(12));

        Label lblTitle = new Label("Devices found");
        lblTitle.getStyleClass().add("section-title");

        lblResultSummary = new Label("No scan has run yet.");
        lblResultSummary.getStyleClass().add("summary-label");

        HBox titleRow = new HBox(12, lblTitle, lblResultSummary);
        titleRow.setAlignment(Pos.BASELINE_LEFT);

        tvResults = buildResultsTable();
        VBox details = buildDetailsPane();

        SplitPane split = new SplitPane(tvResults, details);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);

        vbox.getChildren().addAll(titleRow, split);
        return vbox;
    }

    private TableView<Device> buildResultsTable() {
        TableView<Device> table = new TableView<>(devices);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Choose a network and start a scan."));

        table.getColumns().add(textColumn("Address", 130, Device::getIpAddress));
        table.getColumns().add(textColumn("Status", 105, d -> d.getStatus().label()));
        table.getColumns().add(textColumn("Type", 110, Device::getDeviceType));
        table.getColumns().add(textColumn("Name", 150, Device::getDeviceName));
        table.getColumns().add(textColumn("Make", 110, Device::getManufacturer));
        table.getColumns().add(textColumn("Model", 140, Device::getModel));

        TableColumn<Device, String> streams = textColumn("Streams", 85,
                d -> String.valueOf(d.getRtspStreams().size()));
        streams.setStyle("-fx-alignment: CENTER;");
        table.getColumns().add(streams);

        TableColumn<Device, String> findings = textColumn("Issues", 75,
                d -> d.getFindings().isEmpty() ? "" : String.valueOf(d.getFindings().size()));
        findings.setStyle("-fx-alignment: CENTER;");
        table.getColumns().add(findings);

        table.getColumns().add(textColumn("Note", 180, Device::getErrorMessage));

        table.setRowFactory(tv -> {
            TableRow<Device> row = new TableRow<>() {
                @Override
                protected void updateItem(Device device, boolean empty) {
                    super.updateItem(device, empty);
                    getStyleClass().removeAll("row-completed", "row-working", "row-auth-failed",
                            "row-unknown", "row-pending");
                    if (!empty && device != null) {
                        getStyleClass().add(rowStyleClass(device));
                    }
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showRetryCredentialDialog(row.getItem());
                }
            });
            return row;
        });

        MenuItem retry = new MenuItem("Try other credentials...");
        retry.setOnAction(e -> {
            Device selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showRetryCredentialDialog(selected);
            }
        });
        MenuItem copyUrls = new MenuItem("Copy stream addresses");
        copyUrls.setOnAction(e -> copyStreamUrls(table.getSelectionModel().getSelectedItem()));
        ContextMenu menu = new ContextMenu(retry, copyUrls);
        table.setContextMenu(menu);
        table.setOnContextMenuRequested(e -> {
            boolean none = table.getSelectionModel().getSelectedItem() == null;
            retry.setDisable(none);
            copyUrls.setDisable(none);
        });

        table.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> showDeviceDetails(selected));
        return table;
    }

    private TableColumn<Device, String> textColumn(String title, double width,
                                                   java.util.function.Function<Device, String> getter) {
        TableColumn<Device, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> {
            String value = getter.apply(data.getValue());
            return new javafx.beans.property.SimpleStringProperty(value == null ? "" : value);
        });
        return column;
    }

    /** Style class driving the row colour; the palette lives in app.css. */
    private static String rowStyleClass(Device device) {
        return switch (device.getStatus()) {
            case COMPLETED -> "row-completed";
            case AUTHENTICATING, ANALYZING, SCANNING -> "row-working";
            case AUTH_FAILED -> device.isAuthFailed() ? "row-auth-failed" : "row-unknown";
            default -> "row-pending";
        };
    }

    /** Streams and findings for whichever device is selected. */
    private VBox buildDetailsPane() {
        lblDeviceDetail = new Label("Select a device to see its streams and any issues.");
        lblDeviceDetail.getStyleClass().add("summary-label");
        lblDeviceDetail.setWrapText(true);

        tvStreams = new TableView<>();
        tvStreams.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tvStreams.setPlaceholder(new Label("No streams"));
        tvStreams.setPrefHeight(150);

        tvStreams.getColumns().add(streamColumn("Stream", 120, RTSPStream::getStreamName));
        tvStreams.getColumns().add(streamColumn("Role", 60, s2 -> s2.getRole().label()));
        tvStreams.getColumns().add(streamColumn("Resolution", 100, RTSPStream::getResolution, "Resolution"));
        tvStreams.getColumns().add(streamColumn("Codec", 70, RTSPStream::getCodec, "Codec"));
        tvStreams.getColumns().add(streamColumn("Profile", 95, RTSPStream::getProfile, "profile"));
        tvStreams.getColumns().add(streamColumn("kbps", 70,
                s2 -> s2.getBitrateKbps() == null ? "" : String.valueOf(s2.getBitrateKbps()), "Bitrate"));
        tvStreams.getColumns().add(streamColumn("fps", 60,
                s2 -> s2.getFps() == null ? "" : String.format("%.1f", s2.getFps())));
        tvStreams.getColumns().add(streamColumn("Address", 260, RTSPStream::getRtspUrl));
        tvStreams.setTooltip(tip("Double-click a device above to retry it with other credentials."));

        lvFindings = new ListView<>();
        lvFindings.setPrefHeight(90);
        lvFindings.setPlaceholder(new Label("No issues found"));
        lvFindings.setTooltip(tip("Problems worth acting on, most serious first."));

        TitledPane streamPane = new TitledPane("Streams", tvStreams);
        streamPane.setCollapsible(false);
        TitledPane findingPane = new TitledPane("Issues", lvFindings);
        findingPane.setCollapsible(false);

        VBox box = new VBox(6, lblDeviceDetail, streamPane, findingPane);
        box.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(streamPane, Priority.ALWAYS);
        return box;
    }

    private TableColumn<RTSPStream, String> streamColumn(String title, double width,
                                                          java.util.function.Function<RTSPStream, String> getter) {
        return streamColumn(title, width, getter, null);
    }

    /**
     * A stream column. When {@code issueKeyword} is given, the cell is marked
     * only if the compliance text mentions it, so a single broken rule does not
     * paint the whole row as wrong.
     */
    private TableColumn<RTSPStream, String> streamColumn(String title, double width,
                                                          java.util.function.Function<RTSPStream, String> getter,
                                                          String issueKeyword) {
        TableColumn<RTSPStream, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> {
            String value = getter.apply(data.getValue());
            return new javafx.beans.property.SimpleStringProperty(value == null ? "" : value);
        });
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().remove("cell-flagged");
                if (empty || issueKeyword == null || getTableRow() == null) {
                    return;
                }
                RTSPStream stream = getTableRow().getItem();
                if (stream != null && stream.getComplianceIssues() != null
                        && stream.getComplianceIssues().toLowerCase(java.util.Locale.ROOT)
                                .contains(issueKeyword.toLowerCase(java.util.Locale.ROOT))) {
                    getStyleClass().add("cell-flagged");
                }
            }
        });
        return column;
    }

    /** Fill the details pane for one device. */
    private void showDeviceDetails(Device device) {
        if (device == null) {
            lblDeviceDetail.setText("Select a device to see its streams and any issues.");
            tvStreams.getItems().clear();
            lvFindings.getItems().clear();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(device.getIpAddress());
        if (device.getMacAddress() != null) {
            sb.append("  •  ").append(device.getMacAddress());
        }
        if (device.getVendorFromMac() != null && !MacLookupService.UNKNOWN.equals(device.getVendorFromMac())) {
            sb.append(" (").append(device.getVendorFromMac()).append(')');
        }
        if (device.getFirmwareVersion() != null) {
            sb.append("  •  firmware ").append(device.getFirmwareVersion());
        }
        if (!device.getAllOpenPorts().isEmpty()) {
            sb.append("  •  ports ").append(device.getAllOpenPorts());
        }
        if (device.getTimeDifferenceSeconds() != null) {
            sb.append("  •  clock ").append(device.getTimeDifferenceSeconds()).append("s from this computer");
        }
        lblDeviceDetail.setText(sb.toString());

        tvStreams.getItems().setAll(device.getRtspStreams());
        lvFindings.getItems().setAll(device.getFindings().stream()
                .sorted(java.util.Comparator.comparingInt(f -> f.severity().ordinal()))
                .map(f -> f.severity().label() + " - " + f.title() + ": " + f.recommendation())
                .toList());
    }

    /** Put the selected device's stream addresses on the clipboard. */
    private void copyStreamUrls(Device device) {
        if (device == null || device.getRtspStreams().isEmpty()) {
            return;
        }
        String text = device.getRtspStreams().stream()
                .map(RTSPStream::getRtspUrl)
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("");
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        lblProgress.setText("Copied " + device.getRtspStreams().size() + " stream address(es).");
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
                textField.getStyleClass().remove("field-invalid");
            } else if (NetworkUtils.isValidIP(newValue.trim())) {
                // Valid IP - remove error styling
                textField.getStyleClass().remove("field-invalid");
            } else {
                // Invalid IP - show red styling
                textField.getStyleClass().add("field-invalid");
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
        int limit = config.getMaxCredentials();
        if (credentials.size() >= limit) {
            showAlert("Enough credentials",
                    "Up to " + limit + " credentials can be tried on each device.", Alert.AlertType.WARNING);
            return;
        }

        String username = tfUsername.getText().trim();
        String password = tfPassword.getText();

        if (username.isEmpty()) {
            showAlert("Invalid Input", "Enter a username. A blank password is allowed.", Alert.AlertType.WARNING);
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

        if (credentials.size() >= limit) {
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

    /**
     * The scan needs a network. Credentials are optional: plenty of devices
     * answer without any, and the test camera serves RTSP with none at all.
     * Requiring them, as the old build did, blocked those scans entirely.
     */
    private void updateStartButtonState() {
        if (btnStart == null) {
            return;
        }
        btnStart.setDisable(!networkConfigured || discoveryInProgress);
        if (!networkConfigured) {
            btnStart.setTooltip(tip("Choose a network first."));
        } else if (credentials.isEmpty()) {
            btnStart.setTooltip(tip("""
                    Scan without credentials. Devices that require a password will \
                    be listed but their streams cannot be checked."""));
        } else {
            btnStart.setTooltip(tip("Scan the chosen network."));
        }
        if (discoveryCompleted && !discoveryInProgress) {
            btnStart.setText("Scan again");
        }
    }

    private void startDiscovery() {
        discoveryInProgress = true;
        cancelRequested = false;
        disableInputs();
        devices.clear();
        showDeviceDetails(null);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lblProgress.setText("Starting...");
        lblResultSummary.setText("");
        btnStop.setVisible(true);
        btnStop.setManaged(true);

        configureRtspValidation();
        rtspService.reset();

        executorService.submit(() -> {
            try {
                runDiscovery();
            } catch (Exception e) {
                logger.error("Scan failed", e);
                Platform.runLater(() -> {
                    showAlert("Scan failed", String.valueOf(e.getMessage()), Alert.AlertType.ERROR);
                    finishDiscovery();
                });
            }
        });
    }

    /** Ask every running stage to stop; partial results are kept. */
    private void stopDiscovery() {
        cancelRequested = true;
        btnStop.setDisable(true);
        lblProgress.setText("Stopping...");
        networkScanner.cancel();
        rtspService.shutdown();
        streamAnalyzer.cancel();
        logger.info("Scan cancelled by the user");
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

    /**
     * Run the scan: announce, scan, identify, measure.
     *
     * <p>Devices appear in the table as they are found, and identification runs
     * several devices at a time. The previous version collected everything
     * first, then worked through the list one device at a time, and stopped
     * mid-scan to ask a question in a modal dialog.
     */
    private void runDiscovery() {
        long started = System.currentTimeMillis();

        progress(0.02, "Listening for cameras that announce themselves...");
        List<Device> announced = networkScanner.performWsDiscovery();
        if (!announced.isEmpty()) {
            publish(announced);
            progress(0.08, "Found " + announced.size() + " device(s) by announcement.");
        }
        if (cancelRequested) {
            Platform.runLater(this::finishDiscovery);
            return;
        }

        TargetParser.Targets targets = getTargets();
        List<Device> scanned = List.of();
        if (!targets.isEmpty()) {
            long total = targets.count();
            progress(0.1, "Scanning " + total + " address(es)...");
            scanned = networkScanner.performPortScan(targets, (current, count) -> {
                if (current % 16 == 0 || current == count) {
                    progress(0.1 + 0.35 * current / count, "Scanning address " + current + " of " + count);
                }
            });
        }

        List<Device> all = networkScanner.mergeDeviceLists(announced, scanned);
        Platform.runLater(() -> {
            devices.setAll(all);
            updateResultSummary();
        });

        if (all.isEmpty() || cancelRequested) {
            Platform.runLater(this::finishDiscovery);
            return;
        }

        identifyDevices(all);
        if (!cancelRequested) {
            measureStreams(all);
        }

        long seconds = (System.currentTimeMillis() - started) / 1000;
        Platform.runLater(() -> {
            lblProgress.setText(cancelRequested
                    ? "Stopped after " + seconds + "s."
                    : "Finished in " + seconds + "s.");
            discoveryCompleted = true;
            finishDiscovery();
        });
    }

    /** Identify devices in parallel, bounded by the configured fan-out. */
    private void identifyDevices(List<Device> all) {
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Semaphore permits =
                new java.util.concurrent.Semaphore(config.getDeviceParallelism());

        try (var scope = java.util.concurrent.StructuredTaskScope.open(
                java.util.concurrent.StructuredTaskScope.Joiner.<Void>awaitAll(),
                cfg -> cfg.withName("identify"))) {
            for (Device device : all) {
                scope.fork(() -> {
                    if (cancelRequested) {
                        return null;
                    }
                    permits.acquire();
                    try {
                        authenticateAndDiscoverStreams(device);
                    } catch (Exception e) {
                        logger.warn("Could not identify {}: {}", device.getIpAddress(), e.toString());
                        device.setStatus(Device.DeviceStatus.ERROR);
                        device.setErrorMessage(String.valueOf(e.getMessage()));
                    } finally {
                        permits.release();
                    }
                    int count = done.incrementAndGet();
                    progress(0.45 + 0.35 * count / all.size(),
                            "Identified " + count + " of " + all.size() + " devices");
                    Platform.runLater(() -> {
                        tvResults.refresh();
                        updateResultSummary();
                    });
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Measure the streams of every device that has any. */
    private void measureStreams(List<Device> all) {
        List<Device> withStreams = all.stream().filter(d -> !d.getRtspStreams().isEmpty()).toList();
        if (withStreams.isEmpty()) {
            return;
        }
        int index = 0;
        for (Device device : withStreams) {
            if (cancelRequested) {
                return;
            }
            device.setStatus(Device.DeviceStatus.ANALYZING);
            Platform.runLater(tvResults::refresh);
            streamAnalyzer.analyzeDevice(device);
            device.setStatus(device.getRtspStreams().isEmpty()
                    ? Device.DeviceStatus.AUTH_FAILED : Device.DeviceStatus.COMPLETED);
            index++;
            progress(0.8 + 0.2 * index / withStreams.size(),
                    "Measured " + index + " of " + withStreams.size() + " devices");
            Device shown = tvResults.getSelectionModel().getSelectedItem();
            Platform.runLater(() -> {
                tvResults.refresh();
                updateResultSummary();
                if (shown == device) {
                    showDeviceDetails(device);
                }
            });
        }
    }

    /** Add devices to the table as soon as they are known. */
    private void publish(List<Device> found) {
        Platform.runLater(() -> {
            for (Device device : found) {
                if (devices.stream().noneMatch(d -> d.getIpAddress().equals(device.getIpAddress()))) {
                    devices.add(device);
                }
            }
            updateResultSummary();
        });
    }

    private void progress(double fraction, String message) {
        Platform.runLater(() -> {
            progressBar.setProgress(Math.min(1.0, Math.max(0.0, fraction)));
            lblProgress.setText(message);
        });
    }

    /** One line above the table counting what was found. */
    private void updateResultSummary() {
        long withStreams = devices.stream().filter(d -> !d.getRtspStreams().isEmpty()).count();
        long streams = devices.stream().mapToLong(d -> d.getRtspStreams().size()).sum();
        long issues = devices.stream().mapToLong(d -> d.getFindings().size()).sum();
        if (devices.isEmpty()) {
            lblResultSummary.setText("");
            return;
        }
        lblResultSummary.setText(String.format("%d device%s, %d with video, %d stream%s, %d issue%s",
                devices.size(), devices.size() == 1 ? "" : "s",
                withStreams, streams, streams == 1 ? "" : "s", issues, issues == 1 ? "" : "s"));
    }

    /** Return the window to its idle state. */
    private void finishDiscovery() {
        // Keep what this run learned about stream paths for the next survey.
        rtspService.saveLearnedPaths();
        progressBar.setProgress(cancelRequested ? 0 : 1.0);
        btnStop.setVisible(false);
        btnStop.setManaged(false);
        btnStop.setDisable(false);
        btnExport.setDisable(devices.isEmpty());
        updateExportButtonColor();
        updateResultSummary();
        enableInputs();
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
            btnExport.getStyleClass().add("button-success");
        } else {
            // Amber if all devices failed
            btnExport.getStyleClass().add("button-secondary");
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

        // A device reachable only over its maker's own protocol is worth
        // calling out, because no amount of RTSP probing will reach it.
        for (int port : device.getOpenSpecialPorts()) {
            String vendor = NetworkScanner.vendorForSdkPort(port);
            if (vendor != null) {
                device.addFinding(new Finding(Finding.Severity.INFO, "Access",
                        "Reachable only over the manufacturer's own protocol",
                        vendor + " management port " + port + " is open, but no ONVIF or RTSP service answered.",
                        "Enable ONVIF and RTSP on the device, or record it from " + vendor
                                + "'s own software; this tool speaks ONVIF and RTSP only."));
                break;
            }
        }
    }

    /**
     * Show dialog to retry authentication with a different credential.
     */
    private void showRetryCredentialDialog(Device device) {
        logger.info("User requested retry for device: {}", device.getIpAddress());

        Dialog<ButtonType> dialog = Modals.dialog("Retry Sign-in", "Try other sign-in details",
                "For " + device.getIpAddress() + ". What works here is kept for the devices after it.");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField tfRetryUsername = new TextField("admin");
        tfRetryUsername.setPromptText("Username");
        tfRetryUsername.setTooltip(tip("The account name configured on this device."));

        // Masked, like the main credential dialog: a survey is often run with
        // someone watching over the operator's shoulder.
        PasswordField tfRetryPassword = new PasswordField();
        tfRetryPassword.setPromptText("Password");
        tfRetryPassword.setTooltip(tip("Tried against this device only, and added to the list for later devices."));

        grid.add(new Label("Username"), 0, 0);
        grid.add(tfRetryUsername, 1, 0);
        grid.add(new Label("Password"), 0, 1);
        grid.add(tfRetryPassword, 1, 1);

        dialog.getDialogPane().setContent(Modals.content(grid));

        ButtonType retryButton = new ButtonType("Retry", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = ButtonType.CANCEL;
        dialog.getDialogPane().getButtonTypes().addAll(retryButton, cancelButton);
        Modals.primary(dialog, retryButton);
        Modals.secondary(dialog, cancelButton);

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == retryButton) {
            String username = tfRetryUsername.getText().trim();
            // Not trimmed: a leading or trailing space is a valid part of a password.
            String password = tfRetryPassword.getText();

            if (!username.isEmpty()) {
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
                showAlert("Invalid Input", "Enter a username. A blank password is allowed.", Alert.AlertType.WARNING);
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

    /**
     * Collect the report details, then write the workbook.
     */
    private void exportToExcel() {
        ExportRequest request = promptForExportDetails();
        if (request == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save report");
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .format(java.time.LocalDateTime.now());
        fileChooser.setInitialFileName("cctv-report-" + safeFileName(request.siteId()) + "-" + timestamp
                + "." + request.format().extension());

        File initialDir = new File(config.getExportDefaultDirectory());
        fileChooser.setInitialDirectory(initialDir.isDirectory()
                ? initialDir : new File(System.getProperty("user.home")));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                request.format().toString(), "*." + request.format().extension()));

        File file = fileChooser.showSaveDialog(primaryStage);
        if (file == null) {
            return;
        }

        List<Device> snapshot = new ArrayList<>(devices);
        try {
            boolean encrypted = false;
            switch (request.format()) {
                case EXCEL -> {
                    ExcelExporter.ReportOptions options = new ExcelExporter.ReportOptions(
                            request.siteId(), request.premise(), request.surveyor(),
                            request.includeCredentials(), request.password());
                    excelExporter.export(snapshot, hostAuditData, options, file);
                    encrypted = options.encrypted();
                }
                case CSV -> ReportWriter.writeCsv(snapshot, request.includeCredentials(), file.toPath());
                case JSON -> ReportWriter.writeJson(snapshot, request.siteId(),
                        request.includeCredentials(), file.toPath());
            }

            String protection = encrypted
                    ? "The file is encrypted. It cannot be opened without the password you set."
                    : "The file is not encrypted. Anyone with it can read it.";
            String credentials = request.includeCredentials()
                    ? "\n\nIt contains camera usernames and passwords."
                    : "";
            showAlert("Report saved", file.getAbsolutePath() + "\n\n" + protection + credentials,
                    Alert.AlertType.INFORMATION);
            logger.info("Report written for site {} as {} ({})", request.siteId(), request.format(),
                    encrypted ? "encrypted" : "unencrypted");
        } catch (Exception e) {
            logger.error("Export failed", e);
            showAlert("Could not save the report", e.getMessage(), Alert.AlertType.ERROR);
        }
    }

    /**
     * Load a scan saved as JSON, replacing what is on screen.
     *
     * <p>Loaded devices are shown exactly as they were recorded; nothing is
     * re-probed, so the view is a record of that survey rather than of the
     * network now.
     */
    private void openSavedScan() {
        if (discoveryInProgress) {
            showAlert("Scan running", "Stop the scan before opening a saved one.", Alert.AlertType.WARNING);
            return;
        }
        if (!devices.isEmpty()) {
            boolean replace = Modals.confirm("Open a Saved Scan", "Replace these results?",
                    "Opening a saved scan clears what is on screen. Export it first if you need it.",
                    "Replace");
            if (!replace) {
                return;
            }
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a saved scan");
        File initialDir = new File(config.getExportDefaultDirectory());
        chooser.setInitialDirectory(initialDir.isDirectory()
                ? initialDir : new File(System.getProperty("user.home")));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Saved scan", "*.json"));

        File file = chooser.showOpenDialog(primaryStage);
        if (file == null) {
            return;
        }

        try {
            ScanReader.Scan scan = ScanReader.read(file.toPath());
            devices.setAll(scan.devices());
            showDeviceDetails(null);
            updateResultSummary();
            discoveryCompleted = true;
            btnExport.setDisable(devices.isEmpty());
            updateExportButtonColor();
            progressBar.setProgress(1.0);
            lblProgress.setText(String.format("Opened %s (%s)",
                    scan.site() == null ? file.getName() : scan.site(),
                    scan.generated() == null ? "date unknown" : scan.generated()));
            if (!scan.includedCredentials()) {
                lblProgress.setText(lblProgress.getText() + " — saved without credentials");
            }
            logger.info("Opened a saved scan of {} device(s) from {}", scan.devices().size(), file);
        } catch (ScanReader.NotAScanException e) {
            showAlert("Not a saved scan", e.getMessage(), Alert.AlertType.WARNING);
        } catch (Exception e) {
            logger.error("Could not open {}", file, e);
            showAlert("Could not open that file", String.valueOf(e.getMessage()), Alert.AlertType.ERROR);
        }
    }

    /** Strip characters that cannot appear in a file name. */
    private static String safeFileName(String text) {
        String cleaned = text.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "site" : cleaned;
    }

    /** The file formats a report can be written in. */
    private enum ExportFormat {
        EXCEL("Excel workbook (.xlsx)", "xlsx"),
        CSV("Comma-separated values (.csv)", "csv"),
        JSON("JSON (.json)", "json");

        private final String label;
        private final String extension;

        ExportFormat(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        @Override
        public String toString() {
            return label;
        }

        String extension() {
            return extension;
        }

        /** Only the workbook can be encrypted; the text formats cannot. */
        boolean supportsEncryption() {
            return this == EXCEL;
        }
    }

    /** What the user chose in the export dialog. */
    private record ExportRequest(String siteId, String premise, String surveyor,
                                 boolean includeCredentials, String password, ExportFormat format) {
    }

    /**
     * One dialog for the report details, what to include and the password.
     * The old flow asked for the site in one dialog and derived the password
     * from the device count, the date and a fixed code compiled into the
     * application, which anyone holding a copy could reproduce.
     */
    private ExportRequest promptForExportDetails() {
        Dialog<ExportRequest> dialog = Modals.dialog("Export Report", "Report details",
                "These appear on the summary sheet and in the file name.");

        TextField tfSite = new TextField();
        tfSite.setPromptText("Required, for example BLR-WH-02");
        tfSite.setTooltip(tip("Identifies the site in the report and in the file name."));

        TextField tfPremise = new TextField();
        tfPremise.setPromptText("Optional");
        tfPremise.setTooltip(tip("The building or area surveyed."));

        TextField tfSurveyor = new TextField(System.getProperty("user.name", ""));
        tfSurveyor.setTooltip(tip("Recorded on the summary sheet as who ran the survey."));

        CheckBox cbCredentials = new CheckBox("Include camera usernames and passwords");
        cbCredentials.setSelected(config.isExportIncludeCredentialsDefault());
        cbCredentials.setTooltip(tip("""
                Ticked, the report carries the credentials that worked and stream \
                URLs that include them, which is what an installer needs. Unticked, \
                both are left out so the file can be shared more widely."""));

        ComboBox<ExportFormat> cbFormat = new ComboBox<>();
        cbFormat.getItems().setAll(ExportFormat.values());
        cbFormat.getSelectionModel().select(ExportFormat.EXCEL);
        cbFormat.setMaxWidth(Double.MAX_VALUE);
        cbFormat.setTooltip(tip("""
                Excel is the formatted report. CSV suits a spreadsheet or \
                analysis tool. JSON suits feeding another system."""));

        CheckBox cbEncrypt = new CheckBox("Encrypt the workbook with a password");
        cbEncrypt.setSelected(config.isExportEncryptionDefault());
        cbEncrypt.setTooltip(tip("Excel will ask for this password before opening the file."));

        PasswordField pfPassword = new PasswordField();
        pfPassword.setPromptText("Password");
        PasswordField pfConfirm = new PasswordField();
        pfConfirm.setPromptText("Repeat password");

        Label message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(420);
        message.getStyleClass().add("label-error");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label("Site ID"), 0, row);
        grid.add(tfSite, 1, row++);
        grid.add(new Label("Premise"), 0, row);
        grid.add(tfPremise, 1, row++);
        grid.add(new Label("Surveyed by"), 0, row);
        grid.add(tfSurveyor, 1, row++);
        grid.add(new Label("Format"), 0, row);
        grid.add(cbFormat, 1, row++);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(cbCredentials, 0, row++, 2, 1);
        grid.add(cbEncrypt, 0, row++, 2, 1);
        grid.add(new Label("Password"), 0, row);
        grid.add(pfPassword, 1, row++);
        grid.add(new Label("Confirm"), 0, row);
        grid.add(pfConfirm, 1, row++);
        grid.add(message, 0, row, 2, 1);
        dialog.getDialogPane().setContent(Modals.content(grid));

        ButtonType saveType = new ButtonType("Choose file...", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        Node saveButton = Modals.primary(dialog, saveType);
        Modals.secondary(dialog, ButtonType.CANCEL);

        Runnable validate = () -> {
            ExportFormat format = cbFormat.getSelectionModel().getSelectedItem();
            boolean canEncrypt = format != null && format.supportsEncryption();
            cbEncrypt.setDisable(!canEncrypt);
            boolean encrypt = canEncrypt && cbEncrypt.isSelected();
            pfPassword.setDisable(!encrypt);
            pfConfirm.setDisable(!encrypt);

            String problem = null;
            if (tfSite.getText().trim().isEmpty()) {
                problem = "Enter a site ID.";
            } else if (encrypt && pfPassword.getText().isEmpty()) {
                problem = "Enter a password, or untick encryption.";
            } else if (encrypt && !pfPassword.getText().equals(pfConfirm.getText())) {
                problem = "The two passwords do not match.";
            } else if (!encrypt && cbCredentials.isSelected()) {
                problem = null; // allowed, but warned about below
            }
            message.setText(problem == null && !encrypt && cbCredentials.isSelected()
                    ? "This file will hold camera passwords and will not be encrypted."
                    : (problem == null ? "" : problem));
            message.getStyleClass().setAll("label", problem == null ? "label-warning" : "label-error");
            saveButton.setDisable(problem != null);
        };
        tfSite.textProperty().addListener((o, a, b) -> validate.run());
        pfPassword.textProperty().addListener((o, a, b) -> validate.run());
        pfConfirm.textProperty().addListener((o, a, b) -> validate.run());
        cbEncrypt.selectedProperty().addListener((o, a, b) -> validate.run());
        cbCredentials.selectedProperty().addListener((o, a, b) -> validate.run());
        cbFormat.valueProperty().addListener((o, a, b) -> validate.run());
        validate.run();
        Platform.runLater(tfSite::requestFocus);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            ExportFormat format = cbFormat.getSelectionModel().getSelectedItem();
            boolean encrypt = format.supportsEncryption() && cbEncrypt.isSelected();
            return new ExportRequest(
                    tfSite.getText().trim(),
                    tfPremise.getText().trim(),
                    tfSurveyor.getText().trim(),
                    cbCredentials.isSelected(),
                    encrypt ? pfPassword.getText() : null,
                    format);
        });

        return dialog.showAndWait().orElse(null);
    }

    private void showSettings() {
        logger.info("Opening settings dialog");
        SettingsDialog settingsDialog = new SettingsDialog(primaryStage);
        settingsDialog.showAndWait();
    }

    private void showHelpManual() {
        Dialog<Void> dialog = Modals.dialog("Help", "Quick guide",
                AppConfig.getInstance().getAppName() + " in five steps.");
        dialog.getDialogPane().setPrefWidth(560);

        VBox content = Modals.content();
        content.setSpacing(8);

        Label quickGuide = new Label("""
                1. Where to look
                   Pick an adapter on this computer, or type an address range, a                 CIDR block, or a list. Overlapping entries are scanned once.

                2. Sign-in details (optional)
                   Add the usernames and passwords used on site. Each is tried in                 turn. Devices that need no password are found without any.

                3. How thoroughly to check
                   Quick check reads the stream description. Stream test confirms                 video is actually flowing. Video capture decodes a picture, which                 is the most reliable and the slowest.

                4. Run the scan
                   Devices appear as they are found. Stop keeps whatever has been                 found so far.

                5. Report
                   Export to Excel. You choose whether to include camera passwords                 and whether to encrypt the file.

                Row colours: green finished, amber working, red no credential                 accepted, grey not a camera, blue found but not yet checked.                 Select a row to see its streams and issues; right-click for more.""");
        quickGuide.setWrapText(true);
        quickGuide.setStyle("-fx-font-size: 10px;");

        content.getChildren().add(quickGuide);
        dialog.getDialogPane().setContent(content);

        // In the real button bar rather than a hand-built row inside the body,
        // so this dialog's footer matches every other one.
        ButtonType manualType = new ButtonType("Open User Manual", ButtonBar.ButtonData.HELP_2);
        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(manualType, closeType);

        Button btnUserManual = Modals.secondary(dialog, manualType);
        // The manual opens beside the dialog; consuming the event stops the
        // button bar closing it, which is what a button type would normally do.
        btnUserManual.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            openUserManual();
        });
        Modals.primary(dialog, closeType);

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
        switch (type) {
            case ERROR -> Modals.error(title, title, content);
            case WARNING -> Modals.warn(title, title, content);
            default -> Modals.inform(title, title, content);
        }
    }

    private void showNetworkConfigDialog() {
        Dialog<ButtonType> dialog = Modals.dialog("Select Network", "Where to look",
                "Choose an adapter on this computer, or enter the addresses to scan.");
        dialog.getDialogPane().setPrefWidth(620);

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab simpleTab = new Tab("Simple");
        simpleTab.setContent(createSimpleNetworkBox());

        Tab advancedTab = new Tab("Advanced");
        advancedTab.setContent(createAdvancedNetworkBox());

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

        dialog.getDialogPane().setContent(Modals.content(tabPane));

        ButtonType okButton = new ButtonType("Use this network", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
        Modals.primary(dialog, okButton);
        Modals.secondary(dialog, cancelButton);

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
        Dialog<ButtonType> dialog = Modals.dialog("Add Credential", "Sign-in details",
                "Each is tried in turn on every device. Devices that need no password are found without any.");
        dialog.getDialogPane().setPrefWidth(520);

        Label lblUsername = new Label("Username");
        tfUsername = new TextField("admin");
        tfUsername.setPromptText("Username");
        tfUsername.setTooltip(tip("The account name configured on the camera or recorder."));

        Label lblPassword = new Label("Password");
        // A password field, so the value is not shown to anyone standing nearby.
        tfPassword = new PasswordField();
        tfPassword.setPromptText("Password");
        tfPassword.setTooltip(tip("Stored only for this session and used to sign in to devices."));

        btnAddCredential = new Button("Add");
        btnAddCredential.setMaxWidth(Double.MAX_VALUE);
        btnAddCredential.setPrefHeight(30);
        btnAddCredential.setOnAction(e -> addCredential());

        VBox entry = new VBox(8, lblUsername, tfUsername, lblPassword, tfPassword, btnAddCredential);
        entry.getStyleClass().add("dialog-section");

        Label lblList = new Label("Added so far");
        lblList.getStyleClass().add("dialog-section-title");
        lvCredentials = new ListView<>(credentials);
        lvCredentials.setPrefHeight(150);
        lvCredentials.setPlaceholder(new Label("None yet. Devices without a password are still found."));
        lvCredentials.setCellFactory(param -> new CredentialListCell());
        lvCredentials.setContextMenu(createCredentialContextMenu());
        VBox.setVgrow(lvCredentials, Priority.ALWAYS);

        dialog.getDialogPane().setContent(Modals.content(entry, lblList, lvCredentials));

        ButtonType doneButton = new ButtonType("Done", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(doneButton);
        Modals.primary(dialog, doneButton);

        dialog.showAndWait();

        // Update summary after dialog closes
        updateCredentialSummary();
        updateStartButtonState();
    }

    private void updateCredentialSummary() {
        int count = credentials.size();
        if (count == 0) {
            lblCredentialSummary.setText("No credentials added");
        } else {
            lblCredentialSummary.setText(String.format("%d credential%s added", count, count > 1 ? "s" : ""));
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
