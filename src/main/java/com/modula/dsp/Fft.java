package com.modula.dsp;

/**
 * An in-place radix-2 FFT, and the pieces around it needed to turn IQ into a spectrum.
 *
 * <p>Hand-written rather than pulled from a library: the whole need is one transform for the
 * spectrum strip, at status cadence rather than per sample, and a dependency for sixty lines that
 * would then also need a module descriptor under jlink is a poor trade.
 *
 * <p>Pure and allocation-free — the caller owns every array.
 */
public final class Fft {

    private Fft() {}

    /**
     * Transforms {@code re}/{@code im} in place. Length must be a power of two.
     *
     * <p>Decimation in time: bit-reverse the input, then combine in log2(n) passes of butterflies.
     */
    public static void transform(float[] re, float[] im) {
        int n = re.length;
        if (n != im.length || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("length must match and be a power of two, got " + n);
        }

        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }

        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            float stepRe = (float) Math.cos(angle);
            float stepIm = (float) Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                float wr = 1f;
                float wi = 0f;
                for (int k = 0; k < length / 2; k++) {
                    int a = start + k;
                    int b = a + length / 2;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nextWr = wr * stepRe - wi * stepIm;
                    wi = wr * stepIm + wi * stepRe;
                    wr = nextWr;
                }
            }
        }
    }

    /** Fills {@code window} with a Hann window, which trades a little resolution for far less leakage. */
    public static void hann(float[] window) {
        int n = window.length;
        for (int i = 0; i < n; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
    }

    /**
     * Magnitudes of a complex block in dB, ordered from the most negative frequency to the most
     * positive — so the array reads left to right as a spectrum display does, with DC in the middle.
     *
     * @param out receives {@code re.length} values, each in dBFS relative to a full-scale bin
     */
    public static void magnitudesDb(float[] re, float[] im, float[] out, double floorDb) {
        transform(re, im);
        int n = re.length;
        int half = n / 2;
        double scale = 1.0 / n;
        for (int i = 0; i < n; i++) {
            // Rotate so bin 0 (DC) lands in the middle: negative frequencies are the upper half.
            int bin = (i < half) ? i + half : i - half;
            double magnitude = Math.hypot(re[bin], im[bin]) * scale;
            out[i] = (float) (magnitude <= 0.0 ? floorDb : Math.max(floorDb, 20.0 * Math.log10(magnitude)));
        }
    }
}
