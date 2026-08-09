package com.modula;

/**
 * Synthesis and measurement helpers shared by the DSP tests.
 *
 * <p>Being able to build a broadcast-accurate signal in a few lines is what makes the whole receiver
 * testable without hardware — and being able to measure one tone's amplitude and spectral purity is
 * what turns "it sounds right" into an assertion.
 */
public final class TestSignals {

    /** Peak deviation of broadcast FM. */
    public static final double DEVIATION_HZ = 75_000.0;

    /** Stereo pilot frequency and its standard 10% injection level. */
    public static final double PILOT_HZ = 19_000.0;

    public static final double PILOT_AMPLITUDE = 0.1;

    private TestSignals() {}

    /** A sine of a given frequency and amplitude. */
    public record Tone(double hz, double amplitude) {
        public static final Tone SILENCE = new Tone(1_000.0, 0.0);

        double at(double sampleRate, int n) {
            return amplitude * Math.sin(2.0 * Math.PI * hz * n / sampleRate);
        }
    }

    /** A mono multiplex: just the programme audio, normalised so ±1 is full deviation. */
    public static float[] monoMpx(int count, double sampleRate, Tone programme) {
        float[] mpx = new float[count];
        for (int n = 0; n < count; n++) {
            mpx[n] = (float) programme.at(sampleRate, n);
        }
        return mpx;
    }

    /**
     * A composite stereo multiplex:
     *
     * <pre>
     *   0.45·(L+R)  +  0.45·(L−R)·cos(2π·38k·t)  +  0.1·cos(2π·19k·t)
     * </pre>
     *
     * <p>The 38 kHz subcarrier is generated as the exact second harmonic of the pilot, in phase,
     * which is what the standard specifies and what {@code Pll.cosDoubleOut} reconstructs.
     */
    public static float[] stereoMpx(int count, double sampleRate, Tone left, Tone right) {
        float[] mpx = new float[count];
        for (int n = 0; n < count; n++) {
            double l = left.at(sampleRate, n);
            double r = right.at(sampleRate, n);
            double pilotPhase = 2.0 * Math.PI * PILOT_HZ * n / sampleRate;
            mpx[n] = (float) (0.45 * (l + r)
                    + 0.45 * (l - r) * Math.cos(2.0 * pilotPhase)
                    + PILOT_AMPLITUDE * Math.cos(pilotPhase));
        }
        return mpx;
    }

    /** RDS subcarrier frequency, and a realistic injection level (the standard allows about 4%). */
    public static final double RDS_HZ = 57_000.0;

    public static final double RDS_AMPLITUDE = 0.04;

    /**
     * Adds an RDS subcarrier carrying {@code dataBits}, repeated, to an existing multiplex.
     *
     * <p>Mirrors the transmitter: differentially encode the data, shape each bit as a bi-phase symbol
     * (positive half then negative half, or the reverse), and amplitude-modulate the pilot's third
     * harmonic with it. Generated in phase with the pilot; the decoder must also cope with the
     * quadrature convention, which {@link #quadrature} switches to.
     *
     * <p>The symbols are ideal squares rather than the standard's shaped pulses — enough to exercise
     * timing recovery and everything above it, but not a substitute for real RF.
     */
    public static float[] withRds(float[] mpx, double sampleRate, boolean[] dataBits, boolean quadrature) {
        boolean[] transmitted = differentiallyEncode(dataBits, symbolsFor(mpx.length, sampleRate));
        float[] out = mpx.clone();
        for (int n = 0; n < out.length; n++) {
            double symbolPosition = n * RdsTiming.SYMBOL_RATE / sampleRate;
            int index = (int) symbolPosition;
            double within = symbolPosition - index;

            boolean bit = transmitted[Math.min(index, transmitted.length - 1)];
            double biphase = (within < 0.5 ? 1.0 : -1.0) * (bit ? 1.0 : -1.0);

            double pilotPhase = 2.0 * Math.PI * PILOT_HZ * n / sampleRate;
            double carrier = quadrature ? Math.sin(3.0 * pilotPhase) : Math.cos(3.0 * pilotPhase);
            out[n] += (float) (RDS_AMPLITUDE * biphase * carrier);
        }
        return out;
    }

    /**
     * RDS sends the running XOR of the data, so carrier polarity cannot corrupt it.
     *
     * <p>The encoding runs continuously over the <b>repeating data</b> rather than encoding one copy
     * and repeating the result. Those are not the same: differential encoding adds a bit, so a
     * repeated encoded array has a period one longer than the data it carries, and every repetition
     * slips a bit against the group structure — which desynchronises the block decoder for good.
     */
    public static boolean[] differentiallyEncode(boolean[] data, int symbols) {
        boolean[] out = new boolean[Math.max(symbols, 1)];
        for (int n = 1; n < out.length; n++) {
            out[n] = out[n - 1] ^ data[(n - 1) % data.length];
        }
        return out;
    }

    private static int symbolsFor(int samples, double sampleRate) {
        return (int) (samples * RdsTiming.SYMBOL_RATE / sampleRate) + 4;
    }

    /** Keeps the symbol rate in one place without depending on the production class from here. */
    private static final class RdsTiming {
        static final double SYMBOL_RATE = 1187.5;
    }

    /**
     * FM-modulates a baseband signal into interleaved unsigned-8-bit IQ, exactly as the dongle would
     * deliver it — including the quantisation, so the tests see the same 8-bit noise floor the
     * hardware imposes.
     */
    public static byte[] fmModulate(float[] baseband, double sampleRate, double deviationHz) {
        byte[] raw = new byte[baseband.length * 2];
        double phase = 0.0;
        for (int n = 0, b = 0; n < baseband.length; n++, b += 2) {
            phase += 2.0 * Math.PI * deviationHz * baseband[n] / sampleRate;
            raw[b] = quantise(Math.cos(phase));
            raw[b + 1] = quantise(Math.sin(phase));
        }
        return raw;
    }

    /** Extracts one channel from interleaved PCM, scaled back to ±1. */
    public static float[] channel(short[] pcm, int sampleCount, int channel, int channels) {
        int frames = sampleCount / channels;
        float[] out = new float[frames];
        for (int n = 0; n < frames; n++) {
            out[n] = pcm[n * channels + channel] / (float) Short.MAX_VALUE;
        }
        return out;
    }

    /** A tone's recovered amplitude, and its share of the signal's total power. */
    public record Measurement(double amplitude, double purity) {}

    /**
     * Correlates against a reference tone. Choose {@code count} to span a whole number of cycles, or
     * spectral leakage will understate the amplitude.
     */
    public static Measurement measure(float[] x, int offset, int count, double hz, double sampleRate) {
        double cos = 0.0;
        double sin = 0.0;
        double totalEnergy = 0.0;
        for (int n = 0; n < count; n++) {
            double v = x[offset + n];
            double a = 2.0 * Math.PI * hz * n / sampleRate;
            cos += v * Math.cos(a);
            sin += v * Math.sin(a);
            totalEnergy += v * v;
        }
        double amplitude = 2.0 * Math.hypot(cos, sin) / count;
        double tonePower = amplitude * amplitude / 2.0;
        double totalPower = totalEnergy / count;
        return new Measurement(amplitude, totalPower == 0.0 ? 0.0 : tonePower / totalPower);
    }

    /** Magnitude response of the single-pole de-emphasis at a given frequency. */
    public static double deemphasisGainAt(double hz, double tauMicros) {
        double wt = 2.0 * Math.PI * hz * tauMicros * 1e-6;
        return 1.0 / Math.sqrt(1.0 + wt * wt);
    }

    /** Channel separation in dB, positive when the wanted channel dominates. */
    public static double separationDb(double wanted, double leakage) {
        if (leakage <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return 20.0 * Math.log10(wanted / leakage);
    }

    private static byte quantise(double x) {
        return (byte) Math.clamp(Math.round(x * 127.5 + 127.5), 0, 255);
    }
}
