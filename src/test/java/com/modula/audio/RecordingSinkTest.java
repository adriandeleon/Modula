package com.modula.audio;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
