package com.modula.audio;

/** Somewhere to put demodulated PCM. One implementation today; a WAV recorder is the obvious second. */
public interface AudioSink extends AutoCloseable {

    /** Opens the underlying device. */
    void open() throws Exception;

    /** Hands over {@code count} mono 16-bit samples. Must not block the calling (DSP) thread. */
    void write(short[] pcm, int count);

    /** Playback gain, 0.0 to 1.0. */
    void setVolume(double volume);

    @Override
    void close();
}
