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
        Settings settings = new Settings(89_300_000L, Region.EUROPE, 0.42, false);
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
        assertEquals(1.0, new Settings(101_500_000L, Region.AMERICAS, 9.0, true).volume());
        assertEquals(0.0, new Settings(101_500_000L, Region.AMERICAS, -3.0, true).volume());
    }

    @Test
    void acceptsANullOrEmptyPropertySet() {
        assertEquals(Settings.DEFAULTS, Settings.fromProperties(null));
        assertEquals(Settings.DEFAULTS, Settings.fromProperties(new Properties()));
    }

    @Test
    void storeRoundTripsBothFilesOnDisk() {
        ConfigStore store = new ConfigStore(tempDir.resolve("config"));
        Settings settings = new Settings(97_100_000L, Region.EUROPE, 0.5, false);
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
}
