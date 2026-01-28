package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.HostAuditData;
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
     * Export devices to Excel file with password protection.
     */
    public void exportToExcel(List<Device> devices, String siteId, String premiseName,
            String operatorName, File outputFile, String password, HostAuditData hostAudit) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("CCTV Report");

        // Create styles with locked cells
        CellStyle warningStyle = createWarningStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle redStyle = createRedHighlightStyle(workbook);

        int rowNum = 0;

        // Warning row - Enhanced with protection notice
        Row warningRow = sheet.createRow(rowNum++);
        Cell warningCell = warningRow.createCell(0);
        warningCell
                .setCellValue("⚠ WARNING: This document contains PLAINTEXT PASSWORDS. Handle with care! [PROTECTED]");
        warningCell.setCellStyle(warningStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 17));
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
                "Stream Name", "RTSP URL", "Resolution", "Codec", "Profile", "Bitrate (kbps)", "FPS"
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

        // Apply worksheet protection to CCTV sheet
        if (password != null && !password.isEmpty()) {
            protectSheet(sheet, password);
            logger.info("CCTV Audit sheet protected with password");
        }

        // Create Host Audit sheet
        if (hostAudit != null) {
            Sheet hostSheet = workbook.createSheet("Host Report");
            createHostAuditSheet(hostSheet, hostAudit, headerStyle, dataStyle);

            // Protect host audit sheet too
            if (password != null && !password.isEmpty()) {
                protectSheet(hostSheet, password);
                logger.info("Host Audit sheet protected with password");
            }
        }

        // Write to file
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            workbook.write(fos);
        }

        workbook.close();
        logger.info("Excel report exported to: {}", outputFile.getAbsolutePath());
    }

    /**
     * Protect worksheet to prevent tampering.
     * Users can view and select cells but cannot modify content.
     *
     * The protectSheet() method locks all cells by default and prevents:
     * - Editing cell values
     * - Inserting/deleting rows and columns
     * - Formatting cells, rows, and columns
     * - Any structural changes to the worksheet
     */
    private void protectSheet(Sheet sheet, String password) {
        // Enable sheet protection with password
        // This automatically locks all cells and prevents modifications
        sheet.protectSheet(password);

        logger.info("Sheet protection applied successfully");
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
        createCell(row, 7,
                device.getTimeDifferenceSeconds() != null ? device.getTimeDifferenceSeconds().toString() : "",
                dataStyle);
        createCell(row, 8, device.getUsername(), dataStyle);
        createCell(row, 9, device.getPassword(), dataStyle);
        createCell(row, 10, device.getErrorMessage(), dataStyle);

        // Stream columns
        if (stream != null) {
            createCell(row, 11, stream.getStreamName(), dataStyle);
            createCell(row, 12, stream.getRtspUrl(), dataStyle);

            // Per-cell compliance flagging based on individual issues
            String issues = stream.getComplianceIssues();
            boolean hasIssues = issues != null && !issues.isEmpty();

            // Resolution: red if non-compliant sub-stream resolution
            boolean resolutionFlagged = hasIssues && issues.contains("Resolution");
            createCell(row, 13, stream.getResolution(),
                    resolutionFlagged ? redStyle : dataStyle);

            // Codec: red if not H.264
            boolean codecFlagged = hasIssues && issues.contains("Codec");
            createCell(row, 14, stream.getCodec(),
                    codecFlagged ? redStyle : dataStyle);

            // Profile: red if High profile (requires transcoding)
            boolean profileFlagged = hasIssues && issues.contains("High profile");
            createCell(row, 15, stream.getProfile() != null ? stream.getProfile() : "",
                    profileFlagged ? redStyle : dataStyle);

            // Bitrate: red if >= 512kbps
            boolean bitrateFlagged = hasIssues && issues.contains("Bitrate");
            createCell(row, 16, stream.getBitrateKbps() != null ? stream.getBitrateKbps().toString() : "",
                    bitrateFlagged ? redStyle : dataStyle);

            // FPS: normal style (no compliance rule)
            createCell(row, 17, stream.getFps() != null ? String.format("%.2f", stream.getFps()) : "", dataStyle);
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
        font.setFontHeightInPoints((short) 10);
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
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /**
     * Create Host Audit sheet with system information.
     */
    private void createHostAuditSheet(Sheet sheet, HostAuditData data, CellStyle headerStyle, CellStyle dataStyle) {
        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("HOST AUDIT REPORT");
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
        rowNum++; // Blank row

        // System Information Section
        rowNum = addSectionHeader(sheet, rowNum, "SYSTEM INFORMATION", headerStyle);
        rowNum = addInfoRow(sheet, rowNum, "Computer Name", data.getComputerName(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Domain", data.getDomain(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Username", data.getUsername(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Operating System", data.getOperatingSystem(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "OS Version", data.getOsVersion(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "OS Architecture", data.getOsArchitecture(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "OS Build", data.getOsBuild(), dataStyle);
        rowNum++; // Blank row

        // Hardware Information Section
        rowNum = addSectionHeader(sheet, rowNum, "HARDWARE INFORMATION", headerStyle);
        rowNum = addInfoRow(sheet, rowNum, "Make", data.getMake(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Model", data.getModel(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "CPU Name", data.getCpuName(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "CPU Cores", String.valueOf(data.getCpuCores()), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "CPU Threads", String.valueOf(data.getCpuThreads()), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "CPU Speed", data.getCpuSpeed(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Total Memory", data.getTotalMemory(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Available Memory", data.getAvailableMemory(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Memory Usage", data.getMemoryUsage(), dataStyle);
        rowNum++; // Blank row

        // BIOS and Motherboard
        rowNum = addSectionHeader(sheet, rowNum, "BIOS & MOTHERBOARD", headerStyle);
        rowNum = addInfoRow(sheet, rowNum, "BIOS Information", data.getBiosInformation(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Motherboard", data.getMotherboard(), dataStyle);
        rowNum++; // Blank row

        // Disk Information
        if (!data.getDisks().isEmpty()) {
            rowNum = addSectionHeader(sheet, rowNum, "DISK INFORMATION", headerStyle);
            Row diskHeaderRow = sheet.createRow(rowNum++);
            createCell(diskHeaderRow, 0, "Name", headerStyle);
            createCell(diskHeaderRow, 1, "Model", headerStyle);
            createCell(diskHeaderRow, 2, "Size", headerStyle);
            createCell(diskHeaderRow, 3, "Type", headerStyle);

            for (HostAuditData.DiskInfo disk : data.getDisks()) {
                Row diskRow = sheet.createRow(rowNum++);
                createCell(diskRow, 0, disk.getName(), dataStyle);
                createCell(diskRow, 1, disk.getModel(), dataStyle);
                createCell(diskRow, 2, disk.getSize(), dataStyle);
                createCell(diskRow, 3, disk.getType(), dataStyle);
            }
            rowNum++; // Blank row
        }

        // Network Adapters
        if (!data.getNetworkAdapters().isEmpty()) {
            rowNum = addSectionHeader(sheet, rowNum, "NETWORK ADAPTERS", headerStyle);
            Row netHeaderRow = sheet.createRow(rowNum++);
            createCell(netHeaderRow, 0, "Name", headerStyle);
            createCell(netHeaderRow, 1, "MAC Address", headerStyle);
            createCell(netHeaderRow, 2, "IP Address", headerStyle);
            createCell(netHeaderRow, 3, "Status", headerStyle);
            createCell(netHeaderRow, 4, "Speed", headerStyle);

            for (HostAuditData.NetworkAdapterInfo adapter : data.getNetworkAdapters()) {
                Row netRow = sheet.createRow(rowNum++);
                createCell(netRow, 0, adapter.getName(), dataStyle);
                createCell(netRow, 1, adapter.getMacAddress(), dataStyle);
                createCell(netRow, 2, adapter.getIpAddress(), dataStyle);
                createCell(netRow, 3, adapter.getStatus(), dataStyle);
                createCell(netRow, 4, adapter.getSpeed(), dataStyle);
            }
            rowNum++; // Blank row
        }

        // System Status
        rowNum = addSectionHeader(sheet, rowNum, "SYSTEM STATUS", headerStyle);
        rowNum = addInfoRow(sheet, rowNum, "System Uptime", data.getSystemUptime(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Current Time", data.getCurrentTime(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Time Zone", data.getTimeZone(), dataStyle);
        rowNum = addInfoRow(sheet, rowNum, "Time Server Sync", data.getTimeServerSync(), dataStyle);

        // NTP Time Drift with alert
        if (data.getNtpTimeDrift() != null) {
            String driftValue = String.format("%.3f seconds", data.getNtpTimeDrift());
            if (data.isNtpTimeDriftAlert()) {
                driftValue += " ⚠ ALERT: Drift > 1 second!";
            }
            rowNum = addInfoRow(sheet, rowNum, "NTP Time Drift", driftValue,
                    data.isNtpTimeDriftAlert() ? createRedHighlightStyle(sheet.getWorkbook()) : dataStyle);
        }
        rowNum++; // Blank row

        // Top Processes - CPU
        if (!data.getTopCpuProcesses().isEmpty()) {
            rowNum = addSectionHeader(sheet, rowNum, "TOP 5 PROCESSES BY CPU", headerStyle);
            Row cpuHeaderRow = sheet.createRow(rowNum++);
            createCell(cpuHeaderRow, 0, "Process Name", headerStyle);
            createCell(cpuHeaderRow, 1, "CPU Usage", headerStyle);

            for (HostAuditData.ProcessInfo proc : data.getTopCpuProcesses()) {
                Row procRow = sheet.createRow(rowNum++);
                createCell(procRow, 0, proc.getName(), dataStyle);
                createCell(procRow, 1, proc.getValue(), dataStyle);
            }
            rowNum++; // Blank row
        }

        // Top Processes - Memory
        if (!data.getTopMemoryProcesses().isEmpty()) {
            rowNum = addSectionHeader(sheet, rowNum, "TOP 5 PROCESSES BY MEMORY", headerStyle);
            Row memHeaderRow = sheet.createRow(rowNum++);
            createCell(memHeaderRow, 0, "Process Name", headerStyle);
            createCell(memHeaderRow, 1, "Memory Usage", headerStyle);

            for (HostAuditData.ProcessInfo proc : data.getTopMemoryProcesses()) {
                Row procRow = sheet.createRow(rowNum++);
                createCell(procRow, 0, proc.getName(), dataStyle);
                createCell(procRow, 1, proc.getValue(), dataStyle);
            }
            rowNum++; // Blank row
        }

        // Top Processes - Disk IO
        if (!data.getTopDiskIOProcesses().isEmpty()) {
            rowNum = addSectionHeader(sheet, rowNum, "TOP 5 PROCESSES BY DISK I/O", headerStyle);
            Row ioHeaderRow = sheet.createRow(rowNum++);
            createCell(ioHeaderRow, 0, "Process Name", headerStyle);
            createCell(ioHeaderRow, 1, "Disk I/O", headerStyle);

            for (HostAuditData.ProcessInfo proc : data.getTopDiskIOProcesses()) {
                Row procRow = sheet.createRow(rowNum++);
                createCell(procRow, 0, proc.getName(), dataStyle);
                createCell(procRow, 1, proc.getValue(), dataStyle);
            }
        }

        // Auto-size columns
        for (int i = 0; i < 5; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private int addSectionHeader(Sheet sheet, int rowNum, String title, CellStyle headerStyle) {
        Row row = sheet.createRow(rowNum);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 1));
        return rowNum + 1;
    }

    private int addInfoRow(Sheet sheet, int rowNum, String label, String value, CellStyle style) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(style);

        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value != null ? value : "N/A");
        valueCell.setCellStyle(style);

        return rowNum + 1;
    }
}
