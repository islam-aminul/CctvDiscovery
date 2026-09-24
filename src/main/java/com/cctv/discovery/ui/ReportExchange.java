package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import com.cctv.discovery.export.ExcelExporter;
import com.cctv.discovery.export.ReportWriter;
import com.cctv.discovery.export.ScanReader;
import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.HostAuditData;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writing a survey out and reading one back in.
 *
 * <p>Separate from the window because none of it is about the window: it asks
 * for the details a report needs, picks a file, and hands the work to the
 * exporters. What it returns is data; the caller decides what to show.
 */
public final class ReportExchange {

    private static final Logger logger = LoggerFactory.getLogger(ReportExchange.class);

    /** The file formats a survey can be written as. */
    public enum Format {
        EXCEL("Excel workbook (.xlsx)", "xlsx", true),
        CSV("Comma-separated values (.csv)", "csv", false),
        JSON("JSON (.json)", "json", false);

        private final String label;
        private final String extension;
        private final boolean supportsEncryption;

        Format(String label, String extension, boolean supportsEncryption) {
            this.label = label;
            this.extension = extension;
            this.supportsEncryption = supportsEncryption;
        }

        public String extension() {
            return extension;
        }

        public boolean supportsEncryption() {
            return supportsEncryption;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What the operator asked for. A null password means no encryption. */
    public record Request(String siteId, String premise, String surveyor,
                          boolean includeCredentials, String password, Format format) {
    }

    private final Stage owner;
    private final AppConfig config;
    private final ExcelExporter excelExporter;

    public ReportExchange(Stage owner, AppConfig config, ExcelExporter excelExporter) {
        this.owner = owner;
        this.config = config;
        this.excelExporter = excelExporter;
    }

    /** Ask for the details, pick a file, write it. Reports the outcome itself. */
    public void export(List<Device> devices, HostAuditData hostAuditData) {
        Request request = promptForDetails();
        if (request == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save report");
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now());
        fileChooser.setInitialFileName("cctv-report-" + safeFileName(request.siteId()) + "-" + timestamp
                + "." + request.format().extension());
        fileChooser.setInitialDirectory(startingDirectory());
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                request.format().toString(), "*." + request.format().extension()));

        File file = fileChooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }

        List<Device> snapshot = new ArrayList<>(devices);
        try {
            boolean encrypted = write(request, snapshot, hostAuditData, file);

            String protection = encrypted
                    ? "The file is encrypted. It cannot be opened without the password you set."
                    : "The file is not encrypted. Anyone with it can read it.";
            String credentials = request.includeCredentials()
                    ? "\n\nIt contains camera usernames and passwords."
                    : "";
            Modals.inform("Export Report", "Report saved",
                    file.getAbsolutePath() + "\n\n" + protection + credentials);
            logger.info("Report written for site {} as {} ({})", request.siteId(), request.format(),
                    encrypted ? "encrypted" : "unencrypted");
        } catch (Exception e) {
            logger.error("Export failed", e);
            Modals.error("Export Report", "Could not save the report", String.valueOf(e.getMessage()));
        }
    }

    /** @return true when the written file carries a password */
    private boolean write(Request request, List<Device> snapshot, HostAuditData audit, File file)
            throws Exception {
        Path path = file.toPath();
        switch (request.format()) {
            case EXCEL -> {
                ExcelExporter.ReportOptions options = new ExcelExporter.ReportOptions(
                        request.siteId(), request.premise(), request.surveyor(),
                        request.includeCredentials(), request.password());
                excelExporter.export(snapshot, audit, options, file);
                return options.encrypted();
            }
            case CSV -> ReportWriter.writeCsv(snapshot, request.includeCredentials(), path);
            case JSON -> ReportWriter.writeJson(snapshot, request.siteId(),
                    request.includeCredentials(), path);
        }
        return false;
    }

    /**
     * Ask for a saved scan and read it.
     *
     * @return the scan, or null when the operator cancelled or the file was not
     *         one of ours; the reason is reported before returning
     */
    public ScanReader.Scan open() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a saved scan");
        chooser.setInitialDirectory(startingDirectory());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Saved scan", "*.json"));

        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return null;
        }
        try {
            ScanReader.Scan scan = ScanReader.read(file.toPath());
            logger.info("Opened a saved scan of {} device(s) from {}", scan.devices().size(), file);
            return scan;
        } catch (ScanReader.NotAScanException e) {
            Modals.warn("Open a Saved Scan", "Not a saved scan", String.valueOf(e.getMessage()));
        } catch (Exception e) {
            logger.error("Could not open {}", file, e);
            Modals.error("Open a Saved Scan", "Could not open that file", String.valueOf(e.getMessage()));
        }
        return null;
    }

    private File startingDirectory() {
        File configured = new File(config.getExportDefaultDirectory());
        return configured.isDirectory() ? configured : new File(System.getProperty("user.home"));
    }

    /** A site ID can hold anything the operator typed; a file name cannot. */
    static String safeFileName(String text) {
        String cleaned = text.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "site" : cleaned;
    }

    /**
     * The details that go on the summary sheet, with the checks that stop a
     * report being written that cannot be used: no site ID, or encryption asked
     * for without a password that matches its confirmation.
     */
    private Request promptForDetails() {
        Dialog<Request> dialog = Modals.dialog("Export Report", "Report details",
                "These appear on the summary sheet and in the file name.");

        TextField tfSite = new TextField();
        tfSite.setPromptText("Required, for example BLR-WH-02");
        tfSite.setTooltip(new Tooltip("Identifies the site in the report and in the file name."));

        TextField tfPremise = new TextField();
        tfPremise.setPromptText("Optional");
        tfPremise.setTooltip(new Tooltip("The building or area surveyed."));

        TextField tfSurveyor = new TextField(System.getProperty("user.name", ""));
        tfSurveyor.setTooltip(new Tooltip("Recorded on the summary sheet as who ran the survey."));

        CheckBox cbCredentials = new CheckBox("Include camera usernames and passwords");
        cbCredentials.setSelected(config.isExportIncludeCredentialsDefault());
        cbCredentials.setTooltip(new Tooltip("""
                Ticked, the report carries the credentials that worked and stream \
                URLs that include them, which is what an installer needs. Unticked, \
                both are left out so the file can be shared more widely."""));

        ComboBox<Format> cbFormat = new ComboBox<>();
        cbFormat.getItems().setAll(Format.values());
        cbFormat.getSelectionModel().select(Format.EXCEL);
        cbFormat.setMaxWidth(Double.MAX_VALUE);
        cbFormat.setTooltip(new Tooltip("""
                Excel is the formatted report. CSV suits a spreadsheet or \
                analysis tool. JSON suits feeding another system."""));

        CheckBox cbEncrypt = new CheckBox("Encrypt the workbook with a password");
        cbEncrypt.setSelected(config.isExportEncryptionDefault());
        cbEncrypt.setTooltip(new Tooltip("Excel will ask for this password before opening the file."));

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
            Format format = cbFormat.getSelectionModel().getSelectedItem();
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
            Format format = cbFormat.getSelectionModel().getSelectedItem();
            boolean encrypt = format.supportsEncryption() && cbEncrypt.isSelected();
            return new Request(
                    tfSite.getText().trim(),
                    tfPremise.getText().trim(),
                    tfSurveyor.getText().trim(),
                    cbCredentials.isSelected(),
                    encrypt ? pfPassword.getText() : null,
                    format);
        });

        return dialog.showAndWait().orElse(null);
    }
}
