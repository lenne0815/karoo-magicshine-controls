# Magicshine BLE Sniffer Notes

## Purpose

`magicshine-sniffer` is a separate diagnostic Karoo app. It connects directly to a
Magicshine M1-B0/M2-B0 lamp, sends known protocol requests, records every response
as full hex, and validates frame length, end marker, and XOR checksum.

It intentionally does not share state with the released control app. Only one app
may connect to the lamp during a capture.

## Build and package

- Gradle module: `magicshine-sniffer`
- Android package: `com.lenne0815.magicshinesniffer`
- Karoo extension ID: `karoo-magicshine-sniffer`
- Build task: `./gradlew :magicshine-sniffer:assembleDebug`
- Device log directory: `files/ble-sniffer/` inside the app sandbox

## Capture workflow

1. Stop or disable `com.lenne0815.karoomagicshine` and disconnect the official
   Magicshine phone app.
2. Open **Magicshine BLE Sniffer** on the Karoo.
3. Press **CONNECT** and wait for `READY notifications enabled`.
4. Press **MARK CURRENT** to mark the known physical battery state in the log.
5. Press **A4 CANDIDATE** once. Use **POLL A4** only for a controlled discharge
   test where the lamp remains on and its physical indicator is observed.
   **DRAIN 100%** sets the tested M2 output to full power, polls A4 every five
   seconds, and records A1/AB/AC support telemetry every minute until disconnect.
6. Use **SUPPORT SWEEP** to query A1, A4, A5, A9, AB, AC, and AD.
7. Use **OFFICIAL BURST** only when needed. It repeats the captured official-app
   sequence and briefly changes the lamp to low steady mode.
8. Pull the newest text file from the app's `files/ble-sniffer/` directory.
9. Re-enable the released control app after the sniffer disconnects.

## Capture on 2026-07-20

Device under test:

- Advertised name: `M2-B0 EVO_1700`
- Address shown by Android: `02:04:00:00:56:FF`
- Proprietary service: `FFE1`
- Notification/write characteristic: `FFE0`
- No standard BLE Battery Service (`180F` / `2A19`) is exposed.
- Reported firmware revision in the B1 response: `v1`

The A4 request and response were:

```text
TX DE06A400A2ED
RX DE13B40000000000640000000000000000C3ED
```

The response is structurally valid:

- Actual and declared length are both 19 bytes.
- The final marker is `ED`.
- The received and calculated XOR checksum are both `C3`.
- The B4 payload is `00 00 00 00 00 64 00 00 00 00 00 00 00 00`.

Repeating A4 by itself, in the support sweep, and after the official sequence
produced the exact same response. Therefore the production parser is reading the
captured byte correctly, but there is no evidence that payload byte 5 is a live
battery percentage. Naming `0x64` as 100% was based on a single official-app
capture that already displayed 100%, so that interpretation was circular.

### Controlled discharge test

The light remained on for ten minutes while A4 was polled every five seconds and
the full support sweep was sent every minute. All 135 valid B4 frames were
byte-for-byte identical:

```text
DE13B40000000000640000000000000000C3ED
```

During the same connection, the B1 temperature changed from 25 C (`0x19`) to
27 C (`0x1B`). This proves the device was returning live telemetry while the
supposed battery byte remained fixed. The complete capture is stored at
`.artifacts/magicshine-discharge-10min.txt` (SHA-256
`a51d370d17ad08b75ee0a433bf9d5687cdef9557082f13276d65cb983cb669c8`).

### Official app comparison

Magicshine Android app 1.2.91 was decompiled for protocol comparison. Its
`BatteryInfo` implementation sends A4 and reads `content[4]`, which is the same
B4 data byte that this project parsed. It therefore repeats the firmware's fixed
100 value rather than obtaining a second battery measurement.

The official app also defines these read requests:

```text
A1 query info       DE06A100A7ED
A4 battery          DE06A400A2ED
A5 profiles         DE06A500A3ED
A9 endurance        DE06A900AFED
AB speed sensor     DE06AB00ADED
AC light sensor     DE06AC00AAED
AD product model    DE06AD00ABED
```

On the tested EVO 1700 firmware v1, A5 and A9 writes are accepted but produce no
notification response. A9 would contain hours, minutes, and brightness on devices
that implement it, but it cannot provide a runtime estimate on this firmware.

### GATT survey

The complete GATT database was inspected and all readable characteristics were
queried:

- `FFE0` is notify/read/write. A direct read only returned a stale command buffer,
  not independent telemetry.
- `FFE2` is notify/read/write but returned no data.
- `FEB3` with `FED4` through `FED8` is the PHY OTA service. Its readable values
  were empty or zero-filled and contained no changing battery data.
- No standard Battery Service (`180F`) or Battery Level characteristic (`2A19`)
  exists.

Other responses from the same capture:

```text
A1 -> DE0DB10000111703170101AEED
A6 -> DE07B70001B1ED
AB -> DE08BB0000FF4CED
AC -> DE08BC0000FF4BED
AD -> DE13BD0045564F5F3137303020000000008BED
mode write -> DE07B60001B0ED
```

The AD payload contains the ASCII device name `EVO_1700`. AB and AC both contain
`00 00 FF`; A6 and the mode write return short acknowledgements. None of these
responses exposes an obvious live percentage in this capture.

## Current conclusion

EVO 1700 firmware v1 does not expose an exact, continuously changing battery
percentage over its available BLE interface. B4 payload byte 5 is live battery
telemetry, but a complete discharge proves that it is heavily quantized. The
official app incorrectly presents these coarse values as exact percentages. The
official A5/A9 reads, standard Battery Service, alternate readable GATT
characteristics, and other known query responses provide no better replacement.

The production app must not display B4 as a valid percentage for M2-B0/M2-BO
lights. It now shows `--%` for this family. Obtaining a useful value requires one
of the following, neither of which is an exact BLE measurement from current
firmware:

- a calibrated consumption estimator initialized after a confirmed full charge;
- different lamp firmware that implements changing B4 or BA data;
- hardware/firmware access to the lamp's battery-voltage ADC.

The lamp's power-button LED documents only three local battery ranges:

- green: 21-100%;
- red: 11-20%;
- flashing red: 1-10%.

These ranges explain why the lamp can warn the rider without exposing an exact
percentage. They cannot be mapped directly to A4: the complete BLE capture
returned 100, 50, and 30, not the documented LED range boundaries 100, 20, and
10. The power-button LED color at the A4 transitions was not remotely observable.

### Complete full-power discharge

A full-power discharge started at 10:04:32 and disconnected at 12:50:03 on
2026-07-20, for a total runtime of 2:45:30. A4 changed in discrete steps rather
than continuously:

```text
10:04:33.845 - 11:37:57.615  0x64  100 decimal  1,078 frames
11:38:02.756 - 11:52:00.088  0x32   50 decimal    162 frames
11:52:05.225 - 12:49:57.560  0x1E   30 decimal    669 frames
12:50:03.236                  BLE disconnected
```

All 1,909 B4 frames had valid length, end marker, and checksum. There were no
intermediate values or one-off outliers. AB and AC also remained fixed for all 160
support sweeps. A1 temperature rose from 28 C to a peak of 61 C, then declined to
30 C before shutdown; it did not provide battery information.

This disproves the initial `100/20/10` range-ceiling hypothesis. The only truthful
UI based on A4 is categorical, not a percentage. The production parser maps the
confirmed M2-B0/M2-BO values to `HIGH`, `MID`, and `LOW`; any unobserved value is
left unavailable rather than guessed.

Final capture: `.artifacts/magicshine-discharge-full-2026-07-20.txt`, 4,790 lines,
609,688 bytes, SHA-256
`1e5952b00c1c635190e873ff827687b4423d3e0a029cecb905544bd01fc46c94`.

Related implementation: [KarooFireFly](https://github.com/derstrassi/karoofirefly)
also sends A4 and reads the same B4 field, so it is not an independent source for
the battery percentage.
