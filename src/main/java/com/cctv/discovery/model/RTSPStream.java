package com.cctv.discovery.model;

/**
 * An RTSP stream (main or sub) of a camera or recorder channel, with the
 * values measured during analysis.
 */
public class RTSPStream {

    public enum Role {
        MAIN("Main"), SUB("Sub"), OTHER("Other");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private volatile String videoSourceName;
    private volatile String channelName;
    private volatile String streamName;
    private volatile String rtspUrl;
    private volatile String source;
    private volatile Role role = Role.OTHER;

    private volatile String resolution;
    private volatile Integer width;
    private volatile Integer height;
    private volatile String codec;
    private volatile String profile;
    private volatile String audioCodec;
    private volatile Integer bitrateKbps;
    private volatile Double fps;
    private volatile Double keyframeIntervalSeconds;

    private volatile boolean analyzed;
    private volatile String analysisError;
    private volatile boolean compliant = true;
    private volatile String complianceIssues;
    private volatile String sdpSessionName;

    public RTSPStream() {
    }

    public RTSPStream(String streamName, String rtspUrl) {
        this.streamName = streamName;
        this.rtspUrl = rtspUrl;
    }

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

    /** Where the URL came from: "ONVIF", "Path probe", "NVR channel". */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role == null ? Role.OTHER : role;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
        this.resolution = width + "x" + height;
    }

    /** Pixel count, or 0 when unknown. */
    public long pixels() {
        return width == null || height == null ? 0 : (long) width * height;
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

    public String getAudioCodec() {
        return audioCodec;
    }

    public void setAudioCodec(String audioCodec) {
        this.audioCodec = audioCodec;
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

    public Double getKeyframeIntervalSeconds() {
        return keyframeIntervalSeconds;
    }

    public void setKeyframeIntervalSeconds(Double keyframeIntervalSeconds) {
        this.keyframeIntervalSeconds = keyframeIntervalSeconds;
    }

    public boolean isAnalyzed() {
        return analyzed;
    }

    public void setAnalyzed(boolean analyzed) {
        this.analyzed = analyzed;
    }

    public String getAnalysisError() {
        return analysisError;
    }

    public void setAnalysisError(String analysisError) {
        this.analysisError = analysisError;
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
        return "RTSPStream{name='" + streamName + "', role=" + role + ", url='" + rtspUrl + "', res=" + resolution
                + ", codec=" + codec + ", profile=" + profile + ", kbps=" + bitrateKbps + ", fps=" + fps + '}';
    }
}
