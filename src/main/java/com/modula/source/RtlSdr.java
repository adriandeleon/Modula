package com.modula.source;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Foreign-function bindings to {@code librtlsdr}, so the dongle can be driven without {@code rtl_tcp}.
 *
 * <p>Written by hand rather than generated with {@code jextract}. The surface needed is eleven
 * functions of a stable C API, and generating them would add a build-time tool, a headers dependency
 * the runtime package does not ship, and several thousand lines of generated bindings for the rest of
 * the library. Hand-written is smaller, readable, and needs nothing installed beyond the library
 * itself.
 *
 * <p><b>Loading is best-effort and never fatal.</b> A missing library, a machine with no dongle, or a
 * platform we have no name for all leave {@link #isAvailable()} false, and the caller falls back to
 * {@code rtl_tcp}. Nothing here throws during class initialisation.
 *
 * <p>Not thread-safe, and neither is librtlsdr: one device belongs to one thread. {@link
 * RtlSdrNativeSource} is what enforces that.
 */
public final class RtlSdr {

    private static final Logger LOG = Logger.getLogger(RtlSdr.class.getName());

    /**
     * Candidate library names, most specific first.
     *
     * <p>The bare {@code librtlsdr.so} symlink only exists when the <i>development</i> package is
     * installed; a machine with just the runtime has only the versioned names, so looking for the
     * unversioned one alone finds nothing on an otherwise perfectly working system.
     */
    static final List<String> LIBRARY_NAMES = List.of(
            "librtlsdr.so.0",
            "librtlsdr.so.2",
            "librtlsdr.so", // Linux
            "librtlsdr.0.dylib",
            "librtlsdr.dylib", // macOS
            "rtlsdr.dll",
            "librtlsdr.dll"); // Windows

    private static final Arena ARENA = Arena.ofAuto();

    /** Whether a library was located at all, which separates "not installed" from "wrong version". */
    private static boolean libraryFound;

    private static final Bindings BINDINGS = load();

    private RtlSdr() {}

    /** Whether the library was found and every symbol resolved. */
    public static boolean isAvailable() {
        return BINDINGS != null;
    }

    /** Why the library is unavailable, for a status message. Empty when it loaded. */
    public static String unavailableReason() {
        return BINDINGS == null ? diagnose().message() : "";
    }

    /**
     * What state the native path is in, and what the user should do about it.
     *
     * <p>Cheap: it reads a field and, when the library did load, asks it how many devices there are.
     * No probing beyond that.
     */
    public static NativeDiagnosis.Diagnosis diagnose() {
        NativeDiagnosis.Os os = NativeDiagnosis.Os.current();
        if (BINDINGS == null) {
            // The distinction matters: a library that loaded and then failed to bind is installed but
            // wrong, and telling that user to install it sends them in a circle.
            return NativeDiagnosis.of(
                    libraryFound ? NativeDiagnosis.Status.LIBRARY_INCOMPLETE : NativeDiagnosis.Status.LIBRARY_MISSING,
                    os);
        }
        return NativeDiagnosis.of(
                deviceCount() > 0 ? NativeDiagnosis.Status.AVAILABLE : NativeDiagnosis.Status.NO_DEVICE, os);
    }

    public static int deviceCount() {
        if (BINDINGS == null) {
            return 0;
        }
        try {
            return (int) BINDINGS.deviceCount.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The manufacturer's name for a device, or empty if it cannot be read. */
    public static String deviceName(int index) {
        if (BINDINGS == null) {
            return "";
        }
        try {
            MemorySegment name = (MemorySegment) BINDINGS.deviceName.invokeExact(index);
            return name.equals(MemorySegment.NULL)
                    ? ""
                    : name.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Opens a device.
     *
     * @return the opaque device handle
     * @throws java.io.IOException if the device cannot be opened
     */
    static MemorySegment open(int index) throws java.io.IOException {
        require();
        // rtlsdr_open takes a pointer-to-pointer and writes the handle into it.
        MemorySegment slot = ARENA.allocate(ValueLayout.ADDRESS);
        int result = call(BINDINGS.open, slot, index);
        if (result != 0) {
            throw new java.io.IOException(describeOpenFailure(result));
        }
        MemorySegment device = slot.get(ValueLayout.ADDRESS, 0);
        if (device.equals(MemorySegment.NULL)) {
            throw new java.io.IOException("rtlsdr_open returned no device");
        }
        return device;
    }

    /**
     * libusb's error numbers are unhelpful on their own, and the two that actually happen have very
     * different remedies — so name them.
     */
    private static String describeOpenFailure(int code) {
        return switch (code) {
            case -6 -> "the dongle is already in use — stop rtl_tcp or any other SDR program first";
            case -3, -4 -> "permission denied opening the dongle (a udev rule may be missing)";
            default -> "could not open the dongle (rtlsdr_open returned " + code + ")";
        };
    }

    static void close(MemorySegment device) {
        try {
            int ignored = (int) BINDINGS.close.invokeExact(device);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "rtlsdr_close failed", t);
        }
    }

    static int setCenterFrequency(MemorySegment device, int hz) {
        return call(BINDINGS.setCenterFreq, device, hz);
    }

    static int setSampleRate(MemorySegment device, int rate) {
        return call(BINDINGS.setSampleRate, device, rate);
    }

    /** {@code manual} false hands gain to the tuner's own AGC. */
    static int setTunerGainMode(MemorySegment device, boolean manual) {
        return call(BINDINGS.setTunerGainMode, device, manual ? 1 : 0);
    }

    static int setAgcMode(MemorySegment device, boolean on) {
        return call(BINDINGS.setAgcMode, device, on ? 1 : 0);
    }

    /** Mode 2 selects the Q branch, the only way an RTL-SDR Blog V3 reaches HF and medium wave. */
    static int setDirectSampling(MemorySegment device, int mode) {
        return call(BINDINGS.setDirectSampling, device, mode);
    }

    /** Must be called before the first read, or the first buffer is stale. */
    static int resetBuffer(MemorySegment device) {
        try {
            return (int) BINDINGS.resetBuffer.invokeExact(device);
        } catch (Throwable t) {
            throw new IllegalStateException("rtlsdr_reset_buffer failed", t);
        }
    }

    /**
     * Blocking read straight into a native buffer.
     *
     * @return bytes actually read, or a negative librtlsdr error code
     */
    static int readSync(MemorySegment device, MemorySegment buffer, int length, MemorySegment readSlot) {
        try {
            int result = (int) BINDINGS.readSync.invokeExact(device, buffer, length, readSlot);
            return result != 0 ? result : readSlot.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            throw new IllegalStateException("rtlsdr_read_sync failed", t);
        }
    }

    static MemorySegment allocate(long bytes) {
        return ARENA.allocate(bytes);
    }

    static MemorySegment allocateInt() {
        return ARENA.allocate(ValueLayout.JAVA_INT);
    }

    private static int call(MethodHandle handle, MemorySegment device, int argument) {
        try {
            return (int) handle.invokeExact(device, argument);
        } catch (Throwable t) {
            throw new IllegalStateException("librtlsdr call failed", t);
        }
    }

    private static void require() throws java.io.IOException {
        if (BINDINGS == null) {
            throw new java.io.IOException(unavailableReason());
        }
    }

    // --- loading -------------------------------------------------------------------------------

    private record Bindings(
            MethodHandle deviceCount,
            MethodHandle deviceName,
            MethodHandle open,
            MethodHandle close,
            MethodHandle setCenterFreq,
            MethodHandle setSampleRate,
            MethodHandle setTunerGainMode,
            MethodHandle setAgcMode,
            MethodHandle setDirectSampling,
            MethodHandle resetBuffer,
            MethodHandle readSync) {}

    private static Bindings load() {
        Optional<SymbolLookup> lookup = findLibrary();
        if (lookup.isEmpty()) {
            LOG.log(Level.INFO, "librtlsdr not found; direct hardware access unavailable");
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup library = lookup.get();
            ValueLayout.OfInt i32 = ValueLayout.JAVA_INT;
            var ptr = ValueLayout.ADDRESS;

            return new Bindings(
                    bind(linker, library, "rtlsdr_get_device_count", FunctionDescriptor.of(i32)),
                    bind(linker, library, "rtlsdr_get_device_name", FunctionDescriptor.of(ptr, i32)),
                    bind(linker, library, "rtlsdr_open", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_close", FunctionDescriptor.of(i32, ptr)),
                    bind(linker, library, "rtlsdr_set_center_freq", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_set_sample_rate", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_set_tuner_gain_mode", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_set_agc_mode", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_set_direct_sampling", FunctionDescriptor.of(i32, ptr, i32)),
                    bind(linker, library, "rtlsdr_reset_buffer", FunctionDescriptor.of(i32, ptr)),
                    bind(linker, library, "rtlsdr_read_sync", FunctionDescriptor.of(i32, ptr, ptr, i32, ptr)));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "librtlsdr loaded but a symbol is missing; falling back to rtl_tcp", e);
            return null;
        }
    }

    private static Optional<SymbolLookup> findLibrary() {
        Optional<SymbolLookup> found = locate();
        libraryFound = found.isPresent();
        return found;
    }

    private static Optional<SymbolLookup> locate() {
        for (String name : LIBRARY_NAMES) {
            try {
                return Optional.of(SymbolLookup.libraryLookup(name, ARENA));
            } catch (IllegalArgumentException e) {
                // Not this name; try the next.
            }
        }
        // Some distributions ship only a versioned file with no entry on the loader path.
        for (Path candidate : searchPaths()) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Optional.of(SymbolLookup.libraryLookup(candidate, ARENA));
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.FINE, "could not load " + candidate, e);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Places to look when the loader path does not know the library.
     *
     * <p>Per host: the list used to be every platform's paths on every platform, so a Mac stat'ed
     * three Debian multiarch directories on the way to its own. Windows resolves a DLL through the
     * process search path rather than absolute locations, so it contributes nothing here — its
     * failure mode is the driver, not the file.
     */
    private static List<Path> searchPaths() {
        return switch (NativeDiagnosis.Os.current()) {
            case LINUX ->
                List.of(
                        Path.of("/usr/lib/x86_64-linux-gnu/librtlsdr.so.0"),
                        Path.of("/usr/lib/aarch64-linux-gnu/librtlsdr.so.0"),
                        Path.of("/usr/lib64/librtlsdr.so.0"),
                        Path.of("/usr/local/lib/librtlsdr.so.0"));
            case MAC ->
                List.of(
                        Path.of("/opt/homebrew/lib/librtlsdr.dylib"), // Apple silicon
                        Path.of("/usr/local/lib/librtlsdr.dylib")); // Intel
            case WINDOWS, OTHER -> List.of();
        };
    }

    private static MethodHandle bind(Linker linker, SymbolLookup library, String symbol, FunctionDescriptor type) {
        return linker.downcallHandle(
                library.find(symbol).orElseThrow(() -> new IllegalStateException("missing symbol " + symbol)), type);
    }
}
