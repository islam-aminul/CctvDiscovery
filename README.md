# CCTV Discovery & Audit Tool

A comprehensive Java desktop application for discovering, validating, and auditing CCTV cameras and Network Video Recorders (NVRs) across enterprise networks. Built with JavaFX for cross-platform GUI support.

![Java](https://img.shields.io/badge/Java-8+-orange)
![Maven](https://img.shields.io/badge/Maven-Build-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

---

## 🎯 Purpose

This tool addresses common challenges in CCTV security audits:
- **Manual discovery is tedious** – Auditors often have incomplete or outdated camera inventories
- **Stream validation is time-consuming** – Verifying each camera stream works correctly takes significant effort
- **Compliance verification is error-prone** – Checking resolution, codec, and bitrate against standards is labor-intensive

CCTV Discovery automates the entire process: scan a network range, authenticate with devices, discover all video streams, analyze their properties, and generate a comprehensive Excel audit report.

---

## ✨ Key Features

### 🔍 Device Discovery
- **WS-Discovery (ONVIF)** – Multicast probe on 239.255.255.250:3702 to find ONVIF-compliant cameras
- **Port Scanning** – Multi-threaded scanning of common CCTV ports (80, 443, 554, 8000, 8080, 8554, 37777, 34567)
- **MAC Address Resolution** – ARP-based MAC lookup with IEEE OUI database for manufacturer identification
- **NVR/DVR Detection** – Automatic identification via special ports (8000 for Hikvision, 37777 for Dahua)

### 🔐 Authentication
- **Multiple Credential Support** – Try up to 4 username/password combinations per scan
- **ONVIF WS-Security** – UsernameToken with password digest (nonce + timestamp + SHA-1)
- **RTSP Digest & Basic Auth** – RFC-2617 compliant authentication for stream access
- **Fallback Mechanism** – Gracefully handles devices requiring different auth methods

### 📹 RTSP Stream Discovery
The tool uses a "waterfall" approach to find working RTSP URLs:

1. **Smart Cache** – Paths that worked for similar devices (by MAC prefix)
2. **Manufacturer-Specific Paths** – Templates for 12+ brands:
   - Hikvision: `/Streaming/Channels/101`, `/h264/ch1/main/av_stream`
   - Dahua: `/cam/realmonitor?channel=1&subtype=0`
   - Axis: `/axis-media/media.amp`
   - And more (CP Plus, Uniview, Hanwha, Amcrest, Foscam, Vivotek, Bosch, Sony, Panasonic)
3. **Custom User Paths** – Configurable via settings
4. **Generic Fallbacks** – Common paths like `/live`, `/stream1`, `/video`

### ✅ Stream Validation (3 Methods)

| Method | Speed | Accuracy | Description |
|--------|-------|----------|-------------|
| **SDP_ONLY** | Fast (~3s) | ~60% | Parses RTSP DESCRIBE response – may have false positives |
| **RTP_PACKET** | Medium (~5s) | ~90% | Verifies actual RTP packets are received |
| **FRAME_CAPTURE** | Slow (~10s) | ~98% | Decodes video frame using FFmpeg – confirms stream is viewable |

### 📊 Stream Analysis
Using JavaCV/FFmpeg, the tool extracts:
- **Resolution** – Width × Height pixels
- **Codec** – H.264, H.265/HEVC, MJPEG, etc.
- **Bitrate** – Estimated kbps
- **Frame Rate** – FPS
- **Compliance Check** – Validates against configurable standards (e.g., sub-stream should be 360p-480p, H.264, <256kbps)

### 📈 Multi-Channel NVR Support
- Probes up to **64 channels** per NVR by default
- Discovers both **main streams** (high-res recording) and **sub-streams** (low-res live view)
- Stops early after 3 consecutive failures to avoid wasting time

### 📋 Excel Export
Generates password-protected `.xlsx` reports with:
- **CCTV Device Sheet** – All discovered devices with columns:
  - Site ID, Premise Name, Operator
  - IP Address, MAC Address, Manufacturer, Model
  - RTSP URL, Resolution, Codec, Bitrate, FPS
  - Compliance status (non-compliant rows highlighted in red)
- **Host Audit Sheet** – System information from the scanning workstation:
  - OS version, hostname, logged-in user
  - Network interfaces with IPs/MACs
  - Disk usage, memory stats
- **Tamper Protection** – Locked cells prevent accidental modification

---

## 🛠️ Technical Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        JavaFX GUI (MainController)              │
│   [Network Settings] [Credentials] [Scan/Stop] [Export Button]  │
└─────────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  NetworkScanner │  │   OnvifService  │  │   RtspService   │
│  - WS-Discovery │  │  - SOAP/WS-Sec  │  │  - URL Guessing │
│  - Port Scan    │  │  - GetDeviceInfo│  │  - Auth Testing │
│  - MAC Lookup   │  │  - GetVideoSrc  │  │  - Validation   │
└─────────────────┘  └─────────────────┘  └─────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  StreamAnalyzer │
                    │  - JavaCV/FFmpeg│
                    │  - Codec Extract│
                    │  - Compliance   │
                    └─────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  ExcelExporter  │
                    │  - Apache POI   │
                    │  - Password Lock│
                    └─────────────────┘
```

### Package Structure
```
com.cctv.discovery/
├── Main.java                 # Entry point (delegates to JavaFX Launcher)
├── config/
│   └── AppConfig.java        # Loads application.properties
├── discovery/
│   ├── NetworkScanner.java   # WS-Discovery + port scanning + MAC resolution
│   └── StreamAnalyzer.java   # JavaCV-based stream analysis
├── export/
│   └── ExcelExporter.java    # Apache POI Excel generation
├── model/
│   ├── Device.java           # Camera/NVR data model
│   ├── RTSPStream.java       # Stream metadata model
│   ├── Credential.java       # Username/password pair
│   └── HostAuditData.java    # System information model
├── service/
│   ├── OnvifService.java     # ONVIF SOAP communication
│   ├── RtspService.java      # RTSP discovery and validation
│   ├── MacLookupService.java # IEEE OUI lookup
│   └── HostAuditService.java # System info collection (OSHI)
├── ui/
│   ├── Launcher.java         # JavaFX Application class
│   ├── MainController.java   # Main window controller
│   └── SettingsDialog.java   # Settings popup
└── util/
    ├── AuthUtils.java        # Digest auth, WS-Security token generation
    └── NetworkUtils.java     # Port checking, MAC resolution, IP range expansion
```

---

## 📋 Prerequisites

- **Java 8** (JRE or JDK) – Bundled JRE supported for portable distribution
- **Maven 3.6+** – For building from source
- **Network Access** – Must be on the same subnet as target CCTV devices

---

## 🚀 Quick Start

### Run from Source
```bash
git clone https://github.com/islam-aminul/CctvDiscovery.git
cd CctvDiscovery

# Compile and run
mvn clean compile exec:java -Dexec.mainClass="com.cctv.discovery.Main"
```

### Build Windows Executable
```bash
# Creates fat JAR + .exe wrapper
mvn clean package

# Outputs:
#   target/cctv-discovery-1.0.0-SNAPSHOT.jar
#   target/dist/CctvDiscovery.exe
```

### Create Distribution ZIP
```bash
mvn clean install

# Output: target/CctvDiscovery-1.0.0-SNAPSHOT.zip
# Contains: CctvDiscovery.exe + bundled JRE (if jre8/ folder exists)
```

---

## ⚙️ Configuration

### application.properties

| Category | Property | Default | Description |
|----------|----------|---------|-------------|
| **Discovery** | `discovery.onvif.timeout` | 5000ms | WS-Discovery multicast timeout |
| | `discovery.http.ports` | 80,8080,8000,8081 | HTTP ports for web interface detection |
| | `discovery.rtsp.ports` | 554,8554 | RTSP ports to scan |
| **Threading** | `threads.port.scan.max` | 64 | Max concurrent port scan threads |
| | `threads.stream.analysis.max` | 8 | Max concurrent stream analysis |
| **RTSP** | `rtsp.validation.method` | FRAME_CAPTURE | SDP_ONLY, RTP_PACKET, or FRAME_CAPTURE |
| | `rtsp.nvr.max.channels` | 64 | Max channels to probe per NVR |
| | `rtsp.nvr.consecutive.failures` | 3 | Stop after N failed channels |
| **Timeouts** | `timeout.socket.connect` | 2000ms | TCP connection timeout |
| | `timeout.rtsp.connect` | 5000ms | RTSP handshake timeout |
| | `timeout.stream.analysis` | 10000ms | Stream analysis timeout |
| **Export** | `export.excel.password.enabled` | true | Enable password protection |
| | `export.excel.password.fixed.code` | 482753 | Fixed password for reports |

### rtsp-templates.properties
Custom RTSP paths per manufacturer:
```properties
manufacturer.HIKVISION.paths=/Streaming/Channels/101,/h264/ch1/main/av_stream
manufacturer.DAHUA.paths=/cam/realmonitor?channel=1&subtype=0,/live/ch00_0
manufacturer.GENERIC.paths=/live,/stream1,/video
```

---

## 🔐 Security Considerations

- **In-Memory Only** – Credentials are never persisted to disk
- **No Logging of Secrets** – Passwords are excluded from log output
- **Protected Reports** – Excel files are password-locked against modification
- **Self-Signed Cert** – Code signing uses auto-generated keystore (for testing only)

---

## 🏗️ Technology Stack

| Component | Library | Purpose |
|-----------|---------|---------|
| GUI | JavaFX 11 | Cross-platform desktop UI |
| Video Processing | JavaCV 1.5.9 + FFmpeg 6.0 | Stream capture, codec detection |
| Computer Vision | OpenCV 4.7.0 | Frame analysis (via JavaCV) |
| Excel Generation | Apache POI 5.4.0 | XLSX creation with password protection |
| HTTP/SOAP | Apache HttpClient 4.5.14 | ONVIF communication |
| System Info | OSHI 6.4.5 | Host audit data collection |
| Logging | SLF4J + slf4j-simple | Lightweight logging |
| EXE Wrapper | Launch4j 2.4.1 | Windows executable generation |

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit changes (`git commit -m 'Add feature'`)
4. Push to branch (`git push origin feature/your-feature`)
5. Open a Pull Request

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE).

---

## 👤 Author

**Consultancy Services Ltd.** – Building tools for security professionals.

---

*Automate your CCTV audits. Discover everything. Miss nothing.*
