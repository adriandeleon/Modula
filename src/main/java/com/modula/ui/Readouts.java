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

    /**
     * How far a carrier has quieted the discriminator, in dB — <b>higher is better</b>.
     *
     * <p>This, and not the dBFS figure beside it, is what says whether a station is any good.
     * {@code signalDbfs} is measured after the front end, and with an AGC running it reports the
     * <i>AGC's target</i> rather than the station: measured on this receiver, an empty channel read
     * −9.85 dBFS against −9.75 for a weak station. Multiplex noise has a 45 dB usable range over the
     * same conditions, which is why seek thresholds on it.
     *
     * <p>Expressed as quieting rather than as raw noise dBFS for two reasons. It rises with signal
     * quality where the underlying number falls, and a readout whose good direction is downward gets
     * misread. And this class already refuses to put two different decibel quantities side by side — a
     * second negative dBFS next to the first would be exactly the confusion {@link #volumeDecibels} is
     * written to avoid, whereas dB of suppression is plainly a different kind of number.
     *
     * <p>Anchors, from {@code DemodChain}'s calibration: 0 is an empty channel, about 8 a barely usable
     * station, 24 or more a solid one.
     *
     * <p>Empty when there is nothing to report — which covers an empty channel and AM alike, since
     * envelope detection leaves the measurement at zero and {@code quietingDb} maps that below the floor.
     *
     * @param quietingDb from {@code DemodChain.quietingDb}; deriving it is that class's job, not this one's
     */
    public static String quieting(double quietingDb) {
        if (Double.isNaN(quietingDb) || quietingDb < 1.0) {
            return "";
        }
        return "quieting %.0f dB".formatted(quietingDb);
    }

    /**
     * How wide the stereo image is, when it is neither fully open nor fully closed.
     *
     * <p>Shown only while blended, because that is the state a listener would otherwise have no way to
     * explain: the STEREO indicator reports what the <i>station</i> is transmitting, so on a marginal
     * signal it lights while the audio is deliberately most of the way to mono. Silent at both ends —
     * full stereo needs no comment, and mono is already visible from the indicator.
     */
    public static String stereoBlend(double blend) {
        if (Double.isNaN(blend) || blend <= 0.0 || blend >= 1.0) {
            return "";
        }
        return "stereo %.0f%%".formatted(blend * 100.0);
    }

    /** Offsets smaller than this are not worth a listener's attention, and saying so every time is noise. */
    static final double OFFSET_WORTH_REPORTING_HZ = 5_000.0;

    /**
     * How far off frequency the carrier is, but only once it is far enough off to matter.
     *
     * <p>Reported in both hertz and parts per million because the two answer different questions: the
     * hertz say how much of the multiplex is being pushed against the edge of the channel filter, and
     * the ppm identify the cause as the dongle's crystal rather than the station — a crystal error
     * scales with the tuned frequency, so the same part reads 7 kHz at 100 MHz and almost nothing on
     * medium wave.
     *
     * <p>Empty below {@link #OFFSET_WORTH_REPORTING_HZ} and empty while the estimate is still settling.
     * A well-calibrated dongle never makes this appear; one that does has an explanation for why its
     * stereo and RDS are worse than its mono.
     *
     * @param offsetHz the measured offset, or {@code NaN} if not yet known
     * @param tunedHz where the receiver is tuned, for the ppm
     */
    public static String carrierOffset(double offsetHz, long tunedHz) {
        if (Double.isNaN(offsetHz) || Math.abs(offsetHz) < OFFSET_WORTH_REPORTING_HZ) {
            return "";
        }
        String hz = minus(String.format(Locale.ROOT, "%.1f", offsetHz / 1000.0)) + " kHz";
        if (tunedHz <= 0) {
            return "offset " + hz;
        }
        long ppm = Math.round(Math.abs(offsetHz) / tunedHz * 1_000_000.0);
        return "offset %s (%d ppm)".formatted(hz, ppm);
    }

    /** Replaces a leading hyphen-minus with a true minus sign. */
    public static String minus(String text) {
        return text.startsWith("-") ? MINUS + text.substring(1) : text;
    }
}
