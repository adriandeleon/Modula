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
a right answer a listener should have to supply. Gain is **fixed** by `source/TunerGain` and the
bandwidth is fixed by `DemodChain`.

**"No gain control" means one right answer, not no answer, and the answer is no longer the AGC.** It
used to be — and `RtlSdrNativeSource.open` enabled *two* AGCs, the tuner's and the RTL2832U's digital
one, hunting the same signal. Both are off now, for the reason documented on `TunerGain`: an AGC
drives an **8-bit** ADC toward full scale whatever the signal is doing, and on a strong local station
its compression products land right across the multiplex — the 19 kHz pilot, the 38 kHz difference
channel and the 57 kHz RDS subcarrier all sit in the debris. That presents as marginal stereo and
absent RDS on a station whose meter reads strong, which is a hard place to start looking. It also
makes the level reading useless: an AGC reports its own target, not the station.

`TunerGain.TARGET_TENTHS` (30 dB) is a **starting point, not a derived optimum**. Refine it against
the quieting figure in the status line, which is why the two were added together.

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
- `rds` — pure. The 57 kHz subcarrier, from bi-phase symbols to a `StationInfo`.
- `update` — the release check. `UpdateCheck` is pure (parse, compare, is-it-due); `UpdateService`
  is the only part that opens a socket.
- `tray` — the system tray, both backends. Free of JavaFX: it is handed callbacks, and the app
  marshals them onto the FX thread.
- `ui` — the only package that touches JavaFX.

### The interface

Implements the **Night Dial** kit in `docs/ui-kit.html`, which is the design source of record — read
it before changing the look. Three bands: `GlassPane` reports, the transport acts, the footer holds
what is set once a session, with the status line pinned to the bottom edge.

**Amber is a budget, not a palette.** It is spent on the frequency, on the controls that change it,
on the spectrum's centre marker, and on a traffic announcement. Nothing else. A fifth amber thing
stops the frequency being the brightest mark and turns the display into a panel of equals, which is
the failure the product is defined against. Lock is teal, faults coral, everything else ink at one
of three weights.

**Every look lives in `modula.css`**, in two token blocks — night by default, daylight behind the
`daylight` class on the root. There are no `setStyle` calls; a state is a style class, which is what
makes `ReceiverState` expressible as a class swap rather than seven branches of string concatenation.

**The command palette is an in-scene overlay, not a popup window.** A separate stage does not
reliably take OS keyboard focus on Windows, which leaves focus orphaned between two scenes and the
keyboard dead app-wide — the exact failure Editora hit and solved the same way. `CommandPalette` is
a node over a dimmed backdrop inside the window that already has focus, and it owns every key while
open so the arrows move its selection rather than reaching the tuner underneath. It is also the
keyboard's discoverability surface: each row shows its shortcut, so the palette teaches the
accelerators instead of replacing them.

**The right-click menu exists for discoverability, not for parity.** Every shortcut in the product
was reachable only by already knowing it, and the palette — which lists all of them — sat behind an
unadvertised chord, so the keyboard had no way in. Each row therefore carries its chord in a muted
column: the menu teaches the keyboard rather than replacing it.

**A footer control that must stay reachable cannot be at the right-hand end, and cannot be
shrinkable.** Two bugs of one kind shipped here. The preset **+** was the last child inside the
scrolling chip row, so a full bank pushed the only control that adds a station out of sight — it
reads as a missing feature, not as a scrolled one; it is now pinned outside the scroller. Then the
settings button, appended to the footer, fell off the end of the 520px window entirely. An `HBox`
over its width also shrinks *every* child proportionally, so the row degraded by ellipsizing
`Stereo` to `St...` while the volume slider — the one control that loses nothing by being shorter —
kept its full size. The rule: **pin every fixed control to `USE_PREF_SIZE` and give the elastic one
`HGrow`**, so slack is absorbed where it costs nothing. Same trap as Editora's status bar.

**Settings apply live.** Every control writes its field and calls back immediately; there is no
OK/Cancel, because a preferences window that can be half-applied is a second state to reason about
for no benefit. What belongs there is what a listener sets once and forgets — where recordings go,
whether the tray is used, whether to look for updates. Not gain, bandwidth, squelch or FFT size:
those are the panel this product is defined against.

**The mark is generated, not drawn by hand.** `scripts/make-icon.py` holds the geometry once and
emits both `branding/modula-icon.svg` and the PNG set — an SVG maintained beside PNGs rendered from
different numbers is a design source of record that lies, which is worse than not having one. It is
the kit's idea applied to a tile: the cabin is the cool near-black ground, and the mark is the only
lit thing on it. Amber is the spend here rather than a budget item, because there is no frequency
readout to compete with; the outer ring is the *same* amber mixed toward the ground, not a second
colour, so the signal reads as radiating and falling off rather than as a target.

**The tray icon and the application icon are one mark, and the shape constants say so.**
`TrayIconRenderer` carries the same `SPAN`/`STROKE`/`DOT` and ring formula the generator does; it
draws at full canvas because it has no tile to sit inside, inset by a `FIT` factor so the outermost
ring plus half its stroke fits (without it the arcs clip flat, which at 16 pixels reads as a drawing
error rather than a signal). **Both simplify at small sizes rather than drawing the same detail
thinner** — below the two-ring floor the dim outer ring becomes mud, so the mark keeps one.

**The native icon containers are hand-written, and that is the point.** `.ico` and `.icns` are both
simple containers — a directory of PNGs, and length-prefixed typed chunks — so `make-icon.py` writes
them directly rather than delegating to Pillow's savers. A saver downsamples one source image, which
would throw away the per-size simplification the small marks depend on and ship exactly the mud the
two-ring floor exists to avoid. Both are validated on generation by walking them back.

**The transport glyphs are drawn, not typed.** ◀◀ ▶ ★ are absent from most UI and mono faces, IBM
Plex Mono included, so typed they render from whatever fallback each platform picks. `Glyphs` draws
them as `SVGPath` shapes, identical everywhere, taking the button's colour from the sheet.

**IBM Plex Mono is bundled and is the only face allowed to show a number.** Its digits are tabular,
so 101.5 → 107.9 does not shift the layout and −41 → −9 does not make the meter row jump while you
listen. `Readouts` substitutes a true minus (U+2212) for the hyphen `%.0f` emits, which in a
monospace readout reads as punctuation rather than a sign.

### Signal flow

```
u8 IQ @ 1.2 MSPS  (16384-pair blocks, ~13.6 ms)
 └ ByteRing  ← modula-usb writes here and goes straight back to reading
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

Four threads, and the fourth was bought with a measurement:

1. **`modula-usb`** (`RadioEngine`) — read a block off the dongle, put it in the `ByteRing`, read
   again. Owns every call into the device, because librtlsdr is not thread-safe.
2. **`modula-dsp`** (`RadioEngine`) — take a block from the ring, run the chain, write PCM into the
   `ShortRing`, drive the seek machine, publish status.
3. **`modula-audio`** (`JavaSoundSink`) — pull from the ring, `line.write()`. Its blocking write is
   the playback clock and is *meant* to block.
4. **The FX thread** — receives status via a coalesced `Platform.runLater` (an `AtomicReference` for
   the latest value plus an `AtomicBoolean` pending flag, so at most one repaint is ever queued).

**This used to be three, with reading and demodulating in one loop, and that shape loses samples by
construction.** `rtlsdr_read_sync` keeps exactly one USB transfer outstanding and does not submit the
next until it is called again, so for the whole demodulate-write-publish stretch nothing is in flight.
The dongle does not wait: at 1.2 MSPS **every millisecond of that gap is 1200 I/Q samples gone**, and
a gap inside a block is a phase discontinuity, so the pilot PLL loses lock. The symptom is not a click
— it is a **stereo indicator flickering on and off while the signal strength sits perfectly still**,
which reads as a reception problem and sends you to the antenna.

It presented as an operating-system difference, because it is worse on macOS: resubmitting a bulk
transfer through IOKit costs more than through Linux usbfs, and an external hub adds another tier of
scheduling. The same build over `rtl_tcp` was never affected at all — `rtl_tcp` drives the dongle
through `rtlsdr_read_async` with about fifteen transfers permanently queued, so **the two delivery
paths have very different tolerance for the same stall**, and a comparison that changes both the
machine and the delivery path at once cannot see the cause. `ShortRing`'s own documentation had
described this failure mode since it was written; it had only ever been applied to the sound card end.

`radio` never calls into JavaFX. `RadioEngine` listeners fire **on the DSP thread** and the UI
marshals them. Keep it that way.

**A retune now spans two threads, and the ring's generation counter is what joins them.** The device
belongs to `modula-usb` and the filter chain to `modula-dsp`, so the reader moves the oscillator,
discards what is still buffered from the old frequency and bumps `ByteRing`'s stamp; the DSP calls
`DemodChain.reset()` when it first reads a block carrying the new one. That is what preserves the
invariant that the previous station's tail is never smeared into the next. **The seek machine stays on
the DSP thread**, because it decides from `noiseDbfs`, and it must not step again until the retune it
asked for has landed — otherwise it measures a block captured at the previous frequency and walks the
whole band without ever hearing the station on it.

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

**The reader thread must never do anything but read.** The same rule one stage up, and the one that
was missing: every instruction between two calls to `source.read` is time the dongle is producing
samples with nowhere to put them. `ByteRing` exists so the reader's only work is a copy, and adding
anything to that loop — a measurement, a filter, a status update — costs real samples rather than
just latency. `RadioEnginePipelineTest` pins it by stalling the consumer outright and asserting the
dongle is still being read, because a timing assertion on the gap both flakes on a loaded runner and
fails to fail on a fast one.

**An overrun in the IQ ring must leave an even gap in the byte stream.** `ByteRing` holds interleaved
u8 where even offsets are I and odd are Q, so dropping an odd number of bytes shifts that parity for
everything after it and I and Q transpose — which conjugates the signal, mirrors the spectrum about
the centre frequency and makes the discriminator recover the inverse of the modulation, until
restart. It is the `ShortRing` frame rule with a subtlety: the constraint is on the gap between two
*retained* bytes, not on any single call, because a full ring has to discard an odd offer whole and
cannot round it off. That forced odd byte is carried as a debt and paid by the next write that keeps
anything. A per-call test cannot see this; `ByteRingTest` feeds a stream marked 0 at every I position
and 1 at every Q, and asserts no pair ever comes back transposed.

**Retune happens on the receive thread.** `RadioEngine.setFrequency` parks the request in an atomic;
the receive loop applies it and calls `DemodChain.reset()` before the next block, so it can never
race the filter state and the previous station's tail is never smeared into the new one. Seek runs
there too, for the same reason.

**Seek thresholds on multiplex noise, never on signal strength.** This is now also what the status
line's **quieting** figure reports, for the same reason. With an AGC running, an empty channel is
gained up until its RF power reads much like an occupied one — measured, −9.85 dBFS empty against
−9.75 dBFS for a weak station, i.e. no usable difference at all. (The AGC is off as of the fixed-gain
change, so `signalDbfs` means more than it did — but multiplex noise remains the honest measure, and it
is now what **three** things key on: seek, the stereo blend, and `ReceiverState`'s weak decision.)

**`ReceiverState` judges weakness on quieting, falling back to power only where there is none.** It used
to test `signalDbfs < -45`, which under an AGC could never fire — nothing came within 35 dB of it — so
WEAK was unreachable and the receiver had no way to tell a listener their signal was poor. Quieting
below `WEAK_QUIETING_DB` (14 dB, the same point seek stops at) is weak. The fallback covers AM, which
leaves multiplex noise at zero because envelope detection has no discriminator to measure, and an empty
FM channel, which has quieted nothing — one test serves both, and neither may read as permanently weak
just because the measurement it lacks is absent. An FM discriminator fed
noise, by contrast, produces loud broadband hiss above the multiplex, and a carrier quiets it:
`DemodChain.noiseDbfs` reads −5.8 dBFS on an empty channel and −51 on a strong station. That is the
same noise-squelch measurement analogue radios have always used, and it is immune to the AGC.
**Lower means stronger** — the comparison in `SeekPolicy.isStation` reads backwards on purpose.

**`SampleFormat` centres on 127.5, not 128.** An unsigned byte's midpoint falls between two codes.
Using 128 leaves a DC offset that the discriminator turns into an audible tone at the tuned
frequency — the classic centre spike.

## Known costs and limits

- **The pilot lock detector needs hysteresis, and it is not a cosmetic detail.** The audio used to be
  switched between the stereo matrix and a mono copy on `isLocked()`, so a detector level sitting near a
  single threshold did not merely flicker an indicator — it changed the audio path several times a
  second, heard as spottiness, while the status line reported *"no pilot"*, i.e. the station cutting
  out. `Pll` acquires at 0.01 and releases at 0.004; acquisition is unchanged, so the pull-in time below
  is untouched and only the decision to give up is slower. Same reasoning as the RDS subcarrier's
  in-phase-versus-quadrature hysteresis.
- **Stereo is blended, not switched.** `demod/StereoBlend` scales the difference channel by 0–1 rather
  than choosing between the matrix and mono, so a marginal station narrows its image *and* attenuates
  the difference channel's noise by the same factor. Hysteresis alone only moves the cliff; either side
  of it the listener still gets all 20 dB of the noise stereo costs, or none of the separation. Driven
  by **quieting**, so what is heard and what the status line says cannot disagree. Full image at 20 dB,
  mono at 8, linear between — from the same calibration, so the same synthetic-RF caveat applies.
  - **It seeds on first lock rather than ramping**, for two reasons that happen to coincide: a fade-in
    on every retune is audible, and `DemodChainTest` measures separation from a settled tail, so a ramp
    lasting a time constant would pull it under the 20 dB floor and make a working blend look like a
    broken matrix. After the first measurement every change is smoothed — which is the point.
  - `pilotLocked` still reports the **transmitter**, so the indicator lights on a station sending a
    pilot even when the blend has taken the audio most of the way to mono. That is the one state a
    listener cannot otherwise explain, which is why `Readouts.stereoBlend` names it while partial.
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
- **The IQ ring's depth is a latency budget.** Eight blocks, about 109 ms. Deep enough to ride out a
  GC pause or a scheduling stall on a busy USB tree; shallow enough that a ring which does briefly
  fill cannot add much delay. It only drains back to empty because the chain runs faster than real
  time, which is the assumption the depth rests on — `Losses.iqDropped` is what shows that assumption
  failing on a machine where it does not hold. Retuning clears the ring, so tuning latency is not
  affected by how full it was.
- **The four loss counters are the diagnosis, and they are not interchangeable.** `iqLost` is time the
  bus had nothing in flight, `iqDropped` is the DSP falling behind real time, `audioDropped` is the
  sound card being outrun, and `audioUnderrun` is the sink being starved. One "dropped samples" number
  attributed all four to the sound card, which is where a day went. `iqLost` is derived from measured
  gap time rather than by comparing a sample count against the clock, deliberately: the dongle's
  crystal is tens of ppm off, and a wall-clock deficit cannot separate that baseline error from real
  loss — a gap can.
  - **Not every gap is a loss, and a counter that assumes otherwise is worse than none.** Charging all
    of it read ~1200 samples a second on a healthy receiver — the 32 KB copy into the ring, about 13 µs
    a block, which the dongle's FIFO absorbs. The total then grew without bound, kept the fault
    permanently on, and because the fault branch *replaced* the status line it hid the quieting and RDS
    figures behind itself: **stereo and RDS were working and invisible, and the counter built to
    diagnose the problem was manufacturing one.** Only gap beyond `GAP_TOLERANCE_NANOS` (500 µs — an
    order of magnitude above the copy, an order below a GC pause or a scheduling miss) counts, a fault
    needs `AUDIBLE_LOSS_SAMPLES` between publishes, and a fault is appended rather than substituted.
    The lesson generalises: a diagnostic that cannot read zero on a working system is not a diagnostic.
- **The carrier-offset readout exists because a frequency error damages stereo before mono.** The
  offset slides the multiplex within the channel filter, and the 38 kHz difference channel and 57 kHz
  RDS subcarrier sit at the top of that band, so they hit the edge while the mono sum below 15 kHz
  still sounds clean. `DemodChain.carrierOffsetHz` reads it straight off the discriminator's DC level
  — full deviation is scaled to ±1.0, so the mean *is* the offset in hertz once programme content has
  averaged out. Reported only above 5 kHz, since most of it is the dongle's crystal and a listener can
  do nothing about a small one. **Correcting it is not implemented**: `rtlsdr_set_freq_correction` is
  not bound, and where the number would come from is a product decision — measure first, which is what
  this readout is for.
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
6. **The Night Dial interface and the spectrum strip** — done, built together because each is half
   of the same feedback: during a seek the dial dims and the strip is where the sweep is visible.
5. **RDS** — done. Station name, radio text and programme type, decoded from the 57 kHz subcarrier.

7. **AM** — done. Envelope detection with a DC-blocking high-pass, medium wave and aviation, chosen
   by a band selector. `Modulation` gates what the chain builds: no pilot, no stereo, no RDS.
8. **Recording, tray, palette, settings, about, update checks** — done. See below.

Deferred: RDS clock-time and alternative-frequency groups, the full RDS character repertoire (the
default table is treated as ASCII, which covers all but a handful of broadcasts), HD Radio (NRSC-5 —
a different and much larger project), multiple simultaneous stations, a squelch control (`noiseDbfs`
already provides the measurement), scheduled recording, and MP3/FLAC output (WAV needs no encoder,
and an encoder is a dependency plus a jlink entry).

## About, and keeping it true

The panel names the author, the licence, the home page and everything Modula is built on. Two rules
keep that from decaying into a claim:

- **Versions are filtered in from `pom.xml`** through `build-info.properties`, never written as
  constants, so the panel cannot advertise a version that is not the one shipped.
- **`DependenciesTest` reads `pom.xml` and fails both ways** — a shipped artifact missing from
  `AppInfo.dependencies()`, and an entry left behind after a dependency was dropped. Test-scope
  libraries must *not* appear, because they are not distributed. Each entry declares the artifactIds
  it covers, which is what makes the check possible when one line ("dbus-java") stands for two
  artifacts. The guard was verified by deleting an entry and watching it fail, because a drift check
  that cannot fail is decoration.

`librtlsdr` is listed as GPLv2 and explicitly **not bundled** — it is the system's own, reached
through FFM at runtime. That line is the reason an MIT licence on this project is defensible, so it
belongs where a user looks rather than only in NOTICE.

The home page is a real hyperlink when `HostServices` is available and **plain text when it is not**:
a link that looks like a link and does nothing is worse than text you can read and type.

## Recording

`RecordingSink` **decorates the sink the receiver already writes to** rather than re-deriving audio
from the demodulator. What lands in the file is therefore exactly what was played, by construction,
and there is no second signal path to keep in step.

**The volume slider is deliberately not applied to the file.** It is a monitoring control; baking it
in makes a recording made at a low listening level unrecoverable, while leaving it out costs nothing
— the file is at unity and the listener adjusts on playback.

**A recording failure never interrupts listening.** A full disk stops the recording, records the
reason, and leaves the audio playing; the alternative — a receiver that goes silent because a file
could not be written — is the wrong failure for a radio.

`WavWriter` streams, because a recording runs until the listener stops it and `AudioSystem.write`
wants the whole thing up front. A WAV header carries two byte counts that are unknown until the end,
so it writes placeholders and patches them on close — and **the placeholders are the largest size a
reader accepts, not zero**, so a file whose process was killed still plays to its end rather than
decoding as silence.

The file is named after the station when RDS has supplied one, else the frequency. That name comes
from a stranger's transmitter, so `sanitise` strips anything that could steer a path: the label is
joined onto a directory, and a station calling itself `../../x` must not get to choose where the
recording lands.

## The volume control

**Squared, not linear.** A slider wired straight to amplitude puts half travel at −6 dB — barely
quieter — and crams everything useful into the bottom tenth; loudness is perceived roughly
logarithmically, so the control has to be too. `audio/VolumeTaper` is pure and exactly invertible,
which is what lets `Settings.volume` keep meaning **gain**: a configuration written before the taper
loads at the same loudness rather than jumping.

The readout shows the **position** as a percentage and the **gain** in decibels, because since the
taper those are different numbers — showing the gain as a percentage would make the slider look like
it was lying about itself.

## Recording formats and schedules

**ffmpeg only, and WAV is the floor.** 48 kHz stereo 16-bit is 691 MB an hour, which is the whole
argument for compression. A missing encoder falls back to WAV and reports why rather than refusing —
someone who pressed Record wants a recording, and a bigger file beats none.

`EncodedWriter.close()` has real work: close the pipe so the encoder sees end-of-input, then **wait
for it**. A container writes its header last, so a process killed early leaves a file that exists,
has plausible bytes, and will not open. ffmpeg's own output is discarded rather than piped, because
an unread pipe fills and blocks the encoder mid-recording — which would stall the audio thread
feeding it. The tests **decode the result back** rather than checking its size: the JDK reads WAV, AU
and AIFF only, and size cannot tell a finished container from a truncated one.

**Scheduling is polled, not timed.** Fifteen seconds, because the list is editable at any moment and
a timer set per schedule would have to be rebuilt on every edit and would drift across a suspend. The
decision lives in the pure `schedule/Scheduler`, so the awkward cases are testable at any instant —
in particular **an occurrence that crosses midnight is still running on a date its own start never
mentions**, which is the case a naive implementation gets wrong. Overlaps resolve to the most
recently started, so adding a schedule takes effect rather than being ignored until an older one
ends. A repeat with no days means never, not every day.

Modula must be open, and it cannot wake a sleeping machine. That is said in the UI, not implied.

## The system tray

Taken from Nux whole, **both backends**, because Modula runs on three platforms and each backend
covers a different set: `SniTray` speaks StatusNotifierItem over D-Bus where a watcher exists (most
current Linux desktops), `AwtTray` uses `java.awt.SystemTray` elsewhere (Windows, macOS, older X11).
`Trays.create` picks; a machine with neither returns empty and the app simply has no tray.

**Recording is a badge, not a recolour.** Coral already means *fault* on this icon, so a coral mark
and a coral-badged mark are two states while a coral mark and a coral mark are one. The badge is a
small disc in the corner, ringed in the ground colour so it stays legible over the arcs it overlaps,
and sized at 0.15 of the canvas — at 0.22 it swallowed the whole mark at 16px, which is the size that
matters most in a panel.

**The tray menu had a latent bug the recording work exposed:** every read of the item properties went
through the two-argument `propsFor`, whose default is "not listening", so the Linux tray permanently
read **Listen** even while playing — the real flag never reached the menu at all. The state now lives
on `SniMenu.Impl` and bumps the revision, which is what makes the panel re-read the labels.
`SniMenuModelTest` pins both labels, the disabled-until-listening rule, and that every id is distinct.

**The icon is drawn, not shipped as a bitmap.** `TrayIconRenderer` paints it at the size asked for,
so it is sharp on a HiDPI panel, and it carries state in colour — amber listening, grey stopped,
coral faulted — because a tray icon that never changes tells the listener nothing.

**`Platform.setImplicitExit(false)` is what makes closing to the tray possible at all.** Without it,
hiding the last window ends the JavaFX runtime, which calls `stop()`, which ends the process — so the
close handler could consume the event and hide the window perfectly, and the application would quit
anyway. It is set only once a tray really exists, because a hidden window with no icon is an
unreachable process.

**When no tray appears, say so.** A desktop with no StatusNotifierWatcher (GNOME without the
AppIndicator extension) and no AWT tray has nowhere to put an icon, so closing has to quit however
the setting is set — and a listener whose "keep playing in the tray" box is ticked will reasonably
call that a bug. `RadioPane.reportNoTray` puts it in the status line.

**Closing the window only hides it when a tray actually appeared.** Hiding a window that leaves no
icon behind is how an application becomes unreachable, so the behaviour is conditional on the real
thing having registered rather than on the preference alone.

Both backends leave non-daemon threads behind — the AWT event thread, D-Bus workers — so `stop()`
ends with an explicit halt. An FX shutdown alone does not end the process.

## Update checks

Off the receive path, at most once a day, and **it sends nothing**: a plain HTTPS GET of a public
releases endpoint, no identifiers, no telemetry, and the About window says so — a radio that phones
home unannounced deserves the suspicion it would get.

Every failure is silent. There is no state in which a listener wants a dialog because a version
check could not reach the network. The attempt is stamped *before* it is made, so an endpoint that
is down is retried tomorrow rather than on every launch.

`isNewer` sorts a **pre-release below its release**, per semver: someone on `0.4.0-SNAPSHOT` is
running something that precedes `0.4.0` and should be told it exists. Dropping the suffix instead —
the obvious reading, since the numbers are equal — reads as correct and silences the check on
exactly the builds most likely to be stale. A test pins it.

**The feature is inert, not broken, until a release endpoint exists.** `AppInfo.RELEASES_API` is
filtered in from the pom and is empty today, so the Settings checkbox disables itself and says why.

## The native library across platforms

The lookup is cross-platform already — versioned `.so`, `.dylib` and `.dll` names, plus per-host
absolute fallbacks for the loader paths a package manager does not register. What differs is not the
library but **who owns the device**:

- **Linux** — the kernel's `dvb_usb_rtl28xxu` claims it. Blacklist the module.
- **Windows** — the factory DVB-T driver claims it. The user must replace it with WinUSB via Zadig,
  on *Interface 0*. **No application can do this**; it needs elevation and swaps a system driver.
- **macOS** — nothing claims it. This is the easy one.

Both of the first two present identically from inside the process: the library loads and reports
**zero devices**, which is also exactly what an unplugged dongle looks like. That is why
**`source/NativeDiagnosis`** exists — it is pure and unit-tested, so the advice for every platform
can be verified from any platform, and `RtlSdr` only observes the state rather than phrasing it.
`Os.of` matches **macOS before Windows and uses `startsWith`**, because `"darwin"` contains `"win"`
and the loose test handed Mac users Zadig instructions (caught by a test, not by review).

An unhelpful message here is not cosmetic: "fell back to rtl_tcp" covered four unrelated situations
with four different fixes, and on Windows the two most likely ones are indistinguishable from the
symptom alone.

**Packaging caveat, not yet hit:** a signed, notarized macOS `.app` running under the hardened
runtime will refuse to load a Homebrew dylib — that needs
`com.apple.security.cs.disable-library-validation`, and `com.apple.security.device.usb` if sandboxed.
It works in development and fails only in the signed build, so it is worth remembering before the
jpackage work the `.icns` is already waiting for.

## Packaging

`./mvnw -Pdist package` → a `.deb`, `.dmg` or `.msi` in `target/dist` with a jlinked runtime, ~74 MB
installed, no JDK needed. `-Djpackage.type=app-image` gives an unpackaged bundle, which is far faster
to iterate on.

**No moditect.** Every dependency ships a real module descriptor, which is the single thing that
usually makes packaging a JavaFX app painful — contrast Editora, where half the dist profile is
hand-written `module-info` sources for automatic modules.

Four things the profile has to get right, each verified rather than assumed:

- **openjfx publishes every module twice.** The classifier-less `javafx-graphics-26.0.1.jar` is
  genuinely **empty** (measured: 2 entries, 70 bytes); the real one, with `module-info` and the
  natives, carries a platform classifier. Both are in the dependency tree, and two jars claiming
  `javafx.graphics` on one module path is an error at best — at worst the empty one wins and the
  image has no graphics stack. The antrun step deletes the classifier-less ones before jpackage runs.
- **`--enable-native-access=com.modula,javafx.graphics`.** Both call restricted methods for good
  reason — FFM for librtlsdr, `System.load` for the JavaFX natives — and the JDK intends to *block*
  that by default. Without it the app printed six warnings per start and would eventually stop
  working.
- **`StartupWMClass=com.modula.ModulaApp`** in the Linux `.desktop`, read off a live window with
  `xprop`, not guessed: JavaFX derives the class from the **module main class**, not the application
  name. Without it a desktop cannot match the running window to its launcher.
- **`Categories` is literal in the template**, not jpackage's `DEPLOY_BUNDLE_CATEGORY` token, because
  the flag that fills that in (`--linux-menu-group`) is Linux-only and would fail the macOS and
  Windows builds. jpackage's own default is the literal string `Unknown`.

**A "No JDK Modules found" warning is not a failure.** JDK 25 links a runtime from its own run-time
image (JEP 493), so a Temurin JDK with no `jmods` directory still produces a complete image —
verified: 21 modules, and the app launches.

**Known issue — the macOS bundle version is not the real version.** jpackage refuses an app version
whose first number is zero *on macOS only*, so `0.1.0` is shipped to it as `1.1.0`, and that number
reaches `CFBundleVersion`: Finder's Get Info will say 1.1.0 while the app itself correctly says
0.1.0 (About reads the pom-derived `AppInfo.VERSION`, which is untouched). Two ways out — release at
`1.0.0` or above, where the problem cannot arise, or rewrite `Info.plist` after the app-image build
and before the DMG wrap, as Editora does. Nothing does the latter yet.

## CI

`build.yml` — tests then packages on Linux, macOS and Windows, for every push and pull request. The
packaging half is the point: it is what tests cannot check, and what only breaks on the two operating
systems the author cannot try. `release.yml` — on a `v*` tag, four targets, opens a **draft** release
with checksums; a manual dispatch is the dry run and publishes nothing.

**`bash` is pinned as the shell for every step**, because `./mvnw` is a shell script and the Windows
runner defaults to PowerShell. `fail-fast` is off so one platform cannot hide the other two, and
`if-no-files-found: error` because a silently empty artifact is worse than a failed job.

The release job **refuses to build when the tag disagrees with the pom** — a release built from a tag
nobody can reproduce from the repository is worse than no release.

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

**Reads are synchronous, and that is only survivable because the reader does nothing else.** The
original reasoning here — that the asynchronous API wants a callback on its own thread while the
engine's loop is already a thread that wants to block on a read — was true and beside the point:
`rtlsdr_read_sync` keeps *one* transfer outstanding, so whatever the caller does between two reads is
a window in which the dongle's output is discarded. The fix was the `ByteRing` and the `modula-usb`
thread (see *Threading*), which shrinks that window to a memcpy.

`rtlsdr_read_async` remains the better primitive and is what `rtl_tcp` itself uses: it keeps about
fifteen transfers queued, so a stall anywhere in the process cannot empty the queue. Moving to it
needs a `Linker.upcallStub` and a callback that only copies and returns — anything slower in there
stalls libusb's own event loop. Worth doing if the `iqLost` counter ever shows loss on a machine where
the reader is genuinely doing nothing else; it is not worth doing speculatively.

## The spectrum strip

**A Canvas has no layout opinion and must be taught one.** Binding its size to its parent's is the
obvious move and is a trap — the canvas is a child of what it measures, so the binding feeds back and
the strip grows without bound until it pushes everything below it out of the panel. `SpectrumStrip`
answers the layout queries and accepts `resize()` instead.

**The dB range is fixed, not adaptive.** −75 to −15 dBFS, measured off air: a station peaks near −22
and the floor sits around −66. A self-rescaling axis would make the sweep during a seek unreadable,
because everything would move at once.

**The transform runs at status cadence, not per block** — `DemodChain.captureSpectrum` is called from
`RadioEngine.publish`, so nine transforms a second rather than seventy-three, and it is the one
deliberately allocating method on the chain.

Ticks land on `BandPlan` grid points rather than at round frequencies, so a neighbour reads as "the
next station up" instead of as an offset in kilohertz. No waterfall, no dB axis, no peak hold, no
span control: the span is the sample rate, which is a fact of the front end rather than a setting.

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

