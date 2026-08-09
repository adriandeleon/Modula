package com.modula.rds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RdsCharsetTest {

    @Test
    void theTableCoversExactlyTheRangeItClaims() {
        assertEquals(0x100 - 0x20, RdsCharset.size(), "table must describe 0x20..0xFF");
    }

    @Test
    void lettersAndDigitsAreAscii() {
        for (int c = 'A'; c <= 'Z'; c++) {
            assertEquals((char) c, RdsCharset.decode(c));
        }
        for (int c = 'a'; c <= 'z'; c++) {
            assertEquals((char) c, RdsCharset.decode(c));
        }
        for (int c = '0'; c <= '9'; c++) {
            assertEquals((char) c, RdsCharset.decode(c));
        }
        assertEquals(' ', RdsCharset.decode(0x20));
    }

    /** The three places the default table is not ASCII, which is the whole reason it is a table. */
    @Test
    void theLowerHalfDivergesFromAsciiInExactlyThreePlaces() {
        int divergences = 0;
        for (int c = 0x20; c <= 0x7E; c++) {
            if (RdsCharset.decode(c) != (char) c) {
                divergences++;
            }
        }
        assertEquals(3, divergences);
        assertEquals('¤', RdsCharset.decode(0x24)); // currency sign, not a dollar
        assertEquals('‖', RdsCharset.decode(0x60)); // double vertical bar, not a backtick
        assertEquals('¯', RdsCharset.decode(0x7E)); // macron, not a tilde
    }

    /** The characters this exists for: Spanish stations are the ones being read here. */
    @Test
    void spanishAccentsDecode() {
        assertEquals('á', RdsCharset.decode(0x80));
        assertEquals('é', RdsCharset.decode(0x82));
        assertEquals('í', RdsCharset.decode(0x84));
        assertEquals('ó', RdsCharset.decode(0x86));
        assertEquals('ú', RdsCharset.decode(0x88));
        assertEquals('ñ', RdsCharset.decode(0x9A));
        assertEquals('Ñ', RdsCharset.decode(0x8A));
        assertEquals('ü', RdsCharset.decode(0x99));
        assertEquals('¡', RdsCharset.decode(0x8E));
        assertEquals('¿', RdsCharset.decode(0xB9));
        assertEquals('Á', RdsCharset.decode(0xC0));
        assertEquals('Ú', RdsCharset.decode(0xC8));
    }

    @Test
    void otherEuropeanAccentsDecode() {
        assertEquals('ä', RdsCharset.decode(0x91));
        assertEquals('ö', RdsCharset.decode(0x97));
        assertEquals('ç', RdsCharset.decode(0x9B));
        assertEquals('ß', RdsCharset.decode(0x8D));
        assertEquals('è', RdsCharset.decode(0x83));
        assertEquals('Ö', RdsCharset.decode(0xD7));
    }

    /**
     * 0x0D is load-bearing: it terminates a radio-text message, and a charset that turned it into a
     * printable character would leave every short message padded with whatever followed it.
     */
    @Test
    void controlCodesPassThroughUntouched() {
        assertEquals('\r', RdsCharset.decode(0x0D));
        assertEquals('\n', RdsCharset.decode(0x0A));
        assertEquals('\0', RdsCharset.decode(0x00));
    }

    /** Nothing in the printable range may decode to a space it did not mean, which was the old bug. */
    @Test
    void noPrintableCodeSilentlyBecomesASpace() {
        for (int c = 0x21; c <= 0xFF; c++) {
            if (c == 0x7F) {
                continue; // undefined in the table, and blanks by design
            }
            assertNotEquals(' ', RdsCharset.decode(c), "0x%02X blanked".formatted(c));
        }
    }

    @Test
    void onlyTheLowEightBitsAreRead() {
        assertEquals(RdsCharset.decode(0x41), RdsCharset.decode(0xFF41));
    }
}
