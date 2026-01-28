package com.cctv.discovery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for resolving manufacturer names from MAC address OUI prefixes.
 */
public class MacLookupService {
    private static final Logger logger = LoggerFactory.getLogger(MacLookupService.class);
    private static final MacLookupService INSTANCE = new MacLookupService();

    private final Map<String, String> ouiMap;

    private MacLookupService() {
        this.ouiMap = new HashMap<>();
        loadOuiDatabase();
    }

    public static MacLookupService getInstance() {
        return INSTANCE;
    }

    /**
     * Load OUI database from resources.
     */
    private void loadOuiDatabase() {
        try {
            // Load optimized India-specific OUI database
            InputStream is = getClass().getClassLoader().getResourceAsStream("oui.csv");
            if (is == null) {
                logger.warn("OUI database not found in resources");
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    String prefix = parts[0].trim().toUpperCase();
                    String manufacturer = parts[1].trim();
                    ouiMap.put(prefix, manufacturer);
                    count++;
                }
            }

            reader.close();
            logger.info("Loaded {} OUI entries from database", count);

        } catch (Exception e) {
            logger.error("Error loading OUI database", e);
        }
    }

    /**
     * Lookup manufacturer name by MAC address.
     *
     * @param macAddress MAC address in format XX:XX:XX:XX:XX:XX
     * @return Manufacturer name or "Unknown" if not found
     */
    public String lookupManufacturer(String macAddress) {
        if (macAddress == null || macAddress.length() < 8) {
            return "Unknown";
        }

        // Get first 3 octets (XX:XX:XX)
        String prefix = macAddress.substring(0, 8).toUpperCase().replace("-", ":");

        // Try exact match first
        String manufacturer = ouiMap.get(prefix);
        if (manufacturer != null) {
            return manufacturer;
        }

        // Try without colons (XXXXXX)
        String prefixNoSeparator = prefix.replace(":", "");
        manufacturer = ouiMap.get(prefixNoSeparator);
        if (manufacturer != null) {
            return manufacturer;
        }

        // Try with hyphens (XX-XX-XX)
        String prefixWithHyphens = prefix.replace(":", "-");
        manufacturer = ouiMap.get(prefixWithHyphens);
        if (manufacturer != null) {
            return manufacturer;
        }

        logger.info("Unknown manufacturer for MAC prefix: {}", prefix);
        return "Unknown";
    }

    /**
     * Check if manufacturer is a known CCTV/camera vendor.
     */
    public boolean isCCTVManufacturer(String manufacturer) {
        if (manufacturer == null) {
            return false;
        }

        String lower = manufacturer.toLowerCase();
        return lower.contains("hikvision") ||
                lower.contains("dahua") ||
                lower.contains("axis") ||
                lower.contains("vivotek") ||
                lower.contains("sony") ||
                lower.contains("panasonic") ||
                lower.contains("samsung") ||
                lower.contains("bosch") ||
                lower.contains("hanwha") ||
                lower.contains("honeywell") ||
                lower.contains("uniview") ||
                lower.contains("cp plus") ||
                lower.contains("godrej") ||
                lower.contains("matrix");
    }

    /**
     * Get total number of OUI entries loaded.
     */
    public int getOuiCount() {
        return ouiMap.size();
    }
}
