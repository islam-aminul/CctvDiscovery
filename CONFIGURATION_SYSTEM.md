# Configuration System Implementation Summary

## Overview
A complete configuration management system has been implemented for the CCTV Discovery application, allowing users to customize ports and RTSP paths through a user-friendly Settings dialog.

## Components Implemented

### 1. Configuration Files

#### `src/main/resources/application.properties`
Default configuration bundled with the application:
- Application metadata (name, version, organization)
- Network discovery settings (ONVIF, HTTP, RTSP ports)
- Threading configuration
- Timeout settings
- RTSP discovery settings (including custom path pairs)
- Stream analysis parameters
- Export settings
- UI preferences

#### `user-settings.properties` (Runtime)
Created in application directory to store user overrides. Persists across application restarts.

### 2. Configuration Manager

#### `src/main/java/com/cctv/discovery/config/AppConfig.java`
Singleton class providing centralized configuration access:
- Loads default properties from `application.properties`
- Loads user overrides from `user-settings.properties`
- Provides typed getters for all configuration values
- Supports property placeholders (e.g., `${user.home}`)
- Handles saving user settings to file
- Supports resetting to defaults

**Key Methods:**
```java
public static AppConfig getInstance()
public String getProperty(String key)
public void setProperty(String key, String value)
public void saveUserSettings()
public void resetAllToDefaults()
public int[] getHttpPorts()
public int[] getRtspPorts()
public String[] getCustomRtspPaths()
```

### 3. Settings Dialog

#### `src/main/java/com/cctv/discovery/ui/SettingsDialog.java`
Simplified user interface for non-technical users (650x600 modal dialog):

**Port Configuration Section:**
- HTTP Ports: Supports single port (e.g., "8000") or comma-separated list (e.g., "80,8080,8000")
- RTSP Ports: Supports single port (e.g., "554") or comma-separated list (e.g., "554,8554")
- Real-time validation (port range 1-65535)
- Clear help text and examples

**Custom RTSP Path Pairs Section:**
- Separate input fields for Main Stream Path and Sub Stream Path
- "Add Path Pair" button with validation
- ListView displaying configured pairs in format: "Main: /path | Sub: /path"
- "Remove Selected" button for easy management
- Path validation (must start with "/")
- Helpful tooltip explaining main vs. sub streams

**Action Buttons:**
- Save: Validates and persists all settings
- Reset to Defaults: Clears all user overrides
- Cancel: Closes without saving

### 4. Integration Points

#### MainController Integration
**File:** `src/main/java/com/cctv/discovery/ui/MainController.java`

```java
private final AppConfig config = AppConfig.getInstance();

// Settings button in header
Button btnSettings = new Button("Settings");
btnSettings.setOnAction(e -> showSettings());

private void showSettings() {
    SettingsDialog settingsDialog = new SettingsDialog(primaryStage);
    settingsDialog.showAndWait();
}

// Export functionality uses config
if (config.isExcelPasswordEnabled()) {
    String fixedCode = config.getExcelPasswordFixedCode();
    generatedPassword = deviceCount + dateStr + fixedCode;
}

String exportDir = config.getExportDefaultDirectory();
```

#### RtspService Integration
**File:** `src/main/java/com/cctv/discovery/service/RtspService.java`

```java
// Discovery waterfall includes custom path pairs
String[] customPaths = AppConfig.getInstance().getCustomRtspPaths();
if (customPaths.length > 0 && customPaths.length % 2 == 0) {
    // Process custom paths as pairs
    for (int i = 0; i < customPaths.length; i += 2) {
        String mainPath = customPaths[i];
        String subPath = customPaths[i + 1];

        // Try main stream
        RTSPStream mainStream = testRtspUrl(mainUrl, username, password);

        // Try paired sub stream
        RTSPStream subStream = testRtspUrl(subUrl, username, password);

        // Add successful paths to smart cache
    }
}
```

## Configuration Flow

### 1. Application Startup
```
Main.java → Launcher.java → MainController
                                  ↓
                          AppConfig.getInstance()
                                  ↓
                Load application.properties (defaults)
                                  ↓
                Load user-settings.properties (overrides)
```

### 2. User Customization
```
User clicks "Settings" button → SettingsDialog opens
                                       ↓
                           Load current configuration
                                       ↓
              User modifies ports/paths and clicks "Save"
                                       ↓
                          Validate all inputs
                                       ↓
                  AppConfig.setProperty(...) for each change
                                       ↓
                   AppConfig.saveUserSettings()
                                       ↓
                Write user-settings.properties to disk
                                       ↓
            Show confirmation: "Restart for changes to take effect"
```

### 3. Discovery with Custom Paths
```
RtspService.discoverStreams(device)
              ↓
    1. Try smart cache (previous successes)
              ↓
    2. Try manufacturer-specific paths
              ↓
    3. Try custom user-configured path pairs ← NEW
              ↓
    4. Try generic common paths
              ↓
    Return discovered streams
```

## Data Format

### Port Configuration
**Input Format:** Single or comma-separated
```
Single: 8000
Multiple: 80,8080,8000,8081
```

**Storage Format:** Comma-separated string
```properties
discovery.http.ports=80,8080,8000,8081
discovery.rtsp.ports=554,8554
```

### RTSP Path Pairs
**Input Format:** Separate fields for main and sub paths
```
Main Stream Path: /h264/ch1/main/av_stream
Sub Stream Path: /h264/ch1/sub/av_stream
```

**Storage Format:** Semicolon-separated pairs
```properties
rtsp.custom.paths=/h264/ch1/main/av_stream;/h264/ch1/sub/av_stream;/stream1;/stream2
```

**Parsing:** `AppConfig.getCustomRtspPaths()` splits by ";" and returns String array

## User Experience Enhancements

1. **Non-Technical User Focus:**
   - Simplified interface with only essential settings
   - Clear labels and help text
   - Real-world examples in placeholders
   - Validation with friendly error messages

2. **Button-Based Path Management:**
   - Visual interface replaces manual text editing
   - Add/Remove buttons for intuitive management
   - ListView shows all configured pairs
   - Prevents format errors

3. **Format Flexibility:**
   - Supports both single port and comma-separated lists
   - Handles whitespace in port lists
   - Clear documentation in UI

4. **Configuration Persistence:**
   - Settings survive application restarts
   - Stored in user-accessible `user-settings.properties`
   - Easy to reset to defaults if needed

## Testing Recommendations

1. **Settings Dialog:**
   - Verify dialog opens from "Settings" button
   - Test port validation (valid/invalid ranges)
   - Test path validation (must start with "/")
   - Test Add/Remove path pair functionality
   - Verify ListView updates correctly
   - Test Save and Reset to Defaults

2. **Configuration Persistence:**
   - Save settings and restart application
   - Verify settings are loaded correctly
   - Check `user-settings.properties` file contents
   - Test reset functionality

3. **RTSP Discovery with Custom Paths:**
   - Add custom path pairs via Settings
   - Run discovery on camera with those paths
   - Verify custom paths are tried during discovery
   - Confirm successful paths are added to smart cache

4. **Port Configuration:**
   - Change HTTP/RTSP ports via Settings
   - Verify discovery uses new ports
   - Test both single and comma-separated formats

## Files Modified/Created

### Created:
- `src/main/resources/application.properties`
- `src/main/java/com/cctv/discovery/config/AppConfig.java`
- `src/main/java/com/cctv/discovery/ui/SettingsDialog.java`

### Modified:
- `src/main/java/com/cctv/discovery/ui/MainController.java`
- `src/main/java/com/cctv/discovery/service/RtspService.java`

### Runtime Generated:
- `user-settings.properties` (in application directory)

## Commit History

```
c3d4916 Clarify port input format in Settings dialog
7cec6bf Add application configuration system with settings dialog
```

## Status

✅ Configuration system fully implemented
✅ Settings dialog created with user-friendly interface
✅ Integration with MainController complete
✅ Integration with RtspService complete
✅ Port format clarification added
✅ All changes committed and pushed

⏳ Pending: Build and runtime testing (requires network connectivity for Maven dependencies)
