package com.modula.source;

import java.util.Locale;

/**
 * Why direct dongle access is or is not working, and what to do about it.
 *
 * <p>Pure: no library loading, no probing. {@link RtlSdr} observes the state and this decides what it
 * means, so the advice is unit-testable on every platform from any platform.
 *
 * <p>It exists because "fell back to rtl_tcp" was the only thing a user was ever told, and that one
 * message covers four unrelated situations with four different fixes. On Windows in particular a
 * missing driver and an unplugged dongle are indistinguishable from the outside — both present as
 * zero devices — and the fix for one is nothing like the fix for the other.
 */
public final class NativeDiagnosis {

    /** The host, for advice that differs per platform. */
    public enum Os {
        LINUX,
        MAC,
        WINDOWS,
        OTHER;

        /** Classifies an {@code os.name} value. Pure, so the hints can be tested for every platform. */
        public static Os of(String osName) {
            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            // Darwin before Windows, and startsWith rather than contains: "darwin" contains "win",
            // so the loose test handed macOS users Zadig instructions for a driver Windows owns.
            if (name.contains("mac") || name.startsWith("darwin")) {
                return MAC;
            }
            if (name.startsWith("windows")) {
                return WINDOWS;
            }
            if (name.contains("linux") || name.contains("nix") || name.contains("nux") || name.contains("bsd")) {
                return LINUX;
            }
            return OTHER;
        }

        public static Os current() {
            return of(System.getProperty("os.name", ""));
        }
    }

    public enum Status {
        /** The library loaded and at least one dongle is attached. */
        AVAILABLE,
        /** No librtlsdr on this machine. */
        LIBRARY_MISSING,
        /** Found, but too old or too different: a symbol we need is not in it. */
        LIBRARY_INCOMPLETE,
        /** The library works and reports no dongle. Often a driver problem rather than a missing one. */
        NO_DEVICE,
        /** A dongle is there and would not open — in use, or not permitted. */
        DEVICE_UNAVAILABLE
    }

    /**
     * @param summary one line, for a status bar
     * @param hint what to actually do, or empty when there is nothing useful to say
     */
    public record Diagnosis(Status status, String summary, String hint) {

        public boolean ok() {
            return status == Status.AVAILABLE;
        }

        /** Both parts for a single-line surface. */
        public String message() {
            return hint.isEmpty() ? summary : summary + " — " + hint;
        }
    }

    private NativeDiagnosis() {}

    public static Diagnosis of(Status status, Os os) {
        return new Diagnosis(status, summary(status), hint(status, os));
    }

    private static String summary(Status status) {
        return switch (status) {
            case AVAILABLE -> "dongle ready";
            case LIBRARY_MISSING -> "librtlsdr not found";
            case LIBRARY_INCOMPLETE -> "librtlsdr is missing a function Modula needs";
            case NO_DEVICE -> "no dongle found";
            case DEVICE_UNAVAILABLE -> "the dongle would not open";
        };
    }

    private static String hint(Status status, Os os) {
        return switch (status) {
            case AVAILABLE -> "";
            case LIBRARY_MISSING ->
                switch (os) {
                    case MAC -> "install it with: brew install librtlsdr";
                    case LINUX -> "install your distribution's librtlsdr package (Debian: librtlsdr0)";
                    // The DLL is not the interesting part of the Windows story, but it is the part
                    // this status is actually about; the driver gets its own hint under NO_DEVICE.
                    case WINDOWS -> "put rtlsdr.dll and libusb-1.0.dll beside Modula, or on PATH";
                    case OTHER -> "install librtlsdr";
                };
            case LIBRARY_INCOMPLETE -> "update librtlsdr";
            // Zero devices from a working library is usually not an unplugged dongle. On Windows
            // the factory DVB-T driver still owns it; on Linux the kernel's dvb_usb_rtl28xxu does
            // the same thing. Both are the single most common reason an RTL-SDR "does not work",
            // and neither is discoverable from the symptom.
            case NO_DEVICE ->
                switch (os) {
                    case WINDOWS ->
                        "if it is plugged in, replace its driver with WinUSB using Zadig, "
                                + "choosing \"Bulk-In, Interface (Interface 0)\"";
                    case LINUX ->
                        "if it is plugged in, the TV tuner driver may have claimed it: "
                                + "blacklist the dvb_usb_rtl28xxu kernel module";
                    case MAC, OTHER -> "check that it is plugged in";
                };
            case DEVICE_UNAVAILABLE ->
                switch (os) {
                    case LINUX -> "another program may be using it, or you may need the rtl-sdr udev rules";
                    default -> "another program may be using it";
                };
        };
    }
}
