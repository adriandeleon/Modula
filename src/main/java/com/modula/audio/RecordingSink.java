package com.modula.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An {@link AudioSink} that tees what it plays into a WAV file.
 *
 * <p>Wraps the real sink rather than replacing it, so recording is a decoration on the audio path and
 * the receiver does not know it is happening.
 *
 * <p><b>A recording failure never interrupts listening.</b> A full disk or an unwritable directory
 * stops the recording, reports it once and lets the audio carry on — the alternative is a radio that
 * goes silent because a file could not be written, which is the wrong trade for something whose whole
 * job is to make noise.
 *
 * <p>Writes happen on the receive thread, which is also where {@code write} is called from. That is
 * deliberate: a buffered file write is measured in microseconds and a second thread would need its
 * own ring, its own drop policy and its own shutdown ordering to save nothing.
 */
public final class RecordingSink implements AudioSink {

    private static final Logger LOG = Logger.getLogger(RecordingSink.class.getName());

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final AudioSink delegate;
    private final int sampleRate;
    private final int channels;

    private volatile RecordingWriter writer;
    private volatile String failure = "";

    public RecordingSink(AudioSink delegate, int sampleRate, int channels) {
        this.delegate = delegate;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * Starts recording to a new file named for the station and the time.
     *
     * @param directory created if absent
     * @param label a station name or frequency, sanitised into the file name
     * @return the file being written
     */
    public Path start(Path directory, String label) throws IOException {
        return start(directory, label, RecordingFormat.WAV, "");
    }

    /**
     * Starts a recording in the requested format.
     *
     * <p>Falls back to WAV rather than refusing when the format needs an encoder that is not
     * installed. A listener who pressed Record wants a recording; silently getting a bigger file is a
     * far better outcome than getting none, and {@link #failure()} says what happened.
     *
     * @param format what to write
     * @param encoderCommand the configured ffmpeg path, or blank for the default
     */
    public Path start(Path directory, String label, RecordingFormat format, String encoderCommand) throws IOException {
        stop();
        Files.createDirectories(directory);

        RecordingFormat effective = format;
        String note = "";
        if (format.needsEncoder() && !Encoders.isAvailable(encoderCommand)) {
            effective = RecordingFormat.WAV;
            note = "ffmpeg was not found, so this is being recorded as WAV";
        }

        Path file = directory.resolve(
                "%s_%s.%s".formatted(sanitise(label), LocalDateTime.now().format(STAMP), effective.extension()));
        writer = effective.needsEncoder()
                ? new EncodedWriter(
                        Encoders.argv(Encoders.command(encoderCommand), effective, sampleRate, channels, file),
                        file,
                        channels)
                : new WavWriter(file, sampleRate, channels);
        failure = note;
        return file;
    }

    /** Stops and finalises the file. Safe to call when not recording. */
    public Path stop() {
        RecordingWriter w = writer;
        writer = null;
        if (w == null) {
            return null;
        }
        try {
            w.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not finalise " + w.path(), e);
        }
        return w.path();
    }

    public boolean isRecording() {
        return writer != null;
    }

    public Path file() {
        RecordingWriter w = writer;
        return w == null ? null : w.path();
    }

    public double seconds() {
        RecordingWriter w = writer;
        return w == null ? 0 : w.seconds(sampleRate);
    }

    /** Why recording stopped, or empty if it did not. */
    public String failure() {
        return failure;
    }

    @Override
    public void open() throws Exception {
        delegate.open();
    }

    @Override
    public void write(short[] pcm, int count) {
        delegate.write(pcm, count);
        RecordingWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            w.write(pcm, count);
        } catch (IOException e) {
            // Stop recording, keep playing.
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.log(Level.WARNING, "recording stopped", e);
            stop();
        }
    }

    @Override
    public void setVolume(double volume) {
        // Deliberately not applied to the recording: the file should carry what was broadcast, not
        // how loudly it happened to be played.
        delegate.setVolume(volume);
    }

    /** The real sink's, not this one's: a recorder has no playback buffer of its own to overrun. */
    @Override
    public long droppedSamples() {
        return delegate.droppedSamples();
    }

    @Override
    public long underrunSamples() {
        return delegate.underrunSamples();
    }

    @Override
    public void close() {
        stop();
        delegate.close();
    }

    /** Keeps a station name usable as a file name on every platform. */
    static String sanitise(String label) {
        if (label == null || label.isBlank()) {
            return "modula";
        }
        String cleaned = label.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (cleaned.isBlank()) {
            return "modula";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
