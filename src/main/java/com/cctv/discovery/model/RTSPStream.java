package com.cctv.discovery.model;

import java.io.Serializable;

/**
 * POJO representing an RTSP stream (main or sub-stream) from a camera/NVR channel.
 */
public class RTSPStream implements Serializable {
    private static final long serialVersionUID = 1L;

    private String videoSourceName;
    private String channelName;
    private String streamName;
    private String rtspUrl;
    private String resolution;
    private String codec;
    private String profile;
    private Integer bitrateKbps;
    private Double fps;
    private boolean compliant;
    private String complianceIssues;
    private String sdpSessionName;

    public RTSPStream() {
    }

    public RTSPStream(String streamName, String rtspUrl) {
        this.streamName = streamName;
        this.rtspUrl = rtspUrl;
        this.compliant = true;
    }

    // Getters and Setters
    public String getVideoSourceName() {
        return videoSourceName;
    }

    public void setVideoSourceName(String videoSourceName) {
        this.videoSourceName = videoSourceName;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Integer getBitrateKbps() {
        return bitrateKbps;
    }

    public void setBitrateKbps(Integer bitrateKbps) {
        this.bitrateKbps = bitrateKbps;
    }

    public Double getFps() {
        return fps;
    }

    public void setFps(Double fps) {
        this.fps = fps;
    }

    public boolean isCompliant() {
        return compliant;
    }

    public void setCompliant(boolean compliant) {
        this.compliant = compliant;
    }

    public String getComplianceIssues() {
        return complianceIssues;
    }

    public void setComplianceIssues(String complianceIssues) {
        this.complianceIssues = complianceIssues;
    }

    public String getSdpSessionName() {
        return sdpSessionName;
    }

    public void setSdpSessionName(String sdpSessionName) {
        this.sdpSessionName = sdpSessionName;
    }

    @Override
    public String toString() {
        return new StringBuilder("RTSPStream{")
                .append("streamName='").append(streamName).append('\'')
                .append(", rtspUrl='").append(rtspUrl).append('\'')
                .append(", resolution='").append(resolution).append('\'')
                .append(", codec='").append(codec).append('\'')
                .append(", profile='").append(profile).append('\'')
                .append(", bitrateKbps=").append(bitrateKbps)
                .append(", fps=").append(fps)
                .append(", compliant=").append(compliant)
                .append('}')
                .toString();
    }
}
