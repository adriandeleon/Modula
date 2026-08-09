package com.modula.rds;

/**
 * Turns decoded groups into what a listener sees: station name, radio text, programme type.
 *
 * <p>Only groups 0 and 2 are read — basic tuning and radio text. Those carry everything on the
 * display; the rest of the sixteen group types are clock, alternative frequencies, traffic messages
 * and open data, none of which this radio surfaces. Unknown groups are ignored rather than treated
 * as errors: a station may transmit any of them and it is not a fault.
 *
 * <p>Pure and stateful. Not thread-safe.
 */
public final class RdsDecoder {

    /** Station name: 8 characters, arriving 2 at a time across 4 segments. */
    private static final int PS_LENGTH = 8;

    private static final int PS_SEGMENT = 2;

    /** Radio text: 64 characters in version A (4 at a time), 32 in version B (2 at a time). */
    private static final int RT_LENGTH = 64;

    /** 0x0D ends a radio-text message early; it must survive character decoding to be seen. */
    private static final int END_OF_TEXT = 0x0D;

    /** Cyclic: stations scroll this field, so each cycle must be assembled on its own. */
    private final RdsText programService = new RdsText(PS_LENGTH, PS_SEGMENT, true, RdsText.NO_TERMINATOR);

    /** Terminated: a short message ends at 0x0D and its later segments are never transmitted. */
    private final RdsText radioTextA = new RdsText(RT_LENGTH, 4, false, END_OF_TEXT);

    private final RdsText radioTextB = new RdsText(RT_LENGTH / 2, 2, false, END_OF_TEXT);

    private int programIdentification;
    private int programType;
    private boolean trafficProgram;
    private boolean trafficAnnouncement;
    private boolean radioTextFlag;
    private boolean radioTextFlagSeen;
    private boolean lastWasVersionB;

    /** Absorbs one group. */
    public void accept(RdsGroup group) {
        programIdentification = group.programIdentification();
        programType = group.programType();
        trafficProgram = group.trafficProgram();

        switch (group.type()) {
            case 0 -> acceptBasicTuning(group);
            case 2 -> acceptRadioText(group);
            default -> {
                // Clock, alternative frequencies, open data — legitimate, just not shown here.
            }
        }
    }

    /** Group 0A/0B: the station name, plus the traffic-announcement flag. */
    private void acceptBasicTuning(RdsGroup group) {
        trafficAnnouncement = (group.payloadBits() & 0x10) != 0;

        int segment = group.payloadBits() & 0x03;
        int d = group.d();
        programService.set(segment, decode(d >>> 8), decode(d));
    }

    /** Group 2A/2B: radio text. */
    private void acceptRadioText(RdsGroup group) {
        boolean flag = (group.payloadBits() & 0x10) != 0;
        boolean versionB = group.isVersionB();

        // The A/B flag toggling is the station saying "this is different text now" — usually a new
        // song. Without honouring it the display splices the end of the old message onto the new.
        if (!radioTextFlagSeen || flag != radioTextFlag || versionB != lastWasVersionB) {
            radioTextA.clear();
            radioTextB.clear();
            radioTextFlag = flag;
            radioTextFlagSeen = true;
            lastWasVersionB = versionB;
        }

        int segment = group.payloadBits() & 0x0F;
        if (versionB) {
            int d = group.d();
            radioTextB.set(segment, decode(d >>> 8), decode(d));
        } else {
            int c = group.c();
            int d = group.d();
            radioTextA.set(segment, decode(c >>> 8), decode(c), decode(d >>> 8), decode(d));
        }
    }

    public StationInfo stationInfo() {
        String text = lastWasVersionB ? radioTextB.value() : radioTextA.value();
        return new StationInfo(
                programIdentification,
                programService.value(),
                trimAtCarriageReturn(text),
                programType,
                trafficProgram,
                trafficAnnouncement);
    }

    /** Wipes everything. Call on retune — none of it belongs to the new station. */
    public void reset() {
        programService.clear();
        radioTextA.clear();
        radioTextB.clear();
        programIdentification = 0;
        programType = 0;
        trafficProgram = false;
        trafficAnnouncement = false;
        radioTextFlag = false;
        radioTextFlagSeen = false;
        lastWasVersionB = false;
    }

    /**
     * Maps one RDS character to Java.
     *
     * <p>RDS has its own repertoire rather than using ASCII. Its default table agrees with ASCII
     * across the printable range, which covers all but a handful of real broadcasts, so anything
     * outside that becomes a space rather than mojibake on the display.
     */
    private static char decode(int value) {
        int c = value & 0xFF;
        if (c == END_OF_TEXT) {
            return (char) END_OF_TEXT; // meaningful: it terminates a radio-text message
        }
        return c >= 0x20 && c < 0x7F ? (char) c : ' ';
    }

    /** Everything from the terminator onward is padding the station did not mean to send. */
    private static String trimAtCarriageReturn(String text) {
        int end = text.indexOf(END_OF_TEXT);
        return (end < 0 ? text : text.substring(0, end)).stripTrailing();
    }
}
