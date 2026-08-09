package com.modula.ui;

/**
 * One named thing the radio can do.
 *
 * @param id stable identifier, used for ordering and future keybinding
 * @param title what the palette shows
 * @param detail the shortcut or a short hint, shown dimmed beside the title
 * @param action what to run, on the FX thread
 */
public record Command(String id, String title, String detail, Runnable action) {

    public static Command of(String id, String title, Runnable action) {
        return new Command(id, title, "", action);
    }

    public static Command of(String id, String title, String detail, Runnable action) {
        return new Command(id, title, detail, action);
    }

    /**
     * Whether this command matches a query.
     *
     * <p>Substring rather than fuzzy: the command set is small enough that a subsequence match mostly
     * adds false positives, and a listener typing "rec" wants Record rather than eleven commands that
     * happen to contain r, e and c in order.
     */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.strip().toLowerCase(java.util.Locale.ROOT);
        return title.toLowerCase(java.util.Locale.ROOT).contains(q)
                || id.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
