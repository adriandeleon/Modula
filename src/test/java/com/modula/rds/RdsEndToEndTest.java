package com.modula.rds;

import com.modula.TestSignals;
import com.modula.TestSignals.Tone;
import com.modula.band.Region;
import com.modula.radio.DemodChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole RDS path against a synthesised broadcast: multiplex in, station name and radio text out.
 *
 * <p><b>What this does and does not prove.</b> It exercises the real 57 kHz demodulator, symbol
 * timing recovery, block synchronisation, CRC and group decoding against a signal built the way a
 * transmitter builds one. What it cannot prove is behaviour on real RF: the symbols here are ideal
 * squares rather than the standard's shaped pulses, and there is no multipath, no adjacent-channel
 * splatter and no realistic noise. Treat a pass as "the decoder is coherent", not "RDS works on air".
 */
class RdsEndToEndTest {

    private static final int PI = 0x4D4F;
    private static final String STATION_NAME = "MODULA";
    private static final String RADIO_TEXT = "Now playing: a test signal";
    private static final int PROGRAM_TYPE = 10;

    /** RDS runs at 1187.5 bps and a full name plus radio text is 20 groups of 104 bits — ~1.8 s. */
    private static final int SECONDS = 6;

    @Test
    void decodesAStationNameAndRadioTextFromTheMultiplex() {
        StationInfo info = receive(false);

        assertEquals(STATION_NAME, info.programService());
        assertEquals(RADIO_TEXT, info.radioText());
        assertEquals(PI, info.programIdentification());
        assertEquals(PROGRAM_TYPE, info.programType());
    }

    /** The standard allows the subcarrier in quadrature, and transmitters differ. Both must work. */
    @Test
    void decodesEquallyWellWithTheSubcarrierInQuadrature() {
        StationInfo info = receive(true);

        assertEquals(STATION_NAME, info.programService());
        assertEquals(RADIO_TEXT, info.radioText());
    }

    @Test
    void aStationWithoutRdsDecodesNothingAndSaysSo() {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        run(chain, plainStereo());

        assertFalse(chain.isRdsSynced(), "a broadcast with no subcarrier must not report RDS sync");
        assertFalse(chain.stationInfo().isPresent());
    }

    @Test
    void reportsSyncOnceGroupBoundariesAreFound() {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        run(chain, withRds(false));
        assertTrue(chain.isRdsSynced());
    }

    @Test
    void retuningForgetsThePreviousStation() {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        run(chain, withRds(false));
        assertTrue(chain.stationInfo().isPresent());

        chain.reset();
        assertFalse(chain.stationInfo().isPresent(), "station data must not survive a retune");
        assertFalse(chain.isRdsSynced());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static StationInfo receive(boolean quadrature) {
        DemodChain chain = new DemodChain(Region.AMERICAS);
        run(chain, withRds(quadrature));
        return chain.stationInfo();
    }

    private static float[] plainStereo() {
        int samples = DemodChain.INPUT_RATE * SECONDS;
        return TestSignals.stereoMpx(samples, DemodChain.INPUT_RATE, new Tone(1_000.0, 0.4), Tone.SILENCE);
    }

    private static float[] withRds(boolean quadrature) {
        boolean[] bits = RdsGroups.station(PI, STATION_NAME, RADIO_TEXT, PROGRAM_TYPE);
        return TestSignals.withRds(plainStereo(), DemodChain.INPUT_RATE, bits, quadrature);
    }

    private static void run(DemodChain chain, float[] mpx) {
        byte[] raw = TestSignals.fmModulate(mpx, DemodChain.INPUT_RATE, TestSignals.DEVIATION_HZ);
        short[] out = new short[chain.audioCapacity()];
        int blockBytes = DemodChain.BLOCK_PAIRS * 2;
        byte[] block = new byte[blockBytes];
        for (int offset = 0; offset + blockBytes <= raw.length; offset += blockBytes) {
            System.arraycopy(raw, offset, block, 0, blockBytes);
            chain.process(block, blockBytes, out);
        }
    }
}
