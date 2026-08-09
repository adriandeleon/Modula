package com.modula.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A recording written by piping raw PCM into ffmpeg.
 *
 * <p>The process is the file: nothing is written here, and the encoder owns the output. That means
 * {@link #close()} has real work to do — the pipe must be closed so the encoder sees end-of-input,
 * and then it must be waited for, because a container like FLAC or MP4 writes its header last and a
 * process killed before that leaves a file no player will open.
 *
 * <p>ffmpeg's own diagnostics are discarded rather than piped: an unread pipe fills its buffer and
 * blocks the encoder mid-recording, which would silently stall the audio thread feeding it.
 */
final class EncodedWriter implements RecordingWriter {

    private static final Logger LOG = Logger.getLogger(EncodedWriter.class.getName());

    /** Long enough for an encoder to flush a large recording, short enough not to hang a quit. */
    private static final long FINISH_TIMEOUT_SECONDS = 30;

    private final Path destination;
    private final Process process;
    private final OutputStream pipe;
    private final int channels;

    private long samplesWritten;
    private boolean broken;

    EncodedWriter(List<String> argv, Path destination, int channels) throws IOException {
        this.destination = destination;
        this.channels = channels;
        this.process = new ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        this.pipe = new BufferedOutputStream(process.getOutputStream(), 1 << 16);
    }

    @Override
    public void write(short[] pcm, int count) throws IOException {
        if (broken) {
            return;
        }
        byte[] bytes = new byte[count * 2];
        for (int n = 0, b = 0; n < count; n++, b += 2) {
            bytes[b] = (byte) (pcm[n] & 0xFF);
            bytes[b + 1] = (byte) ((pcm[n] >> 8) & 0xFF);
        }
        try {
            pipe.write(bytes);
            samplesWritten += count;
        } catch (IOException e) {
            // The encoder died. Remember it so every later block is dropped quietly rather than
            // throwing once per audio buffer at the sink that is trying to keep playing.
            broken = true;
            throw e;
        }
    }

    @Override
    public Path path() {
        return destination;
    }

    @Override
    public double seconds(int sampleRate) {
        return samplesWritten / (double) (channels * sampleRate);
    }

    @Override
    public void close() throws IOException {
        try {
            pipe.close(); // end-of-input: the encoder will not finish the file until it sees this
        } finally {
            try {
                if (!process.waitFor(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOG.log(Level.WARNING, "encoder did not finish in time; the recording may be truncated");
                    process.destroyForcibly();
                } else if (process.exitValue() != 0) {
                    LOG.log(Level.WARNING, "encoder exited with " + process.exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
