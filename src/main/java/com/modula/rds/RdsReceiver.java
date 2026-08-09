package com.modula.rds;

import com.modula.demod.PilotTracker;

/**
 * The whole RDS path in one object: multiplex in, station information out.
 *
 * <p>Wires the three stages that each have a single job — {@link RdsDemodulator} recovers bits,
 * {@link RdsBlockSync} finds group boundaries in them, {@link RdsDecoder} turns groups into text.
 * They are separate classes because they fail differently and are testable at different levels, and
 * this exists so callers do not have to know that.
 *
 * <p>Stateful across blocks. Not thread-safe.
 */
public final class RdsReceiver {

    private final RdsDecoder decoder = new RdsDecoder();
    private final RdsBlockSync sync = new RdsBlockSync(decoder::accept);
    private final RdsDemodulator demodulator;

    public RdsReceiver(double ifRate, int maxInput) {
        this.demodulator = new RdsDemodulator(ifRate, maxInput, sync::accept);
    }

    /** Runs one multiplex block through the whole RDS path. */
    public void process(PilotTracker tracker) {
        demodulator.process(tracker);
    }

    public StationInfo stationInfo() {
        return decoder.stationInfo();
    }

    /** Whether group boundaries have been found — i.e. whether this station carries RDS at all. */
    public boolean isSynced() {
        return sync.isSynced();
    }

    public long groupsDecoded() {
        return sync.groupsDecoded();
    }

    /** Which side of the 57 kHz reference the subcarrier turned out to be on. Diagnostic only. */
    public boolean isUsingQuadrature() {
        return demodulator.isUsingQuadrature();
    }

    /** The recovered symbol clock in bits per second; should sit very near 1187.5. Diagnostic only. */
    public double symbolRateHz() {
        return demodulator.symbolRateHz();
    }

    /** Wipes everything. Call on retune — none of it belongs to the new station. */
    public void reset() {
        demodulator.reset();
        sync.reset();
        decoder.reset();
    }
}
