# CCTV Discovery Tool - Authority Manual

**CONFIDENTIAL - FOR AUTHORIZED PERSONNEL ONLY**

This document contains the password derivation formula for Excel worksheet protection. This information should be kept secure and shared only with authorized supervisors/auditors.

---

## Excel Password Protection System

All exported Excel reports are automatically protected with a password to prevent field operators from tampering with audit results before submission.

**CRITICAL**: The password is **NOT displayed** to field operators and is **NOT logged** by the application. Only authorities can derive the password using the formula below.

### Password Generation Formula

**Format**: `{DeviceCount}{YYYYMMDD}{FixedCode}`

Where:
- **DeviceCount**: Number of devices discovered (without leading zeros)
- **YYYYMMDD**: Report generation date (visible in Excel metadata)
- **FixedCode**: `482753` (secret component)

### Examples

**Example 1**:
- Devices discovered: 25
- Report date: January 7, 2026
- Password: `252026010748275`
- Breakdown: `25` + `20260107` + `482753`

**Example 2**:
- Devices discovered: 142
- Report date: March 15, 2026
- Password: `1422026031548275`
- Breakdown: `142` + `20260315` + `482753`

**Example 3**:
- Devices discovered: 5
- Report date: December 31, 2025
- Password: `52025123148275`
- Breakdown: `5` + `20251231` + `482753`

---

## How to Derive the Password

When you receive an Excel report from a field operator:

### Step 1: Count the Devices
Open the Excel file (it will open in read-only/protected mode). Count the number of device rows:
- Scroll to the data section (after metadata)
- Count rows from the first device to the last
- **Note**: Each device may have multiple rows if it has multiple streams
- Count unique IP addresses OR count total data rows (depending on your audit requirements)

**Recommendation**: Count unique devices by looking at distinct IP addresses in column A.

### Step 2: Find the Report Date
Look at the metadata section at the top of the Excel:
- Row 2 or 3 should show "Report Date: [date]"
- Extract the date in YYYYMMDD format

Example: "Report Date: Tue Jan 07 14:32:15 IST 2026" → `20260107`

### Step 3: Apply the Formula
Calculate the password:
```
Password = DeviceCount + YYYYMMDD + "482753"
```

### Step 4: Unprotect the Worksheet
1. Open Excel
2. Go to **Review** tab
3. Click **Unprotect Sheet**
4. Enter the derived password
5. The worksheet is now editable

---

## Tampering Detection

The password formula provides automatic tampering detection:

### Scenario 1: User Deletes Devices
- Original: 50 devices → Password: `502026010748275`
- Tampered: 45 devices (user deleted 5)
- User tries to re-protect with guessed password → **FAILS** (doesn't know fixed code)
- Authority tries original password → **FAILS** (device count mismatch)
- **Detection**: Password won't work, indicating possible tampering

### Scenario 2: User Adds Fake Devices
- Original: 20 devices → Password: `202026010748275`
- Tampered: 25 devices (user added 5 fake)
- Authority derives: `252026010748275` → **FAILS**
- **Detection**: Count mismatch reveals tampering

### Scenario 3: User Modifies Data (Without Changing Count)
- Original: 30 devices → Password: `302026010748275`
- User modifies IP addresses, credentials, or compliance flags
- User cannot re-protect:
  - Password was NOT shown to them
  - They don't know the fixed code `482753`
  - They cannot derive the password
- User sends unprotected file or attempts to hide modifications
- **Detection**: Missing protection indicates tampering attempt

---

## Password Recovery Process

If a field operator requests the password or claims they "need access":

1. **IMPORTANT**: Field operators are NEVER given the password
2. **Do NOT provide the password** under any circumstances
3. Request the Excel file for verification
4. Derive the password using the formula
5. If password works → File is authentic
6. If password fails → File may be tampered or corrupted
7. Compare device count in file with expected count from field report

**Note**: The password is intentionally NOT shown to field operators during export. This is a security feature, not a bug.

---

## Security Considerations

### Protecting the Fixed Code

The fixed code `482753` is the critical security component:

✅ **DO**:
- Keep this manual secure (encrypted storage, access-controlled)
- Share only with authorized supervisors
- Use different fixed codes for different projects if needed
- Change the code if compromised (requires code rebuild)

❌ **DON'T**:
- Share the formula with field operators
- Include this manual in field operator training materials
- Write the fixed code in field-accessible documentation
- Email the formula over unsecured channels

### Changing the Fixed Code

If you need to change the fixed code (e.g., security breach):

1. Update the code in `MainController.java` line 660:
   ```java
   String fixedCode = "482753"; // Change this value
   ```
2. Rebuild the application
3. Redistribute to field operators
4. Update this manual with the new code

---

## Limitations

This protection system has the following limitations:

1. **Not Cryptographically Secure**: A determined attacker with source code access could discover the formula
2. **Excel Protection Limits**: Excel's worksheet protection can be bypassed with specialized tools
3. **Device Count Ambiguity**: If device count is ambiguous (e.g., NVR with multiple channels), derivation may be unclear

**Mitigation**: Use this as a deterrent against casual tampering, not as cryptographic security. For high-security requirements, implement additional digital signatures or hash verification.

---

## Troubleshooting

### Password Doesn't Work

**Possible Causes**:
1. **Device count mismatch**:
   - Count all rows with data (not just unique IPs if devices have multiple streams)
   - Check if counting method matches application logic

2. **Date format error**:
   - Ensure date is in YYYYMMDD format (not DDMMYYYY)
   - Example: January 7, 2026 = `20260107` (not `07012026`)

3. **Typo in fixed code**:
   - Fixed code is `482753` (verify each digit)
   - No spaces or separators

4. **File was tampered**:
   - User may have modified and re-protected with different password
   - Request original file from device or verify with field logs

### Determining Correct Device Count

The application counts devices in the `devices` list, which includes:
- Each discovered IP address as one device
- NVR/DVR channels are NOT counted separately

**Example**:
- 1 NVR at 192.168.1.100 with 16 channels = **1 device**
- 25 standalone cameras = **25 devices**
- Total in report = **26 devices** → Use `26` in password formula

---

## Contact

For questions about password derivation or suspected tampering, contact:
- IT Security Team
- Project Manager
- System Administrator

**Do not discuss the password formula over phone, email, or in the presence of field operators.**

---

**Last Updated**: January 2026
**Classification**: CONFIDENTIAL
**Distribution**: Authorized Supervisors Only
