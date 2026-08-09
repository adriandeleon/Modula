package com.modula.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetsTest {

    @Test
    void roundTripsThroughText() {
        List<Preset> presets = List.of(new Preset(89_300_000L, "Radio Uno"), new Preset(101_500_000L, "The Current"));

        List<Preset> parsed = Presets.parse(Presets.format(presets));

        assertEquals(presets, parsed);
    }

    @Test
    void keepsAnUnnamedPreset() {
        List<Preset> parsed = Presets.parse(Presets.format(List.of(Preset.of(97_100_000L))));
        assertEquals(1, parsed.size());
        assertEquals(97_100_000L, parsed.getFirst().frequencyHz());
        assertEquals("", parsed.getFirst().name());
    }

    /** One bad line must cost one preset, not the whole file — a radio has to start. */
    @Test
    void skipsMalformedLinesRatherThanFailing() {
        String text =
                """
                101500000\tGood
                not-a-number\tBad
                \t
                -5\tNegative
                89300000\tAlso good
                """;

        List<Preset> parsed = Presets.parse(text);

        assertEquals(2, parsed.size());
        assertEquals("Good", parsed.getFirst().name());
        assertEquals("Also good", parsed.getLast().name());
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        assertEquals(1, Presets.parse("# my stations\n\n101500000\tOne\n\n").size());
    }

    @Test
    void toleratesAnEmptyOrNullInput() {
        assertTrue(Presets.parse("").isEmpty());
        assertTrue(Presets.parse(null).isEmpty());
    }

    /** A tab in a name would silently split the line in two on the next load. */
    @Test
    void foldsSeparatorsInNamesToSpaces() {
        List<Preset> parsed = Presets.parse(Presets.format(List.of(new Preset(101_500_000L, "A\tB\nC"))));
        assertEquals(1, parsed.size());
        assertEquals("A B C", parsed.getFirst().name());
    }

    @Test
    void savingTheSameFrequencyTwiceReplacesRatherThanDuplicates() {
        List<Preset> presets = Presets.withPreset(List.of(), new Preset(101_500_000L, "Old"));
        presets = Presets.withPreset(presets, new Preset(101_500_000L, "New"));

        assertEquals(1, presets.size());
        assertEquals("New", presets.getFirst().name());
    }

    @Test
    void keepsPresetsOrderedByFrequency() {
        List<Preset> presets = Presets.withPreset(List.of(), Preset.of(107_900_000L));
        presets = Presets.withPreset(presets, Preset.of(88_100_000L));
        presets = Presets.withPreset(presets, Preset.of(101_500_000L));

        assertEquals(88_100_000L, presets.get(0).frequencyHz());
        assertEquals(101_500_000L, presets.get(1).frequencyHz());
        assertEquals(107_900_000L, presets.get(2).frequencyHz());
    }

    @Test
    void removesByFrequency() {
        List<Preset> presets = List.of(Preset.of(88_100_000L), Preset.of(101_500_000L));
        List<Preset> remaining = Presets.withoutFrequency(presets, 88_100_000L);

        assertEquals(1, remaining.size());
        assertFalse(Presets.contains(remaining, 88_100_000L));
        assertTrue(Presets.contains(remaining, 101_500_000L));
    }

    @Test
    void labelsAPresetForTheList() {
        assertEquals("101.5 — The Current", new Preset(101_500_000L, "The Current").label());
        assertEquals("101.5", Preset.of(101_500_000L).label());
    }
}
