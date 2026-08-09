package com.modula.tray;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.types.Variant;

/**
 * The pure model of the tray menu: fixed ids and each item's dbusmenu properties.
 * The status line doubles as the tooltip GNOME never shows (its AppIndicator
 * extension renders SNI menus but not SNI tooltips).
 */
final class SniMenuModel {

    static final int ROOT = 0;
    static final int STATUS = 1;
    static final int LISTEN = 2;
    static final int OPEN = 3;
    static final int SEPARATOR = 4;
    static final int QUIT = 5;

    static final List<Integer> ITEM_IDS = List.of(STATUS, LISTEN, OPEN, SEPARATOR, QUIT);

    private SniMenuModel() {}

    static Map<String, Variant<?>> propsFor(int id, String statusLine) {
        return propsFor(id, statusLine, false);
    }

    static Map<String, Variant<?>> propsFor(int id, String statusLine, boolean listening) {
        return switch (id) {
            case ROOT -> Map.of("children-display", new Variant<>("submenu"));
            case STATUS ->
                Map.of(
                        "label", new Variant<>(statusLine),
                        "enabled", new Variant<>(false));
            case LISTEN -> Map.of("label", new Variant<>(listening ? "Stop" : "Listen"));
            case OPEN -> Map.of("label", new Variant<>("Show Modula"));
            case SEPARATOR -> Map.of("type", new Variant<>("separator"));
            case QUIT -> Map.of("label", new Variant<>("Quit"));
            default -> Map.of();
        };
    }

    /** The full layout tree (root + flat items) for GetLayout. */
    static MenuLayout layout(String statusLine) {
        List<Variant<?>> children = new java.util.ArrayList<>();
        for (int id : ITEM_IDS) {
            children.add(new Variant<>(item(id, statusLine), MenuLayout.SIGNATURE));
        }
        return new MenuLayout(ROOT, propsFor(ROOT, statusLine), List.copyOf(children));
    }

    static MenuLayout item(int id, String statusLine) {
        return new MenuLayout(id, propsFor(id, statusLine), List.of());
    }
}
