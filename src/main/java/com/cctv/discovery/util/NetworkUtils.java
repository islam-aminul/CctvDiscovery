package com.cctv.discovery.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Network utility class for IP validation, CIDR parsing, interface discovery, and MAC resolution.
 */
public class NetworkUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    // Cache for IP-to-MAC mappings using OSHI
    private static final Map<String, String> ipToMacCache = new ConcurrentHashMap<>();
    private static volatile long lastCacheUpdate = 0;
    private static final long CACHE_VALIDITY_MS = 60000; // 1 minute

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
    public static long ipToLong(String ipAddress) throws Exception {
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
     * Resolve MAC address for given IP using hybrid approach:
     * 1. Check OSHI cache for local interface IPs (fast, no CLI)
     * 2. Fall back to ARP CLI for remote devices (requires OS ARP table)
     *
     * @param ipAddress The IP address to resolve
     * @return MAC address in format XX:XX:XX:XX:XX:XX or null if not found
     */
    public static String resolveMacAddress(String ipAddress) {
        try {
            // Check OSHI cache first (for local interface IPs)
            long now = System.currentTimeMillis();
            if (now - lastCacheUpdate < CACHE_VALIDITY_MS && ipToMacCache.containsKey(ipAddress)) {
                logger.info("MAC address for {} found in OSHI cache: {}", ipAddress, ipToMacCache.get(ipAddress));
                return ipToMacCache.get(ipAddress);
            }

            // Refresh OSHI cache if expired
            if (now - lastCacheUpdate >= CACHE_VALIDITY_MS) {
                refreshMacCache();
            }

            // Check OSHI cache again after refresh
            String mac = ipToMacCache.get(ipAddress);
            if (mac != null) {
                logger.info("MAC address for {} resolved via OSHI: {}", ipAddress, mac);
                return mac;
            }

            // Fall back to ARP CLI for remote devices (not in OSHI cache)
            logger.info("IP {} not in OSHI cache, querying OS ARP table", ipAddress);
            mac = resolveMacViaArp(ipAddress);

            if (mac != null) {
                // Cache the ARP result for future lookups
                ipToMacCache.put(ipAddress, mac);
                logger.info("MAC address for {} resolved via ARP: {}", ipAddress, mac);
            } else {
                if (!isLocalSubnet(ipAddress)) {
                    logger.info("MAC address for {} unavailable - cross-subnet device (ARP only works within local broadcast domain)", ipAddress);
                } else {
                    logger.info("MAC address for {} not found in ARP table", ipAddress);
                }
            }

            return mac;

        } catch (Exception e) {
            logger.error("Error resolving MAC address for IP {}", ipAddress, e);
            return null;
        }
    }

    /**
     * Resolve MAC address via OS ARP table (fallback for remote devices).
     * This is used when OSHI cache doesn't have the IP (remote devices).
     *
     * @param ipAddress The IP address to resolve
     * @return MAC address or null if not found
     */
    private static String resolveMacViaArp(String ipAddress) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Process process;

            if (os.contains("win")) {
                process = Runtime.getRuntime().exec("arp -a " + ipAddress);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                process = Runtime.getRuntime().exec(new String[]{"arp", "-n", ipAddress});
            } else {
                logger.info("Unsupported OS for ARP resolution: {}", os);
                return null;
            }

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(ipAddress)) {
                        String mac = extractMacFromArpLine(line);
                        if (mac != null) {
                            return mac;
                        }
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            logger.info("Error querying ARP for IP {}: {}", ipAddress, e.getMessage());
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
            return normalizeMac(matcher.group());
        }
        return null;
    }

    /**
     * Refresh the IP-to-MAC cache using OSHI to query all network interfaces.
     * This is more efficient than querying individual IPs via ARP CLI.
     */
    private static synchronized void refreshMacCache() {
        try {
            logger.info("Refreshing MAC address cache using OSHI...");
            ipToMacCache.clear();

            SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hardware = systemInfo.getHardware();
            List<NetworkIF> networkIFs = hardware.getNetworkIFs();

            int totalMappings = 0;
            for (NetworkIF netIF : networkIFs) {
                String macAddress = netIF.getMacaddr();

                // Skip if MAC is empty or invalid
                if (macAddress == null || macAddress.isEmpty() || macAddress.equals("00:00:00:00:00:00")) {
                    continue;
                }

                // Normalize MAC address format
                macAddress = normalizeMac(macAddress);

                // Get all IPv4 addresses assigned to this interface
                String[] ipv4Addresses = netIF.getIPv4addr();
                if (ipv4Addresses != null) {
                    for (String ip : ipv4Addresses) {
                        if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0")) {
                            ipToMacCache.put(ip, macAddress);
                            totalMappings++;
                            logger.trace("Cached MAC mapping: {} -> {}", ip, macAddress);
                        }
                    }
                }
            }

            lastCacheUpdate = System.currentTimeMillis();
            logger.info("MAC address cache refreshed: {} IP-to-MAC mappings from {} network interfaces",
                       totalMappings, networkIFs.size());

        } catch (Exception e) {
            logger.error("Error refreshing MAC address cache with OSHI", e);
        }
    }

    /**
     * Clear the MAC address cache (useful for testing or forcing refresh).
     */
    public static void clearMacCache() {
        ipToMacCache.clear();
        lastCacheUpdate = 0;
        logger.info("MAC address cache cleared");
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

    /**
     * Calculate number of usable IPs in a subnet based on prefix length.
     *
     * @param prefixLength Network prefix length (0-32)
     * @return Number of usable host IPs (excluding network and broadcast addresses)
     */
    public static int countIPsFromPrefix(int prefixLength) {
        if (prefixLength < 0 || prefixLength > 32) {
            return 0;
        }
        if (prefixLength == 31 || prefixLength == 32) {
            // /31 (point-to-point) has 2 usable IPs, /32 (host) has 1
            return (int) Math.pow(2, 32 - prefixLength);
        }
        // Subtract 2 for network and broadcast addresses
        return (int) Math.pow(2, 32 - prefixLength) - 2;
    }

    /**
     * Check if an IP address belongs to any local interface's subnet.
     * Used to detect cross-subnet targets where ARP resolution won't work.
     *
     * @param ipAddress The target IP address to check
     * @return true if the IP is on a local subnet, false if cross-subnet
     */
    public static boolean isLocalSubnet(String ipAddress) {
        try {
            long targetIp = ipToLong(ipAddress);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ifAddr : ni.getInterfaceAddresses()) {
                    InetAddress addr = ifAddr.getAddress();
                    if (!(addr instanceof Inet4Address)) continue;
                    short prefix = ifAddr.getNetworkPrefixLength();
                    if (prefix <= 0 || prefix > 32) continue;
                    long ifIp = ipToLong(addr.getHostAddress());
                    long mask = prefix == 32 ? 0xFFFFFFFFL : ((-1L << (32 - prefix)) & 0xFFFFFFFFL);
                    if ((ifIp & mask) == (targetIp & mask)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.info("Error checking subnet for {}: {}", ipAddress, e.getMessage());
        }
        return false;
    }

    /**
     * Get the network prefix length (CIDR prefix) for a network interface address.
     * Returns the actual prefix length or 24 as default if not available.
     *
     * @param ni NetworkInterface to query
     * @return Network prefix length (typically 8, 16, 24, etc.)
     */
    public static int getNetworkPrefixLength(NetworkInterface ni) {
        try {
            for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                InetAddress inetAddr = addr.getAddress();
                if (inetAddr instanceof Inet4Address) {
                    short prefix = addr.getNetworkPrefixLength();
                    if (prefix > 0 && prefix <= 32) {
                        return prefix;
                    }
                }
            }
        } catch (Exception e) {
            logger.info("Error getting network prefix length for {}: {}", ni.getName(), e.getMessage());
        }
        // Default to /24 if unable to determine
        return 24;
    }
}
