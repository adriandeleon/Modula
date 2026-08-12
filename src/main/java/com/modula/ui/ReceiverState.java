package com.modula.ui;

import com.modula.radio.RadioEngine;

/**
 * The seven states a listener can actually be in, as one function of {@link RadioEngine.Status}.
 *
 * <p>Status has eight fields whose combinations the interface used to imply through the order of a
 * dozen statements — a progress value here, a style string there, two labels and a message. Naming
 * the states makes the interesting cases decisions rather than drawing, and each of them is a
 * decision that has been got wrong at least once:
 *
 * <ul>
 *   <li><b>STEREO reports the transmitter, not the listener's setting.</b> Forcing mono with the
 *       checkbox must not unlight it — the station is still sending a pilot.
 *   <li><b>A fault holds the last frequency rather than blanking it.</b> Losing the reading is worse
 *       than showing one that has stopped updating.
 *   <li><b>Seeking dims the dial.</b> While a seek runs the frequency changes several times a second
 *       and is not information; the band cursor is what is worth watching.
 *   <li><b>Dropped samples are a fault, but a running receiver is not weak just because it is quiet
 *       between blocks.</b> Weakness is measured, not inferred.
 *   <li><b>The fault is judged on recent loss, not on the running total.</b> The counters are
 *       cumulative, so testing them for "greater than zero" meant a single overrun at any point in
 *       the session pinned the receiver coral for as long as it ran — and a state that can never
 *       clear reports the past rather than the present.
 * </ul>
 *
 * <p>Pure, so it is testable without a toolkit — which is the same rule the DSP follows.
 */
public enum ReceiverState {
    /** Stopped. Nothing is being received and the interface explains how to start. */
    NOT_LISTENING,

    /** Running, but no block has arrived yet: the dial lights, the meter is still empty. */
    STARTING,

    /** Receiving, and the station is transmitting a stereo pilot. */
    STEREO,

    /** Receiving a mono broadcast, or a stereo one too weak to lock. */
    MONO,

    /** Receiving, but too weakly for RDS or stereo to be likely. */
    WEAK,

    /** Stepping the band looking for the next station. */
    SEEKING,

    /** Something is wrong and the listener needs to know what. */
    FAULT;

    /**
     * Below this much quieting, the station is too weak for RDS and usually for stereo.
     *
     * <p>14 dB is where seek stops finding a channel worth listening to, on the calibration in
     * {@code DemodChain} — a barely usable station is about 8 and a solid one 24 or more. Reusing that
     * number rather than inventing a second one keeps "worth stopping on" and "worth calling strong"
     * the same judgement.
     */
    public static final double WEAK_QUIETING_DB = 14.0;

    /**
     * Below this, there is no quieting measurement to judge by and {@link #WEAK_DBFS} is used instead.
     *
     * <p>Covers two cases with one test. AM leaves multiplex noise at zero, since envelope detection has
     * no discriminator to measure — and an empty FM channel has genuinely quieted nothing. Neither can be
     * judged on quieting, and both are correctly served by falling back to raw power.
     */
    private static final double MEASURABLE_QUIETING_DB = 1.0;

    /**
     * Below this power, the station is weak — the fallback when quieting is unavailable.
     *
     * <p><b>This was the only test, and for most of this receiver's life it could never fire.</b> With an
     * AGC running, channel power reports the AGC's target and not the station: measured, −9.85 dBFS on an
     * empty channel against −9.75 for a weak one. Nothing ever came near −45, so WEAK was unreachable and
     * the receiver had no way to tell a listener their signal was poor. It means something again now the
     * front-end gain is fixed, but quieting is still the better measure where there is one.
     */
    public static final double WEAK_DBFS = -45.0;

    /**
     * How far a signal must recover before it stops being called weak, in dB.
     *
     * <p><b>A threshold with no hysteresis is the mistake this receiver has now made twice.</b> The pilot
     * detector flapped for the same reason, and it is not hypothetical here: a real station measured
     * quieting of exactly 14 dB, sitting on the boundary, which restyles the dial every time the estimate
     * crosses it. Three decibels either side is enough to settle it without making a genuinely improving
     * signal wait.
     *
     * <p>Applied to the power fallback too, since the argument has nothing to do with which measurement
     * is being compared.
     */
    public static final double WEAK_HYSTERESIS_DB = 3.0;

    /**
     * Classifies a status snapshot.
     *
     * <p>Order matters and encodes the priorities above: a fault outranks everything because it needs
     * acting on; seeking outranks signal quality because the reading is in motion; and weakness is
     * judged before stereo because a weak signal that happens to hold a pilot is still weak.
     *
     * @param status the latest snapshot, or null before the first one
     * @param faulted whether the engine reported an error
     */
    public static ReceiverState of(RadioEngine.Status status, boolean faulted) {
        return of(status, faulted, null);
    }

    /**
     * Classifies a status snapshot, given what it was last time.
     *
     * <p>The previous state is passed in rather than remembered here, which is what lets the weak
     * threshold have hysteresis while this stays a <b>pure function</b> — the property that makes every
     * one of these decisions testable without a toolkit. The caller already knows what it last displayed.
     *
     * @param previous the state this returned last time, or null for no hysteresis (a first
     *     classification, or a caller that does not track it)
     */
    public static ReceiverState of(RadioEngine.Status status, boolean faulted, ReceiverState previous) {
        if (status == null) {
            return NOT_LISTENING;
        }
        if (faulted || status.losses().recent()) {
            return FAULT;
        }
        if (!status.running()) {
            return NOT_LISTENING;
        }
        if (status.seeking()) {
            return SEEKING;
        }
        if (status.signalDbfs() <= com.modula.dsp.PowerMeter.FLOOR_DBFS) {
            return STARTING; // running, but nothing measured yet
        }
        if (isWeak(status, previous == WEAK)) {
            return WEAK;
        }
        return status.pilotLocked() ? STEREO : MONO;
    }

    /**
     * Whether this signal is too poor to promise stereo or RDS.
     *
     * <p>Judged on quieting where there is any, because that measures the station; power is the fallback
     * for AM and for an empty channel, where there is no multiplex noise to have suppressed.
     */
    private static boolean isWeak(RadioEngine.Status status, boolean wasWeak) {
        // Already weak: the signal has to clear the threshold by the hysteresis before we say otherwise.
        double margin = wasWeak ? WEAK_HYSTERESIS_DB : 0.0;
        double quieting = com.modula.radio.DemodChain.quietingDb(status.noiseDbfs());
        if (quieting >= MEASURABLE_QUIETING_DB) {
            return quieting < WEAK_QUIETING_DB + margin;
        }
        return status.signalDbfs() < WEAK_DBFS + margin;
    }

    /** Whether the receiver is running at all, whatever it is managing to hear. */
    public boolean isReceiving() {
        return this != NOT_LISTENING && this != FAULT;
    }

    /** Whether the station's own identity should be shown; a seek clears it because it is stale. */
    public boolean showsStation() {
        return this == STEREO || this == MONO;
    }

    /** The style class the glass carries in this state, so the sheet expresses it rather than code. */
    public String styleClass() {
        return "state-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
