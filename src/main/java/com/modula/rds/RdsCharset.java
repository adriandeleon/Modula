package com.modula.rds;

/**
 * The RDS character repertoire — the default G0 table, otherwise known as EBU Latin.
 *
 * <p>RDS does not send ASCII. Its default table agrees with ASCII across most of the printable range
 * but diverges in three places, and everything above 0x7F is a Latin repertoire covering the accented
 * characters European and Latin American broadcasters actually use. Treating those bytes as ASCII
 * does not merely render them oddly: the previous implementation replaced every one with a space, so
 * a station calling itself {@code MÚSICA} came through as {@code M SICA}.
 *
 * <p>Pure and table-driven. {@link #decode(int)} is the only entry point.
 *
 * <p><b>What this does not do:</b> RDS defines two auxiliary tables, G1 and G2, selected by an escape
 * sequence. They are rare — G0 is what essentially every broadcaster transmits — and a station using
 * one will show G0 characters rather than nothing. Codes below 0x20 are control codes and are passed
 * through unchanged, which matters because 0x0D terminates a radio-text message and the decoder needs
 * to see it.
 */
final class RdsCharset {

    /**
     * Code points 0x20 through 0xFF, in order.
     *
     * <p>Transcribed from the EBU Latin table. The three departures from ASCII in the lower half are
     * 0x24 (a generic currency sign, not a dollar), 0x60 (a double vertical bar, not a backtick) and
     * 0x7E (a macron, not a tilde); 0x7F is undefined and blanks.
     */
    private static final String TABLE =
            // 0x20
            " !\"#¤%&'()*+,-./"
                    // 0x30
                    + "0123456789:;<=>?"
                    // 0x40
                    + "@ABCDEFGHIJKLMNO"
                    // 0x50
                    + "PQRSTUVWXYZ[\\]^_"
                    // 0x60
                    + "‖abcdefghijklmno"
                    // 0x70
                    + "pqrstuvwxyz{|}¯ "
                    // 0x80
                    + "áàéèíìóòúùÑÇŞß¡Ĳ"
                    // 0x90
                    + "âäêëîïôöûüñçşğıĳ"
                    // 0xA0
                    + "ªα©‰Ğěňőπ€£$←↑→↓"
                    // 0xB0
                    + "º¹²³±İńűµ¿÷°¼½¾§"
                    // 0xC0
                    + "ÁÀÉÈÍÌÓÒÚÙŘČŠŽÐĿ"
                    // 0xD0
                    + "ÂÄÊËÎÏÔÖÛÜřčšžđŀ"
                    // 0xE0
                    + "ÃÅÆŒŷÝÕØÞŊŔĆŚŹŦð"
                    // 0xF0
                    + "Ťãåæœŵýõøþŋŕćśźŧ";

    /** The first code point {@link #TABLE} describes; everything below it is a control code. */
    private static final int FIRST = 0x20;

    private RdsCharset() {}

    /**
     * Maps one RDS byte to a Java character.
     *
     * @param value the raw byte; only its low eight bits are read
     * @return the character, or the value unchanged when it is a control code
     */
    static char decode(int value) {
        int c = value & 0xFF;
        if (c < FIRST) {
            return (char) c; // a control code, and 0x0D is load-bearing: it ends a radio-text message
        }
        return TABLE.charAt(c - FIRST);
    }

    /** Exposed for the test that checks the table is the length the range requires. */
    static int size() {
        return TABLE.length();
    }
}
