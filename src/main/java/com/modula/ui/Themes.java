package com.modula.ui;

import javafx.application.Application;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

/**
 * Which AtlantaFX theme dresses the standard controls, night or daylight.
 *
 * <p><b>Switching ground is two changes, and only one of them used to happen.</b> {@code modula.css}
 * carries both token blocks and the {@code daylight} class picks between them, which covers everything
 * Modula draws itself — but the toolkit's own controls are painted by a <i>user-agent</i> stylesheet
 * that knows nothing about that class. It was set to {@link PrimerDark} once at startup and never
 * changed, so daylight put light tokens on top of a dark control theme.
 *
 * <p>The result was worst where a surface is entirely stock chrome. The right-click menu sets no
 * background on {@code .menu-item}, so PrimerDark painted the rows dark while {@code -ink} correctly
 * resolved to daylight's near-black — dark text on dark rows, with only the hovered row legible because
 * that one uses Modula's own {@code -glass}. The Stereo checkbox's label had the same cause and a milder
 * symptom: PrimerDark's light text on a light ground.
 *
 * <p>Worth noting for anyone reading the old code and expecting the tokens to be the problem: the
 * {@code daylight} class <b>does</b> reach a popup's content, even though the popup is its own scene.
 * The tokens were never the issue; the control theme under them was.
 *
 * <p>{@link Application#setUserAgentStylesheet} is global and re-resolves CSS for every scene at once,
 * which is what makes a live switch reach the settings window and any open popup without either of them
 * having to cooperate. It must be called on the FX thread.
 */
public final class Themes {

    private Themes() {}

    /**
     * The user-agent stylesheet for a ground.
     *
     * <p>Separate from {@link #apply} so the choice can be tested without a toolkit — the failure this
     * guards against is both branches ending up on the same theme, which looks like nothing at all in a
     * diff and like a broken toggle on screen.
     */
    public static String stylesheetFor(boolean daylight) {
        return daylight ? new PrimerLight().getUserAgentStylesheet() : new PrimerDark().getUserAgentStylesheet();
    }

    /** Dresses the standard controls for this ground, across every scene in the application. */
    public static void apply(boolean daylight) {
        Application.setUserAgentStylesheet(stylesheetFor(daylight));
    }
}
