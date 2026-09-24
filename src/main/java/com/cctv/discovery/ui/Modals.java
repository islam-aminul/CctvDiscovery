package com.cctv.discovery.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Every window that opens over the main one is built here.
 *
 * <p>Before this existed each dialog repeated its own chrome, and the copies
 * had drifted: some set the window icon, some did not; the buttons carried
 * style classes in one dialog and none in another; Settings was a bare
 * {@link Stage} while the rest were {@link Dialog}s. Worst of all, none of them
 * applied the application's stylesheets. A JavaFX dialog builds its own scene
 * and scenes do not inherit stylesheets from their owner, so every modal
 * rendered in the default look — and the {@code button-success} and
 * {@code button-secondary} classes those dialogs asked for did nothing at all,
 * because the sheet that defines them was never loaded.
 *
 * <p>So: one place that sets the owner, the icon, the theme, the header and the
 * buttons, and one set of style classes in {@code app.css} to dress them.
 */
public final class Modals {

    private static final Logger logger = LoggerFactory.getLogger(Modals.class);

    /**
     * Buttons share a minimum width so short labels line up between dialogs,
     * and grow past it rather than truncating when the label names the action.
     */
    private static final double BUTTON_MIN_WIDTH = 96;
    private static final double BUTTON_HEIGHT = 30;

    private static Stage owner;
    private static Image icon;

    private Modals() {
    }

    /**
     * The window modals belong to. Set once at start-up; without it a dialog
     * opens centred on the screen rather than over the application, and can be
     * lost behind it.
     */
    public static void setOwner(Stage stage) {
        owner = stage;
    }

    /**
     * A dialog wearing the application's chrome.
     *
     * @param title       the window title, shown in the task bar
     * @param headline    the one line at the top of the dialog itself
     * @param explanation a sentence under the headline, or null for none
     */
    public static <T> Dialog<T> dialog(String title, String headline, String explanation) {
        Dialog<T> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().setHeader(header(headline, explanation));
        decorate(dialog);
        return dialog;
    }

    /**
     * Give an existing dialog the same chrome. {@link Alert} and anything built
     * elsewhere goes through here too, so nothing renders unstyled.
     */
    public static void decorate(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (!pane.getStyleClass().contains("app-dialog")) {
            pane.getStyleClass().add("app-dialog");
        }

        // The scene arrives when the dialog is first shown, so the stylesheets
        // are applied then rather than now.
        applyTheme(pane.getScene());
        pane.sceneProperty().addListener((observable, old, scene) -> applyTheme(scene));

        if (owner != null && dialog.getOwner() == null) {
            try {
                dialog.initOwner(owner);
            } catch (IllegalStateException e) {
                logger.debug("Dialog already showing, keeping its owner: {}", e.getMessage());
            }
        }
        dialog.setOnShown(event -> applyIcon(pane));
    }

    /** The content area, with the padding and spacing every dialog uses. */
    public static VBox content(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("dialog-content");
        return box;
    }

    /** The button that carries out what the dialog is for. */
    public static Button primary(Dialog<?> dialog, ButtonType type) {
        return style(dialog, type, "button-success");
    }

    /** Cancel, Close, and anything else that steps back. */
    public static Button secondary(Dialog<?> dialog, ButtonType type) {
        return style(dialog, type, "button-secondary");
    }

    /** A button whose effect is hard to undo. */
    public static Button danger(Dialog<?> dialog, ButtonType type) {
        return style(dialog, type, "button-danger");
    }

    private static Button style(Dialog<?> dialog, ButtonType type, String styleClass) {
        Node node = dialog.getDialogPane().lookupButton(type);
        if (!(node instanceof Button button)) {
            // lookupButton returns null until the type has been added.
            logger.warn("No button for {} yet; add the button type first", type.getText());
            return null;
        }
        if (!button.getStyleClass().contains(styleClass)) {
            button.getStyleClass().add(styleClass);
        }
        button.setMinWidth(BUTTON_MIN_WIDTH);
        button.setMinHeight(BUTTON_HEIGHT);
        button.setPrefHeight(BUTTON_HEIGHT);
        return button;
    }

    // ------------------------------------------------------------- messages --

    /** Ask a yes-or-no question. Returns true only for an explicit yes. */
    public static boolean confirm(String title, String headline, String body, String confirmLabel) {
        Alert alert = message(Alert.AlertType.CONFIRMATION, title, headline, body);
        ButtonType yes = new ButtonType(confirmLabel, ButtonType.OK.getButtonData());
        alert.getButtonTypes().setAll(yes, ButtonType.CANCEL);
        primary(alert, yes);
        secondary(alert, ButtonType.CANCEL);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == yes;
    }

    public static void inform(String title, String headline, String body) {
        show(Alert.AlertType.INFORMATION, title, headline, body);
    }

    public static void warn(String title, String headline, String body) {
        show(Alert.AlertType.WARNING, title, headline, body);
    }

    public static void error(String title, String headline, String body) {
        show(Alert.AlertType.ERROR, title, headline, body);
    }

    private static void show(Alert.AlertType type, String title, String headline, String body) {
        Alert alert = message(type, title, headline, body);
        alert.getButtonTypes().setAll(ButtonType.OK);
        primary(alert, ButtonType.OK);
        alert.showAndWait();
    }

    /** An alert dressed like the rest of the modals. */
    public static Alert message(Alert.AlertType type, String title, String headline, String body) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        // The default header is a large coloured glyph beside the text, which
        // looks nothing like the other dialogs; ours is the same band as theirs.
        alert.setGraphic(null);
        alert.setHeaderText(null);
        alert.getDialogPane().setHeader(header(headline, null));
        alert.getDialogPane().setContent(content(wrapped(body)));
        decorate(alert);
        return alert;
    }

    // -------------------------------------------------------------- internals --

    private static Node header(String headline, String explanation) {
        VBox box = new VBox(3);
        box.getStyleClass().add("dialog-header");

        Label title = new Label(headline);
        title.getStyleClass().add("dialog-headline");
        title.setWrapText(true);
        box.getChildren().add(title);

        if (explanation != null && !explanation.isBlank()) {
            Label sub = new Label(explanation);
            sub.getStyleClass().add("dialog-subhead");
            sub.setWrapText(true);
            box.getChildren().add(sub);
        }
        return box;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text == null ? "" : text);
        label.setWrapText(true);
        label.setMaxWidth(420);
        return label;
    }

    private static void applyTheme(Scene scene) {
        if (scene != null) {
            Theme.current().applyTo(scene);
        }
    }

    private static void applyIcon(DialogPane pane) {
        Scene scene = pane.getScene();
        Window window = scene == null ? null : scene.getWindow();
        if (!(window instanceof Stage stage) || !stage.getIcons().isEmpty()) {
            return;
        }
        Image image = icon();
        if (image != null) {
            stage.getIcons().add(image);
        }
    }

    private static Image icon() {
        if (icon == null) {
            try (InputStream in = Modals.class.getResourceAsStream("/icon.png")) {
                if (in != null) {
                    icon = new Image(in);
                }
            } catch (Exception e) {
                logger.debug("Could not load the dialog icon: {}", e.getMessage());
            }
        }
        return icon;
    }
}
