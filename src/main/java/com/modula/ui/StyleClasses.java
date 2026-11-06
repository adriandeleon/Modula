package com.modula.ui;

import java.util.List;

/**
 * Switches a style class on or off, idempotently.
 *
 * <p>A node's style classes are a <b>list</b>, not a set: {@code add} appends a duplicate every time
 * it is called and {@code remove} takes away one copy. Status arrives about nine times a second, so
 * an unconditional {@code add} on every status grew the station label's list by ~32,000 entries an
 * hour — and the single {@code remove} that ran once RDS supplied a name left the rest behind, so
 * the name stayed in its faint pending style. Every state the status drives goes through here.
 *
 * <p>Pure over {@link List}, so it is checked without a toolkit.
 */
final class StyleClasses {

    private StyleClasses() {}

    /** Leaves exactly one {@code name} in {@code classes} when {@code on}, and none otherwise. */
    static void set(List<String> classes, String name, boolean on) {
        if (on) {
            if (!classes.contains(name)) {
                classes.add(name);
            }
        } else {
            classes.removeIf(name::equals);
        }
    }
}
