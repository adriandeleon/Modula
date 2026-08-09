package com.modula.rds;

import com.modula.band.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsDecoderTest {

    private final RdsDecoder decoder = new RdsDecoder();

    @Test
    void assemblesTheStationNameFromItsFourSegments() {
        sendProgramService("BBC R4  ", 0x1234, 3);

        StationInfo info = decoder.stationInfo();
        assertEquals("BBC R4", info.programService(), "trailing padding should be trimmed");
        assertEquals(0x1234, info.programIdentification());
        assertEquals(3, info.programType());
    }

    /** A half-arrived name flickers if published, so nothing shows until every segment is in. */
    @Test
    void showsNothingUntilTheWholeNameHasArrived() {
        decoder.accept(basicTuning(0x1234, 0, 'B', 'B'));
        assertEquals("", decoder.stationInfo().programService());

        decoder.accept(basicTuning(0x1234, 1, 'C', ' '));
        decoder.accept(basicTuning(0x1234, 2, 'R', '4'));
        assertEquals("", decoder.stationInfo().programService(), "three of four segments is still incomplete");

        decoder.accept(basicTuning(0x1234, 3, ' ', ' '));
        assertEquals("BBC R4", decoder.stationInfo().programService());
    }

    @Test
    void assemblesRadioTextFromItsSixteenSegments() {
        String text = "Now playing: something with a reasonably long title indeed";
        sendRadioText(text, false);
        assertEquals(text, decoder.stationInfo().radioText());
    }

    /** The A/B flag toggling means "different message now" — usually the next song. */
    @Test
    void clearsRadioTextWhenTheStationTogglesTheFlag() {
        sendRadioText("First message", false);
        assertEquals("First message", decoder.stationInfo().radioText());

        // A partial second message under the opposite flag must not splice onto the first.
        decoder.accept(radioText(0x1234, 0, true, 'N', 'e', 'x', 't'));
        assertEquals("", decoder.stationInfo().radioText(), "the old message must be dropped, not extended");

        sendRadioText("Second message", true);
        assertEquals("Second message", decoder.stationInfo().radioText());
    }

    @Test
    void stopsRadioTextAtACarriageReturn() {
        StringBuilder text = new StringBuilder("Short message\r");
        while (text.length() < 64) {
            text.append('x');
        }
        sendRadioText(text.toString(), false);
        assertEquals("Short message", decoder.stationInfo().radioText());
    }

    /**
     * A station whose radio text is shorter than the field ends it with 0x0D and never transmits the
     * remaining segments. Waiting for all sixteen means such a message never appears however cleanly
     * it was received — observed on air, on a station sending 101 radio-text groups, none of which
     * displayed.
     */
    @Test
    void showsAShortMessageThatEndsBeforeTheLastSegment() {
        // Six characters and a terminator: segments 0 and 1 only, as a real station would send.
        decoder.accept(radioText(0x1234, 0, false, 'H', 'e', 'l', 'l'));
        decoder.accept(radioText(0x1234, 1, false, 'o', '!', '\r', ' '));

        assertEquals("Hello!", decoder.stationInfo().radioText(), "a terminated message must not wait for segment 15");
    }

    @Test
    void stillWaitsForAMessageWithNoTerminator() {
        decoder.accept(radioText(0x1234, 0, false, 'H', 'e', 'l', 'l'));
        decoder.accept(radioText(0x1234, 1, false, 'o', '!', ' ', ' '));

        assertEquals("", decoder.stationInfo().radioText(), "with no terminator the field is genuinely incomplete");
    }

    /**
     * Many stations scroll the eight-character name, cycling frames to spell out a longer message.
     * Merging segments across frames splices them: a station alternating "ESCUCHAS" and "D99"
     * displayed as "ES99  AS" until each cycle was assembled on its own.
     */
    @Test
    void doesNotSpliceFramesOfAScrollingStationName() {
        sendProgramService("ESCUCHAS", 0x1234, 0);
        assertEquals("ESCUCHAS", decoder.stationInfo().programService());

        // The next frame begins; a partial one must not merge into the last.
        decoder.accept(basicTuning(0x1234, 0, ' ', 'D'));
        decoder.accept(basicTuning(0x1234, 1, '9', '9'));
        assertEquals("ESCUCHAS", decoder.stationInfo().programService(), "hold the last whole frame, never a splice");

        decoder.accept(basicTuning(0x1234, 2, ' ', ' '));
        decoder.accept(basicTuning(0x1234, 3, ' ', ' '));
        assertEquals(" D99", decoder.stationInfo().programService());
    }

    @Test
    void readsTheTrafficFlags() {
        decoder.accept(new RdsGroup(0x1234, 0x0400 | 0x0010, 0x1234, 0x4142));
        assertTrue(decoder.stationInfo().trafficProgram());
        assertTrue(decoder.stationInfo().trafficAnnouncement());

        decoder.accept(new RdsGroup(0x1234, 0x0000, 0x1234, 0x4142));
        assertFalse(decoder.stationInfo().trafficProgram());
        assertFalse(decoder.stationInfo().trafficAnnouncement());
    }

    @Test
    void ignoresGroupTypesItDoesNotRead() {
        sendProgramService("KEEP ME ", 0x1234, 1);
        // Clock time, alternative frequencies, open data — all legitimate, none displayed.
        for (int type : new int[] {1, 3, 4, 8, 10, 14, 15}) {
            decoder.accept(new RdsGroup(0x1234, type << 12, 0xFFFF, 0xFFFF));
        }
        assertEquals("KEEP ME", decoder.stationInfo().programService(), "unknown groups must not disturb the display");
    }

    /**
     * This test used to assert that 0xFF became a space.
     *
     * <p>That was the bug, not the contract: 0xFF is not an unprintable code in RDS, it is the letter
     * ŧ. The decoder blanked the whole range above 0x7E, which is exactly where the accented
     * characters live, so every station with one in its name showed a hole instead.
     */
    @Test
    void decodesTheRdsRepertoireRatherThanAssumingAscii() {
        decoder.accept(basicTuning(0x1234, 0, (char) 0x80, (char) 0x9A));
        decoder.accept(basicTuning(0x1234, 1, 'O', 'K'));
        decoder.accept(basicTuning(0x1234, 2, ' ', ' '));
        decoder.accept(basicTuning(0x1234, 3, ' ', ' '));
        assertEquals("áñOK", decoder.stationInfo().programService());
    }

    /** A genuine control code still has to vanish rather than print. */
    @Test
    void controlCodesDoNotReachTheDisplay() {
        decoder.accept(basicTuning(0x1234, 0, (char) 0x00, 'X'));
        decoder.accept(basicTuning(0x1234, 1, 'O', 'K'));
        decoder.accept(basicTuning(0x1234, 2, ' ', ' '));
        decoder.accept(basicTuning(0x1234, 3, ' ', ' '));
        String name = decoder.stationInfo().programService();
        assertEquals("XOK", name.strip().replace("\u0000", ""), "a NUL must not render as a glyph");
    }

    @Test
    void resetForgetsTheStation() {
        sendProgramService("STATION ", 0x1234, 5);
        decoder.reset();

        StationInfo info = decoder.stationInfo();
        assertEquals("", info.programService());
        assertEquals(0, info.programIdentification());
        assertFalse(info.isPresent());
    }

    /** The same code names different things either side of the Atlantic. */
    @Test
    void programTypeNamesFollowTheRegion() {
        assertEquals("Rock", ProgramType.name(5, Region.AMERICAS));
        assertEquals("Education", ProgramType.name(5, Region.EUROPE));
        assertEquals("", ProgramType.name(0, Region.AMERICAS), "code zero is 'none', not a name");
        assertEquals("", ProgramType.name(99, Region.EUROPE), "out-of-range codes must not throw");
    }

    // --- helpers -------------------------------------------------------------------------------

    private void sendProgramService(String name, int pi, int programType) {
        for (int segment = 0; segment < 4; segment++) {
            int b = ((programType & 0x1F) << 5) | segment;
            decoder.accept(new RdsGroup(pi, b, pi, (name.charAt(segment * 2) << 8) | name.charAt(segment * 2 + 1)));
        }
    }

    private void sendRadioText(String text, boolean flag) {
        String padded = text.length() >= 64 ? text.substring(0, 64) : text + " ".repeat(64 - text.length());
        for (int segment = 0; segment < 16; segment++) {
            decoder.accept(radioText(
                    0x1234,
                    segment,
                    flag,
                    padded.charAt(segment * 4),
                    padded.charAt(segment * 4 + 1),
                    padded.charAt(segment * 4 + 2),
                    padded.charAt(segment * 4 + 3)));
        }
    }

    private static RdsGroup basicTuning(int pi, int segment, char first, char second) {
        return new RdsGroup(pi, segment, pi, (first << 8) | second);
    }

    private static RdsGroup radioText(int pi, int segment, boolean flag, char... characters) {
        int b = (2 << 12) | (flag ? 0x10 : 0) | segment;
        int c = (characters[0] << 8) | characters[1];
        int d = characters.length > 2 ? (characters[2] << 8) | characters[3] : 0;
        return new RdsGroup(pi, b, c, d);
    }
}
