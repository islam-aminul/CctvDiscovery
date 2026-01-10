package com.cctv.discovery.service;

import com.cctv.discovery.model.HostAuditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.*;
import oshi.software.os.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to collect host audit information using OSHI library.
 */
public class HostAuditService {
    private static final Logger logger = LoggerFactory.getLogger(HostAuditService.class);

    private static final String[] NTP_SERVERS = {
        "time.google.com",
        "time.windows.com",
        "pool.ntp.org"
    };

    /**
     * Collect comprehensive host audit data.
     */
    public HostAuditData collectHostAudit() {
        logger.info("Starting host audit data collection...");
        HostAuditData data = new HostAuditData();
        data.setCollectionTimestamp(System.currentTimeMillis());

        try {
            SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hardware = systemInfo.getHardware();
            OperatingSystem os = systemInfo.getOperatingSystem();

            // System Information
            collectSystemInfo(data, os);

            // Hardware Information
            collectHardwareInfo(data, hardware);

            // Memory Information
            collectMemoryInfo(data, hardware);

            // BIOS and Motherboard
            collectBiosAndMotherboard(data, hardware);

            // Disk Information
            collectDiskInfo(data, hardware);

            // Network Adapters
            collectNetworkAdapters(data, hardware);

            // System Uptime
            collectSystemUptime(data, os);

            // Time Information
            collectTimeInfo(data);

            // NTP Time Drift
            collectNTPTimeDrift(data);

            // Top Processes
            collectTopProcesses(data, os);

            logger.info("Host audit data collection completed successfully");
        } catch (Exception e) {
            logger.error("Error collecting host audit data", e);
        }

        return data;
    }

    private void collectSystemInfo(HostAuditData data, OperatingSystem os) {
        try {
            // Computer Name
            InetAddress localhost = InetAddress.getLocalHost();
            data.setComputerName(localhost.getHostName());

            // Username
            data.setUsername(System.getProperty("user.name"));

            // Domain (Windows specific)
            String domain = System.getenv("USERDOMAIN");
            data.setDomain(domain != null ? domain : "N/A");

            // Operating System
            data.setOperatingSystem(os.getFamily());

            // OS Version
            data.setOsVersion(os.getVersionInfo().getVersion());

            // OS Architecture
            String arch = System.getProperty("os.arch");
            data.setOsArchitecture(arch != null ? arch : "N/A");

            // OS Build
            data.setOsBuild(os.getVersionInfo().getBuildNumber());

        } catch (Exception e) {
            logger.error("Error collecting system information", e);
        }
    }

    private void collectHardwareInfo(HostAuditData data, HardwareAbstractionLayer hardware) {
        try {
            ComputerSystem computerSystem = hardware.getComputerSystem();

            // Make (Manufacturer)
            data.setMake(computerSystem.getManufacturer());

            // Model
            data.setModel(computerSystem.getModel());

            // CPU Information
            CentralProcessor processor = hardware.getProcessor();
            data.setCpuName(processor.getProcessorIdentifier().getName());
            data.setCpuCores(processor.getPhysicalProcessorCount());
            data.setCpuThreads(processor.getLogicalProcessorCount());

            // CPU Speed (in GHz)
            long maxFreq = processor.getMaxFreq();
            if (maxFreq > 0) {
                data.setCpuSpeed(String.format("%.2f GHz", maxFreq / 1_000_000_000.0));
            } else {
                data.setCpuSpeed("N/A");
            }

        } catch (Exception e) {
            logger.error("Error collecting hardware information", e);
        }
    }

    private void collectMemoryInfo(HostAuditData data, HardwareAbstractionLayer hardware) {
        try {
            GlobalMemory memory = hardware.getMemory();

            long total = memory.getTotal();
            long available = memory.getAvailable();
            long used = total - available;

            data.setTotalMemory(formatBytes(total));
            data.setAvailableMemory(formatBytes(available));

            double usagePercent = (used * 100.0) / total;
            data.setMemoryUsage(String.format("%.2f%% (%s / %s)", usagePercent, formatBytes(used), formatBytes(total)));

        } catch (Exception e) {
            logger.error("Error collecting memory information", e);
        }
    }

    private void collectBiosAndMotherboard(HostAuditData data, HardwareAbstractionLayer hardware) {
        try {
            ComputerSystem computerSystem = hardware.getComputerSystem();
            Firmware firmware = computerSystem.getFirmware();
            Baseboard baseboard = computerSystem.getBaseboard();

            // BIOS Information
            String biosInfo = String.format("%s %s (Released: %s)",
                firmware.getManufacturer(),
                firmware.getVersion(),
                firmware.getReleaseDate() != null ? firmware.getReleaseDate() : "Unknown");
            data.setBiosInformation(biosInfo);

            // Motherboard
            String motherboardInfo = String.format("%s %s (SN: %s)",
                baseboard.getManufacturer(),
                baseboard.getModel(),
                baseboard.getSerialNumber());
            data.setMotherboard(motherboardInfo);

        } catch (Exception e) {
            logger.error("Error collecting BIOS and motherboard information", e);
        }
    }

    private void collectDiskInfo(HostAuditData data, HardwareAbstractionLayer hardware) {
        try {
            List<HWDiskStore> diskStores = hardware.getDiskStores();
            List<HostAuditData.DiskInfo> disks = new ArrayList<>();

            for (HWDiskStore disk : diskStores) {
                String name = disk.getName();
                String model = disk.getModel();
                String size = formatBytes(disk.getSize());

                // Determine type (SSD/HDD) - not always available
                String type = "Unknown";

                disks.add(new HostAuditData.DiskInfo(name, model, size, type));
            }

            data.setDisks(disks);

        } catch (Exception e) {
            logger.error("Error collecting disk information", e);
        }
    }

    private void collectNetworkAdapters(HostAuditData data, HardwareAbstractionLayer hardware) {
        try {
            List<NetworkIF> networkIFs = hardware.getNetworkIFs();
            List<HostAuditData.NetworkAdapterInfo> adapters = new ArrayList<>();

            for (NetworkIF net : networkIFs) {
                String name = net.getDisplayName();
                String macAddress = net.getMacaddr();

                // Get IP addresses
                String[] ipAddresses = net.getIPv4addr();
                String ipAddress = (ipAddresses != null && ipAddresses.length > 0) ?
                    String.join(", ", ipAddresses) : "N/A";

                // Status
                String status = net.isKnownVmMacAddr() ? "Virtual" : "Physical";

                // Speed
                long speed = net.getSpeed();
                String speedStr = speed > 0 ? formatBitsPerSecond(speed) : "N/A";

                adapters.add(new HostAuditData.NetworkAdapterInfo(name, macAddress, ipAddress, status, speedStr));
            }

            data.setNetworkAdapters(adapters);

        } catch (Exception e) {
            logger.error("Error collecting network adapter information", e);
        }
    }

    private void collectSystemUptime(HostAuditData data, OperatingSystem os) {
        try {
            long uptimeSeconds = os.getSystemUptime();
            data.setSystemUptime(formatUptime(uptimeSeconds));

        } catch (Exception e) {
            logger.error("Error collecting system uptime", e);
        }
    }

    private void collectTimeInfo(HostAuditData data) {
        try {
            // Current Time
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            data.setCurrentTime(sdf.format(new Date()));

            // Time Zone
            TimeZone tz = TimeZone.getDefault();
            data.setTimeZone(tz.getDisplayName() + " (" + tz.getID() + ")");

        } catch (Exception e) {
            logger.error("Error collecting time information", e);
        }
    }

    private void collectNTPTimeDrift(HostAuditData data) {
        try {
            // Try to get NTP time from multiple servers
            Double drift = null;
            String syncedServer = null;

            for (String ntpServer : NTP_SERVERS) {
                try {
                    drift = getNTPTimeDrift(ntpServer);
                    if (drift != null) {
                        syncedServer = ntpServer;
                        break;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to connect to NTP server: {}", ntpServer);
                }
            }

            if (drift != null) {
                data.setNtpTimeDrift(drift);
                data.setTimeServerSync(syncedServer);

                // Alert if drift > 1 second
                if (Math.abs(drift) > 1.0) {
                    data.setNtpTimeDriftAlert(true);
                    logger.warn("NTP time drift alert: {} seconds (threshold: 1s)", drift);
                } else {
                    data.setNtpTimeDriftAlert(false);
                }
            } else {
                data.setTimeServerSync("Unable to sync");
                data.setNtpTimeDrift(null);
                data.setNtpTimeDriftAlert(false);
            }

        } catch (Exception e) {
            logger.error("Error collecting NTP time drift", e);
        }
    }

    /**
     * Get NTP time drift in seconds using SNTP protocol (RFC 4330).
     */
    private Double getNTPTimeDrift(String ntpServer) throws Exception {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(5000);

            InetAddress address = InetAddress.getByName(ntpServer);
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // NTP mode 3 (client), version 3

            DatagramPacket packet = new DatagramPacket(buf, buf.length, address, 123);

            long t1 = System.currentTimeMillis();
            socket.send(packet);
            socket.receive(packet);
            long t4 = System.currentTimeMillis();

            // Extract transmit timestamp (offset 40-47)
            long seconds = 0;
            for (int i = 40; i <= 43; i++) {
                seconds = (seconds << 8) | (buf[i] & 0xff);
            }

            // NTP epoch is Jan 1, 1900; Java epoch is Jan 1, 1970
            long ntpEpochOffset = 2208988800L;
            long ntpTime = (seconds - ntpEpochOffset) * 1000;

            // Calculate drift (simplified, ignoring network delay)
            double drift = (ntpTime - t4) / 1000.0;

            return drift;

        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void collectTopProcesses(HostAuditData data, OperatingSystem os) {
        try {
            List<OSProcess> processes = os.getProcesses();

            // Top 5 by CPU
            List<HostAuditData.ProcessInfo> topCpu = processes.stream()
                .sorted((p1, p2) -> Double.compare(
                    p2.getProcessCpuLoadCumulative(),
                    p1.getProcessCpuLoadCumulative()))
                .limit(5)
                .map(p -> new HostAuditData.ProcessInfo(
                    p.getName(),
                    p.getProcessID(),
                    String.format("%.2f%%", p.getProcessCpuLoadCumulative() * 100)))
                .collect(Collectors.toList());
            data.setTopCpuProcesses(topCpu);

            // Top 5 by Memory
            List<HostAuditData.ProcessInfo> topMemory = processes.stream()
                .sorted((p1, p2) -> Long.compare(p2.getResidentSetSize(), p1.getResidentSetSize()))
                .limit(5)
                .map(p -> new HostAuditData.ProcessInfo(
                    p.getName(),
                    p.getProcessID(),
                    formatBytes(p.getResidentSetSize())))
                .collect(Collectors.toList());
            data.setTopMemoryProcesses(topMemory);

            // Top 5 by Disk IO (read + write bytes)
            List<HostAuditData.ProcessInfo> topDiskIO = processes.stream()
                .sorted((p1, p2) -> Long.compare(
                    p2.getBytesRead() + p2.getBytesWritten(),
                    p1.getBytesRead() + p1.getBytesWritten()))
                .limit(5)
                .map(p -> new HostAuditData.ProcessInfo(
                    p.getName(),
                    p.getProcessID(),
                    formatBytes(p.getBytesRead() + p.getBytesWritten())))
                .collect(Collectors.toList());
            data.setTopDiskIOProcesses(topDiskIO);

        } catch (Exception e) {
            logger.error("Error collecting top processes", e);
        }
    }

    // Utility methods

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatBitsPerSecond(long bps) {
        if (bps < 1000) return bps + " bps";
        int exp = (int) (Math.log(bps) / Math.log(1000));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sbps", bps / Math.pow(1000, exp), pre);
    }

    private String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format("%d days, %d hours, %d minutes, %d seconds", days, hours, minutes, secs);
    }
}
