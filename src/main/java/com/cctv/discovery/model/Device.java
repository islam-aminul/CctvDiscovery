package com.cctv.discovery.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A discovered network device (camera, NVR/DVR or unknown host).
 * <p>
 * Collections are thread-safe because the discovery engine updates devices
 * from worker threads while the UI reads them.
 */
public class Device {

    public enum OnvifAuthMethod {DIGEST, WS_SECURITY, BASIC, NONE}

    public enum DeviceStatus {
        PENDING("Pending"),
        SCANNING("Scanning"),
        AUTHENTICATING("Identifying"),
        ANALYZING("Analyzing"),
        COMPLETED("Completed"),
        AUTH_FAILED("Failed"),
        ERROR("Error"),
        CANCELLED("Cancelled");

        private final String label;

        DeviceStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum DeviceType {
        CAMERA("IP Camera"), RECORDER("NVR/DVR"), UNKNOWN("Unknown");

        private final String label;

        DeviceType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private volatile String ipAddress;
    private volatile String macAddress;
    private volatile String deviceName;
    private volatile DeviceType deviceType = DeviceType.UNKNOWN;
    private volatile String manufacturer;
    private volatile String vendorFromMac;
    private volatile String model;
    private volatile String serialNumber;
    private volatile String firmwareVersion;
    private volatile String hardwareId;
    private volatile Long timeDifferenceSeconds;
    private volatile String discoverySource;

    private volatile String username;
    private volatile String password;
    private volatile String onvifServiceUrl;
    private volatile String onvifMediaUrl;
    private volatile OnvifAuthMethod onvifAuthMethod;
    private volatile boolean authFailed;
    private volatile Boolean rtspAnonymousAccess;
    private volatile int videoSourceCount;

    private final List<Integer> openOnvifPorts = new CopyOnWriteArrayList<>();
    private final List<Integer> openHttpPorts = new CopyOnWriteArrayList<>();
    private final List<Integer> openRtspPorts = new CopyOnWriteArrayList<>();
    private final List<Integer> openSpecialPorts = new CopyOnWriteArrayList<>();

    private volatile String errorMessage;
    private volatile DeviceStatus status = DeviceStatus.PENDING;

    private final List<RTSPStream> rtspStreams = new CopyOnWriteArrayList<>();
    private final List<Finding> findings = new CopyOnWriteArrayList<>();

    public Device() {
    }

    public Device(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public DeviceType getType() {
        return deviceType;
    }

    public void setType(DeviceType type) {
        this.deviceType = type == null ? DeviceType.UNKNOWN : type;
    }

    /** Display label of the device type. */
    public String getDeviceType() {
        return deviceType.label();
    }

    public boolean isNvrDvr() {
        return deviceType == DeviceType.RECORDER;
    }

    public void setNvrDvr(boolean nvrDvr) {
        if (nvrDvr) {
            deviceType = DeviceType.RECORDER;
        } else if (deviceType == DeviceType.RECORDER) {
            deviceType = DeviceType.CAMERA;
        }
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    /** Vendor registered for the MAC OUI (may differ from the ONVIF-reported brand for OEM devices). */
    public String getVendorFromMac() {
        return vendorFromMac;
    }

    public void setVendorFromMac(String vendorFromMac) {
        this.vendorFromMac = vendorFromMac;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
    }

    public Long getTimeDifferenceSeconds() {
        return timeDifferenceSeconds;
    }

    public void setTimeDifferenceSeconds(Long timeDifferenceSeconds) {
        this.timeDifferenceSeconds = timeDifferenceSeconds;
    }

    public String getDiscoverySource() {
        return discoverySource;
    }

    public void setDiscoverySource(String discoverySource) {
        this.discoverySource = discoverySource;
    }

    public void addDiscoverySource(String source) {
        if (discoverySource == null || discoverySource.isEmpty()) {
            discoverySource = source;
        } else if (!discoverySource.contains(source)) {
            discoverySource = discoverySource + " + " + source;
        }
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getOnvifServiceUrl() {
        return onvifServiceUrl;
    }

    public void setOnvifServiceUrl(String onvifServiceUrl) {
        this.onvifServiceUrl = onvifServiceUrl;
    }

    public String getOnvifMediaUrl() {
        return onvifMediaUrl;
    }

    public void setOnvifMediaUrl(String onvifMediaUrl) {
        this.onvifMediaUrl = onvifMediaUrl;
    }

    public OnvifAuthMethod getOnvifAuthMethod() {
        return onvifAuthMethod;
    }

    public void setOnvifAuthMethod(OnvifAuthMethod onvifAuthMethod) {
        this.onvifAuthMethod = onvifAuthMethod;
    }

    public boolean isAuthFailed() {
        return authFailed;
    }

    public void setAuthFailed(boolean authFailed) {
        this.authFailed = authFailed;
    }

    /** True when an RTSP stream answered DESCRIBE without credentials; null if not tested. */
    public Boolean getRtspAnonymousAccess() {
        return rtspAnonymousAccess;
    }

    public void setRtspAnonymousAccess(Boolean rtspAnonymousAccess) {
        this.rtspAnonymousAccess = rtspAnonymousAccess;
    }

    public int getVideoSourceCount() {
        return videoSourceCount;
    }

    public void setVideoSourceCount(int videoSourceCount) {
        this.videoSourceCount = videoSourceCount;
    }

    public List<Integer> getOpenOnvifPorts() {
        return openOnvifPorts;
    }

    public List<Integer> getOpenHttpPorts() {
        return openHttpPorts;
    }

    public List<Integer> getOpenRtspPorts() {
        return openRtspPorts;
    }

    public List<Integer> getOpenSpecialPorts() {
        return openSpecialPorts;
    }

    /** All open ports found by the scan, sorted and de-duplicated. */
    public List<Integer> getAllOpenPorts() {
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>();
        all.addAll(openOnvifPorts);
        all.addAll(openHttpPorts);
        all.addAll(openRtspPorts);
        all.addAll(openSpecialPorts);
        return List.copyOf(all);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public List<RTSPStream> getRtspStreams() {
        return rtspStreams;
    }

    public void addStream(RTSPStream stream) {
        boolean duplicate = rtspStreams.stream().anyMatch(s -> s.getRtspUrl().equals(stream.getRtspUrl()));
        if (!duplicate) {
            rtspStreams.add(stream);
        }
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void addFinding(Finding finding) {
        boolean duplicate = findings.stream().anyMatch(f -> f.title().equals(finding.title()));
        if (!duplicate) {
            findings.add(finding);
        }
    }

    @Override
    public String toString() {
        return "Device{ip='" + ipAddress + "', mac='" + macAddress + "', manufacturer='" + manufacturer
                + "', type=" + deviceType + ", status=" + status + ", streams=" + rtspStreams.size() + '}';
    }
}
