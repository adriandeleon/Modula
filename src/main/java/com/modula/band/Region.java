package com.modula.band;

/**
 * Broadcast region. One user-facing setting driving two things that must agree: FM channel spacing
 * and the FM de-emphasis time constant.
 */
public enum Region {
    /** North and South America, South Korea: 200 kHz spacing, 75 µs de-emphasis. */
    AMERICAS(200_000, 88_100_000L, 75.0),

    /** Europe, Africa, Asia, Oceania: 100 kHz spacing, 50 µs de-emphasis. */
    EUROPE(100_000, 87_500_000L, 50.0);

    private final int fmSpacingHz;
    private final long fmAnchorHz;
    private final double deemphasisMicros;

    Region(int fmSpacingHz, long fmAnchorHz, double deemphasisMicros) {
        this.fmSpacingHz = fmSpacingHz;
        this.fmAnchorHz = fmAnchorHz;
        this.deemphasisMicros = deemphasisMicros;
    }

    public int fmSpacingHz() {
        return fmSpacingHz;
    }

    /** The lowest channel centre in the FM band; the grid runs upward from here in spacing steps. */
    public long fmAnchorHz() {
        return fmAnchorHz;
    }

    public double deemphasisMicros() {
        return deemphasisMicros;
    }
}
