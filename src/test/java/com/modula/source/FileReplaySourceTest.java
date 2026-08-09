package com.modula.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.modula.band.Region;
import com.modula.radio.DemodChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the source layer end to end: a captured {@code .iq} file driven through the real chain,
 * exactly as {@link com.modula.radio.RadioEngine} drives a live dongle.
 *
 * <p>Once a real capture exists (<code>rtl_sdr -f 101500000 -s 1200000 -n 12000000 station.iq</code>),
 * the same shape becomes a golden-file regression over the decoded audio — which is the only
 * practical way to catch a DSP regression that is audible but not obviously wrong on a synthetic tone.
 */
class FileReplaySourceTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysACaptureThroughTheChain(@TempDir Path dir) throws IOException {
        int pairs = DemodChain.BLOCK_PAIRS * 6;
        Path capture = dir.resolve("synthetic.iq");
        Files.write(capture, syntheticFm(pairs));

        DemodChain chain = new DemodChain(Region.AMERICAS);
        short[] out = new short[chain.audioCapacity()];
        byte[] block = new byte[DemodChain.BLOCK_PAIRS * 2];

        int audioSamples = 0;
        double peak = 0.0;
        try (FileReplaySource source = FileReplaySource.forTest(capture, DemodChain.INPUT_RATE)) {
            source.open();
            for (int n; (n = source.read(block)) == block.length; ) {
                int count = chain.process(block, n, out);
                audioSamples += count;
                for (int i = 0; i < count; i++) {
                    peak = Math.max(peak, Math.abs(out[i] / (double) Short.MAX_VALUE));
                }
            }
        }

        int expected = pairs / (DemodChain.INPUT_RATE / DemodChain.AUDIO_RATE) * DemodChain.CHANNELS;
        assertTrue(
                Math.abs(audioSamples - expected) <= 12,
                "expected about %d samples, got %d".formatted(expected, audioSamples));
        assertTrue(peak > 0.2, "the replayed capture should produce real audio, peaked at " + peak);
    }

    @Test
    void reportsShortReadAtEndOfFile() throws IOException {
        Path capture = tempDir.resolve("tiny.iq");
        Files.write(capture, new byte[100]);

        try (FileReplaySource source = FileReplaySource.forTest(capture, DemodChain.INPUT_RATE)) {
            source.open();
            assertEquals(100, source.read(new byte[1000]), "a short read signals the end of the capture");
            assertEquals(0, source.read(new byte[1000]), "and zero once exhausted");
        }
    }

    @Test
    void loopingRefillsFromTheStart() throws IOException {
        Path capture = tempDir.resolve("loop.iq");
        Files.write(capture, syntheticFm(500));

        try (FileReplaySource source = new FileReplaySource(capture, DemodChain.INPUT_RATE, false, true)) {
            source.open();
            byte[] buf = new byte[1600];
            assertEquals(buf.length, source.read(buf), "a looping source always fills the buffer");
        }
    }

    /** A carrier swept by a 1 kHz tone, quantised the way the dongle delivers it. */
    private static byte[] syntheticFm(int pairs) {
        byte[] raw = new byte[pairs * 2];
        double phase = 0.0;
        for (int n = 0, b = 0; n < pairs; n++, b += 2) {
            double instantaneous = 30_000.0 * Math.sin(2.0 * Math.PI * 1_000.0 * n / DemodChain.INPUT_RATE);
            phase += 2.0 * Math.PI * instantaneous / DemodChain.INPUT_RATE;
            raw[b] = (byte) Math.clamp(Math.round(Math.cos(phase) * 127.5 + 127.5), 0, 255);
            raw[b + 1] = (byte) Math.clamp(Math.round(Math.sin(phase) * 127.5 + 127.5), 0, 255);
        }
        return raw;
    }
}
