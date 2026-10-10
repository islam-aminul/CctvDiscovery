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
*   **Excel, CSV or JSON**: devices, streams, findings and the host audit. The Excel workbook is locked against editing but can be copied from; it is not encrypted, and stream addresses include the credentials that worked.
*   **Saved scans**: a JSON report opens back into the window.

## Download

Get `CctvDiscovery-<version>-sfx.exe` from [Releases](https://github.com/islam-aminul/CctvDiscovery/releases)
and run it. It unpacks to `%LOCALAPPDATA%\Programs\CctvDiscovery\<version>` and starts; nothing else needs
installing. It is signed with a self-signed certificate, so Windows may warn about an unknown publisher.

## Technical Requirements
*   **Operating System**: Windows 10/11 (x64) recommended.
*   **Java Runtime**: None. A trimmed Java 25 runtime is bundled with the distribution.
*   **Dependencies**:
    *   **JavaFX**: For the graphical user interface.
    *   **JavaCV / FFmpeg**: For video stream analysis.
    *   **OSHI**: For low-level system hardware and OS information.

## Installation & Build

### Prerequisites
*   JDK 25
*   Apache Maven 3.9+

### Building from Source
Clone the repository and run the Maven package command:

```bash
mvn clean package
```

This will produce:
1.  A shaded "fat" JAR in the `target/` directory.
2.  A Windows executable (`.exe`) in `target/dist/`, next to the bundled runtime (via Launch4j and jlink).
3.  A distribution ZIP, `target/CctvDiscovery-<version>.zip`.
4.  A self-extracting archive, `target/CctvDiscovery-<version>-sfx.exe`.

#### The self-extracting archive

One file that carries the launcher, the fat JAR and the bundled runtime. Running
it unpacks everything to `%LOCALAPPDATA%\Programs\CctvDiscovery\<version>` and
starts the application; `CctvDiscovery-<version>-sfx.exe /T:<dir> /C` unpacks
without starting anything.

It is built with IExpress, which ships with Windows, so nothing has to be
downloaded or vendored. Two consequences worth knowing:

*   It is only built on Windows. Skip it with `-Dskip.sfx=true` when iterating,
    since compressing the runtime adds a minute or so to the build.
*   Build with a JDK of the architecture you are shipping. `jlink` copies the
    architecture of whichever JDK runs it, so an ARM64 JDK produces an ARM64
    runtime around an x64 JAR — an archive that installs cleanly and then fails
    to start. The build refuses to package that mismatch rather than ship it.
    `-Druntime.jdk=<path>` overrides just the bundled runtime if you need to
    cross-build.

    This is also why a Windows-on-ARM machine needs `JAVA_HOME` pointed at an
    x64 JDK for a normal build: OpenJFX publishes Maven profiles for Windows
    `amd64` and `x86` only, so on `aarch64` its own transitive coordinates never
    resolve and dependency resolution fails before anything is compiled.

### Signing

Both the launcher and the self-extracting archive are signed with the same
certificate, through `scripts/Invoke-CodeSign.ps1`. Signing is off unless a
keystore is supplied:

```bash
mvn clean package "-Dsign.keystore=C:\path\to\cert.pfx" -Dsign.storepass=...
```

`signtool` is located on `PATH` or under the installed Windows SDK, so no
Developer Command Prompt is needed. Signatures are timestamped, which keeps them
valid after the certificate expires.

## Usage Guide

1.  **Where to look**: choose **Choose network...** and pick an adapter, a range, a CIDR block or a list.
2.  **Sign-in details** (optional): **Add credentials...** for the usernames and passwords used on site.
    Each is tried in turn; devices that need none are found without any.
3.  **How thoroughly to check**: Quick Check, Stream Test, or Video Capture (the most reliable).
4.  **Run the scan**: **Start scan**. Devices appear as they are found; **Stop** keeps what was found.
    Select a device to see its streams and issues; right-click it to try other credentials or copy its
    stream addresses.
5.  **Report**: **Export report...**, enter the location code, then choose Excel, CSV or JSON.

The full manual is under **Help**, **Open User Manual**.

## Troubleshooting

*   **No Devices Found**:
    *   Ensure you selected the correct Network Interface affecting the subnet your cameras are on.
    *   Check if Windows Firewall is blocking `javaw.exe` or `CctvDiscovery.exe`.
*   **Stream Analysis Failed**:
    *   Verify credentials are correct.
    *   Ensure the camera allows RTSP connections (port 554).
*   **"Missing DLL" errors**:
    *   Ensure the Visual C++ Redistributable is installed (required for some native FFmpeg/OpenCV libraries).

## Licence

Copyright 2026 Aminul Islam. Licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE).

The Windows packages bundle a Java runtime, JavaFX, FFmpeg (LGPL 3.0) and other libraries under their own
licences, listed in `THIRD-PARTY-NOTICES.txt` beside the program, with the licence texts in its
`licenses` folder.
