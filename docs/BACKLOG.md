# Backlog

What is still open after the Java 25 overhaul, and what was closed along the
way. This replaces the point-in-time `PROJECT_REVIEW.md` that lived on the
`claude/project-review-n2sMq` branch, checked against the current code on
2026-09-24.

## Open

### Protocol coverage

- **Recorder support verified on one site only.** On 2026-10-09 the full scan
  of a Hikvision NVR and six cameras (192.168.1.200 to .206) completed in
  214 s: the NVR was recognised with ONVIF off, all six channels were walked,
  its undecodable main streams were reported rather than dropped, and the
  cameras were not mistaken for recorders. Dahua, Uniview and the other
  recorder patterns have still only run against the fake server.
- **The first port-scan pass can miss everything after the computer has been
  idle.** On 2026-10-10, after an idle night on Wi-Fi, all seven devices on the
  test site answered only on the second pass; the first found nothing. Most
  likely the first connections went out while Windows was still resolving each
  host's hardware address. The second pass covers ranges up to 4096 addresses
  but is skipped above that, where a scan in this state could find nothing.
  Worth either warming the address cache before scanning or keeping some form
  of second pass for large ranges.
- **That recorder's relayed video does not decode, in this tool or ffprobe.**
  Every channel describes itself as H.264 (fmtp says Main level 2.0, the SPS
  says level 4.2) and decodes at 44x32 with errors, while the cameras' own
  streams decode cleanly. Probably a device setting (H.265 or a "smart" codec
  relayed under an H.264 label); worth raising with the site, since most
  viewing software will not play it.
- **ONVIF attempts still count towards an account lockout.** RTSP path probing
  now stops at the first refusal, one failed login per password per device.
  ONVIF is still tried once per password per service address before RTSP, so
  with ONVIF enabled a wrong password costs up to two or three failures per
  device. Hikvision keeps a separate ONVIF user list, so it may not share the
  lockout counter; unverified.
- **No IPv6.** `NetworkUtils` and `TargetParser` are IPv4 only. Cameras on
  IPv6-only segments cannot be scanned.
- **Vendor SDK protocols are still not spoken.** 37777 (Dahua) and 34567
  (Xiongmai) now identify the family and raise a finding, but the protocols
  themselves are proprietary and are not implemented, so a device that offers
  video only over its maker's protocol is still reported without streams.
  Implementing either means reverse-engineering a binary protocol; weigh that
  against telling the operator to turn ONVIF on.

### Product

- **Credentials are per-session.** They are entered again for every run. Stored
  profiles per site would need encryption at rest; decide where the key lives
  before building it.
- **A scan cannot be resumed.** Closing the window mid-scan loses progress.
  Everything found so far is kept if Stop is used instead.

### Engineering

- **`MainController` is still the largest class, at about 1,350 lines.** The
  scan, network selection, export and help were split out on 2026-09-24
  (`ScanCoordinator`, `NetworkSelection`, `ReportExchange`, `HelpDialog`); what
  remains is window layout, the results tables and the credential dialog.
  Splitting further is optional.
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
| ONVIF Media2 never called | Found via GetServices, used when Media1 returns nothing |
| Vendor SDK ports ignored | Identify the family, and raise a finding when nothing else answers |
| Excel the only export | CSV and JSON as well |
| No dark theme | Light, dark, or follow the system |
| Ad-hoc colours scattered through the code | One tri-tone palette in tokens; no hex values left in Java |
| Contrast claimed, never measured | `PaletteContrastTest` reads the tokens and asserts every pairing |
| A saved scan could not be reopened | JSON loads back, with its streams and findings |
