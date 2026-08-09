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
    /** The slider position as a percentage: what the control is, stated plainly. */
    public static String volumePercent(double gain) {
        return Math.round(Math.clamp(gain, 0.0, 1.0) * 100) + "%";
    }

    /**
     * The same position in decibels, which is what the gain actually does to the signal.
     *
     * <p>{@code 20·log10(gain)}: unity is 0 dB, half amplitude is −6 dB. Zero has no logarithm, so it
     * is reported as muted rather than as {@code -Infinity}.
     *
     * <p>Deliberately written {@code dB} and never {@code dBFS}. The status line already reports the
     * received signal in dBFS, and two decibel figures a few pixels apart that mean different things —
     * how strong the station is, and how loud you asked for it — is worse than showing neither.
     */
    public static String volumeDecibels(double gain) {
        double g = Math.clamp(gain, 0.0, 1.0);
        if (g <= 0.0) {
            return "muted";
        }
        return minus("%.1f dB".formatted(20 * Math.log10(g)));
    }

    public static String megahertz(long hz) {
        return String.format(Locale.ROOT, "%.1f", hz / 1_000_000.0);
    }

    /** Replaces a leading hyphen-minus with a true minus sign. */
    public static String minus(String text) {
        return text.startsWith("-") ? MINUS + text.substring(1) : text;
    }
}
