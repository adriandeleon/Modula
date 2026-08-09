package com.modula.audio;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodersTest {

    private static List<String> argv(RecordingFormat format) {
        return Encoders.argv("ffmpeg", format, 48_000, 2, Path.of("/tmp/take.x"));
    }

    /** The input is raw PCM on stdin, in exactly the shape the audio path already produces. */
    @Test
    void describesTheInputItIsActuallyGiven() {
        List<String> argv = argv(RecordingFormat.MP3);
        assertTrue(argv.containsAll(List.of("-f", "s16le")), argv.toString());
        assertEquals("48000", argv.get(argv.indexOf("-ar") + 1));
        assertEquals("2", argv.get(argv.indexOf("-ac") + 1));
        assertEquals("-", argv.get(argv.indexOf("-i") + 1), "input must be stdin");
    }

    @Test
    void picksACodecPerFormat() {
        assertTrue(argv(RecordingFormat.MP3).contains("libmp3lame"));
        assertTrue(argv(RecordingFormat.MP3).contains(Encoders.MP3_BITRATE));
        assertTrue(argv(RecordingFormat.FLAC).contains("flac"));
        assertFalse(argv(RecordingFormat.FLAC).contains("libmp3lame"));
    }

    /** Without -y ffmpeg asks before overwriting, and a prompt on a pipe is a hang. */
    @Test
    void neverPromptsForAnything() {
        assertTrue(argv(RecordingFormat.FLAC).contains("-y"));
    }

    @Test
    void theDestinationIsLast() {
        List<String> argv = argv(RecordingFormat.FLAC);
        assertEquals(Path.of("/tmp/take.x").toString(), argv.get(argv.size() - 1));
    }

    @Test
    void anOverrideWinsOverTheDefault() {
        assertEquals("/opt/bin/ffmpeg", Encoders.command("/opt/bin/ffmpeg"));
        assertEquals("/opt/bin/ffmpeg", Encoders.command("  /opt/bin/ffmpeg  "));
        assertEquals(Encoders.defaultCommand(), Encoders.command(""));
        assertEquals(Encoders.defaultCommand(), Encoders.command(null));
    }

    /** WAV must always be offered: it is the one that cannot fail for want of a tool. */
    @Test
    void wavIsAlwaysUsable() {
        assertTrue(Encoders.usableFormats("definitely-not-a-real-command").contains(RecordingFormat.WAV));
        Encoders.forgetDetection();
    }

    @Test
    void anUnknownFormatNameFallsBackToWav() {
        assertEquals(RecordingFormat.WAV, RecordingFormat.of("nonsense"));
        assertEquals(RecordingFormat.WAV, RecordingFormat.of(null));
        assertEquals(RecordingFormat.MP3, RecordingFormat.of("mp3"));
        assertEquals(RecordingFormat.FLAC, RecordingFormat.of("  FLAC "));
    }
}
