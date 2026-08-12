package com.modula.ui;

import com.modula.radio.DemodChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The quieting and stereo-blend readouts, which exist because the two numbers already on the status line
 * do not answer the questions a listener asks of them: the dBFS figure does not measure the station, and
 * the STEREO indicator reports the transmitter rather than what is being heard.
 *
 * <p>Anchors are {@code DemodChain}'s calibration: an empty channel quiets nothing, a barely usable
 * station about 8 dB, a solid one 24 or more.
 */
class ReadoutsQuietingTest {

    @Test
    void anEmptyChannelHasNothingToReport() {
        assertEquals("", Readouts.quieting(DemodChain.quietingDb(DemodChain.EMPTY_CHANNEL_NOISE_DBFS)));
        assertEquals("", Readouts.quieting(0.0), "an empty channel is the zero point, not a reading");
    }

    /**
     * AM leaves multiplex noise at exactly zero, because envelope detection has no discriminator to read
     * it from — which {@code quietingDb} maps below the floor, so nothing is reported rather than a
     * number being invented.
     */
    @Test
    void amReportsNothing() {
        assertEquals("", Readouts.quieting(DemodChain.quietingDb(0.0)));
    }

    @Test
    void anUnmeasuredValueReportsNothing() {
        assertEquals("", Readouts.quieting(Double.NaN));
    }

    @Test
    void aBarelyUsableStationReadsAboutEight() {
        assertEquals("quieting 8 dB", Readouts.quieting(DemodChain.quietingDb(-14.0)));
    }

    @Test
    void aSolidStationReadsAboutTwentyFour() {
        assertEquals("quieting 24 dB", Readouts.quieting(DemodChain.quietingDb(-30.0)));
    }

    /** Higher is better, which is the whole reason it is not reported as raw noise dBFS. */
    @Test
    void aStrongerStationAlwaysReadsHigher() {
        assertEquals("quieting 45 dB", Readouts.quieting(DemodChain.quietingDb(-51.0)));
    }

    // --- the blend -----------------------------------------------------------------------------

    /** Full stereo needs no comment, and mono is already visible from the indicator. */
    @Test
    void theBlendIsSilentAtBothEnds() {
        assertEquals("", Readouts.stereoBlend(1.0));
        assertEquals("", Readouts.stereoBlend(0.0));
        assertEquals("", Readouts.stereoBlend(Double.NaN));
    }

    /**
     * The one state a listener could not otherwise explain: STEREO lit while the audio is most of the way
     * to mono, because the station is transmitting a pilot the signal cannot support.
     */
    @Test
    void aBlendedImageSaysHowWideItIs() {
        assertEquals("stereo 40%", Readouts.stereoBlend(0.4));
        assertEquals("stereo 75%", Readouts.stereoBlend(0.75));
    }

    // --- headroom ------------------------------------------------------------------------------

    /** A comfortable front end says nothing, and the silence is the answer to "should I add gain?". */
    @Test
    void plentyOfHeadroomIsNotWorthMentioning() {
        assertEquals("", Readouts.headroom(30.0));
        assertEquals("", Readouts.headroom(Readouts.HEADROOM_WORTH_REPORTING_DB));
        assertEquals("", Readouts.headroom(Double.NaN));
    }

    /**
     * It appears exactly when the front end has become the constraint: with less than the usual ten-decibel
     * step left, raising the gain reaches saturation instead of improving anything.
     */
    @Test
    void aConstrainedFrontEndSaysHowMuchIsLeft() {
        assertEquals("headroom 6 dB", Readouts.headroom(6.0));
        assertEquals("headroom 0 dB", Readouts.headroom(0.0), "no room left at all is the case that matters most");
    }

    /** An overloaded front end reads zero rather than a negative number, which would not mean anything. */
    @Test
    void anOverloadedFrontEndDoesNotReportNegativeRoom() {
        assertEquals("headroom 0 dB", Readouts.headroom(-4.0));
    }
}
