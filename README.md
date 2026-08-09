# Modula

Listen to broadcast radio with an RTL-SDR dongle. Java 25 + JavaFX 26.

Not an SDR panel — a radio. One amber number is the brightest thing on screen; everything else is
instrumentation around it, dimmed until it has something to say. No demodulator picker, no
filter-bandwidth slider, no gain or AGC controls, no FFT settings.

**Status:** complete. FM in stereo with RDS, seek, presets, a ±600 kHz spectrum strip, and direct
hardware access. Measured channel separation is 33 dB flat from 100 Hz to 10 kHz; RDS is verified
against an off-air recording.

## Running it

```bash
./mvnw javafx:run
```

That's it — Modula talks to the dongle directly through `librtlsdr`. If the library isn't installed,
or the dongle lives on another machine, it falls back to `rtl_tcp`:

```bash
rtl_tcp -a 127.0.0.1
```

The status line says which it's using. Only one program can hold the dongle at a time, so stop
`rtl_tcp` before using direct access (`usb_claim_interface error -6` means something else has it).

Press **Listen**, then:

| | |
|---|---|
| ◀ ▶ or **←** **→** | step one channel |
| ◀◀ ▶▶ or **shift+←/→** | seek to the next real station (press again to cancel) |
| type a number, **Enter** | tune directly — the entry opens as you type |
| **1**–**9** | recall that preset |
| **↑** **↓** | volume |
| **Space** | listen / stop |
| **+** | save the tuned station, then name it inline |
| right-click a chip | rename or remove it (**Delete** also removes) |

The band across the middle of the glass is a ±600 kHz spectrum: the amber line is where you are
tuned and the humps either side are the neighbours. During a seek the frequency dims and the strip
is where the sweep is visible.

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

Both paths sit behind the same `IqSource` interface, so nothing downstream knows which is in use —
as does `FileReplaySource`, which replays a recorded capture for testing.

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
rtl_sdr -f 98900000 -s 1200000 -n 36000000 station.iq
```

Replay it through `FileReplaySource`, which satisfies the same `IqSource` interface as the dongle.
`scripts/MakeRdsFixture.java` distils one into the 281 KB golden fixture the RDS test decodes.

Check the spectrum before trusting a capture — an empty channel looks exactly like a broken decoder.

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
| `ui` | The only package that touches JavaFX. Implements the Night Dial kit. |

Signal flow and the invariants that keep it correct are in [CLAUDE.md](CLAUDE.md).

## Roadmap

1. **Mono FM over `rtl_tcp`** — done
2. **Stereo** (19 kHz pilot PLL, 38 kHz difference channel) — done
3. **Seek, presets, persistence** — done
4. **RDS** (station name, radio text, programme type) — done
5. **Direct hardware** via Panama/`librtlsdr` — done
6. **Spectrum strip and the Night Dial interface** — done

## Licence

TBD.
