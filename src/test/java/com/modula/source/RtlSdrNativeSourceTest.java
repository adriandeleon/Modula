package com.modula.source;

import java.io.IOException;

import com.modula.radio.DemodChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the native source on whatever machine it happens to run on.
 *
 * <p>Most of this must hold with no library and no dongle, because that is what CI is: loading has to
 * degrade to "unavailable" rather than throw, since a hard failure here would take the whole
 * application down at class-initialisation time on any machine without librtlsdr. The handful of
 * assertions that genuinely need hardware are skipped rather than failed.
 */
class RtlSdrNativeSourceTest {

    @Test
    void reportsAvailabilityWithoutThrowing() {
        // Whatever the machine looks like, asking must be safe.
        assertDoesNotThrow(RtlSdrNativeSource::isAvailable);
        assertDoesNotThrow(RtlSdrNativeSource::unavailableReason);
        assertDoesNotThrow(RtlSdrNativeSource::describeDevice);
    }

    @Test
    void explainsItselfWhenUnavailable() {
        if (RtlSdrNativeSource.isAvailable()) {
            assertEquals("", RtlSdrNativeSource.unavailableReason(), "an available source has nothing to explain");
        } else {
            assertFalse(RtlSdrNativeSource.unavailableReason().isBlank(), "an unavailable source must say why");
        }
    }

    @Test
    void deviceCountIsNeverNegative() {
        assertTrue(RtlSdr.deviceCount() >= 0);
    }

    @Test
    void refusesToBeUsedBeforeItIsOpened() {
        RtlSdrNativeSource source = new RtlSdrNativeSource(0, 1024);
        assertThrows(IOException.class, () -> source.read(new byte[1024]));
        assertThrows(IOException.class, () -> source.setFrequency(98_900_000L));
    }

    @Test
    void closingAnUnopenedSourceIsHarmless() {
        assertDoesNotThrow(() -> new RtlSdrNativeSource(0, 1024).close());
    }

    @Test
    void coversTheFmBandButNotMediumWave() {
        IqSource.Range range = new RtlSdrNativeSource(0, 1024).tunableRange();
        assertTrue(range.contains(98_900_000L), "FM must be reachable");
        assertFalse(range.contains(1_000_000L), "medium wave is below the tuner floor — see BandPlan.mediumWave");
    }

    /** The library-name list is what makes loading work on a runtime-only install. */
    @Test
    void looksForVersionedLibraryNamesNotJustTheDevelopmentSymlink() {
        assertTrue(RtlSdr.LIBRARY_NAMES.contains("librtlsdr.so.0"), "the runtime package ships only versioned names");
        assertTrue(RtlSdr.LIBRARY_NAMES.contains("librtlsdr.dylib"), "macOS");
        assertTrue(RtlSdr.LIBRARY_NAMES.contains("rtlsdr.dll"), "Windows");
    }

    /**
     * Needs a dongle, and needs it free. Skipped otherwise — including when another program holds
     * it, which is the common case on a development machine running rtl_tcp.
     */
    @Test
    void readsRealSamplesFromAnAttachedDongle() throws Exception {
        assumeTrue(RtlSdrNativeSource.isAvailable(), "no dongle attached");

        RtlSdrNativeSource source = new RtlSdrNativeSource(0, DemodChain.BLOCK_PAIRS * 2);
        try {
            source.open();
        } catch (IOException e) {
            assumeTrue(false, "dongle is busy: " + e.getMessage());
            return;
        }
        try {
            source.setSampleRate(DemodChain.INPUT_RATE);
            source.setGainAuto();
            source.setFrequency(98_900_000L);

            byte[] buffer = new byte[DemodChain.BLOCK_PAIRS * 2];
            int read = source.read(buffer);
            assertEquals(buffer.length, read, "a synchronous read should fill the buffer");

            // Real IQ is never a constant; a dead read would come back all zeros or all the same byte.
            boolean varies = false;
            for (int i = 1; i < 512 && !varies; i++) {
                varies = buffer[i] != buffer[0];
            }
            assertTrue(varies, "the samples read do not vary, so nothing is really being received");
        } finally {
            source.close();
        }
    }
}
