package com.cctv.discovery.service;

import com.cctv.discovery.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * MAC vendor lookup backed by the bundled IEEE registries (MA-L, MA-M, MA-S).
 * <p>
 * The longest matching assignment wins (36-bit MA-S, then 28-bit MA-M, then
 * 24-bit MA-L). Registry organisation names are mapped to familiar CCTV brand
 * names through {@code oui/brand-aliases.txt}. Refresh the data with
 * {@code scripts/Update-OuiData.ps1}.
 */
public final class MacLookupService {
    private static final Logger logger = LoggerFactory.getLogger(MacLookupService.class);
    private static final MacLookupService INSTANCE = new MacLookupService();

    public static final String UNKNOWN = "Unknown";

    private final Map<String, String> registry = new HashMap<>(64_000);
    private final List<String[]> aliases = new ArrayList<>();

    private MacLookupService() {
        loadRegistry();
        loadAliases();
    }

    public static MacLookupService getInstance() {
        return INSTANCE;
    }

    public int size() {
        return registry.size();
    }

    private void loadRegistry() {
        try (InputStream raw = MacLookupService.class.getResourceAsStream("/oui/ieee-oui.tsv.gz")) {
            if (raw == null) {
                logger.warn("IEEE OUI database not found in resources");
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        registry.put(line.substring(0, tab), line.substring(tab + 1));
                    }
                }
            }
            logger.info("Loaded {} IEEE OUI assignments", registry.size());
        } catch (Exception e) {
            logger.error("Error loading IEEE OUI database", e);
        }
    }

    private void loadAliases() {
        try (InputStream is = MacLookupService.class.getResourceAsStream("/oui/brand-aliases.txt")) {
            if (is == null) {
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int bar = line.indexOf('|');
                    if (bar > 0) {
                        aliases.add(new String[]{line.substring(0, bar).trim().toLowerCase(Locale.ROOT), line.substring(bar + 1).trim()});
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error loading brand aliases", e);
        }
    }

    /** IEEE organisation name registered for the MAC, or null when unregistered. */
    public String lookupOrganization(String macAddress) {
        String mac = NetworkUtils.normalizeMac(macAddress);
        if (mac == null) {
            return null;
        }
        String hex = mac.replace(":", "");
        for (int len : new int[]{9, 7, 6}) {
            String org = registry.get(hex.substring(0, len));
            if (org != null) {
                return org;
            }
        }
        return null;
    }

    /**
     * Brand for the MAC: the alias for its IEEE organisation when one is
     * defined, otherwise the organisation name without legal suffixes.
     * Returns {@link #UNKNOWN} for unregistered or locally administered MACs.
     */
    public String lookupManufacturer(String macAddress) {
        String org = lookupOrganization(macAddress);
        return org == null ? UNKNOWN : brandFor(org);
    }

    /** Map an organisation or ONVIF-reported manufacturer name to a brand name. */
    public String brandFor(String organization) {
        if (organization == null || organization.isBlank()) {
            return UNKNOWN;
        }
        String lower = organization.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[^a-z0-9]", "");
        for (String[] alias : aliases) {
            if (lower.contains(alias[0]) || compact.contains(alias[0].replaceAll("[^a-z0-9]", ""))) {
                return alias[1];
            }
        }
        return cleanOrganization(organization);
    }

    static String cleanOrganization(String org) {
        String cleaned = org.replaceAll("(?i)[,.]?\\s*(co\\.?,?\\s*ltd\\.?|ltd\\.?|limited|inc\\.?|corporation|corp\\.?|gmbh|llc|s\\.?a\\.?|pvt\\.?|private|technology|technologies)\\b\\.?", "")
                .replaceAll("\\s{2,}", " ").replaceAll("[,\\s]+$", "").trim();
        return cleaned.isEmpty() ? org.trim() : cleaned;
    }
}
