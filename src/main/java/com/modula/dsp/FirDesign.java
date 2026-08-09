package com.modula.dsp;

/**
 * Windowed-sinc FIR coefficient generation. Pure and stateless — every method is a deterministic
 * function of its arguments, so it unit-tests against closed-form expectations.
 */
public final class FirDesign {

    private FirDesign() {}

    /**
     * A linear-phase low-pass FIR, normalised to unity DC gain.
     *
     * @param taps number of coefficients; must be odd, so the impulse response is symmetric about a
     *     real sample (a Type I linear-phase filter) rather than about the gap between two
     * @param cutoff cutoff frequency as a fraction of the sample rate, in (0, 0.5)
     */
    public static float[] lowPass(int taps, double cutoff) {
        if (taps < 3) {
            throw new IllegalArgumentException("taps must be >= 3, got " + taps);
        }
        if ((taps & 1) == 0) {
            throw new IllegalArgumentException("taps must be odd, got " + taps);
        }
        if (!(cutoff > 0.0) || !(cutoff < 0.5)) {
            throw new IllegalArgumentException("cutoff must be in (0, 0.5), got " + cutoff);
        }

        float[] h = new float[taps];
        double center = (taps - 1) / 2.0;
        double sum = 0.0;
        for (int n = 0; n < taps; n++) {
            double x = n - center;
            // sin(2*pi*fc*x)/(pi*x), with the removable singularity at x == 0 evaluated as its limit.
            double sinc = (x == 0.0) ? 2.0 * cutoff : Math.sin(2.0 * Math.PI * cutoff * x) / (Math.PI * x);
            double v = sinc * blackmanHarris(n, taps);
            h[n] = (float) v;
            sum += v;
        }
        for (int n = 0; n < taps; n++) {
            h[n] = (float) (h[n] / sum);
        }
        return h;
    }

    /**
     * A linear-phase high-pass FIR, by spectral inversion of a low-pass.
     *
     * <p>Used to measure the noise above the multiplex, which is what tells a real station from an
     * empty channel — see {@code DemodChain.noiseDbfs}.
     *
     * @param taps number of coefficients; must be odd
     * @param cutoff cutoff frequency as a fraction of the sample rate, in (0, 0.5)
     */
    public static float[] highPass(int taps, double cutoff) {
        float[] h = lowPass(taps, cutoff);
        for (int n = 0; n < taps; n++) {
            h[n] = -h[n];
        }
        h[(taps - 1) / 2] += 1f;
        return h;
    }

    /**
     * A linear-phase band-pass FIR, normalised to unity gain at the centre of its passband.
     *
     * <p>Built as the difference of two low-pass responses. Used to isolate the 19 kHz stereo pilot
     * from the multiplex before the PLL sees it: the pilot sits in a guard band between the mono sum
     * (below 15 kHz) and the difference channel (23–53 kHz), so a band-pass here keeps both out of
     * the loop.
     *
     * @param taps number of coefficients; must be odd
     * @param lowCutoff lower edge as a fraction of the sample rate
     * @param highCutoff upper edge as a fraction of the sample rate
     */
    public static float[] bandPass(int taps, double lowCutoff, double highCutoff) {
        if (taps < 3) {
            throw new IllegalArgumentException("taps must be >= 3, got " + taps);
        }
        if ((taps & 1) == 0) {
            throw new IllegalArgumentException("taps must be odd, got " + taps);
        }
        if (!(lowCutoff > 0.0) || !(highCutoff < 0.5) || lowCutoff >= highCutoff) {
            throw new IllegalArgumentException(
                    "need 0 < lowCutoff < highCutoff < 0.5, got %f and %f".formatted(lowCutoff, highCutoff));
        }

        float[] h = new float[taps];
        double center = (taps - 1) / 2.0;
        for (int n = 0; n < taps; n++) {
            double x = n - center;
            double high = (x == 0.0) ? 2.0 * highCutoff : Math.sin(2.0 * Math.PI * highCutoff * x) / (Math.PI * x);
            double low = (x == 0.0) ? 2.0 * lowCutoff : Math.sin(2.0 * Math.PI * lowCutoff * x) / (Math.PI * x);
            h[n] = (float) ((high - low) * blackmanHarris(n, taps));
        }

        // Unlike a low-pass, the coefficients of a band-pass sum to ~0, so normalise against the
        // response at the band centre instead of at DC.
        double centreFrequency = (lowCutoff + highCutoff) / 2.0;
        double re = 0.0;
        double im = 0.0;
        for (int n = 0; n < taps; n++) {
            double a = -2.0 * Math.PI * centreFrequency * n;
            re += h[n] * Math.cos(a);
            im += h[n] * Math.sin(a);
        }
        double magnitude = Math.hypot(re, im);
        for (int n = 0; n < taps; n++) {
            h[n] = (float) (h[n] / magnitude);
        }
        return h;
    }

    /**
     * Number of taps needed for a given transition width, as a fraction of the sample rate. A rough
     * Blackman-Harris rule of thumb; always rounded up to odd.
     */
    public static int tapsForTransition(double transitionWidth) {
        if (!(transitionWidth > 0.0)) {
            throw new IllegalArgumentException("transitionWidth must be > 0, got " + transitionWidth);
        }
        int n = (int) Math.ceil(5.5 / transitionWidth);
        return (n & 1) == 0 ? n + 1 : n;
    }

    private static double blackmanHarris(int n, int taps) {
        double t = 2.0 * Math.PI * n / (taps - 1);
        return 0.35875 - 0.48829 * Math.cos(t) + 0.14128 * Math.cos(2.0 * t) - 0.01168 * Math.cos(3.0 * t);
    }
}
