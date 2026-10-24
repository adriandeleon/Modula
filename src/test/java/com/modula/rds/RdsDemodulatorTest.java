package com.modula.rds;

import com.modula.TestSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Symbol recovery on its own, from a baseband built here — no multiplex, no carrier. */
class RdsDemodulatorTest {

    private static final double BASEBAND_RATE = 24_000.0;
    private static final int SECONDS = 6;

    /**
     * A clean bi-phase signal whose first sample falls on a symbol's midpoint, so the timing loop
     * starts with its boundaries on the mid-symbol crossings — half a symbol out.
     *
     * <p>That is a stable place to sit: between two equal symbols there is a crossing too, and the
     * loop's error averages to zero. Off air, whether decoding started there was luck: one capture
     * decoded 214 groups or none depending only on where in it the receiver began. The demodulator
     * now notices the half-symbol sums disagreeing with the difference and slips half a symbol.
     */
    @Test
    void escapesAHalfSymbolFalseLock() {
        RdsDecoder decoder = new RdsDecoder();
        int[] groups = {0};
        RdsBlockSync sync = new RdsBlockSync(group -> {
            groups[0]++;
            decoder.accept(group);
        });
        RdsDemodulator demodulator = new RdsDemodulator(240_000.0, 40_960, sync::accept);

        float[] baseband = biphase(RdsGroups.station(0x4D4F, "MODULA", "half a symbol out", 10), 0.5);
        int block = 2_048;
        float[] slice = new float[block];
        for (int at = 0; at + block <= baseband.length; at += block) {
            System.arraycopy(baseband, at, slice, 0, block);
            demodulator.processBaseband(slice, block);
        }

        assertEquals("MODULA", decoder.stationInfo().programService());
        // ~68 groups are sent in six seconds; a decoder stuck half a symbol out decodes none.
        assertTrue(groups[0] > 50, "only %d groups decoded".formatted(groups[0]));
    }

    /**
     * Rectangular bi-phase symbols carrying {@code data}, repeated, starting {@code startSymbols}
     * into the stream — so a fraction puts the first sample partway through a symbol.
     */
    private static float[] biphase(boolean[] data, double startSymbols) {
        double samplesPerSymbol = BASEBAND_RATE / RdsDemodulator.SYMBOL_RATE;
        int count = (int) (BASEBAND_RATE * SECONDS);
        int symbols = (int) (count / samplesPerSymbol + startSymbols) + 2;
        boolean[] transmitted = TestSignals.differentiallyEncode(data, symbols);

        float[] out = new float[count];
        for (int n = 0; n < count; n++) {
            double position = n / samplesPerSymbol + startSymbols;
            int index = (int) position;
            double within = position - index;
            double level = transmitted[index] ? 1.0 : -1.0;
            out[n] = (float) (within < 0.5 ? level : -level);
        }
        return out;
    }
}
