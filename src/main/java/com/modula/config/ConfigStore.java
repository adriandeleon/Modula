package com.modula.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes the config directory, by default {@code ~/.modula/}.
 *
 * <p>The only impure part of {@code config} — {@link Settings} and {@link Presets} do the parsing and
 * formatting, so all the interesting behaviour is unit-testable without touching a disk.
 *
 * <p><b>Every failure here is non-fatal.</b> A missing, unreadable or unwritable config must never
 * stop the radio from playing: loads fall back to defaults, saves log and shrug. Losing a preset list
 * is annoying; refusing to start because of one is not acceptable for something whose whole job is to
 * make noise when you press the button.
 */
public final class ConfigStore {

    private static final Logger LOG = Logger.getLogger(ConfigStore.class.getName());

    private static final String SETTINGS_FILE = "settings.properties";
    private static final String PRESETS_FILE = "presets.txt";

    private final Path directory;

    public ConfigStore(Path directory) {
        this.directory = directory;
    }

    /** The default location, {@code ~/.modula/}. */
    public static ConfigStore userDefault() {
        return new ConfigStore(Path.of(System.getProperty("user.home", "."), ".modula"));
    }

    public Path directory() {
        return directory;
    }

    public Settings loadSettings() {
        Path file = directory.resolve(SETTINGS_FILE);
        if (!Files.isReadable(file)) {
            return Settings.DEFAULTS;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Could not read " + file + "; using defaults", e);
            return Settings.DEFAULTS;
        }
        return Settings.fromProperties(properties);
    }

    public void saveSettings(Settings settings) {
        Path file = directory.resolve(SETTINGS_FILE);
        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                settings.toProperties().store(writer, "Modula settings");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }

    public List<Preset> loadPresets() {
        Path file = directory.resolve(PRESETS_FILE);
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            return Presets.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + file, e);
            return List.of();
        }
    }

    public void savePresets(List<Preset> presets) {
        Path file = directory.resolve(PRESETS_FILE);
        try {
            Files.createDirectories(directory);
            Files.writeString(file, Presets.format(presets), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }
}
