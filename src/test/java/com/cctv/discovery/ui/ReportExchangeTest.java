package com.cctv.discovery.ui;

import javafx.stage.FileChooser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The export asks only for the site ID, so the file type comes from the save
 * dialog: the extension typed into the name when there is one, otherwise the
 * file type picked from the list.
 */
class ReportExchangeTest {

    private static final FileChooser.ExtensionFilter EXCEL =
            new FileChooser.ExtensionFilter("Excel workbook (.xlsx)", "*.xlsx");
    private static final FileChooser.ExtensionFilter CSV =
            new FileChooser.ExtensionFilter("Comma-separated values (.csv)", "*.csv");
    private static final FileChooser.ExtensionFilter JSON =
            new FileChooser.ExtensionFilter("JSON (.json)", "*.json");

    @Test
    @DisplayName("The extension typed into the name decides the format")
    void nameDecides() {
        assertEquals(ReportExchange.Format.CSV, ReportExchange.formatFor(new File("site.csv"), EXCEL));
        assertEquals(ReportExchange.Format.JSON, ReportExchange.formatFor(new File("SITE.JSON"), EXCEL));
        assertEquals(ReportExchange.Format.EXCEL, ReportExchange.formatFor(new File("site.xlsx"), JSON));
    }

    @Test
    @DisplayName("Without an extension, the file type picked in the dialog decides")
    void pickedTypeDecides() {
        assertEquals(ReportExchange.Format.CSV, ReportExchange.formatFor(new File("site"), CSV));
        assertEquals(ReportExchange.Format.JSON, ReportExchange.formatFor(new File("site.report"), JSON));
        assertEquals(ReportExchange.Format.EXCEL, ReportExchange.formatFor(new File("site"), null),
                "Excel when nothing says otherwise");
    }

    @Test
    @DisplayName("A name without the format's extension gets it added")
    void addsTheExtension() {
        assertEquals("site.csv", ReportExchange.withExtension(new File("site"), ReportExchange.Format.CSV).getName());
        assertEquals("site.xlsx",
                ReportExchange.withExtension(new File("site.xlsx"), ReportExchange.Format.EXCEL).getName());
        assertEquals("site.v2.json",
                ReportExchange.withExtension(new File("site.v2"), ReportExchange.Format.JSON).getName());
    }
}
