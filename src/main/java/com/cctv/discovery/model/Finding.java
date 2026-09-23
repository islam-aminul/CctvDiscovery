package com.cctv.discovery.model;

/**
 * An audit observation about a device or stream, shown in the UI and exported
 * to the Findings sheet.
 *
 * @param severity       how urgent the finding is
 * @param category       grouping such as "Security", "Compliance", "Configuration"
 * @param title          short statement of the problem
 * @param detail         evidence (values observed)
 * @param recommendation what to change
 */
public record Finding(Severity severity, String category, String title, String detail, String recommendation) {

    public enum Severity {
        HIGH("High"), MEDIUM("Medium"), LOW("Low"), INFO("Info");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
