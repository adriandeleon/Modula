package com.modula.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams 16-bit PCM into a WAV file.
 *
 * <p>Hand-written rather than {@code AudioSystem.write}, which wants the whole recording as an
 * {@code AudioInputStream} up front — fine for a clip, useless for something that runs until the
 * listener stops it.
 *
 * <p><b>The header is rewritten on close, and the file is still playable if it never is.</b> A WAV
 * header carries two byte counts that are not known until the recording ends, so this writes
 * placeholders, streams the audio, then seeks back and patches them. If the process dies first the
 * placeholders are left as the largest plausible size, which every player treats as "read to the end
 * of the file" — so a crash costs the last buffer, not the recording.
 */
public final class WavWriter implements AutoCloseable {

    private static final int HEADER_BYTES = 44;

    /** Written up front so an unclosed file still plays to its end rather than reporting zero length. */
    private static final int UNKNOWN_SIZE = Integer.MAX_VALUE - HEADER_BYTES;

    private final Path path;
    private final FileChannel channel;
    private final OutputStream out;
    private final int channels;
    private long dataBytes;

    public WavWriter(Path path, int sampleRate, int channels) throws IOException {
        this.path = path;
        this.channels = channels;
        this.channel = FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.out = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel), 1 << 16);
        out.write(header(sampleRate, channels, UNKNOWN_SIZE));
    }

    public Path path() {
        return path;
    }

    /** Seconds recorded so far, from the byte count rather than a clock. */
    public double seconds(int sampleRate) {
        return dataBytes / (double) (2 * channels * sampleRate);
    }

    public long bytesWritten() {
        return dataBytes;
    }

    /** Appends {@code count} samples of interleaved PCM. */
    public void write(short[] pcm, int count) throws IOException {
        byte[] bytes = new byte[count * 2];
        for (int n = 0, b = 0; n < count; n++, b += 2) {
            bytes[b] = (byte) (pcm[n] & 0xFF);
            bytes[b + 1] = (byte) ((pcm[n] >> 8) & 0xFF);
        }
        out.write(bytes);
        dataBytes += bytes.length;
    }

    /** Flushes, then patches the two sizes the header could not know when it was written. */
    @Override
    public void close() throws IOException {
        try (FileChannel ch = channel;
                OutputStream stream = out) {
            stream.flush();
            ByteBuffer sizes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            sizes.putInt((int) (dataBytes + HEADER_BYTES - 8)).flip();
            ch.write(sizes, 4);
            sizes.clear();
            sizes.putInt((int) dataBytes).flip();
            ch.write(sizes, 40);
        }
    }

    /** The canonical 44-byte RIFF/WAVE header for uncompressed PCM. */
    static byte[] header(int sampleRate, int channels, int dataBytes) {
        int byteRate = sampleRate * channels * 2;
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(dataBytes + HEADER_BYTES - 8);
        b.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16); // PCM format chunk size
        b.putShort((short) 1); // uncompressed
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) (channels * 2)); // block align
        b.putShort((short) 16); // bits per sample
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(dataBytes);
        return b.array();
    }
}
