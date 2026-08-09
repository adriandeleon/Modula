package com.modula.rds;

import java.util.function.Consumer;

/**
 * Finds group boundaries in the raw bit stream and hands out whole groups.
 *
 * <p>RDS has no preamble and no frame marker — the offset words in {@link RdsCrc} are the only thing
 * that says where a block begins. So synchronising means sliding a 26-bit window along the stream
 * until the pattern of offset words lines up.
 *
 * <p>Two decisions worth stating:
 *
 * <p><b>Acquiring requires two consecutive valid blocks, not one.</b> A single 26-bit window matches
 * one of five offset words by chance roughly once every 200 bits, so a one-block trigger would false-
 * lock constantly on noise and emit garbage groups.
 *
 * <p><b>Losing sync requires several consecutive bad groups, not one.</b> Once locked, the far more
 * likely cause of a bad block is a burst of interference, and dropping sync on it means re-acquiring
 * from scratch — throwing away a station name that was nearly complete. So bad groups are counted
 * and sync is only abandoned when they persist.
 *
 * <p>Pure and stateful. Not thread-safe.
 */
public final class RdsBlockSync {

    private static final int BLOCK_BITS = 26;
    private static final int GROUP_BLOCKS = 4;

    /** Consecutive bad groups tolerated before giving up and hunting again. */
    private static final int MAX_BAD_GROUPS = 5;

    private final Consumer<RdsGroup> onGroup;

    private long window;
    private int bitsSeen;

    private boolean synced;
    private int blockIndex;
    private int badGroups;
    private long groupsDecoded;

    private final int[] blocks = new int[GROUP_BLOCKS];
    private boolean groupIntact;

    // While hunting: the position of a candidate A block, so the next one can confirm it.
    private boolean candidatePending;
    private int bitsSinceCandidate;
    private RdsCrc.Offset candidateOffset;

    public RdsBlockSync(Consumer<RdsGroup> onGroup) {
        this.onGroup = onGroup;
    }

    /** Feeds one received bit, most significant first as it arrives on air. */
    public void accept(boolean bit) {
        window = ((window << 1) | (bit ? 1 : 0)) & 0x3FF_FFFFL;
        if (bitsSeen < BLOCK_BITS) {
            bitsSeen++;
            if (bitsSeen < BLOCK_BITS) {
                return;
            }
        }

        if (synced) {
            advanceSynced();
        } else {
            hunt();
        }
    }

    public boolean isSynced() {
        return synced;
    }

    /** Groups emitted since construction — a cheap health signal for the UI. */
    public long groupsDecoded() {
        return groupsDecoded;
    }

    public void reset() {
        window = 0;
        bitsSeen = 0;
        synced = false;
        blockIndex = 0;
        badGroups = 0;
        candidatePending = false;
        bitsSinceCandidate = 0;
        groupIntact = false;
    }

    // --- hunting -------------------------------------------------------------------------------

    private void hunt() {
        int block = (int) window;
        RdsCrc.Offset offset = RdsCrc.identify(block);

        if (candidatePending) {
            bitsSinceCandidate++;
            if (bitsSinceCandidate == BLOCK_BITS) {
                // Exactly one block on from the candidate: does the next offset word follow it?
                if (offset != null && follows(candidateOffset, offset)) {
                    acquire(candidateOffset);
                    return;
                }
                candidatePending = false;
            } else if (bitsSinceCandidate > BLOCK_BITS) {
                candidatePending = false;
            }
        }

        if (!candidatePending && offset != null) {
            candidatePending = true;
            bitsSinceCandidate = 0;
            candidateOffset = offset;
        }
    }

    /** Whether {@code next} is the offset word that legitimately follows {@code current}. */
    private static boolean follows(RdsCrc.Offset current, RdsCrc.Offset next) {
        return switch (current) {
            case A -> next == RdsCrc.Offset.B;
            case B -> next == RdsCrc.Offset.C || next == RdsCrc.Offset.C_PRIME;
            case C, C_PRIME -> next == RdsCrc.Offset.D;
            case D -> next == RdsCrc.Offset.A;
        };
    }

    /**
     * Locks on, having just seen the block <em>after</em> {@code firstOffset}. Both are kept: the
     * confirming pair is real data and throwing it away would lose a group for no reason.
     */
    private void acquire(RdsCrc.Offset firstOffset) {
        synced = true;
        badGroups = 0;
        groupIntact = true;

        int firstIndex = indexOf(firstOffset);
        blocks[firstIndex] = 0; // the first block has already slid out of the window
        groupIntact = false; // ... so this group is incomplete by construction

        blockIndex = (firstIndex + 1) % GROUP_BLOCKS;
        blocks[blockIndex] = RdsCrc.dataOf((int) window);
        blockIndex = (blockIndex + 1) % GROUP_BLOCKS;
        bitsSeen = 0;
    }

    private static int indexOf(RdsCrc.Offset offset) {
        return switch (offset) {
            case A -> 0;
            case B -> 1;
            case C, C_PRIME -> 2;
            case D -> 3;
        };
    }

    // --- synced --------------------------------------------------------------------------------

    private void advanceSynced() {
        bitsSeen++;
        if (bitsSeen < BLOCK_BITS) {
            return;
        }
        bitsSeen = 0;

        int block = (int) window;
        RdsCrc.Offset expected = expectedOffset(blockIndex);
        RdsCrc.Offset actual = RdsCrc.identify(block);

        boolean ok = actual == expected
                || (expected == RdsCrc.Offset.C && actual == RdsCrc.Offset.C_PRIME)
                || (expected == RdsCrc.Offset.C_PRIME && actual == RdsCrc.Offset.C);

        if (!ok) {
            groupIntact = false;
        }
        blocks[blockIndex] = RdsCrc.dataOf(block);

        blockIndex++;
        if (blockIndex == GROUP_BLOCKS) {
            blockIndex = 0;
            completeGroup();
        }
    }

    private void completeGroup() {
        if (groupIntact) {
            badGroups = 0;
            groupsDecoded++;
            onGroup.accept(new RdsGroup(blocks[0], blocks[1], blocks[2], blocks[3]));
        } else if (++badGroups >= MAX_BAD_GROUPS) {
            // Persistent failure means we are not really synced; go back to hunting.
            reset();
            return;
        }
        groupIntact = true;
    }

    private static RdsCrc.Offset expectedOffset(int index) {
        return switch (index) {
            case 0 -> RdsCrc.Offset.A;
            case 1 -> RdsCrc.Offset.B;
            case 2 -> RdsCrc.Offset.C;
            default -> RdsCrc.Offset.D;
        };
    }
}
