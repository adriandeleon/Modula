package com.modula.audio;

/** Somewhere to put demodulated PCM. One implementation today; a WAV recorder is the obvious second. */
public interface AudioSink extends AutoCloseable {

    /** Opens the underlying device. */
    void open() throws Exception;

    /** Hands over {@code count} mono 16-bit samples. Must not block the calling (DSP) thread. */
    void write(short[] pcm, int count);

    /** Playback gain, 0.0 to 1.0. */
    void setVolume(double volume);

    /**
     * Samples the sink had to throw away because its buffer was full — the DSP outrunning the card.
     *
     * <p>On the interface rather than reached for with an {@code instanceof}, because the engine
     * publishes it and should not have to know which sink it was handed. A sink with no buffer to
     * overrun answers zero and says something true.
     */
    default long droppedSamples() {
        return 0L;
    }

    /**
     * Samples the sink had to invent because none had arrived — the DSP being starved.
     *
     * <p>The opposite failure to {@link #droppedSamples}, and worth reporting separately: drops mean
     * this end is behind, underruns mean something upstream is.
     */
    default long underrunSamples() {
        return 0L;
    }

    @Override
    void close();
}
