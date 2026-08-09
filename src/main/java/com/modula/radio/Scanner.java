package com.modula.radio;

import com.modula.band.BandPlan;

/**
 * The seek state machine: step along the channel grid, pause on each, stop at the first real station.
 *
 * <p>Pure — no IO, no threads, no clock. It is fed one measurement per block and answers with what
 * to do next, which makes the entire seek behaviour (including walking the whole band and giving up)
 * testable by handing it a list of numbers. {@link RadioEngine} owns the actual retuning.
 *
 * <p>Not thread-safe; it lives on the receive thread alongside the chain.
 */
public final class Scanner {

    public enum Direction {
        UP,
        DOWN
    }

    public enum Action {
        /** Keep listening to the current channel; nothing to do. */
        WAIT,
        /** Move to {@link Step#frequencyHz}. */
        RETUNE,
        /** A station was found here; the scan is over. */
        FOUND,
        /** The whole band was walked with nothing found; returning to where the scan began. */
        EXHAUSTED
    }

    public record Step(Action action, long frequencyHz) {
        static final Step WAIT = new Step(Action.WAIT, 0L);
    }

    private final BandPlan band;
    private final SeekPolicy policy;

    private boolean scanning;
    private boolean firstMovePending;
    private Direction direction = Direction.UP;
    private long startHz;
    private int settled;
    private int visited;

    public Scanner(BandPlan band, SeekPolicy policy) {
        this.band = band;
        this.policy = policy;
    }

    /**
     * Begins a scan from {@code fromHz}. The current channel is deliberately skipped — you are
     * already listening to it, so seek means "find me the next one".
     */
    public void start(long fromHz, Direction direction) {
        this.scanning = true;
        this.firstMovePending = true;
        this.direction = direction;
        this.startHz = fromHz;
        this.settled = 0;
        this.visited = 0;
    }

    public void cancel() {
        scanning = false;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Where the scan began, so an exhausted scan can put the listener back. */
    public long startFrequencyHz() {
        return startHz;
    }

    /**
     * Consults the state machine with one block's worth of measurement.
     *
     * @param currentHz the channel currently tuned
     * @param noiseDbfs this block's multiplex noise level
     */
    public Step onBlock(long currentHz, double noiseDbfs) {
        if (!scanning) {
            return Step.WAIT;
        }
        if (firstMovePending) {
            firstMovePending = false;
            settled = 0;
            return new Step(Action.RETUNE, step(currentHz));
        }

        // Discard the retune transient before believing anything the chain reports.
        if (++settled < policy.settleBlocks()) {
            return Step.WAIT;
        }

        if (policy.isStation(noiseDbfs)) {
            scanning = false;
            return new Step(Action.FOUND, currentHz);
        }

        if (++visited >= band.channelCount()) {
            scanning = false;
            return new Step(Action.EXHAUSTED, startHz);
        }

        settled = 0;
        return new Step(Action.RETUNE, step(currentHz));
    }

    private long step(long fromHz) {
        return direction == Direction.UP ? band.next(fromHz) : band.previous(fromHz);
    }
}
