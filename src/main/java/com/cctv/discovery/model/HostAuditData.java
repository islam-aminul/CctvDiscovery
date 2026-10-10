package com.cctv.discovery.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Model class to hold host audit information collected via OSHI.
 */
public class HostAuditData {
    // System Information
    private String computerName;
    private String domain;
    private String username;
    private String operatingSystem;
    private String osVersion;
    private String osArchitecture;
    private String osBuild;

    // Hardware Information
    private String make;
    private String model;
    private String cpuName;
    private int cpuCores;
    private int cpuThreads;
    private String cpuSpeed;
    private String totalMemory;
    private String availableMemory;
    private String memoryUsage;

    // BIOS and Motherboard
    private String biosInformation;
    private String motherboard;

    // Disk Information
    private List<DiskInfo> disks = new ArrayList<>();

    // Network Information
    private List<NetworkAdapterInfo> networkAdapters = new ArrayList<>();

    // System Status
    private String systemUptime;
    private String currentTime;
    private String timeZone;
    private String timeServerSync;
    private Double ntpTimeDrift; // in seconds
    private boolean ntpTimeDriftAlert; // true if drift > 1 second

    // Process Information
    private List<ProcessInfo> topCpuProcesses = new ArrayList<>();
    private List<ProcessInfo> topMemoryProcesses = new ArrayList<>();
    private List<ProcessInfo> topDiskIOProcesses = new ArrayList<>();

    // Collection timestamp
    private long collectionTimestamp;

    // Getters and Setters
    public String getComputerName() {
        return computerName;
    }

    public void setComputerName(String computerName) {
        this.computerName = computerName;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getOsArchitecture() {
        return osArchitecture;
    }

    public void setOsArchitecture(String osArchitecture) {
        this.osArchitecture = osArchitecture;
    }

    public String getOsBuild() {
        return osBuild;
    }

    public void setOsBuild(String osBuild) {
        this.osBuild = osBuild;
    }

    public String getMake() {
        return make;
    }

    public void setMake(String make) {
        this.make = make;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCpuName() {
        return cpuName;
    }

    public void setCpuName(String cpuName) {
        this.cpuName = cpuName;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public void setCpuCores(int cpuCores) {
        this.cpuCores = cpuCores;
    }

    public int getCpuThreads() {
        return cpuThreads;
    }

    public void setCpuThreads(int cpuThreads) {
        this.cpuThreads = cpuThreads;
    }

    public String getCpuSpeed() {
        return cpuSpeed;
    }

    public void setCpuSpeed(String cpuSpeed) {
        this.cpuSpeed = cpuSpeed;
    }

    public String getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(String totalMemory) {
        this.totalMemory = totalMemory;
    }

    public String getAvailableMemory() {
        return availableMemory;
    }

    public void setAvailableMemory(String availableMemory) {
        this.availableMemory = availableMemory;
    }

    public String getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(String memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public String getBiosInformation() {
        return biosInformation;
    }

    public void setBiosInformation(String biosInformation) {
        this.biosInformation = biosInformation;
    }

    public String getMotherboard() {
        return motherboard;
    }

    public void setMotherboard(String motherboard) {
        this.motherboard = motherboard;
    }

    public List<DiskInfo> getDisks() {
        return disks;
    }

    public void setDisks(List<DiskInfo> disks) {
        this.disks = disks;
    }

    public List<NetworkAdapterInfo> getNetworkAdapters() {
        return networkAdapters;
    }

    public void setNetworkAdapters(List<NetworkAdapterInfo> networkAdapters) {
        this.networkAdapters = networkAdapters;
    }

    public String getSystemUptime() {
        return systemUptime;
    }

    public void setSystemUptime(String systemUptime) {
        this.systemUptime = systemUptime;
    }

    public String getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getTimeServerSync() {
        return timeServerSync;
    }

    public void setTimeServerSync(String timeServerSync) {
        this.timeServerSync = timeServerSync;
    }

    public Double getNtpTimeDrift() {
        return ntpTimeDrift;
    }

    public void setNtpTimeDrift(Double ntpTimeDrift) {
        this.ntpTimeDrift = ntpTimeDrift;
    }

    public boolean isNtpTimeDriftAlert() {
        return ntpTimeDriftAlert;
    }

    public void setNtpTimeDriftAlert(boolean ntpTimeDriftAlert) {
        this.ntpTimeDriftAlert = ntpTimeDriftAlert;
    }

    public List<ProcessInfo> getTopCpuProcesses() {
        return topCpuProcesses;
    }

    public void setTopCpuProcesses(List<ProcessInfo> topCpuProcesses) {
        this.topCpuProcesses = topCpuProcesses;
    }

    public List<ProcessInfo> getTopMemoryProcesses() {
        return topMemoryProcesses;
    }

    public void setTopMemoryProcesses(List<ProcessInfo> topMemoryProcesses) {
        this.topMemoryProcesses = topMemoryProcesses;
    }

    public List<ProcessInfo> getTopDiskIOProcesses() {
        return topDiskIOProcesses;
    }

    public void setTopDiskIOProcesses(List<ProcessInfo> topDiskIOProcesses) {
        this.topDiskIOProcesses = topDiskIOProcesses;
    }

    public long getCollectionTimestamp() {
        return collectionTimestamp;
    }

    public void setCollectionTimestamp(long collectionTimestamp) {
        this.collectionTimestamp = collectionTimestamp;
    }

    // Nested classes for complex data
    public static class DiskInfo {
        private String name;
        private String model;
        private String size;
        private String type;

        public DiskInfo(String name, String model, String size, String type) {
            this.name = name;
            this.model = model;
            this.size = size;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public String getModel() {
            return model;
        }

        public String getSize() {
            return size;
        }

        public String getType() {
            return type;
        }
    }

    public static class NetworkAdapterInfo {
        private String name;
        private String macAddress;
        private String ipAddress;
        private String status;
        private String speed;

        public NetworkAdapterInfo(String name, String macAddress, String ipAddress, String status, String speed) {
            this.name = name;
            this.macAddress = macAddress;
            this.ipAddress = ipAddress;
            this.status = status;
            this.speed = speed;
        }

        public String getName() {
            return name;
        }

        public String getMacAddress() {
            return macAddress;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public String getStatus() {
            return status;
        }

        public String getSpeed() {
            return speed;
        }
    }

    public static class ProcessInfo {
        private String name;
        private int pid;
        private String value; // CPU%, Memory, or Disk IO

        public ProcessInfo(String name, int pid, String value) {
            this.name = name;
            this.pid = pid;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getPid() {
            return pid;
        }

        public String getValue() {
            return value;
        }
    }
}
