package com.cctv.discovery.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the palette to the contrast rules written at the top of app.css.
 *
 * <p>The rules are only worth stating if something checks them: the tokens are
 * read from the stylesheet itself, so editing a hex value there and weakening a
 * pairing fails here rather than shipping.
 */
class PaletteContrastTest {

    private static final double AA = 4.5;
    private static final double AA_LARGE = 3.0;

    private static Map<String, String> tokens;

    @BeforeAll
    static void loadTokens() throws Exception {
        String css;
        try (InputStream in = PaletteContrastTest.class.getResourceAsStream("/css/app.css")) {
            assertTrue(in != null, "app.css is missing from the resources");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        tokens = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(-(?:midnight|teal|sage|paper|surface)-\\d+):\\s*(#[0-9A-Fa-f]{6})\\s*;")
                .matcher(css);
        while (m.find()) {
            tokens.put(m.group(1), m.group(2).toUpperCase());
        }
        assertTrue(tokens.size() >= 20, "expected the full ramp set, found " + tokens.size());
    }

    private static String token(String name) {
        String value = tokens.get(name);
        assertTrue(value != null, name + " is not defined in app.css");
        return value;
    }

    private static double luminance(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    @DisplayName("Every ramp runs dark to light from 700 to 300")
    void rampsAreOrdered() {
        for (String hue : List.of("midnight", "teal", "sage")) {
            double previous = -1;
            for (int stop : new int[]{700, 600, 500, 400, 300}) {
                double lum = luminance(token("-" + hue + "-" + stop));
                assertTrue(lum > previous,
                        hue + "-" + stop + " should be lighter than the stop before it");
                previous = lum;
            }
        }
    }

    @Test
    @DisplayName("600 and 700 carry AA body text on the light text surfaces")
    void bodyTextStopsAreAccessible() {
        for (String surface : List.of(token("-paper-0"), token("-paper-50"))) {
            for (String hue : List.of("midnight", "teal", "sage")) {
                for (int stop : new int[]{700, 600}) {
                    String colour = token("-" + hue + "-" + stop);
                    double ratio = contrast(colour, surface);
                    assertTrue(ratio >= AA, String.format(
                            "%s-%d on %s is %.2f:1, below AA", hue, stop, surface, ratio));
                }
            }
        }
    }

    @Test
    @DisplayName("The lighter accent stops are decorative and must not be treated as text")
    void lightStopsAreNotTextSafe() {
        // Stated as a rule in app.css; asserted here so a future edit that made
        // them pass would be a deliberate change to the rule, not an accident.
        for (String hue : List.of("teal", "sage")) {
            double ratio = contrast(token("-" + hue + "-500"), token("-paper-0"));
            assertFalse(ratio >= AA, String.format(
                    "%s-500 on white is %.2f:1; if it now passes AA, update the rule in app.css",
                    hue, ratio));
        }
    }

    @Test
    @DisplayName("White on a filled 600 or 700 button is AA")
    void filledButtonsAreAccessible() {
        String white = token("-paper-0");
        for (String hue : List.of("midnight", "teal", "sage")) {
            for (int stop : new int[]{700, 600}) {
                String fill = token("-" + hue + "-" + stop);
                double ratio = contrast(white, fill);
                assertTrue(ratio >= AA, String.format(
                        "white on %s-%d is %.2f:1, below AA", hue, stop, ratio));
            }
        }
    }

    @Test
    @DisplayName("Dark surfaces carry AA text in paper, teal and sage")
    void darkSurfacesAreAccessible() {
        List<String> surfaces = List.of(token("-surface-900"), token("-surface-800"), token("-midnight-700"));
        List<String> foregrounds = List.of(token("-paper-100"), token("-paper-200"),
                token("-teal-300"), token("-sage-300"));
        for (String surface : surfaces) {
            for (String foreground : foregrounds) {
                double ratio = contrast(foreground, surface);
                assertTrue(ratio >= AA, String.format(
                        "%s on %s is %.2f:1, below AA", foreground, surface, ratio));
            }
        }
    }

    @Test
    @DisplayName("The header band carries its title and its buttons")
    void headerIsReadable() {
        String band = token("-midnight-700");
        assertTrue(contrast(token("-paper-0"), band) >= AA, "the title must be readable on the header");
        // The organisation line is small and quiet, but still has to be legible.
        double organisation = contrast(token("-sage-300"), band);
        assertTrue(organisation >= AA, String.format("the organisation line is %.2f:1", organisation));
        // Buttons sitting on the band need to separate from it.
        assertTrue(contrast(token("-teal-600"), band) >= AA_LARGE, "the primary header button must stand out");
        assertTrue(contrast(token("-sage-600"), band) >= AA_LARGE, "the secondary header button must stand out");
    }

    @Test
    @DisplayName("The dark theme redefines only roles, never the ramp itself")
    void darkThemeKeepsTheRamps() throws Exception {
        String dark;
        try (InputStream in = PaletteContrastTest.class.getResourceAsStream("/css/dark.css")) {
            assertTrue(in != null, "dark.css is missing");
            dark = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Redefining a ramp stop in the dark sheet would silently break every
        // contrast figure measured against the base sheet.
        Matcher m = Pattern.compile("(-(?:midnight|teal|sage|paper|surface)-\\d+):\\s*#").matcher(dark);
        assertFalse(m.find(), m.hitEnd() ? "" : "dark.css redefines the ramp token " + m.group(1));
    }

    // ------------------------------------------------------------- 1.4.11

    /** The declared value of a property inside a selector block, or null. */
    private static String declaredValue(String css, String selector, String property) {
        int at = 0;
        while (true) {
            at = css.indexOf(selector, at);
            if (at < 0) {
                return null;
            }
            // The selector must stand alone, not be the tail of a longer one.
            char before = at == 0 ? '\n' : css.charAt(at - 1);
            int after = at + selector.length();
            char next = after < css.length() ? css.charAt(after) : ' ';
            boolean standalone = (before == '\n' || before == ' ' || before == ',')
                    && (next == ' ' || next == '{' || next == ',' || next == '\n');
            int brace = css.indexOf('{', after);
            int nextSelector = css.indexOf('}', after);
            if (standalone && brace >= 0 && (nextSelector < 0 || brace < nextSelector)) {
                String block = css.substring(brace, css.indexOf('}', brace));
                Matcher m = Pattern.compile(property + ":\\s*([^;]+);").matcher(block);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
            at = after;
        }
    }

    /** Resolve a declared value to a hex colour, following one token hop. */
    private static String resolve(String value, Map<String, String> extra) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("#")) {
            return v.toUpperCase();
        }
        String direct = extra.get(v);
        if (direct == null) {
            direct = tokens.get(v);
        }
        if (direct == null) {
            return null;
        }
        return direct.startsWith("#") ? direct.toUpperCase() : resolve(direct, extra);
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = PaletteContrastTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Role tokens a sheet defines in its own .root block. */
    private static Map<String, String> roles(String css) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(-app-[a-z-]+):\\s*([^;]+);").matcher(css);
        while (m.find()) {
            map.put(m.group(1), m.group(2).trim());
        }
        return map;
    }

    /**
     * WCAG 1.4.11: a control has to be distinguishable from what it sits on,
     * at 3:1.
     *
     * <p>This reads the fill each stylesheet actually declares rather than the
     * one the test expects, because the first version of this check asserted
     * its own assumptions and so still passed while a midnight button sat on a
     * midnight surface at 1.37:1.
     */
    /**
     * What a button variant actually renders as in the dark theme.
     *
     * <p>Not simply "the dark sheet's rule, or the light one if it has none".
     * The dark sheet restates plain {@code .button}, and JavaFX breaks equal
     * specificity by declaration order, so that rule beats every variant
     * declared earlier in app.css. A variant the dark sheet does not restate is
     * therefore painted as a plain button, whatever app.css says. Modelling
     * that is the point: an earlier version of this check took the light value
     * as the fallback and so passed while the Reset button rendered sage
     * instead of red.
     */
    private static String darkValue(String dark, String light, String selector, String property,
                                    Map<String, String> darkRoles, Map<String, String> lightRoles) {
        String own = declaredValue(dark, selector, property);
        if (own != null) {
            return resolve(own, darkRoles);
        }
        String overriddenByPlainButton = selector.equals(".button")
                ? null : declaredValue(dark, ".button", property);
        if (overriddenByPlainButton != null) {
            return resolve(overriddenByPlainButton, darkRoles);
        }
        return resolve(declaredValue(light, selector, property), darkRoles.isEmpty() ? lightRoles : darkRoles);
    }

    @Test
    @DisplayName("Every button fill separates from the surface behind it")
    void controlsSeparateFromTheirSurface() throws Exception {
        String light = read("/css/app.css");
        String dark = read("/css/dark.css");

        Map<String, String> lightRoles = roles(light);
        Map<String, String> darkRoles = roles(dark);

        // The left rail, which is where the step buttons sit in both themes.
        String lightPanel = resolve(lightRoles.get("-app-surface-quiet"), lightRoles);
        String darkPanel = resolve(darkRoles.get("-app-surface-quiet"), darkRoles);
        assertTrue(lightPanel != null && darkPanel != null, "both themes must define -app-surface-quiet");

        for (String selector : List.of(".button", ".button-success", ".button-danger")) {
            String lightFill = resolve(declaredValue(light, selector, "-fx-background-color"), lightRoles);
            assertTrue(lightFill != null, "light theme declares no fill for " + selector);
            assertComponent("light " + selector, lightFill, lightPanel);

            String darkFill = darkValue(dark, light, selector, "-fx-background-color", darkRoles, lightRoles);
            assertTrue(darkFill != null, "dark theme declares an unresolvable fill for " + selector);
            assertComponent("dark " + selector, darkFill, darkPanel);
        }

        // A variant that the dark sheet forgets to restate is not merely off
        // palette, it is indistinguishable from an ordinary button.
        String plainDark = resolve(declaredValue(dark, ".button", "-fx-background-color"), darkRoles);
        for (String selector : List.of(".button-success", ".button-danger")) {
            String darkFill = darkValue(dark, light, selector, "-fx-background-color", darkRoles, lightRoles);
            assertFalse(darkFill.equals(plainDark), String.format(
                    "dark %s renders as a plain button (%s): the dark sheet restates .button after "
                            + "app.css declares this variant, so it must restate the variant too",
                    selector, darkFill));
        }

        // The secondary button is outlined, so its border carries the boundary.
        for (String[] theme : new String[][]{{"light", light, lightPanel}, {"dark", dark, darkPanel}}) {
            Map<String, String> themeRoles = theme[0].equals("light") ? lightRoles : darkRoles;
            String border = resolve(declaredValue(theme[1], ".button-secondary", "-fx-border-color"), themeRoles);
            assertTrue(border != null, theme[0] + " theme declares no border for .button-secondary");
            assertComponent(theme[0] + " .button-secondary border", border, theme[2]);
        }
    }

    @Test
    @DisplayName("The header band separates from the buttons placed on it")
    void headerButtonsSeparateFromTheBand() throws Exception {
        String light = read("/css/app.css");
        String dark = read("/css/dark.css");
        for (String[] theme : new String[][]{{"light", light}, {"dark", dark}}) {
            Map<String, String> themeRoles = roles(theme[1]);
            String band = resolve(declaredValue(theme[1], ".app-header", "-fx-background-color"), themeRoles);
            if (band == null) {
                continue; // the light sheet supplies it
            }
            for (String selector : List.of(".header-button", ".header-button-secondary")) {
                String fill = resolve(declaredValue(theme[1], selector, "-fx-background-color"), themeRoles);
                if (fill != null) {
                    assertComponent(theme[0] + " " + selector, fill, band);
                }
            }
        }
    }

    /**
     * The modals are one family now, so the pieces {@code Modals} gives every
     * dialog have to exist and have to be readable in both themes.
     */
    @Test
    @DisplayName("Every dialog's header, body and chosen option are readable")
    void dialogChromeIsReadable() throws Exception {
        String light = read("/css/app.css");
        String dark = read("/css/dark.css");

        for (String selector : List.of(".app-dialog", ".dialog-header", ".dialog-headline",
                ".dialog-subhead", ".dialog-content", ".choice-card", ".choice-card-selected", ".badge")) {
            assertTrue(light.contains(selector + " ") || light.contains(selector + ",")
                            || light.contains(selector + "\n"),
                    selector + " is missing from app.css, so Modals would render it unstyled");
        }

        for (String[] theme : new String[][]{{"light", light}, {"dark", dark}}) {
            Map<String, String> themeRoles = roles(theme[1]);
            String header = resolve(declaredValue(theme[1], ".dialog-header", "-fx-background-color"), themeRoles);
            if (header == null) {
                continue; // the light sheet supplies it for both
            }
            String headline = resolve(declaredValue(theme[1], ".dialog-headline", "-fx-text-fill"), themeRoles);
            String subhead = resolve(declaredValue(theme[1], ".dialog-subhead", "-fx-text-fill"), themeRoles);
            assertText(theme[0] + " dialog headline", headline, header);
            assertText(theme[0] + " dialog subhead", subhead, header);
        }
    }

    @Test
    @DisplayName("The chosen card is marked by something that meets 3:1, not by a wash")
    void selectedCardIsDistinguishable() throws Exception {
        String light = read("/css/app.css");
        String dark = read("/css/dark.css");

        for (String[] theme : new String[][]{{"light", light, light}, {"dark", dark, light}}) {
            Map<String, String> themeRoles = roles(theme[1]);
            if (!themeRoles.containsKey("-app-accent")) {
                continue;
            }
            // The rules themselves live in the base sheet; only the roles change.
            Map<String, String> merged = new LinkedHashMap<>(roles(theme[2]));
            merged.putAll(themeRoles);

            String card = resolve(declaredValue(light, ".choice-card", "-fx-background-color"), merged);
            String border = resolve(declaredValue(light, ".choice-card-selected", "-fx-border-color"), merged);
            String wash = resolve(declaredValue(light, ".choice-card-selected", "-fx-background-color"), merged);
            String text = resolve(merged.get("-app-text"), merged);

            assertComponent(theme[0] + " selected card border", border, card);
            assertText(theme[0] + " text on the selected card", text, wash);
        }
    }

    private static void assertText(String what, String foreground, String background) {
        assertTrue(foreground != null && background != null, what + ": colours could not be resolved");
        double ratio = contrast(foreground, background);
        assertTrue(ratio >= AA, String.format(
                "%s: %s on %s is %.2f:1, below AA", what, foreground, background, ratio));
    }

    private static void assertComponent(String what, String fill, String surface) {
        double ratio = contrast(fill, surface);
        assertTrue(ratio >= 3.0, String.format(
                "%s: %s on %s is %.2f:1, below the 3:1 a control needs", what, fill, surface, ratio));
    }
}
