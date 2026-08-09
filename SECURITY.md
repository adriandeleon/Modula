# Security

Modula is a desktop radio receiver. It is worth knowing what it actually does, because three of
those things are the ones worth reporting bugs about:

- it drives a USB device through `librtlsdr` via the Java FFM API;
- it opens a TCP connection to `rtl_tcp` when no dongle is attached;
- it launches `ffmpeg` as a subprocess to encode recordings;
- it makes one outbound HTTPS request a day to the GitHub releases API, sending nothing.

It has no server, no account, and no telemetry.

## Reporting

Please open a [security advisory](https://github.com/adriandeleon/Modula/security/advisories/new)
rather than a public issue. If that is not available to you, open a normal issue saying only that you
have found something and how to reach you.

Modula is a personal project with no security team behind it, so treat any timeline as best effort —
but a report will be read.

## Scope

The parts most worth looking at are the ones that take input from outside the program: the RDS
decoder, which parses whatever a transmitter chooses to send; the recording file names, which are
derived from that same RDS data and joined onto a path; and the schedule and preset files, which are
parsed from disk.
