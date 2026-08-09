package com.modula.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing the preset list. Pure — the text goes in and out, {@link ConfigStore} owns the
 * file.
 *
 * <p>The format is one preset per line, {@code <frequencyHz>\t<name>}. Deliberately plain: a preset
 * list is the one piece of state a user might reasonably want to edit by hand or copy between
 * machines, and a tab-separated line is legible in any editor without pulling in a JSON parser for
 * two fields.
 *
 * <p><b>Parsing is lenient by design.</b> A malformed line is skipped rather than thrown on: losing
 * one bad entry is a far better failure than a radio that will not start because its preset file has
 * a stray character in it.
 */
public final class Presets {

    private static final char SEPARATOR = '\t';

    private Presets() {}

    public static String format(List<Preset> presets) {
        StringBuilder text = new StringBuilder();
        for (Preset preset : presets) {
            // A tab or newline in a name would corrupt the line structure, so fold them to spaces.
            String safe = preset.name().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            text.append(preset.frequencyHz()).append(SEPARATOR).append(safe).append('\n');
        }
        return text.toString();
    }

    public static List<Preset> parse(String text) {
        List<Preset> presets = new ArrayList<>();
        if (text == null) {
            return presets;
        }
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf(SEPARATOR);
            String frequency = separator < 0 ? trimmed : trimmed.substring(0, separator);
            String name = separator < 0 ? "" : trimmed.substring(separator + 1);
            try {
                long hz = Long.parseLong(frequency.strip());
                if (hz > 0) {
                    presets.add(new Preset(hz, name));
                }
            } catch (NumberFormatException ignored) {
                // Skip the bad line; one lost preset beats a radio that will not start.
            }
        }
        return presets;
    }

    /**
     * Adds a preset, replacing any existing one on the same frequency, and keeps the list ordered by
     * frequency — which is how a listener thinks about the band.
     */
    public static List<Preset> withPreset(List<Preset> presets, Preset added) {
        List<Preset> out = new ArrayList<>(presets.size() + 1);
        for (Preset preset : presets) {
            if (preset.frequencyHz() != added.frequencyHz()) {
                out.add(preset);
            }
        }
        out.add(added);
        out.sort((a, b) -> Long.compare(a.frequencyHz(), b.frequencyHz()));
        return out;
    }

    public static List<Preset> withoutFrequency(List<Preset> presets, long frequencyHz) {
        List<Preset> out = new ArrayList<>(presets.size());
        for (Preset preset : presets) {
            if (preset.frequencyHz() != frequencyHz) {
                out.add(preset);
            }
        }
        return out;
    }

    public static boolean contains(List<Preset> presets, long frequencyHz) {
        return presets.stream().anyMatch(p -> p.frequencyHz() == frequencyHz);
    }
}
