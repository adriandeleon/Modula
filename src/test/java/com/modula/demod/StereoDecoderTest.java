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
        int delay = tracker.groupDelaySamples();

        double correlation38 = 0.0;
        double correlation57 = 0.0;
        int from = MPX_SAMPLES - 24_000;
        for (int n = from; n < MPX_SAMPLES; n++) {
            // The tracker's reference belongs to the multiplex as it was `delay` samples ago.
            double phase = 2.0 * Math.PI * PilotTracker.PILOT_HZ * (n - delay) / IF_RATE;
            correlation38 += second[n] * Math.cos(2.0 * phase);
            correlation57 += third[n] * Math.cos(3.0 * phase);
        }
        int count = MPX_SAMPLES - from;
        assertTrue(correlation38 / (count * 0.5) > 0.98, "38 kHz reference is out of phase");
        assertTrue(correlation57 / (count * 0.5) > 0.98, "57 kHz reference is out of phase");
    }

    private static PilotTracker newTracker() {
        return new PilotTracker(IF_RATE, MPX_SAMPLES);
    }

    private static StereoDecoder newDecoder() {
        return new StereoDecoder(IF_RATE, AUDIO_RATE, AUDIO_CUTOFF_HZ, MPX_SAMPLES);
    }
}
