package com.modula.rds;

import java.util.Arrays;

/**
 * Accumulates a text field that arrives in fragments.
 *
 * <p>Neither of the two text fields arrives whole. The station name comes two characters at a time
 * across four groups; radio text comes four characters at a time across sixteen. So the buffer has
 * to hold partial state and decide when the result is worth showing.
 *
 * <p><b>Nothing is published until every segment has arrived</b>, or the field visibly fills in —
 * "BB", "BBC ", "BBC R4" — instead of appearing.
 *
 * <p><b>The station name additionally restarts its assembly at segment zero</b> ({@code cyclic}).
 * Many stations scroll the eight-character field, cycling several frames to spell out a longer
 * message. Merging segments across frames splices them: a station alternating "ESCUCHAS" and "D99"
 * displays as "ES99  AS" and "D99CHAS" — observed on air, and indistinguishable from a decoder bug.
 * Radio text does not do this, because stations do not reliably begin its sixteen segments at zero
 * and it signals a change through the A/B flag instead.
 *
 * <p>Pure and stateful. Not thread-safe.
 */
public final class RdsText {

    /** No terminator: the field is only complete when every segment has arrived. */
    public static final int NO_TERMINATOR = -1;

    private final char[] buffer;
    private final int segmentSize;
    private final boolean[] seen;
    private final boolean cyclic;
    private final int terminator;

    private String completed = "";
    private int nextExpected;

    public RdsText(int length, int segmentSize) {
        this(length, segmentSize, false, NO_TERMINATOR);
    }

    /**
     * @param cyclic whether segment zero begins a fresh assembly, for a field the station scrolls
     * @param terminator a character that ends the message early, or {@link #NO_TERMINATOR}
     */
    public RdsText(int length, int segmentSize, boolean cyclic, int terminator) {
        this.terminator = terminator;
        if (length < 1 || segmentSize < 1 || length % segmentSize != 0) {
            throw new IllegalArgumentException(
                    "length %d must be a positive multiple of segment %d".formatted(length, segmentSize));
        }
        this.buffer = new char[length];
        this.segmentSize = segmentSize;
        this.seen = new boolean[length / segmentSize];
        this.cyclic = cyclic;
        clear();
    }

    /**
     * Writes one segment.
     *
     * @param segment which slot, counted in segments rather than characters
     * @param characters exactly {@code segmentSize} characters
     */
    public void set(int segment, char... characters) {
        if (segment < 0 || segment >= seen.length) {
            return; // a corrupt address; drop it rather than writing outside the field
        }
        if (characters.length != segmentSize) {
            throw new IllegalArgumentException(
                    "expected %d characters, got %d".formatted(segmentSize, characters.length));
        }
        if (cyclic) {
            // A cyclic field must be assembled from segments arriving consecutively from zero.
            // Restarting only at segment zero is not enough: if segment zero is lost to a CRC error,
            // the next frame's remaining segments land on the previous frame's first one and complete
            // a splice — which is how "ESCUCHAS" and " D99" produced "ES99". Losing a frame outright
            // costs nothing, since the field repeats about once a second.
            if (segment == 0) {
                Arrays.fill(seen, false);
            } else if (segment != nextExpected) {
                Arrays.fill(seen, false);
                nextExpected = 0;
                return;
            }
            nextExpected = segment + 1;
        }
        System.arraycopy(characters, 0, buffer, segment * segmentSize, segmentSize);
        seen[segment] = true;

        if (isComplete()) {
            completed = new String(buffer).stripTrailing();
        }
    }

    /**
     * Whether the message is fully assembled: a run of segments from zero that either reaches the
     * terminator or covers the whole field.
     *
     * <p>The terminator matters more than it looks. A station whose radio text is shorter than the
     * field ends it with a terminator and <b>simply stops transmitting the remaining segments</b> —
     * so demanding all sixteen means a short message never displays at all, however perfectly it was
     * received. Observed on air: a station sending 101 radio-text groups, none of which ever showed.
     */
    public boolean isComplete() {
        for (int segment = 0; segment < seen.length; segment++) {
            if (!seen[segment]) {
                return false;
            }
            if (terminator != NO_TERMINATOR && containsTerminator(segment)) {
                return true;
            }
        }
        return true;
    }

    private boolean containsTerminator(int segment) {
        for (int i = 0; i < segmentSize; i++) {
            if (buffer[segment * segmentSize + i] == terminator) {
                return true;
            }
        }
        return false;
    }

    /**
     * The most recent fully assembled value, or empty if none has completed yet.
     *
     * <p>Deliberately holds the last complete value rather than blanking while the next assembly is
     * in flight — otherwise a scrolling field flickers to empty between every frame.
     */
    public String value() {
        return completed;
    }

    /** Wipes the buffer — for a retune, or when a station signals that its text has changed. */
    public void clear() {
        Arrays.fill(buffer, ' ');
        Arrays.fill(seen, false);
        completed = "";
        nextExpected = 0;
    }
}
