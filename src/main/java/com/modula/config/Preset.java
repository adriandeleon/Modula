package com.modula.config;

/**
 * A saved station.
 *
 * @param frequencyHz the tuned frequency
 * @param name what to call it; blank is fine, in which case the UI shows the frequency
 */
public record Preset(long frequencyHz, String name) {

    public Preset {
        if (frequencyHz <= 0) {
            throw new IllegalArgumentException("frequencyHz must be > 0, got " + frequencyHz);
        }
        name = name == null ? "" : name.strip();
    }

    public static Preset of(long frequencyHz) {
        return new Preset(frequencyHz, "");
    }

    /** How this preset reads in a list: "101.5 — The Current", or just the frequency if unnamed. */
    public String label() {
        String megahertz = "%.1f".formatted(frequencyHz / 1_000_000.0);
        return name.isEmpty() ? megahertz : megahertz + " — " + name;
    }
}
