package com.modula.rds;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The alternative-frequency list from group 0A: other transmitters carrying this same programme.
 *
 * <p>Two codes arrive per group, in block C, and the list builds up over several groups. A code of
 * 1–204 is an FM frequency, {@code 87.5 MHz + n × 100 kHz}; 205–223 are unused; 224–249 announce how
 * many entries the list has; 250 says the next code is an LF/MF frequency, which this skips because
 * Modula cannot follow it from the FM band.
 *
 * <p><b>This parses and reports; it does not switch.</b> Retuning to a stronger alternative means
 * leaving the station to measure the candidates, and with a single dongle that is an audible gap in
 * the audio — car radios do it with a second tuner. A list you can see is honest; a switch that
 * stutters is not.
 */
public final class AlternativeFrequencies {

    /** The base the codes count up from. */
    static final long BASE_HZ = 87_500_000L;

    static final long STEP_HZ = 100_000L;

    static final int MAX_FREQUENCY_CODE = 204;

    /** 224 means "no alternatives"; 225–249 mean that many follow. */
    static final int COUNT_BASE = 224;

    static final int COUNT_MAX = 249;

    /** Announces that the following code is an LF/MF frequency rather than an FM one. */
    static final int LF_MF_FOLLOWS = 250;

    /** A filler code, sent to pad a list out to a whole number of groups. */
    static final int FILLER = 205;

    private final Set<Long> frequencies = new LinkedHashSet<>();

    private boolean skipNext;

    /** Feeds one 0A group. Anything else is ignored, so the caller need not filter. */
    public void accept(RdsGroup group) {
        if (group == null || group.type() != 0 || group.isVersionB()) {
            return;
        }
        int c = group.c() & 0xFFFF;
        acceptCode((c >>> 8) & 0xFF);
        acceptCode(c & 0xFF);
    }

    private void acceptCode(int code) {
        if (skipNext) {
            skipNext = false; // an LF/MF frequency, which cannot be tuned from the FM band
            return;
        }
        if (code == LF_MF_FOLLOWS) {
            skipNext = true;
            return;
        }
        if (code >= 1 && code <= MAX_FREQUENCY_CODE) {
            frequencies.add(BASE_HZ + code * STEP_HZ);
        }
        // A count code tells us how long the list will be. Modula shows what it has actually
        // received instead, because a station can announce more than it goes on to transmit.
    }

    /** How many entries the station last announced, or -1 when it has not said. Diagnostic only. */
    public static int announcedCount(int code) {
        return code >= COUNT_BASE && code <= COUNT_MAX ? code - COUNT_BASE : -1;
    }

    /** Every frequency seen so far, in the order first heard. */
    public List<Long> frequencies() {
        return List.copyOf(frequencies);
    }

    public boolean isEmpty() {
        return frequencies.isEmpty();
    }

    public void clear() {
        frequencies.clear();
        skipNext = false;
    }

    /** Turns a code into a frequency, or 0 when it is not one. Pure, for the tests and the decoder. */
    static long frequencyFor(int code) {
        return code >= 1 && code <= MAX_FREQUENCY_CODE ? BASE_HZ + code * STEP_HZ : 0L;
    }
}
