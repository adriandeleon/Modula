# Modula

Listen to broadcast radio with an RTL-SDR dongle. Java 25 + JavaFX 26.

Not an SDR panel — a radio. A frequency, two tune buttons, volume, a signal bar. No demodulator
picker, no filter-bandwidth slider, no gain or AGC controls, no FFT settings.

**Status:** FM works, in stereo, with RDS station names and radio text, over `rtl_tcp`. Measured
channel separation is 33 dB, flat from 100 Hz to 10 kHz.

## Running it

Start the server that talks to the dongle, then the app:

```bash
rtl_tcp -a 127.0.0.1
```

```bash
./mvnw javafx:run
```

Press **Listen**, then:

| | |
|---|---|
| ◀ ▶ or **←** **→** | step one channel |
| ◀◀ ▶▶ or **shift+←/→** | seek to the next real station (press again to cancel) |
| MHz box | tune directly |
| **★ Save** | add the current frequency to the preset bank, optionally named |
| double-click a preset | tune to it (**Delete** removes it) |

The **STEREO** indicator lights when the station is transmitting a pilot; the **Stereo** checkbox
forces mono, which is often more listenable on a weak station since stereo raises the noise floor by
about 20 dB.

Stations carrying RDS show their name and programme type under the frequency, with radio text —
usually the current song — below that. It takes a second or two to appear: the station name arrives
two characters at a time across four transmissions, and radio text four at a time across sixteen.
Many stations scroll the name, cycling frames to spell out a longer message.

The status line reports the RDS state — `no pilot`, `pilot, no RDS`, or a group count and the
recovered symbol rate — which distinguishes a station that simply doesn't transmit RDS from a
decoding problem.

Frequency, region, volume and the stereo setting are remembered between runs, in `~/.modula/`.
Presets live in `~/.modula/presets.txt`, one per line, and are meant to be hand-editable.

`rtl_tcp` is used rather than direct USB on purpose: it needs no native code, no `jextract`
bindings and no driver install, and it keeps working when the dongle lives on another machine.
Direct hardware access is milestone 2 and slots in behind the same `IqSource` interface.

## Tests

```bash
./mvnw test
```

The DSP is pure, so the receiver is verifiable numerically with no hardware, no sound card and no
toolkit — `DemodChainTest` modulates a known tone, runs it through the real chain, and asserts it
comes back at the right frequency and amplitude.

`./mvnw verify` additionally enforces formatting. Run `./mvnw spotless:apply` before committing.

## AM will not work on a stock dongle

Medium-wave AM broadcast is 530–1700 kHz. The R820T2/R828D tuner in a normal RTL-SDR bottoms out
around **24 MHz**, so that band is unreachable — this is hardware, not software. Three ways round it:

- an **RTL-SDR Blog V3** in direct-sampling mode (`RtlTcpSource.setDirectSampling`) — works, with
  mediocre sensitivity and image rejection
- an **upconverter**, which performs better and costs an extra box
- skip medium wave and use the **aviation band** (118–137 MHz), which is also AM and is in range

`IqSource.tunableRange()` exists for exactly this: the UI asks the source whether a band is reachable
rather than tuning into silence.

## Recording a capture

A recorded IQ file makes every later DSP change verifiable without hardware attached:

```bash
rtl_sdr -f 101500000 -s 1200000 -n 12000000 station.iq
```

Replay it through `FileReplaySource`, which satisfies the same `IqSource` interface as the dongle.

## Layout

| Package | |
|---|---|
| `dsp` | FIR design, decimating FIR, de-emphasis, delay line, PLL, power measurement. Pure. |
| `demod` | FM discriminator, pilot tracking, stereo decoder. Pure. |
| `rds` | The 57 kHz data channel: bits, block sync, CRC, group decoding. Pure. |
| `band` | Band plans and channel grids. Pure. |
| `source` | `IqSource` and its implementations — the entire hardware boundary. |
| `audio` | Ring buffer and the `javax.sound.sampled` sink. |
| `radio` | `DemodChain`, `RadioEngine` and seek — the only package that owns threads. |
| `config` | Presets and session state. Only `ConfigStore` touches the disk. |
| `ui` | The only package that touches JavaFX. |

Signal flow and the invariants that keep it correct are in [CLAUDE.md](CLAUDE.md).

## Roadmap

1. **Mono FM over `rtl_tcp`** — done
2. **Stereo** (19 kHz pilot PLL, 38 kHz difference channel) — done
3. **Seek, presets, persistence** — done
4. **RDS** (station name, radio text, programme type) — done
5. Direct hardware via Panama/`librtlsdr`; spectrum strip

## Licence

TBD.
