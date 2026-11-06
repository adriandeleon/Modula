package com.modula.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

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
 *
 * <p><b>Every save replaces the file whole or not at all.</b> Writing in place truncates first, so a
 * save cut short — the shutdown watchdog halting the process, a full disk — used to leave an empty
 * {@code presets.txt}: the whole bank lost to the act of saving it. See {@link #writeAtomically}.
 */
public final class ConfigStore {

    private static final Logger LOG = Logger.getLogger(ConfigStore.class.getName());

    private static final String SETTINGS_FILE = "settings.properties";
    private static final String PRESETS_FILE = "presets.txt";
    private static final String SCHEDULES_FILE = "schedules.txt";

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
        writeAtomically(
                directory.resolve(SETTINGS_FILE),
                writer -> settings.toProperties().store(writer, "Modula settings"));
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
        writeAtomically(directory.resolve(PRESETS_FILE), writer -> writer.write(Presets.format(presets)));
    }

    public List<com.modula.schedule.Recording> loadSchedules() {
        Path file = directory.resolve(SCHEDULES_FILE);
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            return com.modula.schedule.Schedules.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not read schedules", e);
            return List.of();
        }
    }

    public void saveSchedules(List<com.modula.schedule.Recording> schedules) {
        writeAtomically(
                directory.resolve(SCHEDULES_FILE),
                writer -> writer.write(com.modula.schedule.Schedules.format(schedules)));
    }

    /** Produces a file's content. May fail part-way, which is the case {@link #writeAtomically} is for. */
    @FunctionalInterface
    interface Content {
        void writeTo(Writer writer) throws IOException;
    }

    /**
     * Writes {@code file} through a temporary sibling and renames it into place.
     *
     * <p>The temporary file is in the same directory because a rename is only atomic within one file
     * system. It is flushed to the device before the rename, or a power cut could leave the new name
     * pointing at blocks that were never written. Where the file system cannot rename atomically the
     * move falls back to a plain replace, which is still never a truncated file.
     *
     * @return whether the file now holds the new content; on failure the old one is untouched
     */
    static boolean writeAtomically(Path file, Content content) {
        @Nullable Path temp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                    Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                content.writeTo(writer);
                writer.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | UncheckedIOException | SecurityException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp); // gone already after a successful move
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Could not remove " + temp, e);
                }
            }
        }
    }
}
