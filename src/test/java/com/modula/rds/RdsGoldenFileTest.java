package com.modula.rds;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes six seconds of <b>real off-air RDS</b>, recorded from a broadcast station.
 *
 * <p>This test exists because self-consistent tests could not catch the bugs that actually shipped.
 * The block CRC used a transposed polynomial, and every synthetic test passed — encoder and decoder
 * shared the constant, so it round-tripped perfectly and detected every error thrown at it. It was a
 * perfectly good CRC that simply was not RDS's, and only a genuine broadcast could reveal that. Two
 * further faults were the same shape: radio text shorter than the field is terminated early and its
 * remaining segments are never sent, and the station name is often scrolled across several frames.
 * Both are permitted by the specification and neither occurs in signals we generate ourselves.
 *
 * <p>The fixture is the demodulated 57 kHz baseband at 24 kHz rather than raw IQ, which keeps it to
 * 281 KB instead of 7 MB. So it covers symbol timing, differential decoding, block synchronisation,
 * the CRC and group decoding — every layer that has ever broken — but not the channel filter, the
 * FM discriminator or the pilot-derived carrier recovery above it. Those are covered by
 * {@code RdsEndToEndTest} and by the stereo separation measurement.
 *
 * <p>Recorded from 98.9 MHz with {@code rtl_sdr -f 98900000 -s 1200000}, distilled by the extractor
 * described in CLAUDE.md. Amplitude is normalised, which is faithful because the symbol detector
 * takes a sign and the timing loop is bang-bang — neither depends on absolute level.
 */
class RdsGoldenFileTest {

    private static final String FIXTURE = "rds-989-baseband.s16";

    /** The station this was recorded from: a Spanish-language broadcaster scrolling its name. */
    private static final int EXPECTED_PI = 0x9890;

    private static final String EXPECTED_RADIO_TEXT = "ESCUCHAS D99";

    @Test
    void decodesTheStationFromARealBroadcast() throws IOException {
        Decoded decoded = decode();

        assertEquals(EXPECTED_PI, decoded.info().programIdentification(), "programme identification");
        assertEquals(EXPECTED_RADIO_TEXT, decoded.info().radioText(), "radio text");
        assertTrue(decoded.synced(), "should have achieved block synchronisation");
    }

    /**
     * The name is scrolled: the station cycles two frames that together read "ESCUCHAS D99". Both
     * must appear, and — the part that regressed — neither may be spliced from the other's segments.
     */
    @Test
    void followsTheScrollingStationNameWithoutSplicingFrames() throws IOException {
        Set<String> names = decode().names();

        assertEquals(
                Set.of("ESCUCHAS", "D99"),
                names,
                "exactly the station's two frames, and nothing spliced from their segments");
    }

    /** Six seconds at 11.4 groups per second, less acquisition, should yield a healthy count. */
    @Test
    void recoversGroupsAtRoughlyTheBroadcastRate() throws IOException {
        long groups = decode().groups();
        assertTrue(groups > 30, "only %d groups in six seconds — bit recovery has regressed".formatted(groups));
    }

    // --- harness -------------------------------------------------------------------------------

    private record Decoded(StationInfo info, Set<String> names, boolean synced, long groups) {}

    private Decoded decode() throws IOException {
        float[] baseband = readFixture();

        RdsDecoder decoder = new RdsDecoder();
        Set<String> names = new LinkedHashSet<>();
        RdsBlockSync sync = new RdsBlockSync(group -> {
            decoder.accept(group);
            String name = decoder.stationInfo().programService();
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        });
        RdsDemodulator demodulator = new RdsDemodulator(240_000.0, 4_096, sync::accept);

        // Feed it in blocks, so block-boundary state is exercised exactly as it is live.
        int block = 2_400;
        float[] slice = new float[block];
        for (int off = 0; off + block <= baseband.length; off += block) {
            System.arraycopy(baseband, off, slice, 0, block);
            demodulator.processBaseband(slice, block);
        }
        return new Decoded(decoder.stationInfo(), names, sync.isSynced(), sync.groupsDecoded());
    }

    private static float[] readFixture() throws IOException {
        try (InputStream in = RdsGoldenFileTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "missing test fixture " + FIXTURE);
            byte[] bytes = in.readAllBytes();
            float[] samples = new float[bytes.length / 2];
            try (DataInputStream ignored = new DataInputStream(InputStream.nullInputStream())) {
                for (int n = 0, b = 0; n < samples.length; n++, b += 2) {
                    int value = (short) ((bytes[b] & 0xFF) | (bytes[b + 1] << 8)); // little-endian
                    samples[n] = value / 32767f;
                }
            }
            return samples;
        }
    }
}
