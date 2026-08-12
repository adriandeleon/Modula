# Changelog

Notable changes to Modula. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **A station sitting exactly on the weak-signal threshold restyled the dial on every update.** The
  threshold had no hysteresis — the same mistake as the pilot detector, made one layer up and immediately
  after fixing it. A signal must now recover by a margin before it stops being called weak. Observed on a
  real station measuring quieting of exactly 14 dB, which is the threshold itself.

### Added

- **An ADC headroom readout**, shown once there is less than 12 dB left, i.e. once a normal gain step
  would saturate. Measured across the whole sampled window rather than the tuned channel, which is what
  makes it useful: the converter's headroom is spent by the strongest signal anywhere in the window, so a
  weak station flanked by strong neighbours cannot be helped by more gain — they reach saturation first,
  and the compression products land across the multiplex. The receiver previously had no way to answer
  "would more gain help?" about itself.

## [1.1.0] - 2026-08-11

A reception release. Everything in it came out of one report — spotty audio on macOS through a USB hub,
with the stereo indicator turning on and off — which turned out to be three unrelated faults stacked on
top of each other, none of which was the hub.

### Fixed

- **Dropped samples on the USB path, which presented as spotty audio and a stereo indicator flickering
  on and off.** Reading and demodulating shared one thread, and `rtlsdr_read_sync` keeps only one USB
  transfer outstanding — so for as long as each block spent in the filter chain nothing was in flight,
  and whatever the dongle produced was discarded. At 1.2 MSPS a millisecond of that gap is 1200 I/Q
  samples, and a gap inside a block is a phase discontinuity, which is what dropped the 19 kHz pilot
  lock. Reading now happens on its own `modula-usb` thread that does nothing but read, handing blocks
  to the DSP through a new `ByteRing`. It was worse on macOS and worse again through a hub, because
  resubmitting a bulk transfer through IOKit costs more than through Linux usbfs; the `rtl_tcp` path
  was never affected, since `rtl_tcp` keeps about fifteen transfers queued.
- **The front end ran on AGC — two of them, in fact.** The tuner's own AGC and the RTL2832U's digital
  AGC were both enabled, hunting the same signal. Both are off now in favour of a fixed gain, on both
  the librtlsdr and `rtl_tcp` paths. An AGC drives an 8-bit ADC toward full scale whatever the signal is
  doing, and on a strong local station its compression products land across the whole multiplex — the
  19 kHz pilot, the 38 kHz difference channel and the 57 kHz RDS subcarrier included — so stereo goes
  marginal and RDS vanishes on a station whose meter reads strong. It also made the level reading
  meaningless, since an AGC reports its own target rather than the station.
- **Stereo and the pilot indicator turning on and off on a steady signal.** The pilot lock detector was
  a single threshold with no hysteresis, so a detector level sitting anywhere near it alternated. That
  was not cosmetic: the chain switches the audio between the stereo matrix and a mono copy on this flag,
  so the audio path was changing several times a second — heard as spottiness — while the status line
  reported *"no pilot"*, which reads as the station cutting out. It now acquires at one level and
  releases at a lower one. Acquisition is unchanged, so stereo still engages as quickly after a retune.
- **A weak signal could never be reported as weak.** The decision tested channel power against −45 dBFS,
  but with an AGC running that figure reports the AGC's target rather than the station — measured, −9.85
  dBFS on an empty channel against −9.75 for a weak one — so nothing ever came within 35 dB of the
  threshold and the WEAK state was unreachable. It is now judged on quieting, which measures the station,
  falling back to power for AM and for an empty channel where there is no quieting to judge.
- **A single dropped sample used to fault the receiver for the rest of the session.** The fault test
  read a cumulative counter for "greater than zero", so one momentary overrun pinned the display coral
  permanently. It now reports loss since the previous status, and so can clear.

### Added

- **Four separate loss counters** — samples lost on the bus, discarded before demodulation, dropped by
  the sound card, and filled with silence — where there was previously one number that attributed all
  four to the audio device. The status line names the dominant one, and each has a different remedy.
- **A stereo blend.** The difference channel is scaled between 0 and 1 on signal quality rather than the
  output being switched between full stereo and mono, so a marginal station narrows its image and has the
  difference channel's noise attenuated with it — progressively, instead of at a threshold. Hysteresis
  alone only moves the cliff: either side of it the listener gets all 20 dB of the noise stereo costs, or
  none of the separation. Full image at 20 dB of quieting, mono at 8, linear between, smoothed over about
  a third of a second. The stereo indicator still reports what the *station* is transmitting, so the
  status line names the blend while it is partial — otherwise a lit indicator over near-mono audio has no
  explanation.
- **A quieting figure in the status line** — how far the carrier has suppressed the discriminator's
  noise, higher being better, 0 meaning an empty channel. This, not the dBFS beside it, is what says
  whether a station is any good: multiplex noise has a 45 dB usable range where RF power had none worth
  using, which is why seek has always thresholded on it. Expressed as suppression rather than raw noise
  dBFS so that better reads as a bigger number, and so there are not two different negative decibel
  figures side by side. Absent on AM, where there is no discriminator to measure.
- **A carrier-offset readout**, in kHz and ppm, shown once the offset exceeds 5 kHz. Read off the
  discriminator's DC level, which is the offset by definition. Worth surfacing because a frequency
  error damages stereo and RDS before it damages mono: the 38 kHz and 57 kHz subcarriers sit at the top
  of the multiplex and reach the channel filter's edge first, so a miscalibrated dongle sounds perfect
  in mono and poor in stereo — a confusing symptom to diagnose by ear and an obvious one to read.

### Dependencies

- JUnit 5.11.4 → 6.1.2, JavaFX 26.0.1 → 26.0.2, dbus-java 5.1.1 → 5.2.0, and the Maven
  dependency and antrun plugins. Test and packaging jobs pass on all three platforms.
- The workflow actions: checkout 4 → 7, setup-java 4 → 5, upload-artifact 4 → 7,
  download-artifact 4 → 8, action-gh-release 2 → 3. **This is the first release built with them**, so
  it is also the first real exercise of the download-artifact and gh-release majors, which `build.yml`
  never reaches.

## [1.0.0] - 2026-08-09

The first release. Everything below is the whole history rather than a delta.

Numbered 1.0.0 rather than 0.1.0 deliberately: jpackage refuses an application version whose first
number is zero on macOS, so a 0.x build ships there with its version rewritten and the bundle
disagrees with the application about what it is. Starting at 1.0.0 makes that impossible.

### Radio

- **FM, in stereo.** 19 kHz pilot PLL, the 38 kHz difference channel recovered coherently, ~33 dB
  separation. Forced mono is offered because stereo raises the noise floor about 20 dB and a weak
  station is often more listenable without it.
- **AM.** Medium wave and aviation, envelope-detected with a DC-blocking high-pass.
  *Medium wave needs a dongle that wires the Q branch (an RTL-SDR Blog V3); on a stock dongle the
  aviation band is the AM band you can actually reach.*
- **RDS.** Station name, radio text, programme type, traffic flags, clock-time, and the alternative
  frequency list. The full EBU Latin repertoire, so accented station names read correctly.
  Alternative frequencies are shown, not switched — see the README for why.
- **Seek** by multiplex noise rather than RF power, in both directions, with band wrap.
- **Presets**, session state, and a channel-grid spectrum strip across ±600 kHz.

### Hardware

- **Direct access** to librtlsdr through the Java FFM API, with `rtl_tcp` as a fallback.
- **Per-platform diagnosis** when the dongle cannot be used: which library to install, or that the
  driver needs replacing with WinUSB via Zadig, or that the kernel's DVB module has claimed it.

### Recording

- **WAV, FLAC and MP3.** The compressed formats use ffmpeg when it is present, and fall back to WAV
  with an explanation when it is not. WAV is about 690 MB an hour, which is why the option exists.
- **Scheduled recordings** from a hand-editable `schedules.txt`, one-off or weekly.

### Interface

- The **Night Dial** design: amber spent only on the frequency and the controls that change it.
- **Command palette** (`ctrl+shift+P`), right-click menu that names every shortcut, settings, about.
- **System tray** on Linux (StatusNotifierItem) and elsewhere (AWT), showing listening, stopped,
  faulted and recording states, with Listen and Record in its menu.
- **Update checking** against the releases endpoint, once a day, sending nothing.

### Build

- **Native installers** — `.deb`, `.dmg`, `.msi` — with a jlinked runtime, about 74 MB installed.
- **CI** on Linux, macOS and Windows: tests and installers on every push, releases from a `v*` tag.

### Fixed late

- The volume slider was linear in amplitude, so half travel was only −6 dB and everything useful was
  crammed into the bottom tenth. It is squared now: half travel is about −12 dB, close to the −10 dB
  usually described as half as loud. The stored setting still means gain, so an existing
  configuration loads at the same loudness.
- A dongle that will not open — almost always one another program is holding — now falls back to
  `rtl_tcp` rather than failing, which in the commonest case means falling back to the very program
  holding it.
- `settings-git-found` and `settings-git-missing` were used from Java but never defined in the
  stylesheet, so the ffmpeg and tray detection results rendered at #03080A on a #0B0C0E ground.

### Known issues

- Installers are **unsigned**. macOS will warn on first open (right-click → Open, once); Windows
  SmartScreen may too. Signing and notarisation are the obvious next step.
- Medium-wave AM needs a dongle that wires the Q branch, such as an RTL-SDR Blog V3. On a stock
  dongle the aviation band is the AM band you can actually reach.
- Alternative frequencies are listed but not switched to; see the README for why one tuner makes
  that an audible gap rather than a feature.
- Closing to the tray requires a tray. A desktop with no StatusNotifierWatcher — GNOME without the
  AppIndicator extension — has nowhere to put the icon, so closing the window quits; Modula says so
  in the status line and in Settings rather than leaving it to be discovered.

[1.0.0]: https://github.com/adriandeleon/Modula/releases/tag/v1.0.0
