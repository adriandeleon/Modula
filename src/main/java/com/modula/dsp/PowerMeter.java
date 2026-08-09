package com.modula.dsp;

/**
 * Block signal-strength measurement, in dBFS relative to a full-scale amplitude of 1.0. Feeds both
 * the signal bar and the seek/scan threshold. Pure and stateless.
 */
public final class PowerMeter {

    /** Floor reported for digital silence, rather than negative infinity. */
    public static final double FLOOR_DBFS = -120.0;

    private PowerMeter() {}

    /** RMS level of a complex block held as parallel I/Q arrays. */
    public static double rmsDbfs(float[] i, float[] q, int count) {
        if (count <= 0) {
            return FLOOR_DBFS;
        }
        double sum = 0.0;
        for (int n = 0; n < count; n++) {
            sum += (double) i[n] * i[n] + (double) q[n] * q[n];
        }
        return toDbfs(Math.sqrt(sum / count));
    }

    /** RMS level of a real block. */
    public static double rmsDbfs(float[] x, int count) {
        if (count <= 0) {
            return FLOOR_DBFS;
        }
        double sum = 0.0;
        for (int n = 0; n < count; n++) {
            sum += (double) x[n] * x[n];
        }
        return toDbfs(Math.sqrt(sum / count));
    }

    private static double toDbfs(double rms) {
        if (rms <= 0.0) {
            return FLOOR_DBFS;
        }
        return Math.max(FLOOR_DBFS, 20.0 * Math.log10(rms));
    }
}
