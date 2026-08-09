package com.modula.rds;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RdsCrcTest {

    /**
     * The one property no self-consistent test can check.
     *
     * <p>Encoder and decoder share the constant, so a transposed polynomial still round-trips
     * perfectly and still detects every error the tests below throw at it — it is simply a different
     * CRC from the one transmitters use, and the only symptom is that no real broadcast ever decodes.
     * This shipped once, as 0x6D9 in place of 0x5B9, and cost a full field-debugging cycle to find.
     */
    @Test
    void polynomialMatchesTheGeneratorItDocuments() {
        int expected = 0;
        for (int exponent : new int[] {10, 8, 7, 5, 4, 3, 0}) {
            expected |= 1 << exponent;
        }
        assertEquals(expected, RdsCrc.POLYNOMIAL, "g(x) = x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + 1");
        assertEquals(0x5B9, RdsCrc.POLYNOMIAL, "the value the RDS standard specifies");
    }

    @Test
    void everyBlockIdentifiesItsOwnPosition() {
        for (RdsCrc.Offset offset : RdsCrc.Offset.values()) {
            int block = RdsCrc.encodeBlock(0x1234, offset);
            assertEquals(offset, RdsCrc.identify(block), "round trip for " + offset);
            assertEquals(0x1234, RdsCrc.dataOf(block));
        }
    }

    @Test
    void roundTripsEveryDataWord() {
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            int data = random.nextInt(0x10000);
            int block = RdsCrc.encodeBlock(data, RdsCrc.Offset.B);
            assertEquals(RdsCrc.Offset.B, RdsCrc.identify(block));
            assertEquals(data, RdsCrc.dataOf(block));
        }
    }

    /** The code's whole purpose: a corrupted block must not be mistaken for a valid one. */
    @Test
    void detectsEverySingleBitError() {
        int block = RdsCrc.encodeBlock(0xABCD, RdsCrc.Offset.A);
        for (int bit = 0; bit < 26; bit++) {
            int corrupted = block ^ (1 << bit);
            assertNotEquals(RdsCrc.Offset.A, RdsCrc.identify(corrupted), "undetected error at bit " + bit);
        }
    }

    @Test
    void detectsEveryDoubleBitError() {
        int block = RdsCrc.encodeBlock(0x5555, RdsCrc.Offset.D);
        for (int i = 0; i < 26; i++) {
            for (int j = i + 1; j < 26; j++) {
                int corrupted = block ^ (1 << i) ^ (1 << j);
                assertNotEquals(
                        RdsCrc.Offset.D, RdsCrc.identify(corrupted), "undetected error at %d,%d".formatted(i, j));
            }
        }
    }

    /** The five-bit burst the standard guarantees to catch. */
    @Test
    void detectsEveryBurstOfUpToFiveBits() {
        int block = RdsCrc.encodeBlock(0x0F0F, RdsCrc.Offset.C);
        for (int start = 0; start <= 26 - 5; start++) {
            for (int pattern = 1; pattern < 32; pattern++) {
                int corrupted = block ^ (pattern << start);
                assertNotEquals(
                        RdsCrc.Offset.C,
                        RdsCrc.identify(corrupted),
                        "undetected burst %d at %d".formatted(pattern, start));
            }
        }
    }

    @Test
    void randomNoiseAlmostNeverLooksLikeAValidBlock() {
        Random random = new Random(3);
        int matches = 0;
        int trials = 200_000;
        for (int i = 0; i < trials; i++) {
            if (RdsCrc.identify(random.nextInt() & 0x3FF_FFFF) != null) {
                matches++;
            }
        }
        // Five offset words out of 1024 syndromes, so roughly one window in 205.
        double rate = matches / (double) trials;
        assertEquals(5.0 / 1024.0, rate, 0.002, "false-match rate should match the 5-in-1024 expectation");
    }

    @Test
    void aBlockAtTheWrongPositionIsRejected() {
        int block = RdsCrc.encodeBlock(0x1234, RdsCrc.Offset.A);
        assertNotEquals(RdsCrc.Offset.B, RdsCrc.identify(block));
    }

    @Test
    void identifiesNothingForAnUnrecognisableSyndrome() {
        // Corrupt the checkword into a syndrome no offset word uses.
        int block = RdsCrc.encodeBlock(0x0000, RdsCrc.Offset.A) ^ 0b01_0101_0101;
        assertNull(RdsCrc.identify(block));
    }
}
