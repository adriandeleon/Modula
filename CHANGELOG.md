# Changelog

Notable changes to Modula. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything so far. Modula has not been released yet, so this is the whole history rather than a
delta; the first tag will close this section.

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

- The macOS bundle reports version `1.x.y` for a `0.x.y` build. jpackage refuses a leading zero on
  macOS alone; the application itself reports the true version. Releasing at `1.0.0` or above makes
  it moot.
- Installers are unsigned. macOS will warn on first open.
- Closing to the tray requires a tray. A desktop with no StatusNotifierWatcher — GNOME without the
  AppIndicator extension — has nowhere to put the icon, so closing the window quits; Modula now says
  so rather than leaving it to be discovered.
