package com.modula.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.modula.band.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsThroughProperties() {
        Settings settings = tuned(89_300_000L, Region.EUROPE, 0.42, false);
        assertEquals(settings, Settings.fromProperties(settings.toProperties()));
    }

    /** A corrupt value must cost that one setting, not the whole session. */
    @Test
    void fallsBackPerFieldOnBadValues() {
        Properties properties = new Properties();
        properties.setProperty("frequency", "not-a-number");
        properties.setProperty("region", "ATLANTIS");
        properties.setProperty("volume", "loud");
        properties.setProperty("stereo", "maybe");

        Settings settings = Settings.fromProperties(properties);

        assertEquals(Settings.DEFAULTS, settings);
    }

    @Test
    void keepsTheGoodFieldsAlongsideABadOne() {
        Properties properties = new Properties();
        properties.setProperty("frequency", "89300000");
        properties.setProperty("region", "nonsense");

        Settings settings = Settings.fromProperties(properties);

        assertEquals(89_300_000L, settings.frequencyHz(), "a valid field must survive an invalid neighbour");
        assertEquals(Region.AMERICAS, settings.region());
    }

    @Test
    void clampsVolumeIntoRange() {
        assertEquals(1.0, tuned(101_500_000L, Region.AMERICAS, 9.0, true).volume());
        assertEquals(0.0, tuned(101_500_000L, Region.AMERICAS, -3.0, true).volume());
    }

    @Test
    void acceptsANullOrEmptyPropertySet() {
        assertEquals(Settings.DEFAULTS, Settings.fromProperties(null));
        assertEquals(Settings.DEFAULTS, Settings.fromProperties(new Properties()));
    }

    @Test
    void storeRoundTripsBothFilesOnDisk() {
        ConfigStore store = new ConfigStore(tempDir.resolve("config"));
        Settings settings = tuned(97_100_000L, Region.EUROPE, 0.5, false);
        List<Preset> presets = List.of(new Preset(89_300_000L, "One"), Preset.of(101_500_000L));

        store.saveSettings(settings);
        store.savePresets(presets);

        assertEquals(settings, store.loadSettings());
        assertEquals(presets, store.loadPresets());
    }

    /** A first run has no config directory at all, and must not be a failure. */
    @Test
    void loadsDefaultsWhenNothingHasBeenSavedYet() {
        ConfigStore store = new ConfigStore(tempDir.resolve("never-written"));
        assertEquals(Settings.DEFAULTS, store.loadSettings());
        assertTrue(store.loadPresets().isEmpty());
    }

    /** The four fields these tests care about; the rest ride their defaults. */
    private static Settings tuned(long frequencyHz, Region region, double volume, boolean stereo) {
        return Settings.DEFAULTS.withSession(frequencyHz, region, "FM", volume, stereo, false);
    }

    /** Every field must survive the file, or a setting silently reverts on the next launch. */
    @Test
    void everyFieldRoundTrips() {
        assertEquals(EVERYTHING_SET, Settings.fromProperties(EVERYTHING_SET.toProperties()));
    }

    /** No field at its default, so a wither that drops one to its default cannot hide. */
    private static final Settings EVERYTHING_SET = new Settings(
            88_100_000L,
            Region.EUROPE,
            "AIR",
            0.31,
            false,
            true,
            false,
            true,
            false,
            12_345L,
            "/tmp/tapes",
            "FLAC",
            "/opt/ffmpeg/bin/ffmpeg");

    /**
     * Each wither changes its own field and carries every other one.
     *
     * <p>The bug this guards: an older eleven-field constructor filled in the recording format and
     * encoder as WAV and blank, and every call site that used it — ticking Daylight, changing the
     * folder, pressing Stop — silently reset a listener's FLAC or MP3 choice. Compared through the
     * persisted form, which is what the listener actually loses.
     */
    @Test
    void everyWitherChangesOnlyItsOwnField() {
        assertOnlyChanges(EVERYTHING_SET.withDaylight(false), "daylight");
        assertOnlyChanges(EVERYTHING_SET.withTray(true), "tray");
        assertOnlyChanges(EVERYTHING_SET.withCloseToTray(false), "closeToTray");
        assertOnlyChanges(EVERYTHING_SET.withUpdateCheck(true), "updateCheck");
        assertOnlyChanges(EVERYTHING_SET.withLastUpdateCheck(99L), "lastUpdateCheck");
        assertOnlyChanges(EVERYTHING_SET.withRecordingDirectory("/srv/radio"), "recordings");
        assertOnlyChanges(EVERYTHING_SET.withRecordingFormat("MP3"), "recordingFormat");
        assertOnlyChanges(EVERYTHING_SET.withEncoderPath("ffmpeg"), "encoderPath");
        assertOnlyChanges(
                EVERYTHING_SET.withSession(101_500_000L, Region.AMERICAS, "FM", 0.9, true, false),
                "frequency",
                "region",
                "band",
                "volume",
                "stereo",
                "daylight");
    }

    private static void assertOnlyChanges(Settings changed, String... keys) {
        Properties before = EVERYTHING_SET.toProperties();
        Properties after = changed.toProperties();
        java.util.Set<String> expected = java.util.Set.of(keys);
        for (String key : before.stringPropertyNames()) {
            if (expected.contains(key)) {
                assertTrue(!before.getProperty(key).equals(after.getProperty(key)), key + " should have changed");
            } else {
                assertEquals(before.getProperty(key), after.getProperty(key), key + " must be carried, not reset");
            }
        }
    }

    /** Recordings land somewhere sensible when the listener has never chosen. */
    @Test
    void recordingDirectoryDefaultsUnderTheHomeDirectory() {
        Path resolved = Settings.DEFAULTS.resolveRecordingDirectory();
        assertTrue(resolved.isAbsolute());
        assertEquals("Modula", resolved.getFileName().toString());
    }

    @Test
    void aConfiguredRecordingDirectoryWins() {
        assertEquals(
                Path.of("/tmp/tapes"),
                Settings.DEFAULTS.withRecordingDirectory("/tmp/tapes").resolveRecordingDirectory());
    }

    /** An unknown band name falls back rather than leaving the radio unable to open. */
    @Test
    void theBandIsNormalised() {
        assertEquals("FM", Settings.fromProperties(new Properties()).band());
        assertEquals(
                "AM",
                Settings.DEFAULTS
                        .withSession(101_500_000L, Region.AMERICAS, " am ", 0.5, true, false)
                        .band());
    }
}
