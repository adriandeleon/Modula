package com.modula.audio;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives a real encoder. Skips when ffmpeg is absent, which on a CI runner it may well be — the
 * argv is covered by {@link EncodersTest} either way, but only this proves the pipe works.
 */
class EncodedRecordingTest {

    private static final int RATE = 48_000;

    private static short[] tone(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i += 2) {
            short v = (short) (Math.sin(i * 2 * Math.PI * 440 / RATE) * 8000);
            pcm[i] = v;
            pcm[i + 1] = v;
        }
        return pcm;
    }

    private void encodes(RecordingFormat format, @TempDir Path dir) throws Exception {
        assumeTrue(Encoders.isAvailable(""), "ffmpeg not installed");
        RecordingSink sink = new RecordingSink(new NullSink(), RATE, 2);
        Path file = sink.start(dir, "TEST", format, "");
        assertEquals(format.extension(), file.getFileName().toString().replaceAll(".*\\.", ""));

        short[] pcm = tone(RATE); // half a second of stereo
        for (int i = 0; i < 4; i++) {
            sink.write(pcm, pcm.length);
        }
        Path done = sink.stop();

        assertTrue(Files.exists(done), "no file was produced");
        assertTrue(Files.size(done) > 512, "file is implausibly small: " + Files.size(done));
        assertTrue(decodable(done), "the file exists but will not decode: " + done);
    }

    /**
     * Decodes the file back with ffmpeg and discards the result.
     *
     * <p>The JDK reads WAV, AU and AIFF and nothing else, so it cannot check these. Size alone is not
     * enough either: an encoder killed before it finishes its container leaves a file that exists, has
     * plausible bytes, and will not open. A full decode is what proves close() waited.
     */
    private static boolean decodable(Path file) throws Exception {
        Process p = new ProcessBuilder(Encoders.command(""), "-v", "error", "-i", file.toString(), "-f", "null", "-")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        return p.waitFor() == 0;
    }

    @Test
    void writesAPlayableFlac(@TempDir Path dir) throws Exception {
        encodes(RecordingFormat.FLAC, dir);
    }

    @Test
    void writesAnMp3(@TempDir Path dir) throws Exception {
        assumeTrue(Encoders.isAvailable(""), "ffmpeg not installed");
        RecordingSink sink = new RecordingSink(new NullSink(), RATE, 2);
        Path file = sink.start(dir, "TEST", RecordingFormat.MP3, "");
        short[] pcm = tone(RATE);
        for (int i = 0; i < 4; i++) {
            sink.write(pcm, pcm.length);
        }
        sink.stop();
        assertTrue(Files.size(file) > 4_000, "mp3 too small: " + Files.size(file));
        assertTrue(decodable(file), "the mp3 will not decode");
    }

    /** Compression has to actually compress, or the whole feature is pointless. */
    @Test
    void flacIsSubstantiallySmallerThanWav(@TempDir Path dir) throws Exception {
        assumeTrue(Encoders.isAvailable(""), "ffmpeg not installed");
        short[] pcm = tone(RATE * 2);

        RecordingSink wav = new RecordingSink(new NullSink(), RATE, 2);
        Path wavFile = wav.start(dir, "WAV", RecordingFormat.WAV, "");
        wav.write(pcm, pcm.length);
        wav.stop();

        RecordingSink flac = new RecordingSink(new NullSink(), RATE, 2);
        Path flacFile = flac.start(dir, "FLAC", RecordingFormat.FLAC, "");
        flac.write(pcm, pcm.length);
        flac.stop();

        assertTrue(
                Files.size(flacFile) < Files.size(wavFile),
                "flac %d vs wav %d".formatted(Files.size(flacFile), Files.size(wavFile)));
    }

    /** A missing encoder must produce a WAV, not nothing. */
    @Test
    void fallsBackToWavWhenTheEncoderIsMissing(@TempDir Path dir) throws Exception {
        Encoders.forgetDetection();
        RecordingSink sink = new RecordingSink(new NullSink(), RATE, 2);
        Path file = sink.start(dir, "TEST", RecordingFormat.FLAC, "definitely-not-a-real-command");
        sink.write(tone(1024), 1024);
        sink.stop();
        assertTrue(file.toString().endsWith(".wav"), "expected a wav fallback, got " + file);
        assertTrue(sink.failure().contains("ffmpeg"), "the reason must be reported: " + sink.failure());
        Encoders.forgetDetection();
    }

    /** A sink that goes nowhere, so the test exercises recording and not the sound card. */
    private static final class NullSink implements AudioSink {
        @Override
        public void open() {}

        @Override
        public void write(short[] pcm, int count) {}

        @Override
        public void setVolume(double volume) {}

        @Override
        public void close() {}
    }
}
