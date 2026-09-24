package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.discovery.NetworkScanner;
import com.cctv.discovery.discovery.ScanCoordinator;
import com.cctv.discovery.discovery.StreamAnalyzer;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.export.ScanReader;
import com.cctv.discovery.model.Credential;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.HostAuditData;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.service.HostAuditService;
import com.cctv.discovery.service.MacLookupService;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.service.RtspService;
import com.cctv.discovery.util.TargetParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.shape.SVGPath;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Network selection - Advanced mode

    // Network summary in left panel

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
    private NetworkSelection network;
    private ScanCoordinator scanner;
    private ReportExchange reports;
    private HostAuditService hostAuditService;
    private ExecutorService executorService;

    // State
    private boolean discoveryInProgress = false;
    private boolean discoveryCompleted = false;
    private HostAuditData hostAuditData; // Collected at startup

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Every modal opens over this window and inherits its appearance.
        Modals.setOwner(primaryStage);
        this.credentials = FXCollections.observableArrayList();
        this.devices = FXCollections.observableArrayList();
        this.network = new NetworkSelection(this::updateStartButtonState);

        // Initialize services
        this.scanner = new ScanCoordinator(new NetworkScanner(), new OnvifService(),
                new RtspService(), new StreamAnalyzer(), config);
        this.reports = new ReportExchange(primaryStage, config, new ExcelExporter());
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
        btnSettings.setTooltip(Modals.tip("Ports to scan, extra stream paths and check timings."));
        btnSettings.getStyleClass().add("header-button");

        Button btnHelp = new Button("Help");
        btnHelp.setOnAction(e -> HelpDialog.show());
        btnHelp.setTooltip(Modals.tip("A short guide to running a survey."));
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
        button.setTooltip(Modals.tip(state + ". Click for " + next.toString().toLowerCase(java.util.Locale.ROOT) + "."));
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
        VBox networkSection = network.section();

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




    private VBox createCredentialSection() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("2. Sign-in details");
        lblTitle.getStyleClass().add("section-title");

        btnManageCredentials = new Button("Add credentials...");
        btnManageCredentials.setMaxWidth(Double.MAX_VALUE);
        btnManageCredentials.setPrefHeight(34);
        btnManageCredentials.setOnAction(e -> showCredentialManagementDialog());
        btnManageCredentials.setTooltip(Modals.tip("""
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
        btnVerificationMethod.setTooltip(Modals.tip("""
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
        btnStop.setTooltip(Modals.tip("Stop the scan. Everything found so far is kept and can be exported."));

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
        btnExport.setOnAction(e -> reports.export(devices, hostAuditData));

        Button btnOpen = new Button("Open a saved scan...");
        btnOpen.getStyleClass().add("button-secondary");
        btnOpen.setMaxWidth(Double.MAX_VALUE);
        btnOpen.setPrefHeight(30);
        btnOpen.setOnAction(e -> openSavedScan());
        btnOpen.setTooltip(Modals.tip("""
                Load a scan previously exported as JSON, to review it or compare                 it with what is on site now."""));
        btnExport.setTooltip(Modals.tip("""
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
        tvStreams.setTooltip(Modals.tip("Double-click a device above to retry it with other credentials."));

        lvFindings = new ListView<>();
        lvFindings.setPrefHeight(90);
        lvFindings.setPlaceholder(new Label("No issues found"));
        lvFindings.setTooltip(Modals.tip("Problems worth acting on, most serious first."));

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
        btnStart.setDisable(!network.isConfigured() || discoveryInProgress);
        if (!network.isConfigured()) {
            btnStart.setTooltip(Modals.tip("Choose a network first."));
        } else if (credentials.isEmpty()) {
            btnStart.setTooltip(Modals.tip("""
                    Scan without credentials. Devices that require a password will \
                    be listed but their streams cannot be checked."""));
        } else {
            btnStart.setTooltip(Modals.tip("Scan the chosen network."));
        }
        if (discoveryCompleted && !discoveryInProgress) {
            btnStart.setText("Scan again");
        }
    }

    private void startDiscovery() {
        discoveryInProgress = true;
        disableInputs();
        devices.clear();
        showDeviceDetails(null);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lblProgress.setText("Starting...");
        lblResultSummary.setText("");
        btnStop.setVisible(true);
        btnStop.setManaged(true);

        scanner.configureValidation(selectedValidationMethod);
        TargetParser.Targets targets = network.targets();
        List<Credential> forThisRun = List.copyOf(credentials);

        executorService.submit(() -> {
            try {
                scanner.run(targets, forThisRun, scanListener);
            } catch (Exception e) {
                logger.error("Scan failed", e);
                Platform.runLater(() -> {
                    showAlert("Scan failed", String.valueOf(e.getMessage()), Alert.AlertType.ERROR);
                    finishDiscovery(true);
                });
            }
        });
    }

    /** Ask every running stage to stop; partial results are kept. */
    private void stopDiscovery() {
        btnStop.setDisable(true);
        lblProgress.setText("Stopping...");
        scanner.cancel();
    }

    /**
     * What the scan tells the window. Every method is called from a background
     * thread, so each one hops to the JavaFX thread before touching anything.
     */
    private final ScanCoordinator.Listener scanListener = new ScanCoordinator.Listener() {

        @Override
        public void onProgress(double fraction, String message) {
            Platform.runLater(() -> {
                progressBar.setProgress(Math.min(1.0, Math.max(0.0, fraction)));
                lblProgress.setText(message);
            });
        }

        @Override
        public void onDevicesFound(List<Device> found) {
            Platform.runLater(() -> {
                for (Device device : found) {
                    if (devices.stream().noneMatch(d -> d.getIpAddress().equals(device.getIpAddress()))) {
                        devices.add(device);
                    }
                }
                updateResultSummary();
            });
        }

        @Override
        public void onDeviceList(List<Device> all) {
            Platform.runLater(() -> {
                devices.setAll(all);
                updateResultSummary();
            });
        }

        @Override
        public void onDeviceChanged(Device device) {
            Device shown = tvResults.getSelectionModel().getSelectedItem();
            Platform.runLater(() -> {
                tvResults.refresh();
                updateResultSummary();
                if (shown == device) {
                    showDeviceDetails(device);
                }
            });
        }

        @Override
        public void onFinished(long seconds, boolean cancelled) {
            Platform.runLater(() -> {
                lblProgress.setText(cancelled
                        ? "Stopped after " + seconds + "s."
                        : "Finished in " + seconds + "s.");
                discoveryCompleted = !cancelled;
                finishDiscovery(cancelled);
            });
        }
    };

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
    private void finishDiscovery(boolean cancelled) {
        progressBar.setProgress(cancelled ? 0 : 1.0);
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
        tfRetryUsername.setTooltip(Modals.tip("The account name configured on this device."));

        // Masked, like the main credential dialog: a survey is often run with
        // someone watching over the operator's shoulder.
        PasswordField tfRetryPassword = new PasswordField();
        tfRetryPassword.setPromptText("Password");
        tfRetryPassword.setTooltip(Modals.tip("Tried against this device only, and added to the list for later devices."));

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
                    scanner.identify(device, List.copyOf(credentials), scanListener);

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



    /**
     * Collect the report details, then write the workbook.
     */
    /**
     * Put a saved scan on screen. Reading the file belongs to ReportExchange;
     * deciding it is safe to replace what is showing, and what the window looks
     * like afterwards, belongs here.
     */
    private void openSavedScan() {
        if (discoveryInProgress) {
            showAlert("Scan running", "Stop the scan before opening a saved one.", Alert.AlertType.WARNING);
            return;
        }
        if (!devices.isEmpty() && !Modals.confirm("Open a Saved Scan", "Replace these results?",
                "Opening a saved scan clears what is on screen. Export it first if you need it.",
                "Replace")) {
            return;
        }

        ScanReader.Scan scan = reports.open();
        if (scan == null) {
            return;
        }
        devices.setAll(scan.devices());
        showDeviceDetails(null);
        updateResultSummary();
        discoveryCompleted = true;
        btnExport.setDisable(devices.isEmpty());
        updateExportButtonColor();
        progressBar.setProgress(1.0);
        lblProgress.setText(String.format("Opened %s (%s)",
                scan.site() == null ? "a saved scan" : scan.site(),
                scan.generated() == null ? "date unknown" : scan.generated()));
        if (!scan.includedCredentials()) {
            lblProgress.setText(lblProgress.getText() + " — saved without credentials");
        }
    }

    private void showSettings() {
        logger.info("Opening settings dialog");
        SettingsDialog settingsDialog = new SettingsDialog(primaryStage);
        settingsDialog.showAndWait();
    }

    private void disableInputs() {
        // Disable modal buttons and start button during discovery
        network.setDisable(true);
        btnManageCredentials.setDisable(true);
        btnVerificationMethod.setDisable(true);
        btnStart.setDisable(true);
    }

    private void enableInputs() {
        discoveryInProgress = false;
        network.setDisable(false);
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




    private void showCredentialManagementDialog() {
        Dialog<ButtonType> dialog = Modals.dialog("Add Credential", "Sign-in details",
                "Each is tried in turn on every device. Devices that need no password are found without any.");
        dialog.getDialogPane().setPrefWidth(520);

        Label lblUsername = new Label("Username");
        tfUsername = new TextField("admin");
        tfUsername.setPromptText("Username");
        tfUsername.setTooltip(Modals.tip("The account name configured on the camera or recorder."));

        Label lblPassword = new Label("Password");
        // A password field, so the value is not shown to anyone standing nearby.
        tfPassword = new PasswordField();
        tfPassword.setPromptText("Password");
        tfPassword.setTooltip(Modals.tip("Stored only for this session and used to sign in to devices."));

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
        if (scanner != null) {
            scanner.shutdown();
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
