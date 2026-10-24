package com.modula.radio;

/**
 * When seek should stop on a channel, and how long to wait before judging it.
 *
 * @param noiseThresholdDbfs stop when the chain's squelch measure falls below this; see
 *     {@link DemodChain#squelchDb}, where <b>lower means a stronger station</b>
 * @param settleBlocks blocks to discard after a retune before measuring, covering the tuner's own
 *     settling and the filter transients that follow a {@link DemodChain#reset}
 */
public record SeekPolicy(double noiseThresholdDbfs, int settleBlocks) {

    /**
     * Calibrated against synthesised signals: an empty channel reads about −6 dBFS of multiplex
     * noise, a barely-usable station about −14, a solid one −30 or below. −20 sits in the gap, so
     * seek stops on stations worth listening to and walks past the rest.
     *
     * <p>Synthetic noise is not real RF, so expect to want this adjusted once it has met an actual
     * band — it is a single number precisely so that is easy.
     */
    public static final SeekPolicy DEFAULT = new SeekPolicy(-20.0, 3);

    /**
     * For AM, whose measure is the envelope's ripple: noise alone reads a steady −5.6 dB whatever its
     * level (a Rayleigh envelope's spread is a fixed share of its mean), and a carrier whose
     * modulation averages 30% rms reads about −10.5. −7.5 stops on carriers averaging up to about 42%
     * rms while staying two decibels — many times a block's own scatter — clear of an empty channel. Synthesis only:
     * medium wave is out of a stock dongle's reach and airband is quiet most of the time, so this has
     * not yet met a real band.
     */
    public static final SeekPolicy AM_DEFAULT = new SeekPolicy(-7.5, 3);

    /** The default for a band's modulation. */
    public static SeekPolicy defaultFor(com.modula.band.Modulation modulation) {
        return modulation == com.modula.band.Modulation.AM ? AM_DEFAULT : DEFAULT;
    }

    public SeekPolicy {
        if (settleBlocks < 1) {
            throw new IllegalArgumentException("settleBlocks must be >= 1, got " + settleBlocks);
        }
    }

    /** Whether a measurement looks like a station rather than an empty channel. */
    public boolean isStation(double noiseDbfs) {
        return noiseDbfs < noiseThresholdDbfs;
    }
}
