package com.modula.audio;

/**
 * What a recording is written as.
 *
 * <p>WAV needs nothing and always works. The other two need an external encoder, because there is no
 * credible pure-Java MP3 encoder and shipping one for FLAC would be a dependency, a module descriptor
 * and a jlink entry for something ffmpeg does on almost every machine already.
 *
 * <p>The sizes are the reason this exists at all: 48 kHz stereo 16-bit PCM is <b>691 MB per hour</b>.
 * A two-hour show is 1.4 GB of WAV, about 700 MB of FLAC, or 57 MB of 128 kbps MP3 — and since the
 * source is band-limited to 15 kHz with noise on top, that last one is effectively transparent.
 */
public enum RecordingFormat {
    WAV("wav", false, "uncompressed, about 690 MB an hour"),
    FLAC("flac", true, "lossless, about half the size of WAV"),
    MP3("mp3", true, "lossy, about 57 MB an hour");

    private final String extension;
    private final boolean needsEncoder;
    private final String description;

    RecordingFormat(String extension, boolean needsEncoder, String description) {
        this.extension = extension;
        this.needsEncoder = needsEncoder;
        this.description = description;
    }

    public String extension() {
        return extension;
    }

    public boolean needsEncoder() {
        return needsEncoder;
    }

    public String description() {
        return description;
    }

    /** Lenient: an unreadable or absent setting records WAV rather than refusing to record. */
    public static RecordingFormat of(String name) {
        if (name != null) {
            for (RecordingFormat format : values()) {
                if (format.name().equalsIgnoreCase(name.strip())) {
                    return format;
                }
            }
        }
        return WAV;
    }
}
