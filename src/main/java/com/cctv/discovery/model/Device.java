package com.cctv.discovery.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * POJO representing a CCTV camera or NVR/DVR device.
 * Based on specification line 148-149.
 */
public class Device implements Serializable {
    private static final long serialVersionUID = 1L;

    // Basic identification
    private String ipAddress;
    private String macAddress;
    private String deviceName;
    private String deviceType;
    private String manufacturer;
    private String model;
    private String serialNumber;
    private Long timeDifferenceSeconds;

    // Authentication
    private String username;
    private String password;
    private String onvifServiceUrl;
    private OnvifAuthMethod onvifAuthMethod;
    private boolean authFailed;

    // Network ports
    private List<Integer> openOnvifPorts;
    private List<Integer> openRtspPorts;
    private List<Integer> openSpecialPorts;

    // Device type flags
    private boolean isNvrDvr;

    // Status and errors
    private String errorMessage;
    private DeviceStatus status;

    // Streams
    private List<RTSPStream> rtspStreams;

    public enum OnvifAuthMethod {
        DIGEST,
        WS_SECURITY,
        BASIC,
        NONE
    }

    public enum DeviceStatus {
        PENDING,
        SCANNING,
        AUTHENTICATING,
        ANALYZING,
        COMPLETED,
        AUTH_FAILED,
        ERROR
    }

    public Device() {
        this.openOnvifPorts = new ArrayList<>();
        this.openRtspPorts = new ArrayList<>();
        this.openSpecialPorts = new ArrayList<>();
        this.rtspStreams = new ArrayList<>();
        this.status = DeviceStatus.PENDING;
    }

    public Device(String ipAddress) {
        this();
        this.ipAddress = ipAddress;
    }

    // Getters and Setters
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

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
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

    public Long getTimeDifferenceSeconds() {
        return timeDifferenceSeconds;
    }

    public void setTimeDifferenceSeconds(Long timeDifferenceSeconds) {
        this.timeDifferenceSeconds = timeDifferenceSeconds;
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

    public List<Integer> getOpenOnvifPorts() {
        return openOnvifPorts;
    }

    public void setOpenOnvifPorts(List<Integer> openOnvifPorts) {
        this.openOnvifPorts = openOnvifPorts;
    }

    public List<Integer> getOpenRtspPorts() {
        return openRtspPorts;
    }

    public void setOpenRtspPorts(List<Integer> openRtspPorts) {
        this.openRtspPorts = openRtspPorts;
    }

    public List<Integer> getOpenSpecialPorts() {
        return openSpecialPorts;
    }

    public void setOpenSpecialPorts(List<Integer> openSpecialPorts) {
        this.openSpecialPorts = openSpecialPorts;
    }

    public boolean isNvrDvr() {
        return isNvrDvr;
    }

    public void setNvrDvr(boolean nvrDvr) {
        isNvrDvr = nvrDvr;
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

    public void setRtspStreams(List<RTSPStream> rtspStreams) {
        this.rtspStreams = rtspStreams;
    }

    public void addStream(RTSPStream stream) {
        this.rtspStreams.add(stream);
    }

    @Override
    public String toString() {
        return new StringBuilder("Device{")
                .append("ip='").append(ipAddress).append('\'')
                .append(", mac='").append(macAddress).append('\'')
                .append(", name='").append(deviceName).append('\'')
                .append(", manufacturer='").append(manufacturer).append('\'')
                .append(", status=").append(status)
                .append(", streams=").append(rtspStreams.size())
                .append('}')
                .toString();
    }
}
