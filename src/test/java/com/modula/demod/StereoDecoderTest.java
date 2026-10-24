package com.modula.demod;

import com.modula.TestSignals;
import com.modula.TestSignals.Tone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereoDecoderTest {

    private static final double IF_RATE = 240_000.0;
    private static final double AUDIO_RATE = 48_000.0;
    private static final double AUDIO_CUTOFF_HZ = 15_000.0;

    /** Half a second of multiplex — several times the pilot loop's settling time. */
    private static final int MPX_SAMPLES = 120_000;

    /** Exactly 100 cycles of a 1 kHz tone at 48 kHz, so the correlation suffers no leakage. */
    private static final int ANALYSIS_SAMPLES = 4_800;

    @Test
    void recoversTheDifferenceChannel() {
        Tone left = new Tone(1_000.0, 0.5);
        float[] mpx = TestSignals.stereoMpx(MPX_SAMPLES, IF_RATE, left, Tone.SILENCE);

        PilotTracker tracker = newTracker();
        StereoDecoder decoder = newDecoder();
        tracker.process(mpx, MPX_SAMPLES);
        float[] difference = new float[decoder.outputCapacity(MPX_SAMPLES)];
        int count = decoder.decodeDifference(tracker, difference);

        assertTrue(tracker.isLocked(), "a multiplex carrying a pilot must lock");
        assertEquals(PilotTracker.PILOT_HZ, tracker.pilotHz(), 2.0);

        // With R silent, L−R is just L, carried in the multiplex at 0.45 scale.
        int offset = count - ANALYSIS_SAMPLES;
        var measured = TestSignals.measure(difference, offset, ANALYSIS_SAMPLES, 1_000.0, AUDIO_RATE);
        assertEquals(0.45 * left.amplitude(), measured.amplitude(), 0.45 * left.amplitude() * 0.1);
        assertTrue(measured.purity() > 0.9, "difference channel should be a clean tone, purity " + measured.purity());
    }

    @Test
    void producesNoDifferenceWhenBothChannelsCarryTheSameAudio() {
        Tone same = new Tone(1_000.0, 0.5);
        float[] mpx = TestSignals.stereoMpx(MPX_SAMPLES, IF_RATE, same, same);

        PilotTracker tracker = newTracker();
        StereoDecoder decoder = newDecoder();
        tracker.process(mpx, MPX_SAMPLES);
        float[] difference = new float[decoder.outputCapacity(MPX_SAMPLES)];
        int count = decoder.decodeDifference(tracker, difference);

        assertTrue(tracker.isLocked(), "the pilot is present even when the programme is dual mono");

        int offset = count - ANALYSIS_SAMPLES;
        var measured = TestSignals.measure(difference, offset, ANALYSIS_SAMPLES, 1_000.0, AUDIO_RATE);
        assertTrue(measured.amplitude() < 0.01, "L−R should vanish for dual mono, got " + measured.amplitude());
    }

    @Test
    void doesNotLockToAMonoBroadcast() {
        float[] mpx = TestSignals.monoMpx(MPX_SAMPLES, IF_RATE, new Tone(1_000.0, 0.4));

        PilotTracker tracker = newTracker();
        tracker.process(mpx, MPX_SAMPLES);

        assertFalse(tracker.isLocked(), "a mono broadcast has no pilot and must not report stereo");
    }

    @Test
    void isContinuousAcrossBlockBoundaries() {
        float[] mpx = TestSignals.stereoMpx(MPX_SAMPLES, IF_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE);

        PilotTracker wholeTracker = newTracker();
        StereoDecoder whole = newDecoder();
        wholeTracker.process(mpx, MPX_SAMPLES);
        float[] a = new float[whole.outputCapacity(MPX_SAMPLES)];
        int wholeCount = whole.decodeDifference(wholeTracker, a);

        PilotTracker chunkedTracker = newTracker();
        StereoDecoder chunked = newDecoder();
        float[] b = new float[a.length + 16];
        int chunkedCount = 0;
        int pos = 0;
        for (int chunk : new int[] {997, 12_003, 1, 40_999, 66_000}) {
            float[] slice = new float[chunk];
            System.arraycopy(mpx, pos, slice, 0, chunk);
            chunkedTracker.process(slice, chunk);
            float[] tmp = new float[chunked.outputCapacity(chunk)];
            int n = chunked.decodeDifference(chunkedTracker, tmp);
            System.arraycopy(tmp, 0, b, chunkedCount, n);
            chunkedCount += n;
            pos += chunk;
        }

        assertEquals(MPX_SAMPLES, pos, "chunks must cover the multiplex exactly");
        assertEquals(wholeCount, chunkedCount);
        for (int n = 0; n < wholeCount; n++) {
            assertEquals(a[n], b[n], 1e-5f, "block boundary changed the output at sample " + n);
        }
    }

    @Test
    void resetDropsTheLock() {
        float[] mpx = TestSignals.stereoMpx(MPX_SAMPLES, IF_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE);
        PilotTracker tracker = newTracker();
        tracker.process(mpx, MPX_SAMPLES);
        assertTrue(tracker.isLocked());

        tracker.reset();
        assertFalse(tracker.isLocked(), "retuning must not carry the old station's lock across");
    }

    /** The harmonics must be exactly the pilot's second and third, or both decoders drift. */
    @Test
    void derivesBothHarmonicsInPhaseWithThePilot() {
        float[] mpx = TestSignals.stereoMpx(MPX_SAMPLES, IF_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE);
        PilotTracker tracker = newTracker();
        tracker.process(mpx, MPX_SAMPLES);

        float[] second = tracker.subcarrier38();
        float[] third = tracker.subcarrier57();
        float[] thirdQuadrature = tracker.subcarrier57Quadrature();
        int delay = tracker.groupDelaySamples();

        double correlation38 = 0.0;
        double correlation57 = 0.0;
        double correlation57q = 0.0;
        int from = MPX_SAMPLES - 24_000;
        for (int n = from; n < MPX_SAMPLES; n++) {
            // The tracker's reference belongs to the multiplex as it was `delay` samples ago.
            double phase = 2.0 * Math.PI * PilotTracker.PILOT_HZ * (n - delay) / IF_RATE;
            correlation38 += second[n] * Math.sin(2.0 * phase);
            correlation57 += third[n] * Math.sin(3.0 * phase);
            correlation57q += thirdQuadrature[n] * Math.cos(3.0 * phase);
        }
        int count = MPX_SAMPLES - from;
        // The 38 kHz sign is not negotiable — inverting it swaps left and right. The 57 kHz ones are
        // checked by magnitude only, because RDS's differential coding makes carrier polarity moot.
        assertTrue(correlation38 / (count * 0.5) > 0.98, "38 kHz reference is out of phase");
        assertTrue(Math.abs(correlation57) / (count * 0.5) > 0.98, "57 kHz reference is out of phase");
        assertTrue(Math.abs(correlation57q) / (count * 0.5) > 0.98, "57 kHz quadrature is out of phase");
    }

    /**
     * The standard's own wording, checked directly rather than through {@link TestSignals}: the
     * subcarrier "shall cross the time axis with a positive slope simultaneously with each crossing
     * of the time axis by the pilot subcarrier" (FCC §73.322; ITU-R BS.450 says the same).
     *
     * <p>This test exists because the decoder once reconstructed cos(2θ) from the loop's cosine and
     * every other stereo test agreed with it — they were all built on a test signal written to the
     * same convention. On air that reference was in quadrature with the real subcarrier, and stereo
     * quietly decoded as mono. A constant taken from a standard needs a test against the standard.
     */
    @Test
    void subcarrierRisesThroughZeroWhereThePilotDoes() {
        // A bare pilot, written here from the standard rather than borrowed from the helper.
        float[] mpx = new float[MPX_SAMPLES];
        for (int n = 0; n < MPX_SAMPLES; n++) {
            mpx[n] = (float) (0.1 * Math.sin(2.0 * Math.PI * PilotTracker.PILOT_HZ * n / IF_RATE));
        }
        PilotTracker tracker = newTracker();
        tracker.process(mpx, MPX_SAMPLES);
        float[] pilot = tracker.alignedMpx();
        float[] subcarrier = tracker.subcarrier38();

        int crossings = 0;
        for (int n = MPX_SAMPLES - 24_000; n < MPX_SAMPLES; n++) {
            if (!(pilot[n - 1] < 0f && pilot[n] >= 0f)) {
                continue;
            }
            crossings++;
            // Where between the two samples the pilot crosses, and the subcarrier there. Linear
            // interpolation is exact enough: a sinusoid is at its straightest through zero.
            double t = pilot[n - 1] / (double) (pilot[n - 1] - pilot[n]);
            double atCrossing = subcarrier[n - 1] + t * (subcarrier[n] - subcarrier[n - 1]);
            assertEquals(0.0, atCrossing, 0.05, "subcarrier must cross zero with the pilot, sample " + n);
            assertTrue(subcarrier[n] > subcarrier[n - 1], "and with a positive slope, sample " + n);
        }
        assertTrue(crossings > 1_000, "expected about 1900 pilot crossings, saw " + crossings);
    }

    private static PilotTracker newTracker() {
        return new PilotTracker(IF_RATE, MPX_SAMPLES);
    }

    private static StereoDecoder newDecoder() {
        return new StereoDecoder(IF_RATE, AUDIO_RATE, AUDIO_CUTOFF_HZ, MPX_SAMPLES);
    }
}
