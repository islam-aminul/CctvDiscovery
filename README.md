# CCTV Discovery & Audit Tool

A comprehensive Java 8 application for discovering, auditing, and documenting IP-based CCTV cameras and NVR/DVR systems.

## Features

- **WS-Discovery (ONVIF)**: Automatic discovery of ONVIF-compliant devices via multicast probe
- **Port Scanning**: Deep network scanning with configurable IP ranges (CIDR, manual range, or interface)
- **Multi-Protocol Authentication**: Supports ONVIF (WS-Security, Digest) and RTSP authentication
- **Smart RTSP Discovery**: Intelligent path guessing with manufacturer-specific templates and caching
- **NVR/DVR Support**: Automatic channel iteration (1-64) for multi-camera systems
- **Stream Analysis**: JavaCV-powered video analysis (codec, resolution, FPS, bitrate)
- **Compliance Checking**: Automatic validation against technical requirements with visual highlighting
- **Excel Export**: Professional reports with Consolas font, compliance highlighting, metadata, and password protection
- **Professional UI**: Modern JavaFX interface with real-time progress tracking

## Architecture

- **Language**: Java 8
- **Build System**: Maven 3.6+
- **UI Framework**: JavaFX 11
- **Video Processing**: JavaCV 1.5.9 (FFmpeg 6.0, OpenCV 4.7)
- **ONVIF**: javax.xml.soap (Java 8 SOAP API - NO external ONVIF libraries)
- **Excel**: Apache POI 4.1.2
- **Logging**: Logback 1.2.12
- **Distribution**: Fat JAR with platform-specific natives

## Project Structure

```
CctvDiscovery/
├── src/
│   ├── main/
│   │   ├── java/com/cctv/discovery/
│   │   │   ├── Main.java                 # Entry point
│   │   │   ├── model/                    # POJOs (Device, RTSPStream, Credential)
│   │   │   ├── service/                  # Business logic (ONVIF, RTSP, MAC lookup)
│   │   │   ├── discovery/                # Network scanning and stream analysis
│   │   │   ├── ui/                       # JavaFX controllers and UI components
│   │   │   ├── export/                   # Excel export functionality
│   │   │   └── util/                     # Utilities (Network, Auth, etc.)
│   │   └── resources/
│   │       ├── css/app.css               # UI styling
│   │       ├── help/manual.html          # User documentation
│   │       ├── oui.csv                   # MAC address manufacturer lookup
│   │       ├── icon.{ico,png}            # Application icons
│   │       └── logback.xml               # Logging configuration
│   └── assembly/
│       └── dist.xml                      # Distribution packaging
├── pom.xml                               # Maven configuration
├── jre8/                                 # Amazon Corretto 8 JRE (bundled)
└── README.md
```

## Prerequisites

- **JDK 8** (for building)
- **Maven 3.6+**
- **Windows SDK** (for Windows builds with signtool.exe)

## Building

### On Linux/Mac (Cross-platform JAR only)

```bash
mvn clean package
```

This creates:
- `target/cctv-discovery-1.0.0-SNAPSHOT.jar` - Fat JAR with all dependencies
- `target/dist/` - Contains JRE and supporting files
- Signing is skipped (Windows-only)

### On Windows (Full Distribution with Signed EXE)

```bash
# Requires signtool.exe in PATH (Windows SDK)
mvn clean package -P windows-x64
```

This creates:
- Fat JAR
- `target/dist/CctvDiscovery.exe` - Signed Windows executable
- `target/dist/jre8/` - Bundled JRE
- `target/CctvDiscovery-1.0.0-SNAPSHOT.zip` - Complete distribution

### Platform-Specific Profiles

The build automatically detects your platform:

- **windows-x64**: Includes Windows natives, creates signed .exe
- **linux-x64**: Includes Linux natives
- **mac-arm64**: Includes macOS ARM64 natives

## Running

### From JAR (Any Platform)

```bash
java -jar target/cctv-discovery-1.0.0-SNAPSHOT.jar
```

### From EXE (Windows Only)

```
target\dist\CctvDiscovery.exe
```

The EXE automatically uses the bundled `jre8/` folder.

## Usage Guide

### 1. Network Selection

Choose one of three modes:

- **Network Interface**: Select from detected adapters (auto-detects /24 subnet)
- **Manual IP Range**: Specify start/end IPs (e.g., 192.168.1.1 - 192.168.1.254)
- **CIDR Notation**: Enter CIDR block (e.g., 192.168.1.0/24)

The tool displays the number of IPs to scan.

### 2. Add Credentials

- Add up to 4 username/password combinations
- Default username: `admin`
- All credentials are tested against each device
- Right-click to delete credentials

### 3. Start Discovery

Click "Start Discovery" to begin:

1. **WS-Discovery**: Finds ONVIF devices (5 seconds)
2. **Port Scan**: Optional deep scan (prompted if needed)
3. **Authentication**: Tests all credentials against each device
4. **Stream Discovery**: Finds RTSP URLs using smart guessing
5. **NVR Iteration**: Detects and iterates NVR channels (1-64)
6. **Stream Analysis**: Analyzes video for 10 seconds per stream

### 4. Export Results

- Click "Export to Excel" when complete
- Enter Site ID (required)
- **Set a protection password** (minimum 6 characters)
- Confirm password
- Choose save location
- Report includes all device/stream metadata with compliance highlighting
- **Worksheet is password-protected** to prevent tampering

## Technical Details

### Supported Manufacturers

- **Hikvision**: `/Streaming/Channels/101`, `/h264/ch1/main/av_stream`
- **Dahua**: `/cam/realmonitor?channel=1&subtype=0`, `/live/ch00_0`
- **Axis**: `/axis-media/media.amp`
- **CP Plus**: (India-specific paths)
- **Generic**: `/live`, `/ch0`, `/stream1`, etc.

### ONVIF Implementation

- **Discovery**: UDP multicast probe to 239.255.255.250:3702
- **Authentication**: WS-Security UsernameToken with password digest
- **APIs**: GetDeviceInformation, GetVideoSources
- **Parsing**: javax.xml.soap + org.w3c.dom (NO REGEX)

### RTSP Implementation

- **Transport**: TCP
- **Authentication**: HTTP Digest (RFC 2617)
- **Smart Cache**: Stores successful paths by MAC prefix

### Compliance Requirements

Sub-streams are flagged if they violate:
- **Resolution**: Not 360p-480p (640x360 to 720x480)
- **Codec**: Not H.264
- **Bitrate**: >= 256 kbps

Non-compliant cells are highlighted in **red** in Excel.

### Performance

- **Port Scan**: 8x CPU cores (max 64 threads)
- **Stream Analysis**: Max 8 concurrent streams
- **Timeout**: 2 seconds per port, 10 seconds per stream analysis
- **NVR Stop Condition**: 3 consecutive channel failures

## Security Considerations

⚠️ **IMPORTANT**:

- **Excel Protection**: All exported files are password-protected to prevent unauthorized modifications
- Excel reports contain **plaintext passwords** - handle securely and limit access
- **Password Management**: Use a strong password (minimum 6 characters) for Excel protection
- Network scanning may trigger IDS/IPS alerts
- **Only use on networks where you have explicit authorization**
- Logs may contain sensitive information (stored in `logs/`)

### Excel Protection Features

The worksheet protection prevents:
- Modifying cell values
- Deleting or inserting rows/columns
- Changing cell formatting
- Tampering with compliance indicators

Users can still:
- View all data (including passwords)
- Select and copy cells
- Print the report
- Save a copy of the file

To unprotect: Excel → Review → Unprotect Sheet → Enter password

## Troubleshooting

### "Runtime Components Missing" Error

This is a JavaFX initialization issue. The app uses a decoupled entry point:
- `Main.java` → `Launcher.main()` to properly initialize JavaFX runtime

### No Devices Found

- Verify network connectivity
- Check firewall (allow UDP 3702 for ONVIF)
- Enable deep port scan
- Try manual IP range instead of interface detection

### Authentication Failures

- Verify credentials are correct
- Try common defaults: admin/admin, admin/12345, admin/[blank]
- Some devices require ONVIF user creation first

### Stream Analysis Fails

- Ensure RTSP port 554 is accessible
- Check network bandwidth
- Verify streaming credentials (may differ from admin credentials)

## Dependencies

### Core Libraries

- JavaFX 11.0.2 (UI)
- JavaCV 1.5.9 (FFmpeg 6.0, OpenCV 4.7)
- Apache POI 4.1.2 (Excel)
- OSHI 6.4.5 (System info)
- Logback 1.2.12 (Logging)

### Authentication & Networking

- Apache HttpClient 4.5.14
- Commons Codec 1.15 (Digest auth)
- javax.xml.soap-api 1.4.0 (ONVIF)
- Gson 2.8.9 (JSON)

### Build Plugins

- maven-shade-plugin (Fat JAR)
- launch4j-maven-plugin (EXE wrapper)
- keytool-maven-plugin (Certificate generation)
- exec-maven-plugin (Signing with signtool)
- maven-assembly-plugin (ZIP distribution)

## Build Artifacts

After successful build on Windows:

```
target/
├── cctv-discovery-1.0.0-SNAPSHOT.jar    # Fat JAR (~200MB with natives)
├── dist/
│   ├── CctvDiscovery.exe                # Signed executable
│   └── jre8/                            # Amazon Corretto 8 JRE
├── CctvDiscovery-1.0.0-SNAPSHOT.zip     # Complete distribution
└── logs/                                # Application logs
```

## License

© 2026 Consultancy Services Ltd. All rights reserved.

## Changelog

### Version 1.0.0 (January 2026)
- Initial release
- WS-Discovery implementation (ONVIF)
- Port scanning with MAC resolution
- Smart RTSP path guessing
- NVR channel iteration
- JavaCV stream analysis
- Excel export with compliance highlighting
- Professional JavaFX UI

## Credits

- IEEE OUI Database for manufacturer lookup
- Optimized for Indian CCTV market (Hikvision, Dahua, CP Plus, etc.)
