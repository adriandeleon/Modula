package com.modula.rds;

/**
 * One RDS group: four 16-bit data words, 104 bits on the wire.
 *
 * <p>Blocks A and B always mean the same thing — the station's identity and what kind of group this
 * is. C and D depend on the group type.
 *
 * @param a the PI code, a station identifier that never changes
 * @param b group type, version, and the flags common to every group
 * @param c group-dependent; in a version-B group it repeats the PI code
 * @param d group-dependent
 */
public record RdsGroup(int a, int b, int c, int d) {

    /** The station identifier. */
    public int programIdentification() {
        return a & 0xFFFF;
    }

    /** Group type 0–15. Together with {@link #isVersionB} this says how to read C and D. */
    public int type() {
        return (b >>> 12) & 0x0F;
    }

    /** Version B repeats the PI code in block C, leaving less room for payload. */
    public boolean isVersionB() {
        return (b & 0x0800) != 0;
    }

    /** Traffic Programme: this station carries traffic announcements. */
    public boolean trafficProgram() {
        return (b & 0x0400) != 0;
    }

    /** Programme type, 0–31. Meaning depends on region — see {@link ProgramType}. */
    public int programType() {
        return (b >>> 5) & 0x1F;
    }

    /** The five group-specific bits at the bottom of block B. */
    public int payloadBits() {
        return b & 0x1F;
    }
}
