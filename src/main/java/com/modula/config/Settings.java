package com.modula.config;

import java.util.Properties;

import com.modula.band.Region;

/**
 * What the radio remembers between sessions: where it was tuned and how it was set up.
 *
 * <p>Pure. {@link ConfigStore} owns the file.
 *
 * <p>{@link #fromProperties} is lenient <b>per field</b> — an unreadable or missing value falls back
 * to its default rather than discarding the whole file. Losing one setting is a much better failure
 * than a radio that refuses to open because its config has one bad line.
 */
public record Settings(long frequencyHz, Region region, double volume, boolean stereo) {

    public static final Settings DEFAULTS = new Settings(101_500_000L, Region.AMERICAS, 0.7, true);

    private static final String KEY_FREQUENCY = "frequency";
    private static final String KEY_REGION = "region";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_STEREO = "stereo";

    public Settings {
        volume = Math.clamp(volume, 0.0, 1.0);
        if (frequencyHz <= 0) {
            frequencyHz = DEFAULTS.frequencyHz();
        }
        if (region == null) {
            region = DEFAULTS.region();
        }
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty(KEY_FREQUENCY, Long.toString(frequencyHz));
        properties.setProperty(KEY_REGION, region.name());
        properties.setProperty(KEY_VOLUME, Double.toString(volume));
        properties.setProperty(KEY_STEREO, Boolean.toString(stereo));
        return properties;
    }

    public static Settings fromProperties(Properties properties) {
        if (properties == null) {
            return DEFAULTS;
        }
        return new Settings(
                parseLong(properties.getProperty(KEY_FREQUENCY), DEFAULTS.frequencyHz()),
                parseRegion(properties.getProperty(KEY_REGION)),
                parseDouble(properties.getProperty(KEY_VOLUME), DEFAULTS.volume()),
                parseBoolean(properties.getProperty(KEY_STEREO), DEFAULTS.stereo()));
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.strip();
        return trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")
                ? Boolean.parseBoolean(trimmed)
                : fallback;
    }

    private static Region parseRegion(String value) {
        if (value == null) {
            return DEFAULTS.region();
        }
        try {
            return Region.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULTS.region();
        }
    }
}
