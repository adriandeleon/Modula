package com.modula.band;

/** How a band carries its audio, which decides the whole demodulation path. */
public enum Modulation {
    /** Wideband FM: ±75 kHz deviation, stereo pilot at 19 kHz, RDS at 57 kHz. */
    FM,

    /** Amplitude modulation: no pilot, no subcarriers, roughly 10 kHz of channel. */
    AM;

    public boolean carriesStereo() {
        return this == FM;
    }

    public boolean carriesRds() {
        return this == FM;
    }
}
