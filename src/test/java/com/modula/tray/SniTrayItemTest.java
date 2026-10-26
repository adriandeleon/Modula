package com.modula.tray;

import java.util.Map;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SniTrayItemTest {

    /**
     * The backend came from Nux whole, and through 1.3.0 still introduced itself as Nux: KDE showed
     * that name for Modula's icon. The host reads these three, so all three are pinned.
     */
    @Test
    void theItemIntroducesItselfAsModula() {
        SniTray.Item item = new SniTray.Item(() -> {});
        item.setState(java.util.List.of(), "Modula · 98.9 MHz", "Active");
        Map<String, Variant<?>> props = item.GetAll("org.kde.StatusNotifierItem");

        assertEquals("modula", props.get("Id").getValue());
        assertEquals("Modula", props.get("Title").getValue());
        assertEquals("Modula", ((SniTooltip) props.get("ToolTip").getValue()).title);
    }
}
