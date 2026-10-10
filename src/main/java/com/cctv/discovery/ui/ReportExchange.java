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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
import java.util.Locale;

/**
 * Writing a survey out and reading one back in.
 *
 * <p>Separate from the window because none of it is about the window: it asks
 * for the location code, picks a file, and hands the work to the exporters.
 *
 * <p>The location code is the only question: the code of the test centre,
 * premises, site or LAN being surveyed. Stream addresses always carry the
 * credentials that worked, because a report whose addresses cannot be opened is
 * of no use to the installer it is written for, and the file is not encrypted.
 * The file type is picked in the save dialog, Excel first; JSON stays available
 * because it is what "Open a saved scan" reads.
 */
public final class ReportExchange {

    private static final Logger logger = LoggerFactory.getLogger(ReportExchange.class);

    /** The file formats a survey can be written as. */
    public enum Format {
        EXCEL("Excel workbook (.xlsx)", "xlsx"),
        CSV("Comma-separated values (.csv)", "csv"),
        JSON("JSON (.json)", "json");

        private final String label;
        private final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }

        @Override
        public String toString() {
            return label;
        }

        /** The format a file name asks for by its extension, or null. */
        static Format forFileName(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (Format format : values()) {
                if (lower.endsWith("." + format.extension)) {
                    return format;
                }
            }
            return null;
        }
    }

    private final Stage owner;
    private final AppConfig config;
    private final ExcelExporter excelExporter;

    public ReportExchange(Stage owner, AppConfig config, ExcelExporter excelExporter) {
        this.owner = owner;
        this.config = config;
        this.excelExporter = excelExporter;
    }

    /** Ask for the location code, pick a file, write it. Reports the outcome itself. */
    public void export(List<Device> devices, HostAuditData hostAuditData) {
        String siteId = promptForSiteId();
        if (siteId == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save report");
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now());
        String baseName = "cctv-report-" + safeFileName(siteId) + "-" + timestamp;
        fileChooser.setInitialFileName(baseName + "." + Format.EXCEL.extension());
        fileChooser.setInitialDirectory(startingDirectory());
        for (Format format : Format.values()) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(format.toString(), "*." + format.extension()));
        }
        fileChooser.setSelectedExtensionFilter(fileChooser.getExtensionFilters().getFirst());

        File chosen = fileChooser.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }
        Format format = formatFor(chosen, fileChooser.getSelectedExtensionFilter());
        File file = withExtension(chosen, format);

        List<Device> snapshot = new ArrayList<>(devices);
        try {
            write(format, siteId, snapshot, hostAuditData, file);
            Modals.inform("Export Report", "Report saved",
                    file.getAbsolutePath() + "\n\nStream addresses include each camera's username and password.");
            logger.info("Report written for site {} as {}", siteId, format);
        } catch (Exception e) {
            logger.error("Export failed", e);
            Modals.error("Export Report", "Could not save the report", String.valueOf(e.getMessage()));
        }
    }

    /**
     * The format to write: the extension typed into the file name when it is
     * one of ours, since that is what the operator sees, otherwise the file
     * type picked in the dialog.
     */
    static Format formatFor(File chosen, FileChooser.ExtensionFilter selected) {
        Format byName = Format.forFileName(chosen.getName());
        if (byName != null) {
            return byName;
        }
        if (selected != null) {
            for (Format format : Format.values()) {
                if (selected.getExtensions().contains("*." + format.extension())) {
                    return format;
                }
            }
        }
        return Format.EXCEL;
    }

    /** Add the format's extension when the chosen name has none of ours. */
    static File withExtension(File chosen, Format format) {
        return Format.forFileName(chosen.getName()) == format
                ? chosen
                : new File(chosen.getParentFile(), chosen.getName() + "." + format.extension());
    }

    private void write(Format format, String siteId, List<Device> snapshot, HostAuditData audit, File file)
            throws Exception {
        Path path = file.toPath();
        switch (format) {
            case EXCEL -> excelExporter.export(snapshot, audit,
                    new ExcelExporter.ReportOptions(siteId, null, System.getProperty("user.name", ""),
                            true, null),
                    file);
            case CSV -> ReportWriter.writeCsv(snapshot, true, path);
            case JSON -> ReportWriter.writeJson(snapshot, siteId, true, path);
        }
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

    /** A location code can hold anything the operator typed; a file name cannot. */
    static String safeFileName(String text) {
        String cleaned = text.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "site" : cleaned;
    }

    /** The one question a report needs answered. Null when cancelled. */
    private String promptForSiteId() {
        Dialog<String> dialog = Modals.dialog("Export Report", "Export report",
                "The code of the test centre, premises, site or LAN surveyed. "
                        + "It goes on the summary sheet and in the file name.");

        TextField tfSite = new TextField();
        tfSite.setPromptText("Test centre, premises, site or LAN, e.g. BLR-WH-02");
        tfSite.setPrefColumnCount(30);
        tfSite.setTooltip(Modals.tip("""
                The code that identifies where this survey was done: a test centre, \
                premises, site or LAN. It goes on the summary sheet and in the file name."""));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Location code"), 0, 0);
        grid.add(tfSite, 1, 0);
        dialog.getDialogPane().setContent(Modals.content(grid));

        ButtonType saveType = new ButtonType("Choose file...", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        Node saveButton = Modals.primary(dialog, saveType);
        Modals.secondary(dialog, ButtonType.CANCEL);

        saveButton.setDisable(true);
        tfSite.textProperty().addListener((o, a, b) -> saveButton.setDisable(b.trim().isEmpty()));
        Platform.runLater(tfSite::requestFocus);

        dialog.setResultConverter(button -> button == saveType ? tfSite.getText().trim() : null);
        return dialog.showAndWait().orElse(null);
    }
}
