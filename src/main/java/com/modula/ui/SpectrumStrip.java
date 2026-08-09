package com.modula.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import com.modula.band.BandPlan;
import com.modula.radio.DemodChain;

/**
 * A ±600 kHz view of the band around the tuned frequency. Not a waterfall, and not a panel.
 *
 * <p>The job is narrow: show that the neighbours exist and that you are centred on one of them. So
 * the span is fixed at the sample rate — a fact of the front end rather than a setting — the ticks
 * land on {@link BandPlan} grid points so a neighbour reads as "the next station up" rather than as
 * an offset in kilohertz, and there is no zoom, no dB axis, no peak hold. The test the whole product
 * uses applies here too: would a car radio have it?
 *
 * <p>Drawn as a filled area under a one-pixel line. A stroke-only trace on a dark ground reads as
 * noise; the fill reads as a shape at a glance.
 *
 * <p>It also makes seek legible — during a sweep this is the one surface where the motion across the
 * band is visible, which is why the dial dims at the same time. The two are halves of one signal.
 *
 * <p>Repaints are coalesced to at most one pending, at status cadence, never per FFT frame.
 */
public final class SpectrumStrip extends Canvas {

    /*
     * The dB range mapped onto the height. Fixed, not adaptive: a scale that rescaled itself would
     * make the sweep during a seek unreadable, because everything would move at once.
     *
     * Measured off air at 98.9 MHz: the tuned station peaks near -22 dBFS and the noise floor sits
     * around -66, so this span puts a strong station near the top and the floor just off the bottom.
     */
    private static final double TOP_DBFS = -15.0;

    private static final double BOTTOM_DBFS = -75.0;

    /** Below this the strip stops reading as a shape and starts reading as a line. */
    private static final double MIN_HEIGHT = 46;

    private float[] bins;
    private long centerHz;
    private BandPlan band = BandPlan.fm(com.modula.band.Region.AMERICAS);
    private boolean seeking;

    private Color ink = Color.web("#5C6371");
    private Color fill = Color.web("#9AA0AC");
    private Color dial = Color.web("#FFB454");

    private boolean repaintPending;

    public SpectrumStrip(double width, double height) {
        super(width, height);
    }

    /** Colours come from the theme rather than the class, so the sheet stays the single source. */
    public void setPalette(Color ink, Color fill, Color dial) {
        this.ink = ink;
        this.fill = fill;
        this.dial = dial;
        requestRepaint();
    }

    public void setBand(BandPlan band) {
        this.band = band;
        requestRepaint();
    }

    /** Pushes a new snapshot. Safe to call at status cadence; repaints coalesce. */
    public void update(float[] bins, long centerHz, boolean seeking) {
        this.bins = bins;
        this.centerHz = centerHz;
        this.seeking = seeking;
        requestRepaint();
    }

    public void clear() {
        bins = null;
        requestRepaint();
    }

    private void requestRepaint() {
        if (repaintPending) {
            return;
        }
        repaintPending = true;
        javafx.application.Platform.runLater(() -> {
            repaintPending = false;
            draw();
        });
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) {
            return;
        }

        drawChannelTicks(g, w, h);

        if (bins != null && bins.length > 1) {
            drawTrace(g, w, h);
        }

        // The centre marker: the one place the accent leaves the dial, because it means
        // "the frequency you are reading above".
        double mid = Math.round(w / 2) + 0.5;
        g.setStroke(dial);
        g.setLineWidth(1);
        g.setGlobalAlpha(seeking ? 1.0 : 0.85);
        g.strokeLine(mid, 0, mid, h);
        g.setGlobalAlpha(1.0);
    }

    /** Ticks on real channels, so the display is a band rather than a frequency axis. */
    private void drawChannelTicks(GraphicsContext g, double w, double h) {
        long span = DemodChain.INPUT_RATE;
        long low = centerHz - span / 2;
        long high = centerHz + span / 2;
        if (centerHz == 0) {
            return;
        }
        g.setStroke(ink);
        g.setLineWidth(1);
        g.setGlobalAlpha(0.35);
        for (int channel = 0; channel < band.channelCount(); channel++) {
            long hz = band.frequencyOf(channel);
            if (hz < low || hz > high) {
                continue;
            }
            double x = Math.round(w * (hz - low) / (double) span) + 0.5;
            g.strokeLine(x, h - 4, x, h);
        }
        g.setGlobalAlpha(1.0);
    }

    private void drawTrace(GraphicsContext g, double w, double h) {
        int n = bins.length;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];

        xs[0] = 0;
        ys[0] = h;
        for (int i = 0; i < n; i++) {
            xs[i + 1] = w * i / (double) (n - 1);
            ys[i + 1] = levelToY(bins[i], h);
        }
        xs[n + 1] = w;
        ys[n + 1] = h;

        g.setFill(fill.deriveColor(0, 1, 1, seeking ? 0.18 : 0.28));
        g.fillPolygon(xs, ys, n + 2);

        g.setStroke(fill.deriveColor(0, 1, 1, seeking ? 0.5 : 0.8));
        g.setLineWidth(1);
        g.strokePolyline(java.util.Arrays.copyOfRange(xs, 1, n + 1), java.util.Arrays.copyOfRange(ys, 1, n + 1), n);
    }

    private static double levelToY(double dbfs, double h) {
        double t = (dbfs - BOTTOM_DBFS) / (TOP_DBFS - BOTTOM_DBFS);
        return h - Math.clamp(t, 0.0, 1.0) * h;
    }

    /*
     * A Canvas is not resizable by default and has no layout opinion, so it has to be taught one.
     * Binding its size to the parent's instead is the obvious move and is a trap: the canvas is a
     * child of what it measures, so the binding feeds back and the strip grows without bound until
     * it pushes everything below it out of the panel. Answering the layout queries and accepting
     * resize() is the non-circular way.
     */

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        requestRepaint();
    }

    @Override
    public double minWidth(double height) {
        return 120;
    }

    @Override
    public double minHeight(double width) {
        return MIN_HEIGHT;
    }

    @Override
    public double prefWidth(double height) {
        return 400;
    }

    @Override
    public double prefHeight(double width) {
        return MIN_HEIGHT;
    }

    @Override
    public double maxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    public double maxHeight(double width) {
        return Double.MAX_VALUE;
    }
}
