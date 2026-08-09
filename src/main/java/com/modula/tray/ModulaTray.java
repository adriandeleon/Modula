package com.modula.tray;

/**
 * A platform tray/menu-bar icon. Implementations render {@link TrayDisplay} themselves (each backend
 * has its own preferred pixel sizes) and must tolerate being called from the FX thread — anything
 * slow goes to their own thread.
 */
public interface ModulaTray {

    /** Repaints the icon and tooltip; {@code display.attention()} maps to SNI NeedsAttention. */
    void update(TrayDisplay display, String tooltip);

    /** Sets what the menu's Listen/Stop item does. Called on the FX thread. */
    void setOnListen(Runnable action);

    /** Removes the icon; the instance is dead afterwards. */
    void dispose();
}
