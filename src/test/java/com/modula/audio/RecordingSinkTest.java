package com.modula.audio;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingSinkTest {

    /**
     * The label comes from RDS, which is to say from a stranger's transmitter. Separators must not
     * survive it: the name is joined onto a directory, so a station calling itself {@code ../../x}
     * would otherwise choose where the recording lands.
     */
    @Test
    void stripsAnythingThatWouldSteerThePath() {
        assertFalse(RecordingSink.sanitise("../../etc/passwd").contains("/"));
        assertFalse(RecordingSink.sanitise("..\\..\\windows").contains("\\"));
        assertEquals(Path.of(RecordingSink.sanitise("../../etc/passwd")).getNameCount(), 1);
    }

    @Test
    void keepsOrdinaryStationNamesReadable() {
        assertEquals("KEXP", RecordingSink.sanitise("KEXP"));
        assertEquals("BBC-R4", RecordingSink.sanitise("BBC R4"));
        assertEquals("98.9", RecordingSink.sanitise("98.9"));
    }

    /** RDS pads to eight characters, so almost every name arrives with spaces around it. */
    @Test
    void trimsThePaddingRdsAlwaysSends() {
        assertEquals("ESCUCHAS", RecordingSink.sanitise("  ESCUCHAS  "));
        assertEquals("D99", RecordingSink.sanitise(" D99 "));
    }

    @Test
    void fallsBackWhenNothingUsableIsLeft() {
        assertEquals("modula", RecordingSink.sanitise(""));
        assertEquals("modula", RecordingSink.sanitise("   "));
        assertEquals("modula", RecordingSink.sanitise(null));
        assertEquals("modula", RecordingSink.sanitise("///"));
    }

    /** A file name is not a place for however many characters a transmitter feels like sending. */
    @Test
    void boundsTheLength() {
        assertTrue(RecordingSink.sanitise("A".repeat(500)).length() <= 40);
    }

    /**
     * A write fails on its own — a full disk. Recording ends and says why, and the listener's Stop that
     * arrives afterwards finds nothing to stop rather than a file to name: the null that used to be
     * dereferenced on the FX thread.
     */
    @Test
    void aFailedWriteEndsTheRecordingAndSaysWhy() {
        RecordingSink sink = new RecordingSink(new SilentSink(), 48_000, 2);
        FakeWriter full = new FakeWriter("full.wav");
        full.onWrite = () -> {
            throw new IOException("No space left on device");
        };
        sink.begin(full, "");

        sink.write(new short[4], 4);

        assertFalse(sink.isRecording());
        assertTrue(full.closed, "the file is finalised so what was written still plays");
        assertEquals("No space left on device", sink.failure());
        assertNull(sink.stop());
    }

    /**
     * The race: the FX thread stops one recording and starts the next while the receive thread is
     * inside a write to the first, which then fails on the closed file. That failure belongs to the
     * old recording; it used to end whichever one was current, so a scheduled hand-over killed the new
     * recording as it began, and reported an error for a Stop that had worked.
     */
    @Test
    void aWriteFailingOnAStoppedFileLeavesTheNextRecordingAlone() {
        RecordingSink sink = new RecordingSink(new SilentSink(), 48_000, 2);
        FakeWriter first = new FakeWriter("first.wav");
        FakeWriter second = new FakeWriter("second.wav");
        first.onWrite = () -> {
            // What the FX thread does mid-write, run here so the interleaving is exact.
            assertEquals(Path.of("first.wav"), sink.stop());
            sink.begin(second, "");
            throw new IOException("Stream closed");
        };
        sink.begin(first, "");

        sink.write(new short[4], 4);

        assertTrue(sink.isRecording(), "the new recording survives the old one's failure");
        assertEquals(Path.of("second.wav"), sink.file());
        assertFalse(second.closed);
        assertEquals("", sink.failure(), "a Stop that worked is not reported as a failure");
    }

    private interface Write {
        void run() throws IOException;
    }

    private static final class FakeWriter implements RecordingWriter {
        private final Path path;
        Write onWrite = () -> {};
        boolean closed;

        FakeWriter(String name) {
            this.path = Path.of(name);
        }

        @Override
        public void write(short[] pcm, int count) throws IOException {
            onWrite.run();
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public double seconds(int sampleRate) {
            return 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class SilentSink implements AudioSink {
        @Override
        public void open() {}

        @Override
        public void write(short[] pcm, int count) {}

        @Override
        public void setVolume(double volume) {}

        @Override
        public void close() {}
    }
}
