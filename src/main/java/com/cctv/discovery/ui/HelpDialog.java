package com.cctv.discovery.ui;

import com.cctv.discovery.config.AppConfig;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The quick guide, and the button that opens the full manual.
 *
 * <p>The manual ships inside the jar, so it is unpacked to a temporary folder
 * before a browser can be pointed at it.
 */
public final class HelpDialog {

    private static final Logger logger = LoggerFactory.getLogger(HelpDialog.class);

    private static final String HELP_FOLDER = "cctv-discovery-help";

    private HelpDialog() {
    }

    public static void show() {
        Dialog<Void> dialog = Modals.dialog("Help", "Quick guide",
                AppConfig.getInstance().getAppName() + " in five steps.");
        dialog.getDialogPane().setPrefWidth(560);

        VBox content = Modals.content();
        content.setSpacing(8);
        content.getChildren().addAll(
                step("1. Where to look",
                        "Pick an adapter on this computer, or type an address range, a CIDR block, "
                                + "or a list. Overlapping entries are scanned once."),
                step("2. Sign-in details (optional)",
                        "Add the usernames and passwords used on site. Each is tried in turn. "
                                + "Devices that need no password are found without any."),
                step("3. How thoroughly to check",
                        "Quick check reads the stream description. Stream test confirms video is "
                                + "actually flowing. Video capture decodes a picture, which is the "
                                + "most reliable and the slowest."),
                step("4. Run the scan",
                        "Devices appear as they are found. Stop keeps whatever has been found so far."),
                step("5. Report",
                        "Export to Excel. You choose whether to include camera passwords and whether "
                                + "to encrypt the file."),
                note("Row colours: green finished, amber working, red no credential accepted, "
                        + "grey not a camera, blue found but not yet checked. Select a row to see its "
                        + "streams and issues; right-click for more."));
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

    /** One numbered step of the quick guide: a heading and a paragraph. */
    private static VBox step(String heading, String body) {
        Label title = new Label(heading);
        title.getStyleClass().add("dialog-section-title");
        Label text = new Label(body);
        text.setWrapText(true);
        return new VBox(2, title, text);
    }

    private static Label note(String body) {
        Label note = new Label(body);
        note.getStyleClass().add("dialog-note");
        note.setWrapText(true);
        return note;
    }

    /** Unpack the manual and its pictures, then hand them to a browser. */
    private static void openUserManual() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), HELP_FOLDER);
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw new IOException("Could not create " + tempDir);
            }

            File manualFile = extractResource("/help/manual.html", tempDir, "manual.html");

            File imagesDir = new File(tempDir, "images");
            if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                throw new IOException("Could not create " + imagesDir);
            }
            for (String imageFile : listedImages()) {
                extractResource("/help/images/" + imageFile, imagesDir, imageFile);
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(manualFile.toURI());
                logger.info("User manual opened in browser: {}", manualFile.getAbsolutePath());
            } else {
                Modals.warn("Help", "Cannot open the manual",
                        "No browser could be started. Open this file yourself:\n"
                                + manualFile.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("Error opening user manual", e);
            Modals.error("Help", "Could not open the manual", String.valueOf(e.getMessage()));
        }
    }

    /** The pictures the manual refers to, named in image-list.txt beside them. */
    private static List<String> listedImages() {
        List<String> imageFiles = new ArrayList<>();
        try (InputStream listStream = HelpDialog.class.getResourceAsStream("/help/images/image-list.txt")) {
            if (listStream == null) {
                logger.warn("image-list.txt not found, no images will be extracted");
                return imageFiles;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(listStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        imageFiles.add(line);
                    }
                }
            }
            logger.info("Found {} images to extract from image-list.txt", imageFiles.size());
        } catch (Exception e) {
            logger.warn("Error reading image-list.txt: {}", e.getMessage());
        }
        return imageFiles;
    }

    private static File extractResource(String resourcePath, File targetDir, String fileName)
            throws IOException {
        File targetFile = new File(targetDir, fileName);
        try (InputStream is = HelpDialog.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.warn("Resource not found: {}", resourcePath);
                return targetFile;
            }
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                is.transferTo(fos);
            }
            logger.info("Extracted resource: {} to {}", resourcePath, targetFile.getAbsolutePath());
        }
        return targetFile;
    }
}
