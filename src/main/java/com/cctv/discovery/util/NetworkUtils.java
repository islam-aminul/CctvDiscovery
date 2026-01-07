package com.cctv.discovery.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Network utility class for IP validation, CIDR parsing, interface discovery, and MAC resolution.
 */
public class NetworkUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    /**
     * Get list of active physical network interfaces.
     */
    public static List<NetworkInterface> getActiveNetworkInterfaces() {
        List<NetworkInterface> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface ni = enumeration.nextElement();
                if (!ni.isLoopback() && ni.isUp() && !ni.isVirtual() && ni.getHardwareAddress() != null) {
                    Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress addr = inetAddresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            interfaces.add(ni);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error getting network interfaces", e);
        }
        return interfaces;
    }

    /**
     * Calculate CIDR from interface IP and subnet mask.
     */
    public static String calculateCIDR(InetAddress ip, short prefixLength) {
        return ip.getHostAddress() + "/" + prefixLength;
    }

    /**
     * Validate IP address format.
     */
    public static boolean isValidIP(String ip) {
        return ip != null && IP_PATTERN.matcher(ip).matches();
    }

    /**
     * Validate CIDR format.
     */
    public static boolean isValidCIDR(String cidr) {
        if (cidr == null || !cidr.contains("/")) {
            return false;
        }
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        if (!isValidIP(parts[0])) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= 32;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parse CIDR and return list of IP addresses.
     */
    public static List<String> parseCIDR(String cidr) throws Exception {
        List<String> ips = new ArrayList<>();
        String[] parts = cidr.split("/");
        String baseIp = parts[0];
        int prefix = Integer.parseInt(parts[1]);

        long ip = ipToLong(baseIp);
        long mask = (-1L << (32 - prefix)) & 0xFFFFFFFFL;
        long network = ip & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);

        for (long i = network + 1; i < broadcast; i++) {
            ips.add(longToIp(i));
        }

        return ips;
    }

    /**
     * Parse IP range and return list of IP addresses.
     */
    public static List<String> parseIPRange(String startIp, String endIp) throws Exception {
        List<String> ips = new ArrayList<>();
        long start = ipToLong(startIp);
        long end = ipToLong(endIp);

        if (start > end) {
            throw new IllegalArgumentException("Start IP must be less than or equal to End IP");
        }

        for (long i = start; i <= end; i++) {
            ips.add(longToIp(i));
        }

        return ips;
    }

    /**
     * Count IPs in CIDR range.
     */
    public static int countIPsInCIDR(String cidr) {
        String[] parts = cidr.split("/");
        int prefix = Integer.parseInt(parts[1]);
        return (int) Math.pow(2, 32 - prefix) - 2; // Exclude network and broadcast
    }

    /**
     * Count IPs in range.
     */
    public static int countIPsInRange(String startIp, String endIp) {
        try {
            long start = ipToLong(startIp);
            long end = ipToLong(endIp);
            return (int) (end - start + 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Convert IP address to long.
     */
    private static long ipToLong(String ipAddress) throws Exception {
        String[] octets = ipAddress.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (Long.parseLong(octets[i]) << (24 - (8 * i)));
        }
        return result & 0xFFFFFFFFL;
    }

    /**
     * Convert long to IP address.
     */
    private static String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                (ip & 0xFF);
    }

    /**
     * Resolve MAC address for given IP using ARP (OS-specific).
     */
    public static String resolveMacAddress(String ipAddress) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Process process;

            if (os.contains("win")) {
                process = Runtime.getRuntime().exec("arp -a " + ipAddress);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                process = Runtime.getRuntime().exec("arp -n " + ipAddress);
            } else {
                logger.warn("Unsupported OS for ARP resolution: " + os);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(ipAddress)) {
                    String mac = extractMacFromArpLine(line);
                    if (mac != null) {
                        return mac;
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            logger.error("Error resolving MAC address for IP: " + ipAddress, e);
        }
        return null;
    }

    /**
     * Extract MAC address from ARP output line.
     */
    private static String extractMacFromArpLine(String line) {
        // Match patterns like: 00:11:22:33:44:55 or 00-11-22-33-44-55
        Pattern macPattern = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})");
        java.util.regex.Matcher matcher = macPattern.matcher(line);
        if (matcher.find()) {
            return matcher.group().replace("-", ":").toUpperCase();
        }
        return null;
    }

    /**
     * Check if port is open on given host.
     */
    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Normalize MAC address format to XX:XX:XX:XX:XX:XX.
     */
    public static String normalizeMac(String mac) {
        if (mac == null) {
            return null;
        }
        // Remove any separators and convert to uppercase
        String cleaned = mac.replaceAll("[:-]", "").toUpperCase();
        if (cleaned.length() != 12) {
            return mac; // Return original if invalid length
        }
        // Format as XX:XX:XX:XX:XX:XX
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                formatted.append(":");
            }
            formatted.append(cleaned.substring(i, i + 2));
        }
        return formatted.toString();
    }

    /**
     * Get MAC prefix (first 3 octets) for manufacturer lookup.
     */
    public static String getMacPrefix(String mac) {
        if (mac == null || mac.length() < 8) {
            return null;
        }
        String normalized = normalizeMac(mac);
        if (normalized == null) {
            return null;
        }
        return normalized.substring(0, 8); // XX:XX:XX
    }
}
