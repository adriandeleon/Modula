package com.modula.demod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StereoBlendTest {

    /** The block rate the chain actually runs at: 1.2 MSPS in 16384-pair blocks. */
    private static final double BLOCKS_PER_SECOND = 1_200_000.0 / 16_384.0;

    private static final double SOLID = 24.0;
    private static final double MARGINAL = 14.0;

    // --- the ladder ----------------------------------------------------------------------------

    @Test
    void aSolidStationGetsTheFullImage() {
        assertEquals(1.0, StereoBlend.targetFor(SOLID, true, true));
        assertEquals(1.0, StereoBlend.targetFor(StereoBlend.FULL_STEREO_QUIETING_DB, true, true));
    }

    @Test
    void aBarelyUsableStationGetsMono() {
        assertEquals(0.0, StereoBlend.targetFor(StereoBlend.MONO_QUIETING_DB, true, true));
        assertEquals(0.0, StereoBlend.targetFor(0.0, true, true));
    }

    @Test
    void inBetweenItNarrowsProportionally() {
        double midpoint = (StereoBlend.MONO_QUIETING_DB + StereoBlend.FULL_STEREO_QUIETING_DB) / 2.0;
        assertEquals(0.5, StereoBlend.targetFor(midpoint, true, true), 1e-9);
    }

    @Test
    void theTargetNeverLeavesItsRange() {
        for (double quieting = -20.0; quieting <= 60.0; quieting += 0.25) {
            double target = StereoBlend.targetFor(quieting, true, true);
            assertTrue(target >= 0.0 && target <= 1.0, "out of range at " + quieting + ": " + target);
        }
    }

    @Test
    void noPilotMeansMonoHoweverGoodTheSignalLooks() {
        assertEquals(0.0, StereoBlend.targetFor(SOLID, false, true));
    }

    @Test
    void theListenersOwnMonoSwitchWins() {
        assertEquals(0.0, StereoBlend.targetFor(SOLID, true, false));
    }

    /** An unmeasured value must fall to mono rather than through a comparison that is false either way. */
    @Test
    void anUnmeasuredQuietingMeansMono() {
        assertEquals(0.0, StereoBlend.targetFor(Double.NaN, true, true));
    }

    // --- the smoothing -------------------------------------------------------------------------

    /**
     * The blend adopts its first real measurement instead of fading up to it.
     *
     * <p>Both because a fade on every retune is audible, and because the separation this receiver
     * measures is taken from a settled tail — a ramp lasting a time constant would pull that below its
     * floor and make a working blend look like a broken matrix.
     */
    @Test
    void theFirstLockSeedsRatherThanRamps() {
        StereoBlend blend = new StereoBlend(BLOCKS_PER_SECOND);

        assertEquals(1.0, blend.update(SOLID, true, true), 1e-9, "a clean signal should be fully stereo at once");
    }

    @Test
    void blocksBeforeTheFirstLockStayMonoAndDoNotCountAsSeeding() {
        StereoBlend blend = new StereoBlend(BLOCKS_PER_SECOND);
        for (int n = 0; n < 8; n++) {
            assertEquals(0.0, blend.update(SOLID, false, true), 1e-9);
        }

        assertEquals(1.0, blend.update(SOLID, true, true), 1e-9, "the seed belongs to the first block with a pilot");
    }

    /** Once seeded, a change in quality is followed gradually — that is what stops a threshold flapping. */
    @Test
    void aChangeInQualityIsSmoothedRatherThanStepped() {
        StereoBlend blend = new StereoBlend(BLOCKS_PER_SECOND);
        blend.update(SOLID, true, true);

        double afterOneBlock = blend.update(0.0, true, true);
        assertTrue(afterOneBlock > 0.8, "one block must not collapse the image, was " + afterOneBlock);

        for (int n = 0; n < 200; n++) {
            blend.update(0.0, true, true);
        }
        assertTrue(blend.blend() < 0.01, "but it must get there, was " + blend.blend());
    }

    /**
     * The failure this whole class exists to prevent: quality alternating either side of a boundary must
     * not alternate the audio path with it.
     */
    @Test
    void qualityFlappingAcrossTheBoundaryDoesNotFlapTheAudio() {
        StereoBlend blend = new StereoBlend(BLOCKS_PER_SECOND);
        blend.update(MARGINAL, true, true);

        double lowest = 1.0;
        double highest = 0.0;
        for (int n = 0; n < 400; n++) {
            // Alternate either side of the marginal point, block by block, as a real signal would.
            double quieting = MARGINAL + (n % 2 == 0 ? 1.5 : -1.5);
            double current = blend.update(quieting, true, true);
            lowest = Math.min(lowest, current);
            highest = Math.max(highest, current);
        }

        assertTrue(
                highest - lowest < 0.05,
                "the blend swung between %.3f and %.3f — smoothing is not absorbing the alternation"
                        .formatted(lowest, highest));
    }

    @Test
    void resetClosesTheImageSoARetuneDoesNotOpenOnTheOldStation() {
        StereoBlend blend = new StereoBlend(BLOCKS_PER_SECOND);
        blend.update(SOLID, true, true);
        assertEquals(1.0, blend.blend(), 1e-9);

        blend.reset();
        assertEquals(0.0, blend.blend());
        // And it seeds again afterwards rather than ramping from zero.
        assertEquals(1.0, blend.update(SOLID, true, true), 1e-9);
    }

    @Test
    void anImpossibleUpdateRateIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new StereoBlend(0.0));
    }
}
