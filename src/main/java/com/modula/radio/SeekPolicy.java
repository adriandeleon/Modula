package com.modula.radio;

/**
 * When seek should stop on a channel, and how long to wait before judging it.
 *
 * @param noiseThresholdDbfs stop when the multiplex noise falls below this; see
 *     {@link DemodChain#noiseDbfs}, where <b>lower means a stronger station</b>
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
