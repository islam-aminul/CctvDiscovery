package com.cctv.discovery.export;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.HostAuditData;
import com.cctv.discovery.model.RTSPStream;
import com.cctv.discovery.util.RtspClient;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the audit workbook.
 *
 * <p>Five sheets: a summary, one row per device, one row per stream, the audit
 * findings and the host report. When a password is supplied the whole workbook
 * is encrypted (OOXML agile encryption, AES). The previous version applied only
 * sheet protection, which does not encrypt anything: the file could be renamed
 * to .zip and every cell read, including camera passwords.
 */
public final class ExcelExporter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExporter.class);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * What to put in the report and how to protect it.
     *
     * @param includeCredentials include usernames, passwords and credentialed
     *                           stream URLs
     * @param password           encrypts the workbook; null or blank writes it
     *                           unencrypted
     */
    public record ReportOptions(String siteId, String premiseName, String operatorName,
                                boolean includeCredentials, String password) {

        public static ReportOptions of(String siteId) {
            return new ReportOptions(siteId, null, null, true, null);
        }

        public boolean encrypted() {
            return password != null && !password.isBlank();
        }
    }

    /** Cell styles, created once per workbook (Excel caps the style table). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle sectionHeader;
        final CellStyle columnHeader;
        final CellStyle text;
        final CellStyle number;
        final CellStyle decimal;
        final CellStyle label;
        final CellStyle flagged;
        final CellStyle good;
        final CellStyle severityHigh;
        final CellStyle severityMedium;
        final CellStyle severityLow;
        final CellStyle wrapped;

        Styles(Workbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            sectionHeader = workbook.createCellStyle();
            sectionHeader.setFont(boldFont);
            sectionHeader.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            columnHeader = workbook.createCellStyle();
            columnHeader.setFont(headerFont);
            columnHeader.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            columnHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            columnHeader.setAlignment(HorizontalAlignment.CENTER);
            columnHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            columnHeader.setWrapText(true);
            border(columnHeader);

            text = workbook.createCellStyle();
            border(text);
            text.setVerticalAlignment(VerticalAlignment.TOP);

            number = workbook.createCellStyle();
            border(number);
            number.setAlignment(HorizontalAlignment.RIGHT);
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            decimal = workbook.createCellStyle();
            border(decimal);
            decimal.setAlignment(HorizontalAlignment.RIGHT);
            decimal.setDataFormat(workbook.createDataFormat().getFormat("0.00"));

            label = workbook.createCellStyle();
            label.setFont(boldFont);
            border(label);

            flagged = workbook.createCellStyle();
            border(flagged);
            flagged.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            flagged.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            good = workbook.createCellStyle();
            border(good);
            good.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            good.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            severityHigh = severity(workbook, IndexedColors.RED, true);
            severityMedium = severity(workbook, IndexedColors.LIGHT_ORANGE, false);
            severityLow = severity(workbook, IndexedColors.LEMON_CHIFFON, false);

            wrapped = workbook.createCellStyle();
            border(wrapped);
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);
        }

        private static CellStyle severity(Workbook workbook, IndexedColors colour, boolean white) {
            CellStyle style = workbook.createCellStyle();
            border(style);
            style.setFillForegroundColor(colour.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(true);
            if (white) {
                font.setColor(IndexedColors.WHITE.getIndex());
            }
            style.setFont(font);
            return style;
        }

        private static void border(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }

        CellStyle forSeverity(Finding.Severity severity) {
            return switch (severity) {
                case HIGH -> severityHigh;
                case MEDIUM -> severityMedium;
                case LOW -> severityLow;
                case INFO -> text;
            };
        }
    }

    /**
     * Write the report.
     *
     * @throws IOException if the file cannot be written
     */
    public void export(List<Device> devices, HostAuditData hostAudit, ReportOptions options, File outputFile)
            throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);

            writeSummarySheet(workbook, styles, devices, options);
            writeDeviceSheet(workbook, styles, devices, options);
            writeStreamSheet(workbook, styles, devices, options);
            writeFindingsSheet(workbook, styles, devices);
            if (hostAudit != null) {
                writeHostSheet(workbook, styles, hostAudit);
            }

            if (options.encrypted()) {
                writeEncrypted(workbook, options.password(), outputFile);
                logger.info("Wrote encrypted report to {}", outputFile.getAbsolutePath());
            } else {
                try (OutputStream out = Files.newOutputStream(outputFile.toPath())) {
                    workbook.write(out);
                }
                logger.info("Wrote report to {} (not encrypted)", outputFile.getAbsolutePath());
            }
        }
    }

    /**
     * Encrypt the workbook with OOXML agile encryption so that Excel asks for
     * the password before any content can be read.
     */
    private void writeEncrypted(Workbook workbook, String password, File outputFile) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        workbook.write(buffer);

        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(password);

            try (OPCPackage opc = OPCPackage.open(new java.io.ByteArrayInputStream(buffer.toByteArray()));
                 OutputStream encrypted = encryptor.getDataStream(fs)) {
                opc.save(encrypted);
            } catch (Exception e) {
                throw new IOException("Could not encrypt the workbook: " + e.getMessage(), e);
            }

            try (OutputStream out = Files.newOutputStream(outputFile.toPath())) {
                fs.writeFilesystem(out);
            }
        }
    }

    // ---------------------------------------------------------------- Summary

    private void writeSummarySheet(Workbook workbook, Styles styles, List<Device> devices, ReportOptions options) {
        Sheet sheet = workbook.createSheet("Summary");
        int row = 0;

        Cell titleCell = sheet.createRow(row++).createCell(0);
        titleCell.setCellValue("CCTV Discovery Report");
        titleCell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        row++;

        row = section(sheet, styles, row, "Report");
        row = labelled(sheet, styles, row, "Site ID", options.siteId());
        if (options.premiseName() != null && !options.premiseName().isBlank()) {
            row = labelled(sheet, styles, row, "Premise", options.premiseName());
        }
        if (options.operatorName() != null && !options.operatorName().isBlank()) {
            row = labelled(sheet, styles, row, "Surveyed by", options.operatorName());
        }
        row = labelled(sheet, styles, row, "Generated", TIMESTAMP.format(Instant.now()));
        row = labelled(sheet, styles, row, "Credentials included", options.includeCredentials() ? "Yes" : "No");
        row = labelled(sheet, styles, row, "File encrypted", options.encrypted() ? "Yes" : "No");
        row++;

        long cameras = devices.stream().filter(d -> d.getType() == Device.DeviceType.CAMERA).count();
        long recorders = devices.stream().filter(d -> d.getType() == Device.DeviceType.RECORDER).count();
        long withStreams = devices.stream().filter(d -> !d.getRtspStreams().isEmpty()).count();
        long streams = devices.stream().mapToLong(d -> d.getRtspStreams().size()).sum();
        long failed = devices.stream().filter(Device::isAuthFailed).count();
        long anonymous = devices.stream().filter(d -> Boolean.TRUE.equals(d.getRtspAnonymousAccess())).count();
        long nonCompliant = devices.stream()
                .flatMap(d -> d.getRtspStreams().stream())
                .filter(s -> !s.isCompliant())
                .count();

        row = section(sheet, styles, row, "Totals");
        row = counted(sheet, styles, row, "Devices found", devices.size());
        row = counted(sheet, styles, row, "Cameras", cameras);
        row = counted(sheet, styles, row, "Recorders", recorders);
        row = counted(sheet, styles, row, "Devices with a working stream", withStreams);
        row = counted(sheet, styles, row, "Streams discovered", streams);
        row = counted(sheet, styles, row, "Streams outside the rules", nonCompliant);
        row = counted(sheet, styles, row, "Devices that rejected every credential", failed);
        row = counted(sheet, styles, row, "Streams readable without a password", anonymous);
        row++;

        Map<Finding.Severity, Long> bySeverity = new LinkedHashMap<>();
        for (Finding.Severity severity : Finding.Severity.values()) {
            long count = devices.stream().flatMap(d -> d.getFindings().stream())
                    .filter(f -> f.severity() == severity).count();
            if (count > 0) {
                bySeverity.put(severity, count);
            }
        }
        if (!bySeverity.isEmpty()) {
            row = section(sheet, styles, row, "Findings");
            for (Map.Entry<Finding.Severity, Long> entry : bySeverity.entrySet()) {
                Row r = sheet.createRow(row++);
                Cell severityCell = r.createCell(0);
                severityCell.setCellValue(entry.getKey().label());
                severityCell.setCellStyle(styles.forSeverity(entry.getKey()));
                Cell countCell = r.createCell(1);
                countCell.setCellValue(entry.getValue());
                countCell.setCellStyle(styles.number);
            }
        }

        sheet.setColumnWidth(0, 12_000);
        sheet.setColumnWidth(1, 9_000);
        sheet.setColumnWidth(2, 6_000);
        sheet.setColumnWidth(3, 6_000);
    }

    private int section(Sheet sheet, Styles styles, int rowNum, String heading) {
        Row row = sheet.createRow(rowNum);
        Cell cell = row.createCell(0);
        cell.setCellValue(heading);
        cell.setCellStyle(styles.sectionHeader);
        Cell filler = row.createCell(1);
        filler.setCellStyle(styles.sectionHeader);
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 1));
        return rowNum + 1;
    }

    private int labelled(Sheet sheet, Styles styles, int rowNum, String label, String value) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(styles.label);
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value == null ? "" : value);
        valueCell.setCellStyle(styles.text);
        return rowNum + 1;
    }

    private int counted(Sheet sheet, Styles styles, int rowNum, String label, long count) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(styles.label);
        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(count);
        valueCell.setCellStyle(styles.number);
        return rowNum + 1;
    }

    // ---------------------------------------------------------------- Devices

    private void writeDeviceSheet(Workbook workbook, Styles styles, List<Device> devices, ReportOptions options) {
        Sheet sheet = workbook.createSheet("Devices");

        List<String> headers = new java.util.ArrayList<>(List.of(
                "IP address", "Status", "Type", "Name", "Manufacturer", "Model", "Firmware",
                "Serial number", "MAC address", "MAC vendor", "Open ports", "ONVIF service",
                "Clock drift (s)", "Streams", "Anonymous RTSP", "Found by", "Note"));
        if (options.includeCredentials()) {
            headers.add("Username");
            headers.add("Password");
        }
        header(sheet, styles, headers);

        int rowNum = 1;
        for (Device device : devices) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            cell(row, col++, device.getIpAddress(), styles.text);
            cell(row, col++, device.getStatus().label(), styles.text);
            cell(row, col++, device.getDeviceType(), styles.text);
            cell(row, col++, device.getDeviceName(), styles.text);
            cell(row, col++, device.getManufacturer(), styles.text);
            cell(row, col++, device.getModel(), styles.text);
            cell(row, col++, device.getFirmwareVersion(), styles.text);
            cell(row, col++, device.getSerialNumber(), styles.text);
            cell(row, col++, device.getMacAddress(), styles.text);
            cell(row, col++, device.getVendorFromMac(), styles.text);
            cell(row, col++, join(device.getAllOpenPorts()), styles.text);
            cell(row, col++, device.getOnvifServiceUrl(), styles.text);

            Long drift = device.getTimeDifferenceSeconds();
            Cell driftCell = row.createCell(col++);
            if (drift == null) {
                driftCell.setCellValue("");
                driftCell.setCellStyle(styles.text);
            } else {
                driftCell.setCellValue(drift);
                driftCell.setCellStyle(Math.abs(drift) > 5 ? styles.flagged : styles.number);
            }

            Cell streamCell = row.createCell(col++);
            streamCell.setCellValue(device.getRtspStreams().size());
            streamCell.setCellStyle(styles.number);

            Boolean anonymous = device.getRtspAnonymousAccess();
            Cell anonCell = row.createCell(col++);
            anonCell.setCellValue(anonymous == null ? "" : (anonymous ? "Yes" : "No"));
            anonCell.setCellStyle(Boolean.TRUE.equals(anonymous) ? styles.flagged : styles.text);

            cell(row, col++, device.getDiscoverySource(), styles.text);
            cell(row, col++, device.getErrorMessage(), styles.text);

            if (options.includeCredentials()) {
                cell(row, col++, device.getUsername(), styles.text);
                cell(row, col, device.getPassword(), styles.text);
            }
        }

        finish(sheet, headers.size(), rowNum);
        widths(sheet, 3_600, 3_000, 3_000, 6_000, 4_000, 6_000, 3_000, 4_000, 4_600, 4_600, 5_000, 11_000,
                3_200, 2_400, 3_600, 4_200, 7_000, 3_000, 3_000);
    }

    // ---------------------------------------------------------------- Streams

    private void writeStreamSheet(Workbook workbook, Styles styles, List<Device> devices, ReportOptions options) {
        Sheet sheet = workbook.createSheet("Streams");

        List<String> headers = List.of(
                "IP address", "Device", "Channel", "Stream", "Role", "Source", "RTSP URL",
                "Resolution", "Codec", "Profile", "Bitrate (kbps)", "Frame rate", "Keyframe (s)",
                "Audio", "Compliant", "Issues");
        header(sheet, styles, headers);

        int rowNum = 1;
        for (Device device : devices) {
            for (RTSPStream stream : device.getRtspStreams()) {
                Row row = sheet.createRow(rowNum++);
                int col = 0;
                cell(row, col++, device.getIpAddress(), styles.text);
                cell(row, col++, device.getDeviceName(), styles.text);
                cell(row, col++, stream.getChannelName(), styles.text);
                cell(row, col++, stream.getStreamName(), styles.text);
                cell(row, col++, stream.getRole().label(), styles.text);
                cell(row, col++, stream.getSource(), styles.text);

                String url = options.includeCredentials()
                        ? RtspClient.withCredentials(stream.getRtspUrl(), device.getUsername(), device.getPassword())
                        : RtspClient.stripCredentials(stream.getRtspUrl());
                cell(row, col++, url, styles.text);

                String issues = stream.getComplianceIssues();
                cell(row, col++, stream.getResolution(),
                        flaggedIf(styles, issues, "Resolution"));
                cell(row, col++, stream.getCodec(), flaggedIf(styles, issues, "Codec"));
                cell(row, col++, stream.getProfile(), flaggedIf(styles, issues, "profile"));

                numeric(row, col++, stream.getBitrateKbps(),
                        issues != null && issues.contains("Bitrate") ? styles.flagged : styles.number, styles.text);
                numeric(row, col++, stream.getFps(), styles.decimal, styles.text);
                numeric(row, col++, stream.getKeyframeIntervalSeconds(), styles.decimal, styles.text);
                cell(row, col++, stream.getAudioCodec(), styles.text);

                Cell compliantCell = row.createCell(col++);
                compliantCell.setCellValue(stream.isCompliant() ? "Yes" : "No");
                compliantCell.setCellStyle(stream.isCompliant() ? styles.good : styles.flagged);

                cell(row, col, issues, styles.wrapped);
            }
        }

        finish(sheet, headers.size(), rowNum);
        widths(sheet, 3_600, 6_000, 3_200, 4_200, 2_400, 3_200, 14_000, 3_400, 2_600, 3_400,
                3_400, 2_800, 3_200, 3_400, 2_800, 12_000);
    }

    private CellStyle flaggedIf(Styles styles, String issues, String keyword) {
        return issues != null && issues.contains(keyword) ? styles.flagged : styles.text;
    }

    // --------------------------------------------------------------- Findings

    private void writeFindingsSheet(Workbook workbook, Styles styles, List<Device> devices) {
        Sheet sheet = workbook.createSheet("Findings");
        List<String> headers = List.of("Severity", "Category", "Device", "Finding", "Detail", "Recommended action");
        header(sheet, styles, headers);

        record Row2(Device device, Finding finding) {
        }
        List<Row2> rows = devices.stream()
                .flatMap(d -> d.getFindings().stream().map(f -> new Row2(d, f)))
                .sorted(Comparator.comparingInt((Row2 r) -> r.finding().severity().ordinal())
                        .thenComparing(r -> r.device().getIpAddress()))
                .toList();

        int rowNum = 1;
        for (Row2 entry : rows) {
            Row row = sheet.createRow(rowNum++);
            Finding finding = entry.finding();
            Cell severityCell = row.createCell(0);
            severityCell.setCellValue(finding.severity().label());
            severityCell.setCellStyle(styles.forSeverity(finding.severity()));
            cell(row, 1, finding.category(), styles.text);
            cell(row, 2, entry.device().getIpAddress(), styles.text);
            cell(row, 3, finding.title(), styles.text);
            cell(row, 4, finding.detail(), styles.wrapped);
            cell(row, 5, finding.recommendation(), styles.wrapped);
        }

        if (rowNum == 1) {
            Row row = sheet.createRow(1);
            cell(row, 0, "No findings were raised.", styles.text);
        }

        finish(sheet, headers.size(), rowNum);
        widths(sheet, 2_800, 3_600, 3_600, 11_000, 16_000, 16_000);
    }

    // ------------------------------------------------------------------- Host

    private void writeHostSheet(Workbook workbook, Styles styles, HostAuditData data) {
        Sheet sheet = workbook.createSheet("Host");
        int row = 0;

        Cell titleCell = sheet.createRow(row++).createCell(0);
        titleCell.setCellValue("Survey computer");
        titleCell.setCellStyle(styles.title);
        row++;

        row = section(sheet, styles, row, "System");
        row = labelled(sheet, styles, row, "Computer name", data.getComputerName());
        row = labelled(sheet, styles, row, "Domain", data.getDomain());
        row = labelled(sheet, styles, row, "User", data.getUsername());
        row = labelled(sheet, styles, row, "Operating system", data.getOperatingSystem());
        row = labelled(sheet, styles, row, "Version", data.getOsVersion());
        row = labelled(sheet, styles, row, "Build", data.getOsBuild());
        row = labelled(sheet, styles, row, "Architecture", data.getOsArchitecture());
        row = labelled(sheet, styles, row, "Uptime", data.getSystemUptime());
        row++;

        row = section(sheet, styles, row, "Hardware");
        row = labelled(sheet, styles, row, "Make", data.getMake());
        row = labelled(sheet, styles, row, "Model", data.getModel());
        row = labelled(sheet, styles, row, "CPU", data.getCpuName());
        row = labelled(sheet, styles, row, "Cores / threads",
                data.getCpuCores() + " / " + data.getCpuThreads());
        row = labelled(sheet, styles, row, "CPU speed", data.getCpuSpeed());
        row = labelled(sheet, styles, row, "Memory", data.getMemoryUsage());
        row = labelled(sheet, styles, row, "BIOS", data.getBiosInformation());
        row = labelled(sheet, styles, row, "Motherboard", data.getMotherboard());
        row++;

        row = section(sheet, styles, row, "Clock");
        row = labelled(sheet, styles, row, "Local time", data.getCurrentTime());
        row = labelled(sheet, styles, row, "Time zone", data.getTimeZone());
        row = labelled(sheet, styles, row, "Time source", data.getTimeServerSync());
        if (data.getNtpTimeDrift() != null) {
            Row driftRow = sheet.createRow(row++);
            Cell label = driftRow.createCell(0);
            label.setCellValue("Drift from internet time");
            label.setCellStyle(styles.label);
            Cell value = driftRow.createCell(1);
            value.setCellValue(String.format("%.3f seconds", data.getNtpTimeDrift()));
            value.setCellStyle(data.isNtpTimeDriftAlert() ? styles.flagged : styles.text);
        }
        row++;

        if (!data.getDisks().isEmpty()) {
            row = section(sheet, styles, row, "Disks");
            row = tableHeader(sheet, styles, row, List.of("Name", "Model", "Size", "Type"));
            for (HostAuditData.DiskInfo disk : data.getDisks()) {
                Row r = sheet.createRow(row++);
                cell(r, 0, disk.getName(), styles.text);
                cell(r, 1, disk.getModel(), styles.text);
                cell(r, 2, disk.getSize(), styles.text);
                cell(r, 3, disk.getType(), styles.text);
            }
            row++;
        }

        if (!data.getNetworkAdapters().isEmpty()) {
            row = section(sheet, styles, row, "Network adapters");
            row = tableHeader(sheet, styles, row, List.of("Name", "MAC address", "IP address", "Kind", "Speed"));
            for (HostAuditData.NetworkAdapterInfo adapter : data.getNetworkAdapters()) {
                Row r = sheet.createRow(row++);
                cell(r, 0, adapter.getName(), styles.text);
                cell(r, 1, adapter.getMacAddress(), styles.text);
                cell(r, 2, adapter.getIpAddress(), styles.text);
                cell(r, 3, adapter.getStatus(), styles.text);
                cell(r, 4, adapter.getSpeed(), styles.text);
            }
            row++;
        }

        row = processes(sheet, styles, row, "Top processes by CPU", data.getTopCpuProcesses());
        row = processes(sheet, styles, row, "Top processes by memory", data.getTopMemoryProcesses());
        processes(sheet, styles, row, "Top processes by disk activity", data.getTopDiskIOProcesses());

        widths(sheet, 9_000, 12_000, 6_000, 6_000, 6_000);
    }

    private int processes(Sheet sheet, Styles styles, int rowNum, String heading,
                          List<HostAuditData.ProcessInfo> processes) {
        if (processes == null || processes.isEmpty()) {
            return rowNum;
        }
        int row = section(sheet, styles, rowNum, heading);
        row = tableHeader(sheet, styles, row, List.of("Process", "PID", "Usage"));
        for (HostAuditData.ProcessInfo process : processes) {
            Row r = sheet.createRow(row++);
            cell(r, 0, process.getName(), styles.text);
            Cell pid = r.createCell(1);
            pid.setCellValue(process.getPid());
            pid.setCellStyle(styles.number);
            cell(r, 2, process.getValue(), styles.text);
        }
        return row + 1;
    }

    private int tableHeader(Sheet sheet, Styles styles, int rowNum, List<String> titles) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < titles.size(); i++) {
            cell(row, i, titles.get(i), styles.columnHeader);
        }
        return rowNum + 1;
    }

    // ----------------------------------------------------------------- Shared

    private void header(Sheet sheet, Styles styles, List<String> titles) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        for (int i = 0; i < titles.size(); i++) {
            cell(row, i, titles.get(i), styles.columnHeader);
        }
    }

    /** Freeze the header and add a filter so long reports stay usable. */
    private void finish(Sheet sheet, int columns, int rowCount) {
        sheet.createFreezePane(1, 1);
        if (rowCount > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowCount - 1, 0, columns - 1));
        }
    }

    private void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i]);
        }
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void numeric(Row row, int column, Number value, CellStyle numberStyle, CellStyle emptyStyle) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setCellValue("");
            cell.setCellStyle(emptyStyle);
        } else {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(numberStyle);
        }
    }

    private static String join(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (Integer value : values) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
