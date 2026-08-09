package com.modula.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import com.modula.band.Region;

/**
 * What the radio remembers between sessions: where it was tuned and how it was set up.
 *
 * <p>Pure. {@link ConfigStore} owns the file.
 *
 * <p>{@link #fromProperties} is lenient <b>per field</b> — an unreadable or missing value falls back
 * to its default rather than discarding the whole file. Losing one setting is a much better failure
 * than a radio that refuses to open because its config has one bad line, and it is also what lets a
 * new field be added without invalidating an existing file.
 */
public record Settings(
        long frequencyHz,
        Region region,
        String band,
        double volume,
        boolean stereo,
        boolean daylight,
        boolean tray,
        boolean closeToTray,
        boolean updateCheck,
        long lastUpdateCheck,
        String recordingDirectory) {

    public static final Settings DEFAULTS =
            new Settings(101_500_000L, Region.AMERICAS, "FM", 0.7, true, false, true, false, true, 0L, "");

    private static final String KEY_FREQUENCY = "frequency";
    private static final String KEY_REGION = "region";
    private static final String KEY_BAND = "band";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_STEREO = "stereo";
    private static final String KEY_DAYLIGHT = "daylight";
    private static final String KEY_TRAY = "tray";
    private static final String KEY_CLOSE_TO_TRAY = "closeToTray";
    private static final String KEY_UPDATE_CHECK = "updateCheck";
    private static final String KEY_LAST_UPDATE_CHECK = "lastUpdateCheck";
    private static final String KEY_RECORDINGS = "recordings";

    public Settings {
        volume = Math.clamp(volume, 0.0, 1.0);
        if (frequencyHz <= 0) {
            frequencyHz = 101_500_000L;
        }
        if (region == null) {
            region = Region.AMERICAS;
        }
        band = band == null || band.isBlank() ? "FM" : band.strip().toUpperCase(Locale.ROOT);
        recordingDirectory = recordingDirectory == null ? "" : recordingDirectory.strip();
    }

    /** Where recordings go: the configured directory, or {@code ~/Music/Modula} by default. */
    public Path resolveRecordingDirectory() {
        if (!recordingDirectory.isBlank()) {
            return Path.of(recordingDirectory);
        }
        return Path.of(System.getProperty("user.home", "."), "Music", "Modula");
    }

    public Settings withLastUpdateCheck(long epochMillis) {
        return new Settings(
                frequencyHz,
                region,
                band,
                volume,
                stereo,
                daylight,
                tray,
                closeToTray,
                updateCheck,
                epochMillis,
                recordingDirectory);
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty(KEY_FREQUENCY, Long.toString(frequencyHz));
        p.setProperty(KEY_REGION, region.name());
        p.setProperty(KEY_BAND, band);
        p.setProperty(KEY_VOLUME, Double.toString(volume));
        p.setProperty(KEY_STEREO, Boolean.toString(stereo));
        p.setProperty(KEY_DAYLIGHT, Boolean.toString(daylight));
        p.setProperty(KEY_TRAY, Boolean.toString(tray));
        p.setProperty(KEY_CLOSE_TO_TRAY, Boolean.toString(closeToTray));
        p.setProperty(KEY_UPDATE_CHECK, Boolean.toString(updateCheck));
        p.setProperty(KEY_LAST_UPDATE_CHECK, Long.toString(lastUpdateCheck));
        p.setProperty(KEY_RECORDINGS, recordingDirectory);
        return p;
    }

    public static Settings fromProperties(Properties p) {
        if (p == null) {
            return DEFAULTS;
        }
        return new Settings(
                parseLong(p.getProperty(KEY_FREQUENCY), DEFAULTS.frequencyHz()),
                parseRegion(p.getProperty(KEY_REGION)),
                p.getProperty(KEY_BAND, DEFAULTS.band()),
                parseDouble(p.getProperty(KEY_VOLUME), DEFAULTS.volume()),
                parseBoolean(p.getProperty(KEY_STEREO), DEFAULTS.stereo()),
                parseBoolean(p.getProperty(KEY_DAYLIGHT), DEFAULTS.daylight()),
                parseBoolean(p.getProperty(KEY_TRAY), DEFAULTS.tray()),
                parseBoolean(p.getProperty(KEY_CLOSE_TO_TRAY), DEFAULTS.closeToTray()),
                parseBoolean(p.getProperty(KEY_UPDATE_CHECK), DEFAULTS.updateCheck()),
                parseLong(p.getProperty(KEY_LAST_UPDATE_CHECK), 0L),
                p.getProperty(KEY_RECORDINGS, ""));
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
        String t = value.strip();
        return t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false") ? Boolean.parseBoolean(t) : fallback;
    }

    private static Region parseRegion(String value) {
        if (value == null) {
            return DEFAULTS.region();
        }
        try {
            return Region.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULTS.region();
        }
    }
}
