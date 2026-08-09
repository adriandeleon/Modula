package com.modula.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PllTest {

    private static final double IF_RATE = 240_000.0;
    private static final double PILOT_HZ = 19_000.0;
    private static final double PILOT_AMPLITUDE = 0.1;

    /** Matches {@code StereoDecoder}, so this test documents the configuration actually shipped. */
    private static final double LOOP_BANDWIDTH_HZ = 50.0;

    /**
     * Half a second. Generous: pulling in a frequency offset is a nonlinear process far slower than
     * the loop's small-signal settling time, and at a 20 Hz bandwidth this loop was still ringing
     * ±7 Hz here — which is what drove the shipped bandwidth up to 50 Hz.
     */
    private static final int SETTLE_SAMPLES = 120_000;

    /** 150 ms. A listener should not wait noticeably for stereo to engage after a retune. */
    private static final int ACQUISITION_SAMPLES = 36_000;

    @Test
    void locksToAPilotAtTheStandardInjectionLevel() {
        Pll pll = newPll();
        drive(pll, PILOT_HZ, PILOT_AMPLITUDE, SETTLE_SAMPLES);

        assertTrue(pll.isLocked(), "should lock, detector level was " + pll.lockLevel());
        assertEquals(
                PILOT_AMPLITUDE / 2.0,
                pll.lockLevel(),
                0.01,
                "a locked loop's detector should read about half the input amplitude");
    }

    @Test
    void tracksAPilotOffsetFromNominal() {
        Pll pll = newPll();
        drive(pll, PILOT_HZ + 30.0, PILOT_AMPLITUDE, SETTLE_SAMPLES);

        assertTrue(pll.isLocked());
        assertEquals(PILOT_HZ + 30.0, pll.frequencyHz(), 2.0, "the NCO should follow a drifting transmitter");
    }

    /**
     * The phase matters more than the lock: the 38 kHz subcarrier is suppressed, so its phase comes
     * entirely from here. A loop that locks in frequency but sits at the wrong phase does not fail
     * loudly — it quietly collapses stereo separation.
     */
    @Test
    void reconstructsTheSubcarrierInPhaseWithThePilot() {
        Pll pll = newPll();
        drive(pll, PILOT_HZ, PILOT_AMPLITUDE, SETTLE_SAMPLES);

        int count = 24_000;
        double correlation = 0.0;
        double reference = 0.0;
        for (int n = 0; n < count; n++) {
            int sample = SETTLE_SAMPLES + n;
            double phase = 2.0 * Math.PI * PILOT_HZ * sample / IF_RATE;
            pll.advance(PILOT_AMPLITUDE * Math.cos(phase));
            double expected = Math.cos(2.0 * phase);
            correlation += pll.cosDoubleOut() * expected;
            reference += expected * expected;
        }

        double normalised = correlation / reference;
        assertTrue(
                normalised > 0.99,
                "reconstructed subcarrier correlated only %.4f with the true one".formatted(normalised));
    }

    /**
     * Acquisition speed is a product requirement, not just a test convenience: this is how long
     * after a retune the stereo indicator lights and the difference channel becomes usable.
     */
    @Test
    void acquiresQuicklyEnoughNotToBeNoticed() {
        Pll pll = newPll();
        drive(pll, PILOT_HZ + 30.0, PILOT_AMPLITUDE, ACQUISITION_SAMPLES);

        assertTrue(pll.isLocked(), "should lock within 150 ms, detector level was " + pll.lockLevel());
        assertEquals(PILOT_HZ + 30.0, pll.frequencyHz(), 10.0, "and be roughly on frequency by then");
    }

    @Test
    void doesNotLockToSilence() {
        Pll pll = newPll();
        for (int n = 0; n < SETTLE_SAMPLES; n++) {
            pll.advance(0.0);
        }
        assertFalse(pll.isLocked(), "silence must not read as a pilot");
    }

    @Test
    void doesNotLockToAToneWellOutsideItsRange() {
        Pll pll = newPll();
        drive(pll, 15_000.0, PILOT_AMPLITUDE, SETTLE_SAMPLES);
        assertFalse(pll.isLocked(), "a 15 kHz tone is 4 kHz outside the lock range and must be ignored");
    }

    @Test
    void restsAtItsCentreFrequencyBeforeAnySignal() {
        assertEquals(PILOT_HZ, newPll().frequencyHz(), 1e-6);
    }

    @Test
    void resetReturnsItToTheFreeRunningState() {
        Pll pll = newPll();
        drive(pll, PILOT_HZ + 50.0, PILOT_AMPLITUDE, SETTLE_SAMPLES);
        pll.reset();

        assertFalse(pll.isLocked());
        assertEquals(PILOT_HZ, pll.frequencyHz(), 1e-6);
    }

    private static Pll newPll() {
        return new Pll(IF_RATE, PILOT_HZ, LOOP_BANDWIDTH_HZ, 100.0);
    }

    private static void drive(Pll pll, double hz, double amplitude, int count) {
        for (int n = 0; n < count; n++) {
            pll.advance(amplitude * Math.cos(2.0 * Math.PI * hz * n / IF_RATE));
        }
    }
}
