# Backlog

What is still open after the Java 25 overhaul, and what was closed along the
way. This replaces the point-in-time `PROJECT_REVIEW.md` that lived on the
`claude/project-review-n2sMq` branch, checked against the current code on
2026-09-24.

## Open

### Protocol coverage

- **Recorder channel walking is untested against real hardware.** The code and
  its unit tests exist, but no NVR or DVR has been on the test network. Put one
  on the LAN and confirm the channel patterns, the stop-after-consecutive-misses
  rule and the per-channel roles.
- **ONVIF Media2 (`ver20/media`) is not used.** Only the Media1 service is
  called. Newer firmware may expose profiles only through Media2.
- **No IPv6.** `NetworkUtils` and `TargetParser` are IPv4 only. Cameras on
  IPv6-only segments cannot be scanned.
- **Vendor SDK ports are recorded but not spoken.** 37777 (Dahua) and 34567
  (Xiongmai) are noted as open and nothing more, so devices that expose streams
  only over those protocols are reported without streams.

### Product

- **No scan history.** Results live only in the running application. Saving a
  scan, reloading it and comparing two scans of the same site over time would
  make change detection possible.
- **Credentials are per-session.** They are entered again for every run. Stored
  profiles per site would need encryption at rest; decide where the key lives
  before building it.
- **Excel is the only export.** CSV for analysis and JSON for feeding another
  system have both been asked for.
- **No dark theme.** `app.css` is light only.
- **A scan cannot be resumed.** Closing the window mid-scan loses progress.
  Everything found so far is kept if Stop is used instead.

### Engineering

- **`MainController` is about 2,400 lines.** It builds the window, runs the
  scan and drives the export. Splitting the scan orchestration into its own
  class would make both testable without JavaFX.
- **Protocol behaviour still needs hardware to verify.** The measurement maths
  is now pinned by a fixture, but DESCRIBE, SETUP and interleaved RTP are still
  only exercised against the one camera on the bench. A recorded RTSP server
  would let those be asserted in CI too.

## Closed by the overhaul

Kept here so the same ground is not re-reviewed.

| Item | Where it stands |
|---|---|
| No unit tests | 66 tests |
| Excel password derived from a fixed code in the source | Password is asked for; the code is gone |
| Report claimed protection it did not have | Real OOXML agile encryption, proven by a decrypt test |
| JVM-wide TLS verification disabled | Scoped to the ONVIF client only |
| XML external entities accepted from the network | DOCTYPE and entities refused in `XmlUtils` |
| MAC invented from the ONVIF endpoint UUID | Never derived from the UUID; ARP, then ONVIF |
| 126-entry vendor list | 54,023 IEEE assignments, refreshed by `scripts/Update-OuiData.ps1` |
| Ports classified by number | Confirmed by protocol, so non-standard ports are found |
| Port 8000 open meant "recorder" | Channel count, ONVIF scope or model name |
| Media requests sent to the device service | Sent to the media service from `GetCapabilities` |
| Frame capture failed on cameras with audio | Asks for an image rather than the next frame |
| `stimeout` ignored by FFmpeg 6 | Uses `timeout`; a silent host is dropped in about 3s |
| Bitrate off by four orders of magnitude | Packet sizes over the presentation-timestamp span |
| Profile never reported | Read from the codec context and the SDP |
| Sub-stream rules keyed on the stream name | Keyed on measured resolution |
| Credentials required before scanning | Optional |
| Devices identified one at a time | Parallel, bounded by `threads.devices.parallel` |
| A modal interrupted a running scan | Removed |
| Settings and logs written beside the executable | Per-user directories |
| Passwords visible while typing | Masked in both credential dialogs |
| `/8` accepted and stalled; `/31` and `/32` scanned nothing | Merged intervals, lazy iteration, RFC 3021 |
| Interface mode assumed /24 | Uses the adapter's real prefix |
| Keystore password printed in the build log | Signing only with a supplied keystore |
| Java 8 with a build that failed on JDK 8 | Java 25 with a jlink runtime |
| Stream timing never tested | A committed clip pins bitrate, frame rate and GOP; the old bug now fails 4 tests |
| Path cache lost between runs | Remembered per vendor in the user data directory |
| Three build platforms | Five: adds macOS x64 and Linux ARM64 |
| Ingenic/Happytime OEM path not probed | `/live/channel0` added, verified on the bench camera |
