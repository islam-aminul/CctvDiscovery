package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.util.NetworkUtils;
import com.cctv.discovery.util.TargetParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Choosing what to scan.
 *
 * <p>Simple mode offers one adapter, a range, a block or a list. Advanced mode
 * combines several sources at once. Both live here, together with the counting
 * and validation that goes with them, because none of it has anything to do
 * with the rest of the window: this was more than a third of
 * {@code MainController} and touched nothing else in it.
 *
 * <p>The window is told when the selection changes through the callback given
 * to the constructor, and asks for the result with {@link #targets()}.
 */
public final class NetworkSelection {

    private static final Logger logger = LoggerFactory.getLogger(NetworkSelection.class);

    private final AppConfig config = AppConfig.getInstance();
    private final Runnable onChanged;

    // Simple mode
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

    // Advanced mode
    private final CheckBox cbAdvancedMode = new CheckBox();
    private ListView<NetworkInterfaceItem> lvNetworkInterfaces;
    private final ObservableList<NetworkInterfaceItem> networkInterfaces = FXCollections.observableArrayList();
    private TableView<IpRangeItem> tvIpRanges;
    private final ObservableList<IpRangeItem> ipRanges = FXCollections.observableArrayList();
    private TableView<CidrItem> tvCidrs;
    private final ObservableList<CidrItem> cidrs = FXCollections.observableArrayList();
    private Label lblAdvancedIpCount;

    // The one-line summary in the left panel
    private Label lblNetworkSummary;
    private Button btnConfigureNetwork;

    private boolean networkConfigured;

    public NetworkSelection(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    /** Whether anything has been chosen yet. */
    public boolean isConfigured() {
        return networkConfigured;
    }

    /** Grey the button out while a scan is running. */
    public void setDisable(boolean disable) {
        if (btnConfigureNetwork != null) {
            btnConfigureNetwork.setDisable(disable);
        }
    }

    private void changed() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /** The left-panel block: the button that opens the chooser, and a summary. */
    public VBox section() {
        VBox vbox = new VBox(6);

        Label lblTitle = new Label("1. Where to look");
        lblTitle.getStyleClass().add("section-title");

        btnConfigureNetwork = new Button("Choose network...");
        btnConfigureNetwork.setMaxWidth(Double.MAX_VALUE);
        btnConfigureNetwork.setPrefHeight(34);
        btnConfigureNetwork.setOnAction(e -> showDialog());
        btnConfigureNetwork.setTooltip(Modals.tip("""
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
        rbInterface.setTooltip(Modals.tip("Scan the network one of this computer's adapters is on."));
        rbManualRange = new RadioButton("An address range");
        rbManualRange.setTooltip(Modals.tip("Scan every address between a first and last address."));
        rbCIDR = new RadioButton("A CIDR block");
        rbCIDR.setTooltip(Modals.tip("Scan a whole subnet, for example 192.168.1.0/24."));
        rbIpList = new RadioButton("A list of addresses");
        rbIpList.setTooltip(Modals.tip("Scan named addresses only. Ranges and CIDR blocks are accepted here too."));
        rbInterface.setToggleGroup(tg);
        rbManualRange.setToggleGroup(tg);
        rbCIDR.setToggleGroup(tg);
        rbIpList.setToggleGroup(tg);
        rbInterface.setSelected(true);

        // Interface dropdown
        cbInterfaces = new ComboBox<>();
        cbInterfaces.setTooltip(Modals.tip("Each entry shows the address and the size of its network."));
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
        tfCIDR.setTooltip(Modals.tip("Network address and prefix length."));
        tfCIDR.setDisable(true);

        // IP Address List - accepts multiple IPs separated by commas, spaces, or newlines
        taIpList = new TextArea();
        taIpList.setPromptText("""
                192.168.1.10, 192.168.1.20
                192.168.1.30-192.168.1.60
                192.168.2.0/24""");
        taIpList.setTooltip(Modals.tip("""
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
        changed();
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
        changed();
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
        changed();
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

    /** The addresses the scan will cover, de-duplicated across all sources. */
    public TargetParser.Targets targets() {
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
        return targets().toList(config.getMaxTargets());
    }

    /** The Select Network modal. */
    public void showDialog() {
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

            cbAdvancedMode.setSelected(isAdvanced);

            // Update network summary
            updateNetworkSummary();
            changed();
        }
    }

    /**
     * Describe the current selection in one line. Counting through the parser
     * means overlapping sources are reported once, matching what will be scanned.
     */
    private void updateNetworkSummary() {
        String style = "-fx-font-style: italic; -fx-text-fill: #0078d4;";
        TargetParser.Targets targets = targets();
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
