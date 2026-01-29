# CCTV Discovery - Project Review Report

**Review Date:** 2026-01-29
**Project Version:** 1.1.4
**Reviewed By:** Claude Code Review

---

## Executive Summary

This project is a well-structured Java desktop application for CCTV camera discovery and auditing. The codebase is functional and demonstrates good architectural patterns. However, there are several gaps, improvements, and unimplemented features that should be addressed for a production-ready release.

---

## 1. Critical Gaps

### 1.1 No Unit Tests
**Severity:** High
**Location:** `src/test/` (missing)

The project has **zero unit tests**. This is a significant risk for a production application handling security-sensitive operations.

**Recommendation:**
- Add JUnit 5 dependency to pom.xml
- Create tests for core services: `RtspService`, `OnvifService`, `NetworkUtils`, `AuthUtils`
- Add integration tests for the discovery workflow
- Target minimum 60% code coverage

### 1.2 Hardcoded Security Values
**Severity:** High
**Location:** `application.properties:50`

```properties
export.excel.password.fixed.code=482753
```

The fixed password code is visible in source code and committed to git.

**Recommendation:**
- Move to environment variable or secure configuration
- Consider using a password derivation function
- Document the password generation algorithm separately from source code

### 1.3 Global SSL Certificate Validation Disabled
**Severity:** Medium
**Location:** `OnvifService.java:41-74`

The static block globally disables SSL certificate validation for all HTTPS connections:

```java
HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
```

This affects the entire JVM, not just ONVIF connections.

**Recommendation:**
- Use per-connection SSL context instead of global default
- Implement certificate pinning for known camera manufacturers
- Add configuration option to enable/disable strict SSL validation

---

## 2. Unimplemented Features

### 2.1 SSH Port Discovery Defined But Not Used
**Location:** `application.properties:16`

```properties
discovery.ssh.port=22
```

SSH port is defined but not used anywhere in the codebase.

**Recommendation:** Either implement SSH-based device detection or remove the unused configuration.

### 2.2 Gson Dependency Unused
**Location:** `pom.xml:165-170`

Gson is included as a dependency but not actively used.

**Recommendation:** Remove if not needed, or document planned usage for future API integrations.

### 2.3 Retry Logic Configuration Unused
**Location:** `application.properties:65-66`

```properties
advanced.retry.attempts=3
advanced.retry.delay=1000
```

These retry settings are defined but not referenced in the service implementations.

**Recommendation:** Implement retry logic in `RtspService` and `OnvifService` for transient network failures.

### 2.4 IPv6 Support Missing
**Location:** `NetworkUtils.java`

Only IPv4 addresses are supported. The IP validation pattern and parsing logic don't handle IPv6.

**Recommendation:** Add IPv6 support for modern network environments.

---

## 3. Code Quality Improvements

### 3.1 MainController Is Too Large
**Location:** `MainController.java` (2,783 lines)

This file handles UI creation, network configuration, credential management, discovery orchestration, and export logic all in one class.

**Recommendation:** Split into smaller, focused controllers:
- `NetworkConfigController` - Network selection dialogs
- `CredentialController` - Credential management
- `DiscoveryController` - Scan orchestration
- `ExportController` - Excel export logic

### 3.2 Resource Cleanup Issues
**Location:** Multiple files

Several places have potential resource leaks:

1. `RtspService.java:502-508` - Socket cleanup in finally block can throw
2. `StreamAnalyzer.java:161-168` - Grabber release may fail silently
3. `MainController.java` - ExecutorService not shutdown on window close

**Recommendation:**
- Use try-with-resources where possible
- Add shutdown hooks for executor services
- Implement proper cleanup on application exit

### 3.3 Exception Handling
**Location:** Various

Many methods catch generic `Exception` and only log:

```java
} catch (Exception e) {
    logger.error("Error during...", e);
}
```

**Recommendation:**
- Catch specific exceptions
- Propagate errors appropriately for UI feedback
- Consider using custom exception types for different failure modes

### 3.4 Code Duplication
**Location:** `MainController.java:1797-1893` and `MainController.java:1898-1935`

ONVIF and RTSP authentication methods have similar patterns for credential iteration and device updates.

**Recommendation:** Extract common authentication orchestration logic into a shared method or service.

---

## 4. Missing Features for Production

### 4.1 No Scan History/Persistence
Users cannot:
- Save scan results
- Load previous scan results
- Compare scans over time

**Recommendation:** Add SQLite or JSON-based persistence for scan history.

### 4.2 No Credential Profiles
**Location:** UI only supports manual credential entry

Users must re-enter credentials for each session.

**Recommendation:** Add encrypted credential storage with profiles (e.g., "Site A", "Site B").

### 4.3 Limited Export Options
**Location:** `ExcelExporter.java`

Only Excel export is available.

**Recommendation:** Add:
- CSV export for data analysis tools
- PDF export for formal reports
- JSON export for API integration

### 4.4 No Dark Mode
**Location:** `app.css`

Only light theme is available.

**Recommendation:** Add dark mode support for users who prefer it.

### 4.5 No Scan Progress Persistence
If the application closes during a scan, all progress is lost.

**Recommendation:** Implement checkpoint saving during long scans.

---

## 5. Performance Improvements

### 5.1 Smart Cache Not Persisted
**Location:** `RtspService.java:32`

```java
private static final Map<String, List<String>> SMART_CACHE = new HashMap<>();
```

The RTSP path cache is in-memory only and lost between sessions.

**Recommendation:** Persist smart cache to file for faster subsequent scans of similar networks.

### 5.2 No HTTP Connection Pooling
**Location:** `OnvifService.java:593-676`

Each ONVIF request creates a new `HttpURLConnection`.

**Recommendation:** Use Apache HttpClient with connection pooling for better performance.

### 5.3 Sequential Device Authentication
**Location:** `MainController.java` discovery loop

Devices are authenticated sequentially rather than in parallel.

**Recommendation:** Use CompletableFuture or executor service for parallel device authentication.

---

## 6. Build & Deployment Issues

### 6.1 Version Mismatch
**Location:** `pom.xml:9` vs `pom.xml:466-467`

- pom.xml version: `1.1.4`
- Launch4j versionInfo: `1.0.0.0`

**Recommendation:** Synchronize version numbers using Maven properties.

### 6.2 Missing Platform Support
**Location:** `pom.xml:185-225`

Profiles only support:
- Windows x64
- Linux x64
- macOS ARM64

Missing:
- macOS x64 (Intel Macs)
- Linux ARM64 (Raspberry Pi, etc.)

**Recommendation:** Add additional platform profiles or document supported platforms.

### 6.3 Deprecated API Usage
**Location:** `OnvifService.java:596`

```java
URL url = new URL(serviceUrl);
```

`java.net.URL` constructor is deprecated in newer Java versions.

**Recommendation:** Use `URI.create(serviceUrl).toURL()` pattern.

---

## 7. Documentation Gaps

### 7.1 Missing JavaDoc
Most public methods lack JavaDoc documentation, especially:
- `RtspService.discoverStreams()`
- `StreamAnalyzer.analyzeDevice()`
- `NetworkScanner.performPortScan()`

### 7.2 No API Documentation
No developer documentation explaining:
- How to add new manufacturer RTSP templates
- How to extend authentication methods
- How the smart cache algorithm works

### 7.3 Configuration Documentation
`application.properties` has comments but no comprehensive configuration guide.

**Recommendation:** Create a `CONFIGURATION.md` document explaining all settings.

---

## 8. Specific Bug Risks

### 8.1 Potential NPE in Profile Detection
**Location:** `StreamAnalyzer.java:204-207`

```java
String videoCodecName = grabber.getVideoCodecName();
if (videoCodecName == null) {
    return null;
}
```

But later:
```java
if (videoCodecName.contains("h264")...
```

The second check assumes videoCodecName is not null, but the method could be called without the null check succeeding first.

### 8.2 Race Condition in Discovery
**Location:** `MainController.java:146-150`

Host audit collection runs in background but `hostAuditData` is accessed later without synchronization.

### 8.3 Integer Overflow in IP Count
**Location:** `NetworkUtils.java:139`

```java
return (int) Math.pow(2, 32 - prefix) - 2;
```

For small prefix values (e.g., /8), this could overflow.

---

## 9. Recommendations Summary

### High Priority
1. Add unit tests (minimum 60% coverage)
2. Secure the fixed password code
3. Fix SSL validation to be per-connection
4. Add proper resource cleanup on application exit

### Medium Priority
5. Split MainController into smaller components
6. Implement retry logic for network operations
7. Persist smart cache between sessions
8. Add credential profiles feature
9. Fix version number inconsistency

### Low Priority
10. Add additional export formats
11. Implement dark mode
12. Add IPv6 support
13. Add scan history persistence
14. Document all configuration options

---

## 10. Positive Aspects

The project demonstrates several good practices:
- Clear separation between services (ONVIF, RTSP, Network)
- Configurable settings via properties files
- Proper logging with SLF4J/Logback
- Multi-threaded port scanning
- Smart caching for RTSP path discovery
- Comprehensive manufacturer OUI database
- Professional UI with JavaFX

---

*End of Review Report*
