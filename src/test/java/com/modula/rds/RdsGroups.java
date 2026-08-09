package com.modula.rds;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds RDS bit streams the way a transmitter would, so the decoder can be tested against known
 * content rather than against itself.
 */
public final class RdsGroups {

    private RdsGroups() {}

    /** The 104 bits of one group: four blocks, each 16 data bits plus checkword and offset. */
    public static boolean[] encodeGroup(int a, int b, int c, int d, boolean versionB) {
        boolean[] bits = new boolean[104];
        writeBlock(bits, 0, RdsCrc.encodeBlock(a, RdsCrc.Offset.A));
        writeBlock(bits, 26, RdsCrc.encodeBlock(b, RdsCrc.Offset.B));
        writeBlock(bits, 52, RdsCrc.encodeBlock(c, versionB ? RdsCrc.Offset.C_PRIME : RdsCrc.Offset.C));
        writeBlock(bits, 78, RdsCrc.encodeBlock(d, RdsCrc.Offset.D));
        return bits;
    }

    private static void writeBlock(boolean[] bits, int offset, int block) {
        for (int i = 0; i < 26; i++) {
            bits[offset + i] = (block & (1 << (25 - i))) != 0;
        }
    }

    /** Concatenates groups into one stream. */
    public static boolean[] stream(List<boolean[]> groups) {
        int total = groups.stream().mapToInt(g -> g.length).sum();
        boolean[] out = new boolean[total];
        int at = 0;
        for (boolean[] group : groups) {
            System.arraycopy(group, 0, out, at, group.length);
            at += group.length;
        }
        return out;
    }

    /**
     * The four group-0A transmissions that spell out an eight-character station name, plus the
     * sixteen group-2A transmissions carrying up to 64 characters of radio text.
     */
    public static boolean[] station(int pi, String programService, String radioText, int programType) {
        List<boolean[]> groups = new ArrayList<>();
        String ps = pad(programService, 8);

        for (int segment = 0; segment < 4; segment++) {
            int b = (0 << 12) | ((programType & 0x1F) << 5) | segment;
            int d = (ps.charAt(segment * 2) << 8) | ps.charAt(segment * 2 + 1);
            groups.add(encodeGroup(pi, b, pi, d, false));
        }

        String rt = pad(radioText, 64);
        for (int segment = 0; segment < 16; segment++) {
            int b = (2 << 12) | ((programType & 0x1F) << 5) | segment;
            int c = (rt.charAt(segment * 4) << 8) | rt.charAt(segment * 4 + 1);
            int d = (rt.charAt(segment * 4 + 2) << 8) | rt.charAt(segment * 4 + 3);
            groups.add(encodeGroup(pi, b, c, d, false));
        }
        return stream(groups);
    }

    private static String pad(String text, int length) {
        String trimmed = text.length() > length ? text.substring(0, length) : text;
        return trimmed + " ".repeat(length - trimmed.length());
    }
}
