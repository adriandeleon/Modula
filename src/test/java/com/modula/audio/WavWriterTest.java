package com.modula.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavWriterTest {

    private static final int RATE = 48_000;

    /**
     * The header is checked by decoding with the JDK's own reader rather than by asserting bytes: a
     * header can satisfy every field assertion and still be one a real decoder rejects.
     */
    @Test
    void writesAFileTheJdkCanRead(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("take.wav");
        short[] frames = ramp(480);
        try (WavWriter writer = new WavWriter(file, RATE, 2)) {
            writer.write(frames, frames.length);
        }

        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = in.getFormat();
            assertEquals(RATE, (int) format.getSampleRate());
            assertEquals(2, format.getChannels());
            assertEquals(16, format.getSampleSizeInBits());
            assertFalse(format.isBigEndian(), "WAV is little-endian");
            assertEquals(frames.length / 2, in.getFrameLength());
            assertArrayEquals(frames, readSamples(in, frames.length));
        }
    }

    /** Growing the file must keep both sizes honest, not just the first write. */
    @Test
    void sizesCoverEveryWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("long.wav");
        short[] chunk = ramp(200);
        try (WavWriter writer = new WavWriter(file, RATE, 2)) {
            for (int i = 0; i < 7; i++) {
                writer.write(chunk, chunk.length);
            }
        }
        long dataBytes = 7L * chunk.length * 2;
        assertEquals(44 + dataBytes, Files.size(file));
        assertEquals(dataBytes, leInt(file, 40), "data chunk size");
        assertEquals(36 + dataBytes, leInt(file, 4), "RIFF size");
    }

    /** Honours a partial buffer: the audio path hands over a fixed array with a live count. */
    @Test
    void writesOnlyTheGivenCount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("partial.wav");
        try (WavWriter writer = new WavWriter(file, RATE, 2)) {
            writer.write(ramp(1000), 100);
        }
        assertEquals(200, leInt(file, 40));
    }

    /**
     * A file whose writer never closed — a crash, a power cut — must still play. The placeholder
     * sizes are therefore the largest a reader accepts rather than zero, which decodes as silence.
     */
    @Test
    void anAbandonedFileDeclaresPlayableSizes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("orphan.wav");
        byte[] header = WavWriter.header(RATE, 2, Integer.MAX_VALUE - 44);
        Files.write(file, header);
        assertTrue(leInt(file, 40) > 0, "an abandoned file must not claim zero samples");
        assertTrue(leInt(file, 4) > 0, "nor zero total length");
    }

    @Test
    void secondsComeFromTheByteCount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("timed.wav");
        try (WavWriter writer = new WavWriter(file, RATE, 2)) {
            writer.write(new short[RATE * 2], RATE * 2); // one second, stereo
            assertEquals(1.0, writer.seconds(RATE), 1e-9);
        }
    }

    private static short[] ramp(int count) {
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            out[i] = (short) (i * 37 - 5000);
        }
        return out;
    }

    private static short[] readSamples(AudioInputStream in, int samples) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = buffer.getShort();
        }
        return out;
    }

    private static long leInt(Path file, int offset) throws IOException {
        byte[] all = Files.readAllBytes(file);
        return ByteBuffer.wrap(all, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFF_FFFFL;
    }
}
