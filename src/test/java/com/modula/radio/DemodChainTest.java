package com.modula.radio;

import com.modula.TestSignals;
import com.modula.TestSignals.Tone;
import com.modula.band.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the receive chain against synthesised broadcast signals.
 *
 * <p>This is the payoff of keeping the DSP pure: a whole stereo receiver is verifiable numerically,
 * with no hardware, no sound card and no toolkit. Modulate known audio, run it through the real
 * chain, and assert what comes back out.
 */
class DemodChainTest {

    private static final double TONE_HZ = 1_000.0;

    /** Exactly 100 cycles of a 1 kHz tone at 48 kHz, so the correlation suffers no leakage. */
    private static final int ANALYSIS_FRAMES = 4_800;

    /** Enough multiplex for the pilot loop and its lock detector to settle several times over. */
    private static final int STEREO_BLOCKS = 48;

    private static final double DEEMPHASIS_AT_1K = TestSignals.deemphasisGainAt(TONE_HZ, 75.0);

    @Test
    void recoversAMonoBroadcastOnBothChannels() {
        Tone programme = new Tone(TONE_HZ, 0.4);
        Decoded decoded = run(mono(programme, 24), true);

        assertFalse(decoded.pilotLocked(), "a mono broadcast has no pilot");

        var left = measureTail(decoded.left());
        var right = measureTail(decoded.right());

        assertTrue(left.purity() > 0.95, "expected a clean tone, purity was " + left.purity());
        assertEquals(programme.amplitude() * DEEMPHASIS_AT_1K, left.amplitude(), programme.amplitude() * 0.08);
        assertEquals(left.amplitude(), right.amplitude(), 1e-6, "mono must be identical on both channels");
    }

    /**
     * The headline test: audio sent only to the left channel must come back only on the left. This
     * exercises the pilot band-pass, the PLL's phase accuracy, the double-angle subcarrier
     * reconstruction and the L/R matrix all at once — and separation is the one measurement that
     * degrades gracefully when any of them is subtly wrong, rather than failing outright.
     */
    @Test
    void separatesTheChannelsOfAStereoBroadcast() {
        Tone left = new Tone(TONE_HZ, 0.5);
        Decoded decoded = run(stereo(left, Tone.SILENCE, STEREO_BLOCKS), true);

        assertTrue(decoded.pilotLocked(), "a stereo broadcast must report its pilot");

        double wanted = measureTail(decoded.left()).amplitude();
        double leakage = measureTail(decoded.right()).amplitude();
        double separation = TestSignals.separationDb(wanted, leakage);

        assertTrue(separation > 20.0, "channel separation was only %.1f dB".formatted(separation));

        // The multiplex carries each channel at 0.45, and the matrix sums sum and difference.
        assertEquals(0.9 * left.amplitude() * DEEMPHASIS_AT_1K, wanted, 0.9 * left.amplitude() * 0.12);
    }

    @Test
    void routesAudioToTheRightChannelToo() {
        Tone right = new Tone(TONE_HZ, 0.5);
        Decoded decoded = run(stereo(Tone.SILENCE, right, STEREO_BLOCKS), true);

        double wanted = measureTail(decoded.right()).amplitude();
        double leakage = measureTail(decoded.left()).amplitude();
        assertTrue(
                TestSignals.separationDb(wanted, leakage) > 20.0,
                "right-channel separation was only %.1f dB".formatted(TestSignals.separationDb(wanted, leakage)));
    }

    /**
     * Forcing mono must fold the stereo broadcast down rather than mute a channel — and the pilot
     * indicator must keep reporting what the station is transmitting, not what we chose to hear.
     */
    @Test
    void forcingMonoFoldsTheChannelsButStillReportsThePilot() {
        Decoded decoded = run(stereo(new Tone(TONE_HZ, 0.5), Tone.SILENCE, STEREO_BLOCKS), false);

        assertTrue(decoded.pilotLocked(), "the indicator should still show the station is in stereo");

        double left = measureTail(decoded.left()).amplitude();
        double right = measureTail(decoded.right()).amplitude();
        assertEquals(left, right, 1e-6, "forced mono must be identical on both channels");
        assertTrue(left > 0.1, "forced mono must still carry the programme, got " + left);
    }

    @Test
    void outputIsAlwaysAWholeNumberOfStereoFrames() {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        byte[] raw = mono(new Tone(TONE_HZ, 0.4), 8);
        short[] out = new short[chain.audioCapacity()];

        int total = 0;
        int blockBytes = DemodChain.BLOCK_PAIRS * 2;
        for (int offset = 0; offset + blockBytes <= raw.length; offset += blockBytes) {
            byte[] block = new byte[blockBytes];
            System.arraycopy(raw, offset, block, 0, blockBytes);
            int n = chain.process(block, blockBytes, out);
            assertEquals(0, n % DemodChain.CHANNELS, "a partial frame would swap the channels downstream");
            total += n;
        }

        int expectedFrames = DemodChain.BLOCK_PAIRS * 8 / (DemodChain.INPUT_RATE / DemodChain.AUDIO_RATE);
        assertTrue(
                Math.abs(total / DemodChain.CHANNELS - expectedFrames) <= 2,
                "expected about %d frames, got %d".formatted(expectedFrames, total / DemodChain.CHANNELS));
    }

    @Test
    void reportsSignalStrengthOfTheSelectedChannel() {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        short[] out = new short[chain.audioCapacity()];
        byte[] block = mono(new Tone(TONE_HZ, 0.4), 1);
        chain.process(block, block.length, out);

        double dbfs = chain.signalDbfs();
        assertTrue(dbfs > -12.0 && dbfs <= 0.0, "a full-scale carrier should read near 0 dBFS, got " + dbfs);
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Decoded(float[] left, float[] right, boolean pilotLocked) {}

    private static byte[] mono(Tone programme, int blocks) {
        int pairs = DemodChain.BLOCK_PAIRS * blocks;
        return TestSignals.fmModulate(
                TestSignals.monoMpx(pairs, DemodChain.INPUT_RATE, programme),
                DemodChain.INPUT_RATE,
                TestSignals.DEVIATION_HZ);
    }

    private static byte[] stereo(Tone left, Tone right, int blocks) {
        int pairs = DemodChain.BLOCK_PAIRS * blocks;
        return TestSignals.fmModulate(
                TestSignals.stereoMpx(pairs, DemodChain.INPUT_RATE, left, right),
                DemodChain.INPUT_RATE,
                TestSignals.DEVIATION_HZ);
    }

    private static Decoded run(byte[] raw, boolean stereoEnabled) {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        chain.setStereoEnabled(stereoEnabled);
        short[] out = new short[chain.audioCapacity()];

        int blockBytes = DemodChain.BLOCK_PAIRS * 2;
        int maxFrames = raw.length / 2 / (DemodChain.INPUT_RATE / DemodChain.AUDIO_RATE) + raw.length / blockBytes;
        float[] left = new float[maxFrames];
        float[] right = new float[maxFrames];

        int frames = 0;
        for (int offset = 0; offset + blockBytes <= raw.length; offset += blockBytes) {
            byte[] block = new byte[blockBytes];
            System.arraycopy(raw, offset, block, 0, blockBytes);
            int n = chain.process(block, blockBytes, out);
            float[] l = TestSignals.channel(out, n, 0, DemodChain.CHANNELS);
            float[] r = TestSignals.channel(out, n, 1, DemodChain.CHANNELS);
            System.arraycopy(l, 0, left, frames, l.length);
            System.arraycopy(r, 0, right, frames, r.length);
            frames += l.length;
        }

        return new Decoded(trim(left, frames), trim(right, frames), chain.isPilotLocked());
    }

    private static float[] trim(float[] x, int count) {
        float[] out = new float[count];
        System.arraycopy(x, 0, out, 0, count);
        return out;
    }

    /** Measures the settled tail, past every filter transient and the pilot loop's acquisition. */
    private static TestSignals.Measurement measureTail(float[] audio) {
        int offset = audio.length - ANALYSIS_FRAMES;
        if (offset < 0) {
            throw new IllegalStateException("not enough audio to analyse: " + audio.length);
        }
        return TestSignals.measure(audio, offset, ANALYSIS_FRAMES, TONE_HZ, DemodChain.AUDIO_RATE);
    }
}
