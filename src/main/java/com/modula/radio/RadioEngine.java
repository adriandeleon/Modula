package com.modula.radio;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.modula.audio.AudioSink;
import com.modula.band.BandPlan;
import com.modula.band.Region;
import com.modula.rds.StationInfo;
import com.modula.source.IqSource;

/**
 * Owns the receive thread: read a block from the source, push it through the {@link DemodChain},
 * hand the PCM to the {@link AudioSink}. That is the entire loop.
 *
 * <p>Threading, in full: this class runs <b>one</b> thread. The sink runs a second for playback. The
 * FX thread is the third. Nothing else.
 *
 * <p>This package is deliberately free of JavaFX. {@link #setListener} callbacks arrive <b>on the
 * receive thread</b>, and it is the UI's job to marshal and coalesce them onto the FX thread — the
 * same split Editora uses between a service and its coordinator.
 *
 * <p>Retuning is applied on the receive thread rather than by the caller, so it can never race the
 * chain's filter state; {@link #setFrequency} just parks the request in an atomic.
 */
public final class RadioEngine implements AutoCloseable {

    /** Publish status roughly nine times a second — often enough to look live, rare enough to be free. */
    private static final int BLOCKS_PER_STATUS = 8;

    /** A snapshot of what the receiver is doing. Delivered off the FX thread. */
    public record Status(
            long frequencyHz,
            double signalDbfs,
            boolean pilotLocked,
            boolean seeking,
            StationInfo station,
            String rdsDiagnostic,
            float[] spectrum,
            long droppedSamples,
            boolean running) {}

    private final IqSource source;
    private final AudioSink sink;
    private final DemodChain chain;
    private final BandPlan band;
    private final Scanner scanner;

    private final AtomicLong requestedHz = new AtomicLong(-1);
    private final AtomicReference<Scanner.Direction> requestedSeek = new AtomicReference<>();
    private volatile long currentHz;
    private volatile boolean running;
    private volatile Consumer<Status> listener = status -> {};
    private volatile Consumer<Throwable> errorListener = error -> {};
    private Thread thread;

    public RadioEngine(IqSource source, AudioSink sink, Region region, BandPlan band) {
        this(source, sink, region, band, SeekPolicy.DEFAULT);
    }

    public RadioEngine(IqSource source, AudioSink sink, Region region, BandPlan band, SeekPolicy seekPolicy) {
        this.source = source;
        this.sink = sink;
        this.band = band;
        this.chain = new DemodChain(region, band);
        this.scanner = new Scanner(band, seekPolicy);
    }

    /** Receives status snapshots <b>on the receive thread</b>. */
    public void setListener(Consumer<Status> listener) {
        this.listener = listener == null ? status -> {} : listener;
    }

    /** Receives a fatal source/audio failure <b>on the receive thread</b>. The engine stops after it. */
    public void setErrorListener(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener == null ? error -> {} : errorListener;
    }

    public boolean isRunning() {
        return running;
    }

    public long frequencyHz() {
        return currentHz;
    }

    /** Queues a retune; applied by the receive thread before its next block. Cancels any seek. */
    public void setFrequency(long hz) {
        requestedSeek.set(null);
        requestedHz.set(hz);
    }

    /** Queues a seek in the given direction; driven by the receive thread. */
    public void seek(Scanner.Direction direction) {
        requestedSeek.set(direction);
    }

    /** Abandons a seek in progress, leaving the receiver wherever it had got to. */
    public void cancelSeek() {
        requestedSeek.set(null);
        scanner.cancel();
    }

    public boolean isSeeking() {
        return scanner.isScanning();
    }

    public void start(long initialHz) throws Exception {
        if (running) {
            return;
        }
        source.open();
        // Medium wave sits below the tuner's floor, so the front end has to bypass it entirely.
        if (!source.tunableRange().contains(band.minHz())) {
            source.setDirectSampling(true);
        }
        source.setSampleRate(DemodChain.INPUT_RATE);
        source.setGainAuto();
        source.setFrequency(hz(initialHz));
        currentHz = initialHz;
        sink.open();

        running = true;
        thread = new Thread(this::receive, "modula-dsp");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sink.close();
        source.close();
        publish();
    }

    @Override
    public void close() {
        stop();
    }

    private void receive() {
        byte[] raw = new byte[DemodChain.BLOCK_PAIRS * 2];
        short[] pcm = new short[chain.audioCapacity()];
        long blocks = 0;
        try {
            while (running) {
                applyPendingRetune();

                int n = source.read(raw);
                if (n <= 0) {
                    break; // source exhausted or closed
                }
                int samples = chain.process(raw, n, pcm);

                // Mute while seeking: otherwise every channel stepped over is a burst of hiss.
                if (!scanner.isScanning()) {
                    sink.write(pcm, samples);
                }

                boolean stepped = advanceSeek();
                if (stepped || ++blocks % BLOCKS_PER_STATUS == 0) {
                    publish();
                }
            }
        } catch (IOException e) {
            if (running) {
                errorListener.accept(e);
            }
        } catch (RuntimeException e) {
            errorListener.accept(e);
        } finally {
            running = false;
            publish();
        }
    }

    private void applyPendingRetune() throws IOException {
        Scanner.Direction seek = requestedSeek.getAndSet(null);
        if (seek != null) {
            scanner.start(currentHz, seek);
        }
        long requested = requestedHz.getAndSet(-1);
        if (requested > 0 && requested != currentHz) {
            scanner.cancel();
            retune(requested);
        }
    }

    /**
     * Drives the seek state machine with this block's measurement.
     *
     * @return whether the scan moved or ended, so the caller can publish immediately rather than
     *     waiting for the next periodic status
     */
    private boolean advanceSeek() throws IOException {
        Scanner.Step step = scanner.onBlock(currentHz, chain.noiseDbfs());
        switch (step.action()) {
            case WAIT -> {
                return false;
            }
            case RETUNE, EXHAUSTED -> {
                retune(step.frequencyHz());
                return true;
            }
            case FOUND -> {
                return true;
            }
        }
        return false;
    }

    private void retune(long hz) throws IOException {
        if (hz == currentHz) {
            return;
        }
        source.setFrequency(hz);
        chain.reset();
        currentHz = hz;
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
                droppedSamples(),
                running));
    }

    private long droppedSamples() {
        return sink instanceof com.modula.audio.JavaSoundSink js ? js.droppedSamples() : 0L;
    }

    private static long hz(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("frequency must be > 0, got " + value);
        }
        return value;
    }
}
