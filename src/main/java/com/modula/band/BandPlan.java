package com.modula.band;

/**
 * A tunable band as a channel grid: frequencies are snapped to {@code anchorHz + k * spacingHz} for
 * integer {@code k} in {@code [0, channelCount)}. Pure — no IO, no state.
 *
 * <p>Tuning is expressed as moves along this grid rather than as free-running Hz, which is what
 * makes the UI feel like a radio instead of a signal analyser.
 */
public record BandPlan(String name, long anchorHz, int spacingHz, int channelCount, Modulation modulation) {

    public BandPlan {
        if (spacingHz <= 0) {
            throw new IllegalArgumentException("spacingHz must be > 0, got " + spacingHz);
        }
        if (channelCount <= 0) {
            throw new IllegalArgumentException("channelCount must be > 0, got " + channelCount);
        }
    }

    /** FM broadcast, 88.1–107.9 MHz (Americas) or 87.5–108.0 MHz (Europe). */
    public static BandPlan fm(Region region) {
        long anchor = region.fmAnchorHz();
        int spacing = region.fmSpacingHz();
        long top = 108_000_000L;
        int count = (int) ((top - anchor) / spacing) + 1;
        return new BandPlan("FM", anchor, spacing, count, Modulation.FM);
    }

    /**
     * AM (medium wave) broadcast. <b>Out of reach of a stock RTL-SDR</b> — the R820T2/R828D tuner
     * bottoms out near 24 MHz. Reaching this band needs an RTL-SDR Blog V3 in direct-sampling mode
     * or an upconverter, which is why {@code IqSource.tunableRange()} exists: the UI asks the source
     * whether a band is reachable rather than tuning into silence.
     */
    public static BandPlan mediumWave(Region region) {
        // 10 kHz spacing in the Americas (530–1700), 9 kHz elsewhere (531–1602).
        return region == Region.AMERICAS
                ? new BandPlan("AM", 530_000L, 10_000, 118, Modulation.AM)
                : new BandPlan("AM", 531_000L, 9_000, 120, Modulation.AM);
    }

    /**
     * Aviation AM, 118–137 MHz.
     *
     * <p>The one AM band a stock dongle can actually reach, since it sits well above the tuner's
     * 24 MHz floor — which makes it the way to use the AM path without a direct-sampling dongle.
     */
    public static BandPlan airband() {
        return new BandPlan("AIR", 118_000_000L, 25_000, 761, Modulation.AM);
    }

    public long minHz() {
        return anchorHz;
    }

    public long maxHz() {
        return anchorHz + (long) (channelCount - 1) * spacingHz;
    }

    public boolean contains(long hz) {
        return hz >= minHz() && hz <= maxHz();
    }

    /** Channel index of the grid point nearest {@code hz}, clamped into the band. */
    public int channelOf(long hz) {
        long k = Math.round((hz - anchorHz) / (double) spacingHz);
        return (int) Math.clamp(k, 0, channelCount - 1L);
    }

    public long frequencyOf(int channel) {
        int c = (int) Math.clamp(channel, 0, channelCount - 1L);
        return anchorHz + (long) c * spacingHz;
    }

    /** Nearest grid frequency to {@code hz}, clamped into the band. */
    public long snap(long hz) {
        return frequencyOf(channelOf(hz));
    }

    /** The next channel up, wrapping to the bottom of the band. */
    public long next(long hz) {
        return frequencyOf((channelOf(hz) + 1) % channelCount);
    }

    /** The next channel down, wrapping to the top of the band. */
    public long previous(long hz) {
        return frequencyOf((channelOf(hz) - 1 + channelCount) % channelCount);
    }
}
