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
        return new Settings(frequencyHz, region, "FM", volume, stereo, false, true, false, true, 0L, "");
    }

    /** Every field must survive the file, or a setting silently reverts on the next launch. */
    @Test
    void everyFieldRoundTrips() {
        Settings settings = new Settings(
                88_100_000L, Region.EUROPE, "AIR", 0.31, false, true, false, true, false, 12_345L, "/tmp/tapes");
        assertEquals(settings, Settings.fromProperties(settings.toProperties()));
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
                new Settings(101_500_000L, Region.AMERICAS, "FM", 0.5, true, false, true, false, true, 0L, "/tmp/tapes")
                        .resolveRecordingDirectory());
    }

    /** An unknown band name falls back rather than leaving the radio unable to open. */
    @Test
    void theBandIsNormalised() {
        assertEquals("FM", Settings.fromProperties(new Properties()).band());
        assertEquals(
                "AM",
                new Settings(101_500_000L, Region.AMERICAS, " am ", 0.5, true, false, true, false, true, 0L, "")
                        .band());
    }
}
