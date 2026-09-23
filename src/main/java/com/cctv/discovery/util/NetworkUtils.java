package com.cctv.discovery.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPv4 helpers: interface discovery, address arithmetic, MAC resolution.
 */
public final class NetworkUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern MAC_PATTERN = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");

    private static final Map<String, String> ARP_CACHE = new ConcurrentHashMap<>();

    private NetworkUtils() {
    }

    /** An IPv4 address on a local interface together with its real network. */
    public record LocalInterface(String name, String displayName, String address, int prefixLength) {
        /** Network CIDR, e.g. 192.168.0.0/24. */
        public String networkCidr() {
            long ip = ipToLong(address);
            long mask = prefixMask(prefixLength);
            return longToIp(ip & mask) + "/" + prefixLength;
        }

        public long hostCount() {
            return countHosts(prefixLength);
        }

        @Override
        public String toString() {
            return address + "/" + prefixLength + " - " + displayName;
        }
    }

    /** Active, non-loopback IPv4 interfaces with their real prefix lengths. */
    public static List<LocalInterface> getLocalInterfaces() {
        List<LocalInterface> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp() || ni.isPointToPoint()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address addr && !addr.isLinkLocalAddress()) {
                        int prefix = ia.getNetworkPrefixLength();
                        if (prefix <= 0 || prefix > 32) {
                            prefix = 24;
                        }
                        result.add(new LocalInterface(ni.getName(), ni.getDisplayName(), addr.getHostAddress(), prefix));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error enumerating network interfaces", e);
        }
        return result;
    }

    /** Local IPv4 interfaces as {@link NetworkInterface}s (for multicast send). */
    public static List<NetworkInterface> getMulticastInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) {
                    continue;
                }
                boolean hasV4 = ni.getInterfaceAddresses().stream()
                        .anyMatch(ia -> ia.getAddress() instanceof Inet4Address a && !a.isLinkLocalAddress());
                if (hasV4) {
                    result.add(ni);
                }
            }
        } catch (Exception e) {
            logger.error("Error enumerating multicast interfaces", e);
        }
        return result;
    }

    public static boolean isValidIP(String ip) {
        return ip != null && IP_PATTERN.matcher(ip.trim()).matches();
    }

    public static long ipToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        if (o.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ip);
        }
        long r = 0;
        for (String part : o) {
            int v = Integer.parseInt(part);
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException("Invalid IPv4 address: " + ip);
            }
            r = (r << 8) | v;
        }
        return r;
    }

    public static String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    public static long prefixMask(int prefix) {
        return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    /** Usable host addresses for a prefix (RFC 3021: /31 has two, /32 has one). */
    public static long countHosts(int prefix) {
        if (prefix < 0 || prefix > 32) {
            return 0;
        }
        long size = 1L << (32 - prefix);
        return prefix >= 31 ? size : size - 2;
    }

    /** True if the address falls inside the network of any local interface. */
    public static boolean isLocalSubnet(String ip) {
        try {
            long target = ipToLong(ip);
            for (LocalInterface li : getLocalInterfaces()) {
                long mask = prefixMask(li.prefixLength());
                if ((ipToLong(li.address()) & mask) == (target & mask)) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Subnet check failed for {}: {}", ip, e.getMessage());
        }
        return false;
    }

    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve a MAC address from the OS neighbour (ARP) table. Only works for
     * hosts in the local broadcast domain, and only after traffic has been
     * exchanged with the host (the port scan takes care of that).
     */
    public static String resolveMacAddress(String ip) {
        String cached = ARP_CACHE.get(ip);
        if (cached != null) {
            return cached;
        }
        if (!isValidIP(ip)) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("arp", "-a", ip) : List.of("arp", "-n", ip);
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.contains(ip + " ") && !line.contains("(" + ip + ")") && !line.trim().startsWith(ip)) {
                        continue;
                    }
                    Matcher m = MAC_PATTERN.matcher(line);
                    if (m.find()) {
                        String mac = normalizeMac(m.group());
                        if (isUnicastMac(mac)) {
                            ARP_CACHE.put(ip, mac);
                            return mac;
                        }
                    }
                }
            } finally {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.debug("ARP lookup failed for {}: {}", ip, e.getMessage());
        }
        return null;
    }

    /** Normalise to XX:XX:XX:XX:XX:XX (upper case); returns null for malformed input. */
    public static String normalizeMac(String mac) {
        if (mac == null) {
            return null;
        }
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 12) {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** A real device MAC: not multicast/broadcast and not all zeros. */
    public static boolean isUnicastMac(String mac) {
        String n = normalizeMac(mac);
        if (n == null || n.equals("00:00:00:00:00:00") || n.equals("FF:FF:FF:FF:FF:FF")) {
            return false;
        }
        int firstOctet = Integer.parseInt(n.substring(0, 2), 16);
        return (firstOctet & 0x01) == 0;
    }

    /** Resolve a hostname to IPv4, returning null on failure. */
    public static String toIpv4(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) {
                    return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            logger.debug("Cannot resolve {}", host);
        }
        return null;
    }
}
