package com.modula.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Finding and driving the external encoder.
 *
 * <p>ffmpeg only. It handles both formats, it is on most machines, and one detection path is one
 * thing to explain when it is missing — supporting a second tool would double the ways this can be
 * half-working.
 *
 * <p>The argv builder is pure and unit-tested. Detection is not: it looks on the PATH.
 */
public final class Encoders {

    /** MP3 at this rate is transparent for a source already band-limited to 15 kHz. */
    static final String MP3_BITRATE = "128k";

    private static final long DETECT_TIMEOUT_SECONDS = 5;

    private static volatile Boolean available;

    private Encoders() {}

    /**
     * The command line for encoding raw PCM arriving on stdin.
     *
     * <p>{@code -f s16le} because the sink hands over exactly what it hands the sound card, and
     * {@code -y} because the caller has already chosen the name and a prompt would hang a pipe.
     */
    public static List<String> argv(String ffmpeg, RecordingFormat format, int sampleRate, int channels, Path dest) {
        List<String> argv = new ArrayList<>(List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "s16le",
                "-ar",
                Integer.toString(sampleRate),
                "-ac",
                Integer.toString(channels),
                "-i",
                "-"));
        if (format == RecordingFormat.MP3) {
            argv.addAll(List.of("-codec:a", "libmp3lame", "-b:a", MP3_BITRATE));
        } else if (format == RecordingFormat.FLAC) {
            argv.addAll(List.of("-codec:a", "flac"));
        }
        argv.add(dest.toString());
        return List.copyOf(argv);
    }

    /** The executable name, honouring an override. Pure. */
    public static String command(String configured) {
        String trimmed = configured == null ? "" : configured.strip();
        return trimmed.isEmpty() ? defaultCommand() : trimmed;
    }

    static String defaultCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "ffmpeg.exe"
                : "ffmpeg";
    }

    /**
     * Whether the encoder can be run. Cached: this spawns a process, and the answer does not change
     * while the application is open.
     */
    public static boolean isAvailable(String configured) {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean found = probe(command(configured));
        available = found;
        return found;
    }

    /** Forgets the cached probe, for when the configured path changes. */
    public static void forgetDetection() {
        available = null;
    }

    private static boolean probe(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close(); // nothing to send, and an open pipe can stall it
            if (!process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false; // not on the PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Which formats can actually be written right now, for a picker that should not offer a lie. */
    public static List<RecordingFormat> usableFormats(String configured) {
        List<RecordingFormat> usable = new ArrayList<>();
        for (RecordingFormat format : RecordingFormat.values()) {
            if (!format.needsEncoder() || isAvailable(configured)) {
                usable.add(format);
            }
        }
        return List.copyOf(usable);
    }

    /** Whether a destination's directory exists, so a failure is reported before the process starts. */
    static boolean destinationWritable(Path dest) {
        Path parent = dest.getParent();
        return parent != null && Files.isDirectory(parent);
    }
}
