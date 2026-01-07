package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Excel exporter using Apache POI.
 * Formats data with Consolas font, highlights compliance issues in red.
 */
public class ExcelExporter {
    private static final Logger logger = LoggerFactory.getLogger(ExcelExporter.class);

    /**
     * Export devices to Excel file.
     */
    public void exportToExcel(List<Device> devices, String siteId, String premiseName,
                               String operatorName, File outputFile) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("CCTV Audit Report");

        // Create styles
        CellStyle warningStyle = createWarningStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle redStyle = createRedHighlightStyle(workbook);

        int rowNum = 0;

        // Warning row
        Row warningRow = sheet.createRow(rowNum++);
        Cell warningCell = warningRow.createCell(0);
        warningCell.setCellValue("⚠ WARNING: This document contains PLAINTEXT PASSWORDS. Handle with care!");
        warningCell.setCellStyle(warningStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 15));
        warningRow.setHeightInPoints(25);

        // Metadata rows
        rowNum = addMetadataRow(sheet, rowNum, "Site ID:", siteId, dataStyle);
        if (premiseName != null && !premiseName.isEmpty()) {
            rowNum = addMetadataRow(sheet, rowNum, "Premise Name:", premiseName, dataStyle);
        }
        if (operatorName != null && !operatorName.isEmpty()) {
            rowNum = addMetadataRow(sheet, rowNum, "Operator:", operatorName, dataStyle);
        }
        rowNum = addMetadataRow(sheet, rowNum, "Report Date:", new java.util.Date().toString(), dataStyle);
        rowNum++; // Blank row

        // Header row
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {
                "IP", "MAC", "Name", "Type", "Manufacturer", "Model", "Serial Number",
                "Time Diff (sec)", "Username", "Password", "Error",
                "Stream Name", "RTSP URL", "Resolution", "Codec", "Bitrate (kbps)", "FPS"
        };

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        for (Device device : devices) {
            if (device.getRtspStreams().isEmpty()) {
                // Device with no streams
                rowNum = addDeviceRow(sheet, rowNum, device, null, dataStyle, redStyle);
            } else {
                // Device with streams
                for (RTSPStream stream : device.getRtspStreams()) {
                    rowNum = addDeviceRow(sheet, rowNum, device, stream, dataStyle, redStyle);
                }
            }
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            workbook.write(fos);
        }

        workbook.close();
        logger.info("Excel report exported to: {}", outputFile.getAbsolutePath());
    }

    private int addMetadataRow(Sheet sheet, int rowNum, String label, String value, CellStyle style) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(style);

        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(style);

        return rowNum + 1;
    }

    private int addDeviceRow(Sheet sheet, int rowNum, Device device, RTSPStream stream,
                             CellStyle dataStyle, CellStyle redStyle) {
        Row row = sheet.createRow(rowNum);

        // Device columns
        createCell(row, 0, device.getIpAddress(), dataStyle);
        createCell(row, 1, device.getMacAddress(), dataStyle);
        createCell(row, 2, device.getDeviceName(), dataStyle);
        createCell(row, 3, device.getDeviceType(), dataStyle);
        createCell(row, 4, device.getManufacturer(), dataStyle);
        createCell(row, 5, device.getModel(), dataStyle);
        createCell(row, 6, device.getSerialNumber(), dataStyle);
        createCell(row, 7, device.getTimeDifferenceSeconds() != null ?
                device.getTimeDifferenceSeconds().toString() : "", dataStyle);
        createCell(row, 8, device.getUsername(), dataStyle);
        createCell(row, 9, device.getPassword(), dataStyle);
        createCell(row, 10, device.getErrorMessage(), dataStyle);

        // Stream columns
        if (stream != null) {
            createCell(row, 11, stream.getStreamName(), dataStyle);
            createCell(row, 12, stream.getRtspUrl(), dataStyle);

            // Compliance-checked columns
            boolean isNonCompliant = stream.getComplianceIssues() != null && !stream.getComplianceIssues().isEmpty();
            CellStyle streamStyle = isNonCompliant ? redStyle : dataStyle;

            createCell(row, 13, stream.getResolution(), streamStyle);
            createCell(row, 14, stream.getCodec(), streamStyle);
            createCell(row, 15, stream.getBitrateKbps() != null ?
                    stream.getBitrateKbps().toString() : "", streamStyle);
            createCell(row, 16, stream.getFps() != null ?
                    String.format("%.2f", stream.getFps()) : "", streamStyle);
        }

        return rowNum + 1;
    }

    private void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private CellStyle createWarningStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontName("Consolas");
        font.setFontHeightInPoints((short) 12);
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontName("Consolas");
        font.setFontHeightInPoints((short) 11);
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setWrapText(true);
        return style;
    }

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontName("Consolas");
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createRedHighlightStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontName("Consolas");
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.RED.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
}
