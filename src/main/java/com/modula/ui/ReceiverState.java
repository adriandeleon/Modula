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

    /** Below this, the station is too weak for RDS and usually for stereo. */
    public static final double WEAK_DBFS = -45.0;

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
        if (status == null) {
            return NOT_LISTENING;
        }
        if (faulted || status.droppedSamples() > 0) {
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
        if (status.signalDbfs() < WEAK_DBFS) {
            return WEAK;
        }
        return status.pilotLocked() ? STEREO : MONO;
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
