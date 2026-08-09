package com.modula.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeTaperTest {

    private static double db(double gain) {
        return 20 * Math.log10(gain);
    }

    @Test
    void theEndsAreFixed() {
        assertEquals(0.0, VolumeTaper.gain(0.0));
        assertEquals(1.0, VolumeTaper.gain(1.0));
        assertEquals(0.0, VolumeTaper.position(0.0));
        assertEquals(1.0, VolumeTaper.position(1.0));
    }

    /** The point of the change: half travel used to be −6 dB, which is barely quieter. */
    @Test
    void halfTravelIsAboutTwelveDecibelsDown() {
        double gain = VolumeTaper.gain(0.5);
        assertTrue(db(gain) < -11.0 && db(gain) > -13.0, "half travel is " + db(gain) + " dB");
    }

    /** Exact invertibility is what lets the stored setting keep meaning gain. */
    @Test
    void positionAndGainAreInverses() {
        for (double p = 0.0; p <= 1.0; p += 0.05) {
            assertEquals(p, VolumeTaper.position(VolumeTaper.gain(p)), 1e-9);
        }
        for (double g = 0.0; g <= 1.0; g += 0.05) {
            assertEquals(g, VolumeTaper.gain(VolumeTaper.position(g)), 1e-9);
        }
    }

    /** Louder must always mean louder — a taper that is not monotonic is a broken control. */
    @Test
    void moreTravelIsAlwaysMoreGain() {
        double previous = -1;
        for (double p = 0.0; p <= 1.0; p += 0.01) {
            double gain = VolumeTaper.gain(p);
            assertTrue(gain > previous, "gain fell at position " + p);
            previous = gain;
        }
    }

    /**
     * The bottom of the travel must stay usable. With a linear control the quiet end was compressed
     * into a sliver; the taper should give it real room without making it unreachably quiet.
     */
    @Test
    void theQuietEndHasRoomWithoutVanishing() {
        assertTrue(db(VolumeTaper.gain(0.1)) < -35.0, "10% should be genuinely quiet");
        assertTrue(db(VolumeTaper.gain(0.1)) > -45.0, "but not inaudible");
        assertTrue(db(VolumeTaper.gain(0.25)) < -22.0);
    }

    @Test
    void outOfRangeInputsAreClamped() {
        assertEquals(1.0, VolumeTaper.gain(4.0));
        assertEquals(0.0, VolumeTaper.gain(-1.0));
        assertEquals(1.0, VolumeTaper.position(9.0));
        assertEquals(0.0, VolumeTaper.position(-2.0));
    }
}
