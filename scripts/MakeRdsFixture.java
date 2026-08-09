import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.modula.demod.FmDiscriminator;
import com.modula.demod.PilotTracker;
import com.modula.dsp.FirDesign;
import com.modula.dsp.FirFilter;
import com.modula.radio.DemodChain;
import com.modula.source.SampleFormat;

/**
 * Distils the RDS golden-file fixture from a raw {@code .iq} capture.
 *
 * <pre>
 *   rtl_sdr -f 98900000 -s 1200000 -n 36000000 station.iq
 *   ./mvnw -q compile
 *   java -cp target/classes scripts/MakeRdsFixture.java station.iq 6.0 \
 *        src/test/resources/com/modula/rds/rds-989-baseband.s16
 * </pre>
 *
 * <p>Writes the demodulated 57 kHz baseband at 24 kHz as 16-bit little-endian, which is ~280 KB for
 * six seconds against ~7 MB for the equivalent raw IQ. That is the whole reason the fixture is the
 * baseband rather than the capture: it still covers every layer that has ever broken — symbol
 * timing, differential decoding, block sync, the CRC and group decoding — at a size a repository can
 * carry.
 *
 * <p><b>The filter here must match {@code RdsDemodulator}'s exactly.</b> A fixture distilled through
 * a different front end is not testing production. Learned the hard way: distilling through a wider
 * 4 kHz baseband filter produced a fixture whose spectrum was perfect and which a fixed clock decoded
 * at 3.6%, but which the real demodulator could not lock to at all — the extra 1.7x of noise defeats
 * the bang-bang timing detector, which keys off a single sample.
 *
 * <p>Amplitude is normalised, which is faithful: the symbol detector takes a sign and the timing loop
 * is bang-bang, so neither depends on absolute level.
 */
public class MakeRdsFixture {

    /** Must stay identical to RdsDemodulator's baseband filter. */
    private static final double BASEBAND_CUTOFF_HZ = 2_400.0;

    private static final double BASEBAND_TRANSITION_HZ = 5_600.0;

    private static final int DECIMATION = 10;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: MakeRdsFixture <capture.iq> <seconds> <out.s16>");
            System.exit(2);
        }
        byte[] raw = Files.readAllBytes(Path.of(args[0]));
        int wanted = (int) (Double.parseDouble(args[1]) * DemodChain.IF_RATE / DECIMATION);

        float[] channelTaps = FirDesign.lowPass(
                FirDesign.tapsForTransition(60_000.0 / DemodChain.INPUT_RATE), 100_000.0 / DemodChain.INPUT_RATE);
        FirFilter channelI = new FirFilter(channelTaps, 5);
        FirFilter channelQ = new FirFilter(channelTaps, 5);
        FmDiscriminator discriminator =
                new FmDiscriminator(DemodChain.IF_RATE, FmDiscriminator.BROADCAST_DEVIATION_HZ);

        int pairs = DemodChain.BLOCK_PAIRS;
        int ifCapacity = channelI.outputCapacity(pairs);
        float[] rawI = new float[pairs];
        float[] rawQ = new float[pairs];
        float[] ifI = new float[ifCapacity];
        float[] ifQ = new float[ifCapacity];
        float[] mpx = new float[ifCapacity];
        float[] mixed = new float[ifCapacity];

        PilotTracker tracker = new PilotTracker(DemodChain.IF_RATE, ifCapacity);
        FirFilter lowPass = new FirFilter(
                FirDesign.lowPass(
                        FirDesign.tapsForTransition(BASEBAND_TRANSITION_HZ / DemodChain.IF_RATE),
                        BASEBAND_CUTOFF_HZ / DemodChain.IF_RATE),
                DECIMATION);
        float[] out = new float[lowPass.outputCapacity(ifCapacity)];

        float[] collected = new float[wanted + 4096];
        int at = 0;
        int blockBytes = pairs * 2;
        byte[] block = new byte[blockBytes];
        for (int offset = 0; offset + blockBytes <= raw.length && at < wanted; offset += blockBytes) {
            System.arraycopy(raw, offset, block, 0, blockBytes);
            int p = SampleFormat.u8ToFloat(block, blockBytes, rawI, rawQ);
            int n = channelI.filter(rawI, p, ifI);
            channelQ.filter(rawQ, p, ifQ);
            discriminator.demodulate(ifI, ifQ, n, mpx);
            tracker.process(mpx, n);

            float[] aligned = tracker.alignedMpx();
            float[] carrier = tracker.subcarrier57();
            for (int i = 0; i < n; i++) {
                mixed[i] = aligned[i] * 2f * carrier[i];
            }
            int m = lowPass.filter(mixed, n, out);
            System.arraycopy(out, 0, collected, at, Math.min(m, collected.length - at));
            at += m;
        }
        if (at < wanted) {
            System.err.printf("capture too short: got %.1f s of baseband%n", at * DECIMATION / (double) DemodChain.IF_RATE);
            System.exit(1);
        }

        double peak = 0;
        for (int i = 0; i < wanted; i++) {
            peak = Math.max(peak, Math.abs(collected[i]));
        }
        double scale = 0.9 / peak;

        Path destination = Path.of(args[2]);
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(destination))) {
            for (int i = 0; i < wanted; i++) {
                int value = (int) Math.round(Math.clamp(collected[i] * scale, -1.0, 1.0) * Short.MAX_VALUE);
                stream.write(value & 0xFF);
                stream.write((value >> 8) & 0xFF);
            }
        }
        System.out.printf(
                "wrote %s — %d samples, %.1f s at %d Hz, %.1f KB (baseband peak was %.5f)%n",
                destination, wanted, wanted * DECIMATION / (double) DemodChain.IF_RATE,
                DemodChain.IF_RATE / DECIMATION, Files.size(destination) / 1024.0, peak);
    }
}
