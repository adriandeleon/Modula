package com.modula.ui;

import java.util.Locale;

/**
 * Formatting for the numbers the receiver reports.
 *
 * <p>Exists for one small reason that shows on every screen: {@code %.0f} on a negative value emits
 * a hyphen-minus, which in a monospace readout is a short, high dash that reads as punctuation
 * rather than as a sign. A true minus (U+2212) is drawn at the same weight and height as the digits
 * beside it, which is the whole point of using an instrument face.
 *
 * <p>Pure, so the substitution is testable rather than asserted by eye.
 */
public final class Readouts {

    /** U+2212. Not a hyphen. */
    public static final char MINUS = '−';

    private Readouts() {}

    /** A signal level, e.g. {@code −16 dBFS}. */
    public static String dbfs(double value) {
        return minus("%.0f".formatted(value)) + " dBFS";
    }

    /** A frequency in MHz to one decimal, e.g. {@code 101.5}. */
    public static String megahertz(long hz) {
        return String.format(Locale.ROOT, "%.1f", hz / 1_000_000.0);
    }

    /** Replaces a leading hyphen-minus with a true minus sign. */
    public static String minus(String text) {
        return text.startsWith("-") ? MINUS + text.substring(1) : text;
    }
}
