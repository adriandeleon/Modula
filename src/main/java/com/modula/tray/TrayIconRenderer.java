package com.modula.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * Draws the tray icon: a broadcast glyph — a dot with two arcs radiating from it — in the palette's
 * own language. Amber while listening, ink when stopped, coral on a fault.
 *
 * <p><b>A glyph rather than the frequency.</b> A tray icon is about 22 pixels; "101.5" is five
 * characters including a decimal point, and shrinking it to fit produces something unreadable that
 * also lies by rounding. The glyph says what the icon is actually for — whether the radio is on —
 * and the frequency is one hover away in the tooltip, where it fits.
 *
 * <p>Pure Java2D and headless-safe, so both backends consume it.
 */
public final class TrayIconRenderer {

    /** The kit's palette. Amber is still the budget: it means "this is on". */
    private static final Color LISTENING = new Color(0xFFB454);

    private static final Color STOPPED = new Color(0x9AA0AC);
    private static final Color FAULT = new Color(0xFF6B5A);

    /** The cabin, for punching a hole behind the badge so it reads over the arcs. */
    private static final Color GROUND = new Color(0x0B0C0E);

    /**
     * The mark's shape, shared with the application icon.
     *
     * <p>These are the numbers in {@code scripts/make-icon.py}. The tray draws them at full canvas
     * because it has no tile to sit inside; the dock icon insets them. If they drift, the panel entry
     * and the dock entry stop being the same mark, which is the thing a window manager uses to tell a
     * user that the running window and the launcher are one application.
     */
    private static final double SPAN = 44;

    private static final double STROKE = 0.078;
    private static final double DOT = 0.155;

    /**
     * Insets the mark so the outermost ring plus half its stroke fits the canvas.
     *
     * <p>At 1.0 the outer arcs run past the edge and are clipped flat, which reads as a drawing
     * error at 16 pixels rather than as a signal falling off. The tiled application icon insets
     * further still, because it also has a tile to sit inside.
     */
    private static final double FIT = 0.5 / (0.17 + 0.155 * 2 + 0.078 / 2);

    /**
     * Below this the second ring stops reading as a weaker signal and becomes mud, so the mark keeps
     * one. The application icon simplifies at the same threshold — a mark that survives being small
     * does it by dropping detail, not by drawing the same detail thinner.
     */
    private static final int TWO_RING_FLOOR = 24;

    private TrayIconRenderer() {}

    public static BufferedImage render(TrayDisplay display, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(colorFor(display));

        double centre = size / 2.0;
        double dot = Math.max(2.0, size * DOT * FIT);
        g.fill(new Ellipse2D.Double(centre - dot / 2, centre - dot / 2, dot, dot));

        // Two arcs either side, so the mark reads as radiating rather than as a target.
        g.setStroke(new BasicStroke(
                (float) Math.max(1.2, size * STROKE * FIT), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int rings = size >= TWO_RING_FLOOR ? 2 : 1;
        for (int ring = 1; ring <= rings; ring++) {
            double r = size * (0.17 + 0.155 * ring) * FIT;
            for (int side : new int[] {0, 180}) {
                g.draw(new Arc2D.Double(centre - r, centre - r, r * 2, r * 2, side - SPAN, SPAN * 2, Arc2D.OPEN));
            }
        }
        if (display.recording()) {
            drawRecordingBadge(g, size);
        }
        g.dispose();
        return image;
    }

    /**
     * A filled coral disc in the corner while recording.
     *
     * <p>A badge rather than recolouring the mark, because coral already means <em>fault</em> here: a
     * coral mark and a coral-badged mark are two states, a coral mark and a coral mark are one. It is
     * ringed in the ground colour so it stays legible over the arcs it overlaps.
     */
    private static void drawRecordingBadge(Graphics2D g, int size) {
        // Small: it annotates the mark rather than replacing it. At 0.22 of the canvas the badge
        // swallowed the whole icon at 16px, which is the size that matters most in a panel.
        double r = Math.max(1.6, size * 0.15);
        double ring = Math.max(0.75, size * 0.03);
        double cx = size - r - ring;
        double cy = size - r - ring;
        g.setColor(GROUND);
        g.fill(new Ellipse2D.Double(cx - r - ring, cy - r - ring, (r + ring) * 2, (r + ring) * 2));
        g.setColor(FAULT);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    private static Color colorFor(TrayDisplay display) {
        if (display.attention()) {
            return FAULT;
        }
        return display.listening() ? LISTENING : STOPPED;
    }
}
