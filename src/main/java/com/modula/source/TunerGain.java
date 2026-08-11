package com.modula.source;

/**
 * Which front-end gain to ask for, and how to pick it from what a tuner actually offers.
 *
 * <p><b>Why a fixed gain rather than the tuner's AGC.</b> The AGC drives the front end to keep the ADC
 * near full scale whatever the signal is doing, which has two consequences that only show up as
 * reception problems. It means the reported signal level says nothing about the station — measured on
 * this receiver, an empty channel read −9.85 dBFS against −9.75 for a weak station, no usable
 * difference at all. And on a strong local station it over-drives an <b>8-bit</b> ADC, whose
 * compression products land right across the multiplex: the 19 kHz pilot, the 38 kHz difference
 * channel and the 57 kHz RDS subcarrier all sit in the debris. That presents as marginal stereo and
 * absent RDS on a station whose meter reads strong, which is a confusing place to start looking.
 *
 * <p>A fixed gain gives up some sensitivity on a genuinely weak station in exchange for a front end
 * that behaves the same way all the time and a level reading that means something.
 *
 * <p><b>{@link #TARGET_TENTHS} is a starting point, not a derived optimum.</b> It is well inside the
 * R820T2's 0–49.6 dB range, low enough to leave the ADC real headroom and high enough not to throw
 * away weak stations. The honest way to refine it is the quieting figure in the status line: park on a
 * station, note the quieting, change this number, compare. That is why the two arrived together.
 *
 * <p>Pure and unit-tested; no device, no FFM.
 */
public final class TunerGain {

    /**
     * Gain to aim for by default, in tenths of a decibel — the unit both librtlsdr and {@code rtl_tcp}
     * use.
     *
     * <p>30 dB. Most of the useful range of an R820T2 is 0–49.6 dB, and the top of it overloads on a
     * local FM station.
     */
    public static final int TARGET_TENTHS = 300;

    /**
     * Overrides the target for one run, in <b>decibels</b>: {@code -Dmodula.tunerGain=40}.
     *
     * <p>A calibration hatch, not a setting — there is still no gain control, and this is not persisted
     * or surfaced. It exists because the right number is a property of an antenna and a transmitter
     * rather than of this program, and finding it means comparing the quieting figure at two or three
     * gains. Editing a constant and rebuilding to do that is a poor way to spend an afternoon.
     *
     * <p>Read once, on first use. A value that is not a number is ignored rather than fatal: a mistyped
     * diagnostic flag should not stop a radio starting.
     */
    public static final String GAIN_PROPERTY = "modula.tunerGain";

    /**
     * The same override as an environment variable: {@code MODULA_TUNER_GAIN=44}.
     *
     * <p>Present because the system property is <b>not reliably reachable</b> from the way this project
     * is actually run. {@code mvn javafx:run} takes its JVM options from the plugin's {@code <options>}
     * block, and {@code -Djavafx.args=…} passes <i>application</i> arguments rather than system
     * properties — so the documented invocation silently had no effect, which was discovered only
     * because the log prints the gain it settled on. An environment variable needs no cooperation from
     * a launcher and works identically for {@code javafx:run}, the fat jar and the packaged app.
     *
     * <p>The property wins where both are set, being the more specific of the two.
     */
    public static final String GAIN_ENVIRONMENT = "MODULA_TUNER_GAIN";

    private static final int CONFIGURED_TENTHS = readConfiguredTenths();

    private TunerGain() {}

    /** The target gain in tenths of a dB, honouring {@link #GAIN_PROPERTY} or {@link #GAIN_ENVIRONMENT}. */
    public static int targetTenths() {
        return CONFIGURED_TENTHS;
    }

    private static int readConfiguredTenths() {
        return configured(System.getProperty(GAIN_PROPERTY))
                .or(() -> configured(System.getenv(GAIN_ENVIRONMENT)))
                .orElse(TARGET_TENTHS);
    }

    private static java.util.Optional<Integer> configured(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        java.util.OptionalInt tenths = parseDecibels(raw);
        return tenths.isPresent() ? java.util.Optional.of(tenths.getAsInt()) : java.util.Optional.empty();
    }

    /**
     * Parses a gain expressed in decibels into tenths.
     *
     * <p>Separate and pure so the leniency is testable without setting a system property, which a test
     * cannot do without leaking into every other test in the JVM.
     */
    static java.util.OptionalInt parseDecibels(String decibels) {
        try {
            double value = Double.parseDouble(decibels.strip());
            if (!Double.isFinite(value) || value < 0.0) {
                return java.util.OptionalInt.empty();
            }
            return java.util.OptionalInt.of((int) Math.round(value * 10.0));
        } catch (NumberFormatException e) {
            return java.util.OptionalInt.empty();
        }
    }

    /**
     * The supported gain closest to {@code targetTenths}.
     *
     * <p>A tuner only accepts values from its own discrete list, and asking for something else is
     * either rejected or silently rounded somewhere we cannot see — so the choice is made here, where
     * it can be tested.
     *
     * <p>On a tie the <b>lower</b> gain wins. The failure this exists to avoid is an over-driven ADC,
     * so when two options are equally close, the one with more headroom is the right default.
     *
     * @param supported the tuner's gains in tenths of a dB, in any order; may be empty
     * @param targetTenths what to aim for
     * @return the closest supported gain, or {@code targetTenths} itself when the list is empty — the
     *     caller has nothing better to go on, and a source that cannot enumerate its gains should not
     *     be handed a silent no-op
     */
    public static int nearest(int[] supported, int targetTenths) {
        if (supported == null || supported.length == 0) {
            return targetTenths;
        }
        int best = supported[0];
        int bestDistance = Math.abs(best - targetTenths);
        for (int candidate : supported) {
            int distance = Math.abs(candidate - targetTenths);
            if (distance < bestDistance || (distance == bestDistance && candidate < best)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The gain this receiver wants, given what the tuner offers. */
    public static int choose(int[] supported) {
        return nearest(supported, targetTenths());
    }

    /** Formats a gain in tenths of a dB for a log line, e.g. {@code 29.7 dB}. */
    public static String describe(int tenths) {
        return "%d.%d dB".formatted(tenths / 10, Math.abs(tenths % 10));
    }
}
