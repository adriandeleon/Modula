package com.modula.ui;

import java.time.Duration;

/**
 * Decides whether the status line still belongs to a message, or has gone back to the live readout.
 *
 * <p>The line carries two kinds of text. The <b>readout</b> — level, quieting, stereo, RDS — is
 * republished about nine times a second while listening. A <b>message</b> is said once: why the
 * receiver fell back to rtl_tcp and what to do about it, that ffmpeg is missing so this is a WAV, the
 * file a recording went to, that an update exists. Both used to write the same label, so every
 * message was overwritten by the next readout about 110 ms later — including the per-platform driver
 * advice {@code NativeDiagnosis} exists to give, which no one could read before it vanished.
 *
 * <p>A message now holds the line for as long as it takes to read, and the readout waits. Pure, with
 * the clock passed in, so the timing is testable without a toolkit.
 */
final class StatusHold {

    /** Short enough that a one-word message does not hide the readout for long. */
    static final Duration MINIMUM = Duration.ofSeconds(4);

    /** Long enough for the driver advice, short enough that a stale message does not linger. */
    static final Duration MAXIMUM = Duration.ofSeconds(15);

    /** A comfortable reading pace for short technical text, about 180 words a minute. */
    private static final double CHARACTERS_PER_SECOND = 15.0;

    private long heldUntilNanos = Long.MIN_VALUE;

    /** How long a message of this length needs on screen. */
    static Duration readingTime(String text) {
        int length = text == null ? 0 : text.length();
        long millis = Math.round(length / CHARACTERS_PER_SECOND * 1000.0);
        return Duration.ofMillis(Math.clamp(millis, MINIMUM.toMillis(), MAXIMUM.toMillis()));
    }

    /** A message has just been shown; keep the readout off the line for {@code hold}. */
    void hold(Duration hold, long nowNanos) {
        heldUntilNanos = nowNanos + hold.toNanos();
    }

    /** Whether a message still owns the line, so the readout must not replace it. */
    boolean isHeld(long nowNanos) {
        // Compared as a difference, so a nanoTime origin anywhere in the long range cannot invert it.
        return heldUntilNanos != Long.MIN_VALUE && nowNanos - heldUntilNanos < 0;
    }
}
