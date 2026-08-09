package com.modula.source;

import com.modula.source.NativeDiagnosis.Diagnosis;
import com.modula.source.NativeDiagnosis.Os;
import com.modula.source.NativeDiagnosis.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDiagnosisTest {

    @Test
    void classifiesTheHostsWeShipOn() {
        assertEquals(Os.LINUX, Os.of("Linux"));
        assertEquals(Os.MAC, Os.of("Mac OS X"));
        assertEquals(Os.MAC, Os.of("Darwin"));
        assertEquals(Os.WINDOWS, Os.of("Windows 11"));
        assertEquals(Os.WINDOWS, Os.of("Windows Server 2022"));
        assertEquals(Os.OTHER, Os.of("Plan 9"));
        assertEquals(Os.OTHER, Os.of(null));
    }

    /**
     * The advice that only exists because the symptom is misleading: a working library reporting
     * zero devices is, on Windows and Linux both, usually a driver holding the dongle rather than an
     * empty USB port. Naming the specific driver is the entire value of this class.
     */
    @Test
    void zeroDevicesPointsAtTheDriverNotTheCable() {
        String windows = NativeDiagnosis.of(Status.NO_DEVICE, Os.WINDOWS).hint();
        assertTrue(windows.contains("Zadig"), windows);
        assertTrue(windows.contains("Interface 0"), windows);

        String linux = NativeDiagnosis.of(Status.NO_DEVICE, Os.LINUX).hint();
        assertTrue(linux.contains("dvb_usb_rtl28xxu"), linux);
    }

    @Test
    void aMissingLibraryNamesThePlatformsOwnWayToInstallIt() {
        assertTrue(NativeDiagnosis.of(Status.LIBRARY_MISSING, Os.MAC).hint().contains("brew install librtlsdr"));
        assertTrue(NativeDiagnosis.of(Status.LIBRARY_MISSING, Os.LINUX).hint().contains("librtlsdr0"));
        assertTrue(NativeDiagnosis.of(Status.LIBRARY_MISSING, Os.WINDOWS).hint().contains("rtlsdr.dll"));
    }

    /**
     * An installed-but-wrong library must not be answered with "install it", which sends that user
     * in a circle past the thing they already have.
     */
    @Test
    void anIncompleteLibraryIsToldToUpdateRatherThanInstall() {
        Diagnosis d = NativeDiagnosis.of(Status.LIBRARY_INCOMPLETE, Os.LINUX);
        assertTrue(d.hint().contains("update"), d.hint());
        // Not "must not contain install" — the hint may well say "the installed version". What it
        // must not do is issue the instruction, which is the thing that sends that user in a circle.
        assertFalse(d.hint().contains("install librtlsdr"), d.hint());
        assertFalse(d.hint().contains("brew install"), d.hint());
    }

    @Test
    void availableSaysNothingFurther() {
        for (Os os : Os.values()) {
            Diagnosis d = NativeDiagnosis.of(Status.AVAILABLE, os);
            assertTrue(d.ok());
            assertEquals("", d.hint());
            assertEquals(d.summary(), d.message());
        }
    }

    /** Every combination must produce something sayable: a blank status line explains nothing. */
    @ParameterizedTest
    @EnumSource(Status.class)
    void everyStatusHasASummaryOnEveryHost(Status status) {
        for (Os os : Os.values()) {
            Diagnosis d = NativeDiagnosis.of(status, os);
            assertFalse(d.summary().isBlank(), status + " on " + os);
            assertFalse(d.message().isBlank(), status + " on " + os);
            if (status != Status.AVAILABLE) {
                assertFalse(d.ok());
                assertFalse(d.hint().isBlank(), "no advice for " + status + " on " + os);
                assertTrue(d.message().contains(d.summary()) && d.message().contains(d.hint()));
            }
        }
    }
}
