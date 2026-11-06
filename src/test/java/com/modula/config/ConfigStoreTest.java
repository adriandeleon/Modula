package com.modula.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @TempDir
    Path tempDir;

    /**
     * The failure this exists for: a save cut short. Writing in place truncated the file first, so a
     * write that died half-way — a full disk, the shutdown watchdog — left the preset bank empty.
     */
    @Test
    void aSaveThatFailsPartWayLeavesTheOldFileWhole() throws IOException {
        Path file = tempDir.resolve("presets.txt");
        Files.writeString(file, "89.3 One\n101.5 Two\n", StandardCharsets.UTF_8);

        boolean written = ConfigStore.writeAtomically(file, writer -> {
            writer.write("97.1 Ha");
            writer.flush();
            throw new IOException("No space left on device");
        });

        assertFalse(written);
        assertEquals("89.3 One\n101.5 Two\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(List.of(file), listing(), "the temporary file must not be left behind");
    }

    @Test
    void aSaveThatSucceedsReplacesTheFile() throws IOException {
        Path file = tempDir.resolve("presets.txt");
        Files.writeString(file, "old\n", StandardCharsets.UTF_8);

        assertTrue(ConfigStore.writeAtomically(file, writer -> writer.write("new\n")));

        assertEquals("new\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(List.of(file), listing());
    }

    /** A first save creates the directory, as the in-place write did. */
    @Test
    void createsTheDirectoryOnFirstSave() {
        ConfigStore store = new ConfigStore(tempDir.resolve("fresh").resolve(".modula"));
        List<Preset> presets = List.of(new Preset(89_300_000L, "One"));

        store.savePresets(presets);

        assertEquals(presets, store.loadPresets());
    }

    /** Nothing in config throws: an unwritable location costs the save, not the radio. */
    @Test
    void anUnwritableLocationIsNotAnException() throws IOException {
        Path blocker = tempDir.resolve("not-a-directory");
        Files.writeString(blocker, "", StandardCharsets.UTF_8);

        assertFalse(ConfigStore.writeAtomically(blocker.resolve("presets.txt"), writer -> writer.write("x")));
    }

    private List<Path> listing() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.toList();
        }
    }
}
