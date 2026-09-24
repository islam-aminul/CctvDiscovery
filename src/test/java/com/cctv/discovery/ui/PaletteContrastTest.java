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
}
