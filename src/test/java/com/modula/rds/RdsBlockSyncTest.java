package com.modula.rds;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsBlockSyncTest {

    private final List<RdsGroup> received = new ArrayList<>();
    private final RdsBlockSync sync = new RdsBlockSync(received::add);

    @Test
    void findsGroupBoundariesWithNoPreamble() {
        feed(repeat(group(0x1234, 0x0001, 0x2222, 0x3333), 6));

        assertTrue(sync.isSynced());
        assertFalse(received.isEmpty(), "should have decoded groups");
        assertEquals(0x1234, received.getFirst().programIdentification());
        assertEquals(0x3333, received.getFirst().d());
    }

    /** There is no frame marker, so sync must survive starting mid-group. */
    @Test
    void synchronisesFromAnArbitraryStartingOffset() {
        for (int skip : new int[] {1, 13, 26, 51, 77, 103}) {
            received.clear();
            sync.reset();

            boolean[] stream = repeat(group(0x4321, 0x0002, 0x1111, 0x5555), 8);
            boolean[] shifted = new boolean[stream.length - skip];
            System.arraycopy(stream, skip, shifted, 0, shifted.length);
            feed(shifted);

            assertTrue(sync.isSynced(), "failed to sync when starting " + skip + " bits in");
            assertFalse(received.isEmpty(), "no groups after starting " + skip + " bits in");
            assertEquals(0x4321, received.getFirst().programIdentification());
        }
    }

    /** One block matching by chance must not be enough, or noise produces garbage groups. */
    @Test
    void doesNotSyncToRandomNoise() {
        Random random = new Random(5);
        boolean[] noise = new boolean[200_000];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = random.nextBoolean();
        }
        feed(noise);

        assertTrue(received.isEmpty(), "noise produced %d spurious groups".formatted(received.size()));
    }

    @Test
    void ridesOutABurstOfCorruptionWithoutLosingSync() {
        boolean[] stream = repeat(group(0x1111, 0x0003, 0x2222, 0x4444), 12);
        // Wreck one block in the middle, well after sync is established.
        for (int i = 500; i < 510; i++) {
            stream[i] = !stream[i];
        }
        feed(stream);

        assertTrue(sync.isSynced(), "a single corrupted block should not cost sync");
        assertTrue(received.size() >= 8, "should have kept decoding, got " + received.size());
    }

    @Test
    void abandonsSyncWhenCorruptionPersists() {
        feed(repeat(group(0x1111, 0x0000, 0x2222, 0x3333), 6));
        assertTrue(sync.isSynced());

        Random random = new Random(9);
        for (int i = 0; i < 104 * 8; i++) {
            sync.accept(random.nextBoolean());
        }

        assertFalse(sync.isSynced(), "sustained garbage must drop sync rather than emit rubbish");
    }

    @Test
    void acceptsVersionBGroupsWithTheirAlternateOffsetWord() {
        boolean[] stream = repeat(RdsGroups.encodeGroup(0x2000, 0x2800, 0x2000, 0x4142, true), 6);
        feed(stream);

        assertTrue(sync.isSynced(), "the C-prime offset word must be accepted in block C");
        assertFalse(received.isEmpty());
        assertTrue(received.getFirst().isVersionB());
    }

    @Test
    void countsGroupsForAHealthSignal() {
        feed(repeat(group(0x1234, 0x0000, 0x1111, 0x2222), 10));
        assertEquals(received.size(), sync.groupsDecoded());
        assertTrue(sync.groupsDecoded() >= 8);
    }

    private void feed(boolean[] bits) {
        for (boolean bit : bits) {
            sync.accept(bit);
        }
    }

    private static boolean[] group(int a, int b, int c, int d) {
        return RdsGroups.encodeGroup(a, b, c, d, false);
    }

    private static boolean[] repeat(boolean[] group, int times) {
        List<boolean[]> groups = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            groups.add(group);
        }
        return RdsGroups.stream(groups);
    }
}
