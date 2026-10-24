package com.modula.band;

import java.util.Locale;

/**
 * How a frequency is written for a listener: the number on the dial, a preset chip, a file name.
 *
 * <p>Everything used to be megahertz to one decimal, which is right for FM's 100 and 200 kHz grids
 * and wrong for both AM bands. Medium wave steps 9 or 10 kHz, so 1000 to 1040 kHz all read "1.0";
 * airband steps 25 kHz, so 118.000 and 118.025 both read "118.0". A radio whose dial cannot tell two
 * channels apart cannot be tuned by looking at it.
 *
 * <p>Decided by the frequency alone, so a preset or a schedule entry — which carry a frequency but no
 * band — is written the same way the dial writes it. Pure.
 */
public final class Frequencies {

    /** Below this it is medium or short wave, written in kilohertz as the dials always have. */
    private static final long KILOHERTZ_BELOW_HZ = 30_000_000L;

    /** From the top of FM up, the 25 kHz aviation grid needs three decimals. */
    private static final long THREE_DECIMALS_FROM_HZ = 108_100_000L;

    private Frequencies() {}

    /** The number alone: {@code 98.9}, {@code 1010}, {@code 118.025}. */
    public static String digits(long hz) {
        if (hz < KILOHERTZ_BELOW_HZ) {
            return Long.toString(Math.round(hz / 1_000.0));
        }
        String format = hz >= THREE_DECIMALS_FROM_HZ ? "%.3f" : "%.1f";
        return String.format(Locale.ROOT, format, hz / 1_000_000.0);
    }

    /** The unit {@link #digits} is in. */
    public static String unit(long hz) {
        return hz < KILOHERTZ_BELOW_HZ ? "kHz" : "MHz";
    }

    /** Number and unit together, for text that stands on its own: {@code 1010 kHz}. */
    public static String withUnit(long hz) {
        return digits(hz) + " " + unit(hz);
    }

    /**
     * Reads what a listener typed in {@code band}, in that band's own unit — or in megahertz, which
     * any band accepts, so "1.01" still means 1010 kHz on medium wave.
     *
     * @return the frequency in hertz, or -1 if the text is not a number
     */
    public static long parse(String text, BandPlan band) {
        double value;
        try {
            value = Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return -1L;
        }
        if (!(value > 0.0) || Double.isInfinite(value)) {
            return -1L;
        }
        // On a band written in kHz, a number too large to be megahertz is kilohertz: no medium-wave
        // station is above 30 MHz, and none is below 30 kHz.
        boolean kilohertz = band.maxHz() < KILOHERTZ_BELOW_HZ && value >= KILOHERTZ_BELOW_HZ / 1_000_000.0;
        return Math.round(value * (kilohertz ? 1_000.0 : 1_000_000.0));
    }
}
