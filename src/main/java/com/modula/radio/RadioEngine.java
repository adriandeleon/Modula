package com.modula.radio;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.modula.audio.AudioSink;
import com.modula.band.BandPlan;
import com.modula.band.Region;
import com.modula.rds.StationInfo;
import com.modula.source.ByteRing;
import com.modula.source.IqSource;

/**
 * Owns the receive path: a reader thread that does nothing but pull blocks off the dongle, and a DSP
 * thread that pushes them through the {@link DemodChain} into the {@link AudioSink}.
 *
 * <p><b>Why two threads and not one.</b> The loop used to be read → demodulate → write PCM → publish
 * → read again, all in sequence, and that shape loses samples by construction:
 * {@code rtlsdr_read_sync} has exactly one USB transfer outstanding and does not submit the next until
 * it is called again, so for the whole demodulate-and-publish stretch <b>nothing is in flight</b>. The
 * dongle does not wait — at 1.2 MSPS every millisecond of that gap is 1200 I/Q samples gone, and a
 * gap inside a block is a phase discontinuity, which drops the 19 kHz pilot lock. The audible result
 * is not a click but a stereo indicator that flickers on and off while the signal strength sits still.
 *
 * <p>It is worse on macOS than on Linux with the same dongle and the same code, because resubmitting a
 * bulk transfer through IOKit costs more than through Linux usbfs and an external hub adds a further
 * tier of scheduling — which is why this presented as an operating-system difference. Note also that
 * {@code rtl_tcp} was never affected: it drives the dongle through {@code rtlsdr_read_async} with
 * about fifteen transfers permanently queued, so the two delivery paths had very different tolerance
 * for the same stall.
 *
 * <p>The fix is the one {@link com.modula.audio.ShortRing} already applies at the other end of the
 * chain, and its documentation states this failure mode exactly — it was simply only ever applied
 * downstream. The reader now writes into a {@link ByteRing} and goes straight back to reading, so the
 * gap collapses to a memcpy, and the DSP thread absorbs its own jitter (including the once-per-status
 * spectrum FFT, which used to sit squarely in the dead time) out of that buffer instead of out of the
 * bus.
 *
 * <p>Threading, in full: <b>{@code modula-usb}</b> reads and owns every call into the device;
 * <b>{@code modula-dsp}</b> owns the chain, the scanner, the sink and status publishing. The sink runs
 * a third for playback, and the FX thread is the fourth. Nothing else.
 *
 * <p>This package is deliberately free of JavaFX. {@link #setListener} callbacks arrive <b>on the DSP
 * thread</b>, and it is the UI's job to marshal and coalesce them onto the FX thread — the same split
 * Editora uses between a service and its coordinator.
 *
 * <p><b>Retuning stays on the reader thread</b>, because librtlsdr is not thread-safe and that thread
 * is the one holding the device; {@link #setFrequency} parks the request in an atomic. The filter
 * chain, however, belongs to the DSP thread, so the two halves of a retune are joined by the ring's
 * tuning generation: the reader moves the oscillator, discards what is still buffered from the old
 * frequency and bumps the stamp, and the DSP calls {@link DemodChain#reset()} when it first sees the
 * new one. That keeps the invariant that the previous station's tail is never smeared into the next
 * one, across a hand-off that now spans two threads.
 */
public final class RadioEngine implements AutoCloseable {

    /** Publish status roughly nine times a second — often enough to look live, rare enough to be free. */
    private static final int BLOCKS_PER_STATUS = 8;

    /** Two bytes to an I/Q pair, which is what the u8 delivery format means. */
    private static final int BYTES_PER_PAIR = 2;

    /**
     * How much IQ to buffer between the two threads: eight blocks, about 109 ms.
     *
     * <p>Deep enough to ride out a GC pause or a scheduling stall on a busy USB tree, and shallow
     * enough that a ring which does briefly fill cannot add much latency. It does drain rather than
     * staying full, because the chain runs faster than real time — that is the assumption this depth
     * rests on, and the {@link Losses#iqDropped()} counter is what shows it failing on a machine where
     * it does not hold.
     */
    private static final int RING_BLOCKS = 8;

    /** Bounded so the DSP thread notices a stop even if the source has gone quiet without ending. */
    private static final long READ_TIMEOUT_MS = 250L;

    /**
     * Gap between two reads short enough that the dongle's own FIFO covers it, in nanoseconds.
     *
     * <p><b>Not every gap is a loss, and treating it as one made this counter useless.</b> Even a reader
     * doing nothing but copying spends time between reads — a 32 KB copy into the ring is measured at
     * about 13 µs — so charging all of it produced roughly 1200 "lost" samples a second on a perfectly
     * healthy receiver. That grew without bound, kept {@link Losses#recent()} permanently true, latched
     * the display into a fault and hid the signal diagnostic behind it.
     *
     * <p>500 µs sits an order of magnitude above that floor and an order of magnitude below a real stall
     * (a GC pause or a scheduling miss is milliseconds), so the two do not have to be told apart by
     * modelling the FIFO — which is undocumented — only by separating overhead from interruption.
     */
    private static final long GAP_TOLERANCE_NANOS = 500_000L;

    /**
     * Samples that must go missing between two status publishes before it is called a fault.
     *
     * <p>1200 I/Q samples is about a millisecond of audio, i.e. the point at which a dropout stops being
     * arithmetic and becomes a click. Below it the honest report is the running total, not an alarm.
     */
    private static final long AUDIBLE_LOSS_SAMPLES = 1_200L;

    /** One block's worth of time, the longest the reader ever pauses when the ring is full. */
    private static final long BLOCK_MILLIS = 1_000L * DemodChain.BLOCK_PAIRS / DemodChain.INPUT_RATE;

    /**
     * Everywhere a sample can go missing, counted separately because each one indicts something
     * different and they are otherwise indistinguishable by ear.
     *
     * @param iqLost samples the dongle produced while no USB transfer was in flight — the bus, the
     *     hub, or the reader being held up. Derived from measured gap time rather than from a sample
     *     count, which makes it immune to the dongle's crystal error.
     * @param iqDropped samples the reader had to discard because the ring was full: the DSP thread not
     *     keeping up with real time
     * @param audioDropped PCM the sink discarded because its buffer was full: the DSP outrunning the
     *     sound card, which over a long session is the two crystals drifting apart
     * @param audioUnderrun PCM the sink had to invent because none had arrived: the DSP being starved,
     *     so the cause is upstream of the sink
     * @param recent whether anything was lost since the previous status, as opposed to at some point
     *     since the receiver started
     */
    public record Losses(long iqLost, long iqDropped, long audioDropped, long audioUnderrun, boolean recent) {

        public static final Losses NONE = new Losses(0L, 0L, 0L, 0L, false);

        public long total() {
            return iqLost + iqDropped + audioDropped + audioUnderrun;
        }

        /** Whether anything at all has been lost since the receiver started. */
        public boolean any() {
            return total() > 0L;
        }

        /**
         * The dominant loss, named for the status line.
         *
         * <p>Worth a sentence rather than a number, because the four causes have four different
         * remedies and the old single "dropped samples" message attributed every one of them to the
         * sound card. On a tie the <b>most upstream</b> cause wins: losing samples on the bus makes
         * everything downstream look starved too, so fixing that one first is the only order that
         * converges.
         *
         * <p>A display string from a package that has no UI, for the same reason
         * {@link DemodChain#rdsDiagnostic()} is: the distinction being drawn is a fact about the
         * signal path, and putting it here is what makes it testable without a toolkit.
         */
        public String describe() {
            if (!any()) {
                return "";
            }
            long worst = Math.max(Math.max(iqLost, iqDropped), Math.max(audioDropped, audioUnderrun));
            if (worst == iqLost) {
                return "Lost %,d samples on the USB path — try a direct port rather than a hub".formatted(iqLost);
            }
            if (worst == iqDropped) {
                return "Discarded %,d samples before demodulation — this machine is not keeping up"
                        .formatted(iqDropped);
            }
            if (worst == audioDropped) {
                return "Dropped %,d samples — the audio device is not keeping up".formatted(audioDropped);
            }
            return "Filled %,d samples with silence — the receiver is being starved".formatted(audioUnderrun);
        }
    }

    /**
     * A snapshot of what the receiver is doing. Delivered off the FX thread.
     *
     * @param signalDbfs power in the selected channel. <b>Not a measure of the station</b> while any
     *     AGC is running, since it then reports the AGC's target; {@code noiseDbfs} is the honest one
     * @param noiseDbfs multiplex noise above 60 kHz, where <b>lower means a stronger station</b>; see
     *     {@link DemodChain#noiseDbfs()}
     * @param adcHeadroomDb room left before the ADC saturates, measured across the whole sampled window
     *     rather than the tuned channel; see {@link DemodChain#adcHeadroomDb()}
     * @param stereoBlend how wide the image actually is, 0 to 1 — what the listener is getting, as
     *     opposed to {@code pilotLocked}, which is what the station is sending
     * @param carrierOffsetHz how far the carrier sits from where we tuned, or {@code NaN} until
     *     measured; see {@link DemodChain#carrierOffsetHz()}
     */
    public record Status(
            long frequencyHz,
            double signalDbfs,
            boolean pilotLocked,
            boolean seeking,
            StationInfo station,
            String rdsDiagnostic,
            float[] spectrum,
            double noiseDbfs,
            double adcHeadroomDb,
            double stereoBlend,
            double carrierOffsetHz,
            Losses losses,
            boolean running) {}

    private final IqSource source;
    private final AudioSink sink;
    private final DemodChain chain;
    private final BandPlan band;
    private final Scanner scanner;
    private final ByteRing ring;

    private final AtomicLong requestedHz = new AtomicLong(-1);
    private final AtomicReference<Scanner.Direction> requestedSeek = new AtomicReference<>();
    private final AtomicBoolean requestedCancel = new AtomicBoolean();

    /** Nanoseconds with no transfer outstanding, accumulated by the reader, read by the publisher. */
    private final AtomicLong gapNanos = new AtomicLong();

    private volatile long currentHz;
    private volatile boolean running;
    private volatile Consumer<Status> listener = status -> {};
    private volatile Consumer<Throwable> errorListener = error -> {};
    private volatile long lastLossTotal;

    /** Whether {@link #advanceSeek} actually parked a retune request. DSP thread only. */
    private boolean retuneRequested;

    private Thread readerThread;
    private Thread dspThread;

    public RadioEngine(IqSource source, AudioSink sink, Region region, BandPlan band) {
        this(source, sink, region, band, SeekPolicy.DEFAULT);
    }

    public RadioEngine(IqSource source, AudioSink sink, Region region, BandPlan band, SeekPolicy seekPolicy) {
        this.source = source;
        this.sink = sink;
        this.band = band;
        this.chain = new DemodChain(region, band);
        this.scanner = new Scanner(band, seekPolicy);
        this.ring = new ByteRing(DemodChain.BLOCK_PAIRS * BYTES_PER_PAIR * RING_BLOCKS);
    }

    /** Receives status snapshots <b>on the DSP thread</b>. */
    public void setListener(Consumer<Status> listener) {
        this.listener = listener == null ? status -> {} : listener;
    }

    /** Receives a fatal source/audio failure <b>off the FX thread</b>. The engine stops after it. */
    public void setErrorListener(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener == null ? error -> {} : errorListener;
    }

    public boolean isRunning() {
        return running;
    }

    public long frequencyHz() {
        return currentHz;
    }

    /** Queues a retune; applied by the reader thread before its next block. Cancels any seek. */
    public void setFrequency(long hz) {
        requestedSeek.set(null);
        requestedCancel.set(true);
        requestedHz.set(hz);
    }

    /** Queues a seek in the given direction; driven by the DSP thread, which has the measurements. */
    public void seek(Scanner.Direction direction) {
        requestedSeek.set(direction);
    }

    /**
     * Abandons a seek in progress, leaving the receiver wherever it had got to.
     *
     * <p>Parked rather than applied here: the scanner belongs to the DSP thread, and cancelling it
     * from the caller's thread — which is what this used to do — raced the state machine it was
     * cancelling.
     */
    public void cancelSeek() {
        requestedSeek.set(null);
        requestedCancel.set(true);
    }

    public boolean isSeeking() {
        return scanner.isScanning();
    }

    public void start(long initialHz) throws Exception {
        if (running) {
            return;
        }
        // Device setup runs on the caller's thread, which is safe only because no reader exists yet.
        source.open();
        // Medium wave sits below the tuner's floor, so the front end has to bypass it entirely.
        if (!source.tunableRange().contains(band.minHz())) {
            source.setDirectSampling(true);
        }
        source.setSampleRate(DemodChain.INPUT_RATE);
        source.applyDefaultGain();
        source.setFrequency(hz(initialHz));
        currentHz = initialHz;
        sink.open();

        ring.clear();
        chain.reset(); // a restarted engine must not open with the previous session's filter state
        gapNanos.set(0L);
        lastLossTotal = 0L;
        running = true;

        dspThread = thread(this::demodulate, "modula-dsp");
        readerThread = thread(this::readBlocks, "modula-usb");
        dspThread.start();
        readerThread.start();
    }

    public void stop() {
        running = false;
        // The reader first: it owns the device, and librtlsdr must not be closed underneath it.
        Thread reader = readerThread;
        readerThread = null;
        join(reader);
        ring.finish();

        Thread dsp = dspThread;
        dspThread = null;
        join(dsp);

        sink.close();
        source.close();
        publish();
    }

    @Override
    public void close() {
        stop();
    }

    // --- the reader thread ---------------------------------------------------------------------

    /**
     * Reads blocks and does nothing else.
     *
     * <p>Everything in this loop other than the read itself is time the dongle is producing samples
     * with nowhere to put them, which is why the loop contains a copy and a retune check and no more.
     */
    private void readBlocks() {
        byte[] raw = new byte[DemodChain.BLOCK_PAIRS * BYTES_PER_PAIR];
        long lastReadEnd = 0L;
        try {
            while (running) {
                if (applyPendingRetune()) {
                    // A retune is deliberate dead air while the oscillator moves; not a loss.
                    lastReadEnd = 0L;
                }

                long readStart = System.nanoTime();
                if (lastReadEnd != 0L) {
                    // Only the excess over what the dongle can cover; see GAP_TOLERANCE_NANOS.
                    long excess = readStart - lastReadEnd - GAP_TOLERANCE_NANOS;
                    if (excess > 0L) {
                        gapNanos.addAndGet(excess);
                    }
                }
                int n = source.read(raw);
                lastReadEnd = System.nanoTime();

                if (n <= 0) {
                    break; // source exhausted or closed
                }
                if (ring.write(raw, n) == 0) {
                    // The ring is full, so samples are already being lost whatever we do; pausing
                    // costs nothing and stops us burning a core against a source that cannot be
                    // back-pressured. The pause is ours, so it is not charged to the bus.
                    ring.awaitSpace(BLOCK_MILLIS);
                    lastReadEnd = 0L;
                }
            }
        } catch (IOException e) {
            if (running) {
                errorListener.accept(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            errorListener.accept(e);
        } finally {
            // Whatever happened, release the DSP thread rather than leaving it waiting for a block.
            ring.finish();
        }
    }

    /**
     * Moves the oscillator if a retune is pending.
     *
     * @return whether one was applied
     */
    private boolean applyPendingRetune() throws IOException {
        long requested = requestedHz.getAndSet(-1);
        if (requested <= 0 || requested == currentHz) {
            return false;
        }
        source.setFrequency(requested);
        currentHz = requested;
        // Everything still buffered belongs to the old station. Discarding it here is what keeps its
        // tail out of the new one, and the bumped stamp is what tells the DSP to clear its filters.
        ring.retuned();
        return true;
    }

    // --- the DSP thread -----------------------------------------------------------------------

    private void demodulate() {
        byte[] raw = new byte[DemodChain.BLOCK_PAIRS * BYTES_PER_PAIR];
        short[] pcm = new short[chain.audioCapacity()];
        long tuneGeneration = ring.lastReadGeneration();
        boolean awaitingTune = false;
        long blocks = 0;
        try {
            while (running) {
                applyPendingSeekRequest();

                int n = ring.read(raw, raw.length, READ_TIMEOUT_MS);
                if (n < 0) {
                    break; // the reader has finished and the ring is drained
                }
                if (n == 0) {
                    continue; // nothing arrived in time; re-check that we should still be running
                }

                long generation = ring.lastReadGeneration();
                if (generation != tuneGeneration) {
                    // First block at the new frequency: clear the filters before it reaches them.
                    tuneGeneration = generation;
                    awaitingTune = false;
                    chain.reset();
                }

                int samples = chain.process(raw, n, pcm);

                // Mute while seeking: otherwise every channel stepped over is a burst of hiss.
                if (!scanner.isScanning()) {
                    sink.write(pcm, samples);
                }

                // Only measure once the retune we asked for has actually landed, or the scanner would
                // step again on a block still captured at the previous frequency.
                boolean stepped = false;
                if (!awaitingTune) {
                    stepped = advanceSeek();
                    // Keyed on whether a request was really parked, not on whether the scanner
                    // stepped: a step to the frequency we are already on parks nothing, so no
                    // generation bump is coming and waiting for one would stall the seek for good.
                    awaitingTune = retuneRequested;
                }
                if (stepped || ++blocks % BLOCKS_PER_STATUS == 0) {
                    publish();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            errorListener.accept(e);
        } finally {
            running = false;
            publish();
        }
    }

    /** Starts or cancels a seek on this thread, where the scanner lives. */
    private void applyPendingSeekRequest() {
        if (requestedCancel.getAndSet(false)) {
            scanner.cancel();
        }
        Scanner.Direction seek = requestedSeek.getAndSet(null);
        if (seek != null) {
            scanner.start(currentHz, seek);
        }
    }

    /**
     * Drives the seek state machine with this block's measurement.
     *
     * @return whether the scan moved or ended, so the caller can publish immediately rather than
     *     waiting for the next periodic status
     */
    private boolean advanceSeek() {
        retuneRequested = false;
        Scanner.Step step = scanner.onBlock(currentHz, chain.noiseDbfs());
        switch (step.action()) {
            case WAIT -> {
                return false;
            }
            case RETUNE, EXHAUSTED -> {
                // The device belongs to the reader thread, so ask rather than tune.
                if (step.frequencyHz() != currentHz) {
                    requestedHz.set(step.frequencyHz());
                    retuneRequested = true;
                }
                return true;
            }
            case FOUND -> {
                return true;
            }
        }
        return false;
    }

    /** Forces mono when false; see {@link DemodChain#setStereoEnabled}. */
    public void setStereoEnabled(boolean enabled) {
        chain.setStereoEnabled(enabled);
    }

    private void publish() {
        listener.accept(new Status(
                currentHz,
                chain.signalDbfs(),
                chain.isPilotLocked(),
                scanner.isScanning(),
                chain.stationInfo(),
                chain.rdsDiagnostic(),
                // Computed here rather than in the loop: nine transforms a second, not seventy-three.
                running ? chain.captureSpectrum() : null,
                chain.noiseDbfs(),
                chain.adcHeadroomDb(),
                chain.stereoBlend(),
                chain.carrierOffsetHz(),
                losses(),
                running));
    }

    /**
     * Where the samples went.
     *
     * <p>{@code iqLost} is measured from gap time rather than by comparing a sample count against the
     * clock, because the dongle's crystal is off by tens of ppm and a wall-clock deficit cannot tell
     * that baseline error from real loss. Elapsed time with no transfer outstanding has no such
     * ambiguity: at {@link DemodChain#INPUT_RATE} it converts straight into samples that were
     * produced with nowhere to go.
     */
    private Losses losses() {
        long iqLost = gapNanos.get() * DemodChain.INPUT_RATE / 1_000_000_000L;
        long iqDropped = ring.droppedBytes() / BYTES_PER_PAIR;
        long audioDropped = sink.droppedSamples();
        long audioUnderrun = sink.underrunSamples();
        long total = iqLost + iqDropped + audioDropped + audioUnderrun;
        // Enough since the last status to be heard, rather than any increase at all: a counter that
        // ticks up by a handful is a fact worth reporting, not a fault worth colouring the receiver for.
        boolean recent = total - lastLossTotal >= AUDIBLE_LOSS_SAMPLES;
        lastLossTotal = total;
        return new Losses(iqLost, iqDropped, audioDropped, audioUnderrun, recent);
    }

    private static Thread thread(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        // Neither thread may be left behind a background repaint: one is holding a USB transfer open
        // and the other is feeding a sound card that will underrun if it is late.
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    }

    private static void join(Thread t) {
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long hz(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("frequency must be > 0, got " + value);
        }
        return value;
    }
}
