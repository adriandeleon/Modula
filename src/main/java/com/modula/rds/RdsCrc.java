package com.modula.rds;

/**
 * The RDS block error-protection code, and the offset words that identify block position.
 *
 * <p>Each 26-bit block is 16 data bits followed by a 10-bit checkword, where the checkword is the
 * CRC of the data <b>plus</b> (XOR) a constant that depends on which of the four positions in the
 * group the block occupies. That is a neat trick: it makes error detection and block identification
 * the same operation. Compute the CRC of the received data, XOR it with the received checkword, and
 * whatever offset word comes out tells you both that the block is intact and where it sits.
 *
 * <p>Pure and stateless.
 */
public final class RdsCrc {

    /**
     * The exponents of g(x) = x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + 1.
     *
     * <p>Built from the exponents rather than written as a bit literal, because a transposed literal
     * here is invisible to every self-consistent test: encoder and decoder share the constant, so a
     * wrong polynomial still round-trips perfectly and still detects every single-bit, double-bit and
     * five-bit burst error. It is a perfectly good CRC — just not the one real transmitters use, and
     * the only symptom is that no real broadcast ever decodes. {@code RdsCrcTest} pins the value
     * against these exponents so the code cannot drift from its own specification again.
     */
    static final int[] POLYNOMIAL_EXPONENTS = {10, 8, 7, 5, 4, 3, 0};

    static final int POLYNOMIAL = polynomial();

    private static int polynomial() {
        int value = 0;
        for (int exponent : POLYNOMIAL_EXPONENTS) {
            value |= 1 << exponent;
        }
        return value;
    }

    private RdsCrc() {}

    /** Where a block sits in its group. C' appears in version-B groups in place of C. */
    public enum Offset {
        A(0b00_1111_1100),
        B(0b01_1001_1000),
        C(0b01_0110_1000),
        C_PRIME(0b11_0101_0000),
        D(0b01_1011_0100);

        private final int word;

        Offset(int word) {
            this.word = word;
        }

        public int word() {
            return word;
        }
    }

    /** The 10-bit CRC of a block's 16 data bits, before the offset word is added. */
    public static int checkword(int data) {
        int register = (data & 0xFFFF) << 10;
        for (int bit = 25; bit >= 10; bit--) {
            if ((register & (1 << bit)) != 0) {
                register ^= POLYNOMIAL << (bit - 10);
            }
        }
        return register & 0x3FF;
    }

    /** Builds a complete 26-bit block: data, then CRC plus the position's offset word. */
    public static int encodeBlock(int data, Offset offset) {
        return ((data & 0xFFFF) << 10) | ((checkword(data) ^ offset.word()) & 0x3FF);
    }

    /**
     * Identifies a received 26-bit block.
     *
     * @return which position it occupies, or null if it is corrupt — the two are the same test
     */
    public static Offset identify(int block) {
        int data = (block >>> 10) & 0xFFFF;
        int syndrome = (block & 0x3FF) ^ checkword(data);
        for (Offset offset : Offset.values()) {
            if (offset.word() == syndrome) {
                return offset;
            }
        }
        return null;
    }

    /** Whether a received block is intact and sits at the expected position. */
    public static boolean matches(int block, Offset expected) {
        return identify(block) == expected;
    }

    /** The 16 data bits of a 26-bit block. */
    public static int dataOf(int block) {
        return (block >>> 10) & 0xFFFF;
    }
}
