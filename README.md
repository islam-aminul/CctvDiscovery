# CCTV Discovery & Audit Tool

**CCTV Discovery & Audit Tool** is a robust desktop application designed for security professionals and network administrators. It simplifies the process of discovering CCTV devices (ONVIF/RTSP), analyzing video stream quality and compliance, and auditing the host system's readiness.

## Key Features

### 🔍 Network Discovery
*   **ONVIF WS-Discovery**: Automatically finds ONVIF-compliant devices on the network.
*   **Port Scanning**: Scans for common CCTV ports (80, 554, 8000, 37777, etc.) to identify devices that may not broadcast via ONVIF.
*   **Flexible Scanning Modes**:
    *   **Simple**: Select a local network interface or define a start/end IP range.
    *   **Advanced**: Scan multiple interfaces simultaneously or define custom CIDR blocks (e.g., `192.168.1.0/24`).
*   **Device Identification**: Resolves MAC addresses to Manufacturer names (e.g., Hikvision, Dahua, Axis) for easier identification.

### 🎥 Stream Analysis
*   **Detailed Metrics**: Connects to RTSP streams to extract:
    *   **Codec**: H.264, H.265 (HEVC), MJPEG, MPEG-4.
    *   **Resolution**: e.g., 1920x1080, 704x576.
    *   **Frame Rate (FPS)**: Actual detected frame rate.
    *   **Bitrate**: Estimated bandwidth usage (kbps).
    *   **H.264/H.265 Profile**: Detects profiles like Baseline, Main, High, High 10.
*   **Compliance Validation**:
    *   Flags **High Profile** streams (often problematic for web-based HLS playback without transcoding).
    *   Validates sub-streams against specific criteria (Target: 360p-480p, H.264, <512kbps).

### 🖥️ Host Audit
*   **System Profiling**: Automatically collects hardware and OS details of the machine running the tool.
    *   OS Version & Service Packs.
    *   CPU Model, Cores, and Speed.
    *   Memory (RAM) Usage and Capacity.
    *   Disk Space and Type.
*   **Network Diagnostics**: Lists all network adapters, their speeds, and connection status.
*   **Time Synchronization**: Checks NTP time drift against global time servers (Google, Microsoft, pool.ntp.org) to ensure accurate log timestamps.
*   **Resource Monitoring**: Identifies top resource-consuming processes (CPU, Memory, Disk I/O) that might impact scanning performance.

### 📊 Reporting
*   **Excel Export**: comprehensive export of all discovered devices, stream details, and host audit data into a structured `.xlsx` file for documentation and reporting.

## Technical Requirements
*   **Operating System**: Windows 10/11 (x64) recommended.
*   **Java Runtime**: Requires Java 8 (JRE 8 is often bundled with the distribution).
*   **Dependencies**:
    *   **JavaFX**: For the modern graphical user interface.
    *   **JavaCV / FFmpeg / OpenCV**: For advanced video stream analysis.
    *   **OSHI**: For low-level system hardware and OS information.

## Installation & Build

### Prerequisites
*   JDK 1.8
*   Apache Maven 3.6+

### Building from Source
Clone the repository and run the Maven package command:

```bash
mvn clean package
```

This will produce:
1.  A shaded "fat" JAR in the `target/` directory.
2.  A Windows executable (`.exe`) in `target/dist/` (via Launch4j).

## Usage Guide

1.  **Launch the Application**: Run the `CctvDiscovery.exe` or the JAR file.
2.  **Network Configuration**:
    *   On the left panel, click **Select Network**.
    *   Choose an interface for auto-detection or enter an IP Range manually.
3.  **Credentials (Optional)**:
    *   If your cameras are password-protected, click **Set Credentials**.
    *   Add common Username/Password combinations (e.g., `admin/12345`). The tool will cycle through these when attempting to connect to found RTSP streams.
4.  **Before You Scan**:
    *   Click **Set Verification Method** to choose how video streams are validated (e.g., "Frame Capture" for full analysis).
5.  **Start Discovery**:
    *   Click the **Start Discovery** button.
    *   The progress bar will indicate scanning status. Found devices will appear in the main table.
6.  **Review Results**:
    *   Click on any device in the table to view its details.
    *   Expand the arrow to see individual RTSP streams and their analysis results.
7.  **Export Data**:
    *   Click the **Export to Excel** button in the sidebar to save the current session's findings.

## Troubleshooting

*   **No Devices Found**:
    *   Ensure you selected the correct Network Interface affecting the subnet your cameras are on.
    *   Check if Windows Firewall is blocking `javaw.exe` or `CctvDiscovery.exe`.
*   **Stream Analysis Failed**:
    *   Verify credentials are correct.
    *   Ensure the camera allows RTSP connections (port 554).
*   **"Missing DLL" errors**:
    *   Ensure the Visual C++ Redistributable is installed (required for some native FFmpeg/OpenCV libraries).
