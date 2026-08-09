package com.modula.audio;

import java.io.IOException;
import java.nio.file.Path;

/** Where a recording goes: a WAV file written here, or an encoder fed on its stdin. */
public interface RecordingWriter extends AutoCloseable {

    void write(short[] pcm, int count) throws IOException;

    Path path();

    /** Seconds captured so far, from the sample count rather than a clock. */
    double seconds(int sampleRate);

    @Override
    void close() throws IOException;
}
