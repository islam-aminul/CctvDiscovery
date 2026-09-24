package com.cctv.discovery.discovery;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.Finding;
import com.cctv.discovery.model.RTSPStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The judgements a scan makes about a device once the probing is over.
 *
 * <p>These could not be tested before: the same code lived inside
 * {@code MainController} and needed a JavaFX window to exist. What it decides
 * is what the operator reads in the report, and the distinction it draws —
 * a rejected password against an address we simply do not know — is the
 * difference between "fix the credentials" and "tell me the stream path".
 */
class ScanCoordinatorTest {

    private static Device device(String ip) {
        Device device = new Device(ip);
        device.setStatus(Device.DeviceStatus.AUTHENTICATING);
        return device;
    }

    private static Device withStream(String ip) {
        Device device = device(ip);
        RTSPStream stream = new RTSPStream("PROFILE_1", "rtsp://" + ip + ":554/live");
        device.addStream(stream);
        return device;
    }

    private static boolean hasFinding(Device device, String title) {
        return device.getFindings().stream().anyMatch(f -> f.title().equals(title));
    }

    @Test
    @DisplayName("A device with video is finished, with nothing held against it")
    void streamsMeanSuccess() {
        Device device = withStream("192.168.0.10");
        device.setAuthFailed(true);
        device.setErrorMessage("something earlier");

        ScanCoordinator.finalizeStatus(device, false, false);

        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertFalse(device.isAuthFailed());
        assertEquals(null, device.getErrorMessage());
        assertTrue(device.getFindings().isEmpty());
    }

    @Test
    @DisplayName("Something that answered on no video port is not a camera at all")
    void noVideoPortsMeansNotACamera() {
        Device device = device("192.168.0.11");
        device.setType(Device.DeviceType.CAMERA);

        ScanCoordinator.finalizeStatus(device, false, false);

        assertEquals(Device.DeviceStatus.AUTH_FAILED, device.getStatus());
        assertEquals(Device.DeviceType.UNKNOWN, device.getType());
        assertEquals("Not a camera or recorder", device.getErrorMessage());
        // Not a sign-in problem, so the operator is not sent looking for a password.
        assertFalse(device.isAuthFailed());
        assertTrue(device.getFindings().isEmpty());
    }

    @Test
    @DisplayName("An RTSP server that demanded a password means the credentials are wrong")
    void rtspChallengeMeansRejectedCredentials() {
        Device device = device("192.168.0.12");
        device.getOpenRtspPorts().add(554);

        ScanCoordinator.finalizeStatus(device, false, true);

        assertTrue(device.isAuthFailed());
        assertEquals("No credential was accepted", device.getErrorMessage());
        assertTrue(hasFinding(device, "Could not sign in"));
    }

    @Test
    @DisplayName("An ONVIF port that refused every credential means the same")
    void onvifRefusalMeansRejectedCredentials() {
        Device device = device("192.168.0.13");
        device.getOpenOnvifPorts().add(80);

        ScanCoordinator.finalizeStatus(device, false, false);

        assertTrue(device.isAuthFailed());
        assertEquals("No credential was accepted", device.getErrorMessage());
        assertTrue(hasFinding(device, "Could not sign in"));
    }

    @Test
    @DisplayName("Credentials accepted but no path found is a different problem, and says so")
    void acceptedCredentialsWithoutAStreamIsNotASignInFailure() {
        Device device = device("192.168.0.14");
        device.getOpenRtspPorts().add(554);

        ScanCoordinator.finalizeStatus(device, true, false);

        // The password worked, so telling the operator to fix it would send
        // them the wrong way.
        assertFalse(device.isAuthFailed());
        assertEquals("No stream path matched", device.getErrorMessage());
        assertTrue(hasFinding(device, "Stream address unknown"));
        assertFalse(hasFinding(device, "Could not sign in"));
    }

    @Test
    @DisplayName("A device reachable only over its maker's protocol is called out once")
    void vendorOnlyDeviceIsExplained() {
        Device device = device("192.168.0.15");
        device.getOpenSpecialPorts().add(37777);
        device.getOpenSpecialPorts().add(34567);

        ScanCoordinator.finalizeStatus(device, false, false);

        long explanations = device.getFindings().stream()
                .filter(f -> f.title().contains("manufacturer's own protocol"))
                .count();
        assertEquals(1, explanations, "one explanation is enough, even with several ports open");

        Finding finding = device.getFindings().stream()
                .filter(f -> f.title().contains("manufacturer's own protocol"))
                .findFirst().orElseThrow();
        assertEquals(Finding.Severity.INFO, finding.severity());
        assertTrue(finding.recommendation().contains("ONVIF"));
    }

    @Test
    @DisplayName("A vendor port is not mentioned once the video was found anyway")
    void vendorPortIsSilentWhenStreamsWork() {
        Device device = withStream("192.168.0.16");
        device.getOpenSpecialPorts().add(37777);

        ScanCoordinator.finalizeStatus(device, true, false);

        assertEquals(Device.DeviceStatus.COMPLETED, device.getStatus());
        assertTrue(device.getFindings().isEmpty());
    }
}
