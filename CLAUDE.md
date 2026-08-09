# Modula

An FM/AM broadcast radio receiver for RTL-SDR dongles. JDK 25 + JavaFX 26 + Maven, modular (JPMS,
module `com.modula`).

## Commands

- Run the app: `./mvnw javafx:run` (uses the dongle directly; falls back to `rtl_tcp -a 127.0.0.1`)
- Run tests: `./mvnw test`
- Format: `./mvnw spotless:apply` — **run before committing**; `./mvnw verify` fails on unformatted code
- Record a capture for testing: `rtl_sdr -f <a real local station> -s 1200000 -n 36000000 station.iq`
  (check the spectrum before trusting a capture — an empty channel looks exactly like a broken
  decoder, and once cost a full debugging round)
- Regenerate the RDS golden fixture:
  `java -cp target/classes scripts/MakeRdsFixture.java station.iq 6.0 src/test/resources/com/modula/rds/rds-989-baseband.s16`

## The product decision

Modula is a **radio**, not an SDR panel. General SDR software is overwhelming because it exposes the
DSP graph; Modula exposes a frequency and a volume knob. There is deliberately **no** demodulator
picker, filter-bandwidth slider, gain/AGC panel, squelch control or FFT settings — none of them has
a right answer a listener should have to supply. Gain is left to the dongle's own AGC and the
bandwidth is fixed by `DemodChain`.

When adding a feature, the test is whether a car radio would have it. A preset button, yes. A
"decimation stages" spinner, no.

## Architecture

Layering, strictly enforced by what each package imports:

- `dsp`, `demod`, `band` — **pure**. No JavaFX, no IO, no threads, and no allocation after
  construction. Everything here is a deterministic function or a small state machine over
  `float[]`, so it unit-tests numerically without a toolkit or hardware.
- `source`, `audio` — the only packages that touch sockets, files or the sound card.
- `radio` — the only package that owns threads. Deliberately free of JavaFX.
- `config` — session and preset persistence; only `ConfigStore` touches the disk.
- `ui` — the only package that touches JavaFX.

### Signal flow

```
u8 IQ @ 1.2 MSPS  (16384-pair blocks, ~13.6 ms)
 └ SampleFormat.u8ToFloat       →  float i[], q[]      1.2 MSPS
   └ FirFilter ×2, ÷5           →  float i[], q[]      240 kHz  (channel select)
     └ FmDiscriminator          →  float mpx[]         240 kHz  (real, the multiplex)
       ├ PilotTracker           →  cos(2t) 38 kHz, cos(3t)/sin(3t) 57 kHz, aligned mpx
       │   └ bandPass 17–21 kHz →  Pll locked to the 19 kHz pilot
       ├ Delay → FirFilter ÷5   →  float sum[]         48 kHz   (L+R)
       ├ StereoDecoder          →  float difference[]  48 kHz   (L−R)
       │   └ × 2·cos(2t), ÷5
       │  → matrix + Deemphasis ×2  →  short pcm[]  interleaved stereo
       │    → ShortRing → sound card
       └ RdsReceiver            →  station name, radio text, programme type
           ├ × 2·cos(3t) and 2·sin(3t), ÷10  →  24 kHz baseband
           ├ bi-phase integrate-and-dump + timing NCO  →  1187.5 bps
           ├ RdsBlockSync  →  offset-word hunt, CRC
           └ RdsDecoder    →  groups 0 and 2
```

Both decimations are exactly ÷5, so every stage stays integer. 240 kHz is wide enough to carry the
57 kHz RDS subcarrier too, so RDS can be added **without touching the front end**.

Measured channel separation is **33 dB, flat from 100 Hz to 10 kHz** (`SeparationProbe`; broadcast
transmitters are only required to manage about 29 dB).

### Threading

Three threads, and there is no reason to add a fourth:

1. **`modula-dsp`** (`RadioEngine`) — read a block, run the chain, write PCM into the ring.
2. **`modula-audio`** (`JavaSoundSink`) — pull from the ring, `line.write()`. Its blocking write is
   the playback clock and is *meant* to block.
3. **The FX thread** — receives status via a coalesced `Platform.runLater` (an `AtomicReference` for
   the latest value plus an `AtomicBoolean` pending flag, so at most one repaint is ever queued).

`radio` never calls into JavaFX. `RadioEngine` listeners fire **on the receive thread** and the UI
marshals them. Keep it that way.

## Invariants

These are the things that will silently break the receiver if violated.

**Every stateful stage must survive block boundaries.** `FirFilter` carries both its history *and*
its decimation phase; `FmDiscriminator` carries the previous sample; `Deemphasis` carries its
accumulator. Drop any of them and the stage still passes a single-block test while buzzing at
exactly the block rate in real time — miserable to diagnose from audio alone. Every stateful class
has a block-boundary test asserting that one large call and many irregular small calls produce
identical output. **A new stateful stage needs the same test.**

**Block counts are not constant.** 16384 is not a multiple of 5, so decimator output varies by ±1
per block. Thread the returned count downstream; never read `array.length` instead.

**A filtered phase reference must be time-aligned with what it demodulates.** The pilot band-pass
delays the pilot by 165 samples, which at 38 kHz is 26.125 cycles — the 0.125 is a 45° phase error
that scales the recovered difference channel by cos(45°) and drops separation from 33 dB to 14. So
`PilotTracker` delays the multiplex to meet its own reference and publishes it as `alignedMpx()`,
and `DemodChain` delays the sum path by `groupDelaySamples()` to match. **Mix against
`alignedMpx()`, never the raw input, and never add a filter to one path only.** Nothing errors when
this is wrong; the stereo image just quietly narrows.

**A slightly negative symbol phase is meaningful — do not wrap it.** In `RdsDemodulator` the timing
correction can push the phase just below zero, which correctly says "this symbol ended early, start
the next one later". Adding 1.0 to bring it "back into range" — the obvious defensive move — makes
the very next sample cross the boundary again and emit a spurious symbol. That measured as **14%
more bits than were ever transmitted**, and it presents as a 47% bit error rate, i.e. as though
demodulation were broken rather than as an off-by-one.

**Audio is always interleaved stereo, and frames must never be split.** `ShortRing` takes a
`frameSize` and truncates writes to whole frames: dropping an *odd* number of samples on overrun
would shift everything after it by one and swap the channels permanently, turning a momentary
glitch into an inverted stereo image that survives until restart.

**Zero allocation in the receive path.** The chain is a fixed pipeline, not a dynamic graph, so
every buffer is sized and allocated in `DemodChain`'s constructor. This is what keeps the GC out of
the audio path entirely — no pool, no per-block arrays.

**The DSP thread must never block on audio.** `ShortRing` drops on overrun and counts it. If it
blocked, a stalled sound card would back-pressure into the socket read and the dongle's own buffers
would overflow, turning a momentary audio hiccup into dropped RF.

**Retune happens on the receive thread.** `RadioEngine.setFrequency` parks the request in an atomic;
the receive loop applies it and calls `DemodChain.reset()` before the next block, so it can never
race the filter state and the previous station's tail is never smeared into the new one. Seek runs
there too, for the same reason.

**Seek thresholds on multiplex noise, never on signal strength.** With the dongle's AGC on, an empty
channel is gained up until its RF power reads much like an occupied one — measured, −9.85 dBFS empty
against −9.75 dBFS for a weak station, i.e. no usable difference at all. An FM discriminator fed
noise, by contrast, produces loud broadband hiss above the multiplex, and a carrier quiets it:
`DemodChain.noiseDbfs` reads −5.8 dBFS on an empty channel and −51 on a strong station. That is the
same noise-squelch measurement analogue radios have always used, and it is immune to the AGC.
**Lower means stronger** — the comparison in `SeekPolicy.isStation` reads backwards on purpose.

**`SampleFormat` centres on 127.5, not 128.** An unsigned byte's midpoint falls between two codes.
Using 128 leaves a DC offset that the discriminator turns into an audible tone at the tuned
frequency — the classic centre spike.

## Known costs and limits

- **PLL acquisition time is a product constraint, not a tuning detail.** It is how long after a
  retune the stereo indicator lights. Measured pull-in against a 30 Hz offset: at a 20 Hz loop
  bandwidth the loop still rings ±7 Hz at 500 ms and settles near 1 s; at 50 Hz it is within 3 Hz
  and locked by 100 ms; at 100 Hz it is immediate but starts admitting noise. Shipped at **50 Hz**.
  Pulling in a frequency offset is nonlinear and far slower than the small-signal settling time
  formula suggests — measure, don't derive.
- **The channel filter is the expensive stage** — 111 taps over I and Q at 1.2 MSPS is ~266 M
  multiply-accumulates per second, then the pilot band-pass at ~79 M, then `Math.atan2` in the
  discriminator. All are
  comfortable on a modern core. When it matters, the fix is **multi-stage decimation** (a short
  cheap filter for a first ÷2 or ÷4, then a sharp one at the lower rate) and a polynomial `atan2`
  — not the Vector API, which is still an incubator module. Measure before either.
- **The two clocks drift.** The dongle samples on its crystal, the sound card plays on its own, so
  over a long session the ring creeps toward full or empty and will eventually glitch. The fix is a
  fractional resampler tracking the ring's fill level, and it belongs in `audio` — not in the chain.
- **Medium-wave AM is unreachable on a stock dongle** (R820T2 tuner floor ~24 MHz). See the README.
  `IqSource.tunableRange()` is the mechanism: the UI asks the source before offering a band.

## Conventions

- **Code style:** Palantir Java Format via Spotless, gated at `verify`. Import groups are
  JDK → `javafx` → everything else → static last.
- **Pure first.** Anything expressible as a function over arrays belongs in `dsp`/`demod`/`band`
  with a unit test, not inline in the chain or the UI. This is what makes the receiver testable.
- **Test with synthesis, not hardware.** `DemodChainTest` modulates a known tone and asserts on the
  recovered frequency, amplitude and spectral purity.
- **But keep one test against real off-air data.** `RdsGoldenFileTest` decodes six seconds of a real
  broadcast from a 281 KB fixture, and exists because synthesis provably could not catch the three
  bugs that shipped. The fixture is the demodulated baseband rather than raw IQ purely for size, so
  it covers symbol timing downward — every layer that has ever broken. **Regenerate it with
  `scripts/MakeRdsFixture.java`, whose front end must stay identical to `RdsDemodulator`'s**: a
  fixture distilled through a wider baseband filter had a perfect spectrum and decoded at 3.6% under
  a fixed clock, yet the real demodulator could not lock to it at all.
- **Test the decision and the wiring separately.** `ScannerTest` drives the seek state machine with
  a list of numbers; `RadioEngineSeekTest` drives the real engine against a fake dongle that carries
  a station on one frequency. "The logic is right" and "the engine actually calls it" are different
  failure modes and the pure test cannot see the second.
- **Parsing user-facing files is lenient, per field.** A corrupt preset line costs that preset; a
  corrupt setting costs that setting. Nothing in `config` throws on bad input, because a radio that
  will not start because of a stray character in a text file is a worse outcome than any amount of
  lost configuration.
- **New `IqSource` implementations** must honour `tunableRange()` truthfully — it is what stops the
  UI offering a band the hardware cannot reach.

## Roadmap

1. **Mono FM over `rtl_tcp`** — done.
2. **Stereo** — done. 19 kHz pilot PLL, ×2 to 38 kHz via the double-angle identity, coherent L−R,
   matrix to L/R, 33 dB separation. A forced-mono toggle is exposed because stereo raises the noise
   floor ~20 dB and a weak station is often more listenable without it.
3. **Seek and presets** — done. Noise-squelch seek in both directions with band wrap, a preset bank
   in `~/.modula/presets.txt`, and session state (frequency, region, volume, stereo) remembered
   across runs. Arrow keys step a channel; shift+arrow seeks.
4. **Direct hardware** — done. `RtlSdrNativeSource` drives `librtlsdr` through Panama, so `rtl_tcp`
   is optional rather than required; `RadioPane.createSource` prefers the dongle and falls back.
   Still to do: a small spectrum strip — a Canvas showing ±600 kHz, *not* a waterfall.
5. **RDS** — done. Station name, radio text and programme type, decoded from the 57 kHz subcarrier.

Deferred: RDS clock-time and alternative-frequency groups, the full RDS character repertoire (the
default table is treated as ASCII, which covers all but a handful of broadcasts), recording to WAV,
HD Radio (NRSC-5 — a different and much larger project), multiple simultaneous stations, a squelch
control (`noiseDbfs` already provides the measurement).

## Native bindings

**Hand-written FFM, not `jextract`.** Eleven functions of a stable C API, against a generator that
would need a build-time tool, the development headers (which the runtime package does not ship) and
several thousand lines of bindings for a library we use a sliver of.

**Loading must never be fatal.** `RtlSdr` resolves everything in a static initialiser, so a throw
there would take the application down at class-load time on any machine without librtlsdr. A missing
library, an unknown platform or a missing symbol all leave `isAvailable()` false and the caller falls
back to `rtl_tcp` — which is not a consolation prize, since it is also how the dongle lives on
another machine and how development works with nothing attached.

**Look for versioned library names.** The bare `librtlsdr.so` symlink only exists with the
*development* package installed; a runtime-only machine has just `librtlsdr.so.0`, so searching for
the unversioned name alone finds nothing on a perfectly working system.

**librtlsdr is not thread-safe and `RtlSdrNativeSource` does not make it so.** It does not need to:
`RadioEngine` already confines reads, retunes and gain changes to its receive thread by construction,
and calls `close` only after joining it. Buffers come from an `Arena.ofAuto()` so there is no
close-while-reading hazard — a shared arena closed from the FX thread while the receive thread is
inside `rtlsdr_read_sync` is exactly the crash this avoids.

Reads are synchronous. The asynchronous API wants a callback on its own thread, while the engine's
loop is already a thread that wants to block on a read; at 13.6 ms a block, `rtlsdr_read_sync`
returns long before any timeout.

## RDS notes

**The subcarrier phase is ambiguous by design.** The standard locks 57 kHz to the pilot's third
harmonic but permits *either* in phase *or* in quadrature, and transmitters differ. Both branches
are demodulated and the one carrying more energy wins, with hysteresis so a marginal signal cannot
flap between them and destroy symbol timing. `RdsEndToEndTest` covers both conventions — a decoder
tested against only one will appear to work and then fail on half the stations it meets.

**Only groups 0 and 2 are read.** Those carry the station name, programme type and radio text; the
other fourteen types are clock, alternative frequencies, traffic messages and open data. Unknown
groups are ignored rather than treated as errors — a station may legitimately transmit any of them.

**Self-consistent tests cannot check a constant taken from a standard.** The block CRC shipped with
the polynomial written as `0x6D9` under a comment correctly stating `x^10 + x^8 + x^7 + x^5 + x^4 +
x^3 + 1`, which is `0x5B9`. Every one of the eight CRC tests passed: encoder and decoder shared the
constant, so it round-tripped perfectly and detected every single-bit, double-bit and five-bit burst
error. It was a perfectly good CRC — just not RDS's, and the only symptom was that no real broadcast
ever decoded, while synthesis decoded flawlessly. `POLYNOMIAL` is now built from its exponents and
`RdsCrcTest` pins it. **Any constant copied out of a specification needs a test that asserts the
value itself, not just that the code agrees with itself.**

**Real stations do things the specification permits but a naive reading does not anticipate.** Two
cost a field-debugging cycle each, and both are pinned by tests now:

- *Radio text shorter than the field* ends at 0x0D and the remaining segments are **never
  transmitted**. Waiting for all sixteen means such a message never appears, however cleanly it was
  received — observed on a station sending 101 radio-text groups, none of which displayed.
- *The station name is often scrolled*, cycling several eight-character frames to spell out a longer
  message. Merging segments across frames splices them: a station alternating "ESCUCHAS" and "D99"
  displayed as "ES99  AS". `RdsText`'s `cyclic` flag requires the segments to arrive **consecutively
  from zero** and holds the last complete frame rather than blanking between them. Restarting at
  segment zero alone is not enough — when segment zero is lost to a CRC error the next frame's
  remaining segments complete a splice on the previous frame's first one.

**The RDS timing detector is noise-sensitive, and that constrains the baseband filter.** It is
bang-bang on a *single* sample at the mid-symbol crossing, so widening the 2.4 kHz baseband filter —
which looks like an improvement, since it passes the data band flat instead of at 0.88 gain —
admits 1.7x the noise and stops the loop locking on a real station at ~5 dB subcarrier SNR. If the
filter is ever widened, the detector must be averaged over the symbol first, and both must be
re-validated against the capture together.

**Field debugging beats reading the code.** The path that worked, in order: measure the multiplex
spectrum against a noise reference at a comparable frequency (FM noise rises as f², so 57 kHz is
~9 dB noisier than 19 kHz and comparing them directly misleads); confirm the pilot locks and the
subcarrier branch is stable; then measure the offset-word hit rate on the recovered bits — a sliding
26-bit window matches a valid offset word 0.49% of the time by chance and about 3.85% on a correctly
demodulated stream, which grades bit recovery **without knowing what was transmitted**. That last
number went 0.17% → 3.95% the moment the polynomial was fixed. The probes live in the scratchpad;
recreate them rather than guessing.

*Two measurement traps met along the way:* a coherent Goertzel over a long window averages a
data-modulated signal to zero, so a power spectrum must be averaged across short periodograms; and a
fixed symbol clock drifts 2.6 whole symbols across a 30 s capture at the 72 ppm the dongle's crystal
was off, so any fixed-phase decode over a whole capture fails whatever else is true. Both produced
confident, wrong conclusions before being corrected.

