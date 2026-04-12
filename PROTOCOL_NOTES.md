# Magicshine Protocol Notes

Source capture:
- `magicshine-session-final.pklg.pklg`
- Converted helper text export
- Session window: `2026-04-12 09:05:14+02:00` to `2026-04-12 09:10:58+02:00`

User markers during the capture:
- Start temperature: `27C`
- Later: `34C`
- Later: `31C`
- Battery stayed at `100%`
- Flow:
  - LED module 1: `10 20 30 40 50 60 70 80 90 100%`
  - LED module 1: `SOS`
  - LED module 1: `Blitz`
  - LED module 2: `10 20 30 40 50 60 70 80 90 100%`
  - LED module 2: `SOS`
  - LED module 2: `Blitz`
  - Disconnect

## BLE layout

- Service UUID seen in advertising: `FFE0`
- Active write/notify handle during control: `0x000E`
- Nordic-side app code currently uses:
  - service `0000FFE1-0000-1000-8000-00805F9B34FB`
  - characteristic `0000FFE0-0000-1000-8000-00805F9B34FB`

## Telemetry frames

### Temperature candidate

The strongest temperature candidate is the byte directly after `1703` inside `DE0DB1...` notifications:

- `DE0DB100001117031B0101A2ED` -> `0x1B = 27`
- `DE0DB100001117031F0101A6ED` -> `0x1F = 31`
- `DE0DB100001117032201019BED` -> `0x22 = 34`
- `DE0DB100001117032301019AED` -> `0x23 = 35`

This matches the user markers closely, so the current best assumption is:

- `DE0DB1`
- find `1703`
- the following byte is the lamp temperature in Celsius

### Battery

Battery is still carried by `DE13B4...`:

- Example: `DE13B40000000000640000000000000000...`
- `0x64 = 100`

## Command families

The capture clearly shows two different `DE14A201...` families.

### Module 1 family

Pattern:

- `DE14 A201 0101 01VV 0001 5000 0000 0000 00BB CCED`

Observed steady brightness values:

- `0A`
- `14`
- `1E`
- `28`
- `31`
- `3C`
- `46`
- `4F`
- `59`
- `63`

Observed special frames:

- `DE14 A201 0101 0103 0001 5000 ...`
- `DE14 A201 0101 0108 0001 5000 ...`
- `DE14 A201 0101 0263 0001 5000 ...`
- `DE14 A201 0101 0363 0001 5000 ...`

Working checksum rule for the visible `VV` byte:

- `CC = VV xor 0x5C`

Examples:

- `0x0A xor 0x5C = 0x56`
- `0x63 xor 0x5C = 0x3F`

### Module 2 family

Pattern:

- `DE14 A201 0200 010A 01MM VV00 0000 0000 00BB CCED`

Observed steady brightness values:

- `0A`
- `14`
- `1E`
- `28`
- `32`
- `3B`
- `46`
- `50`
- `59`
- `64`

Observed special frames:

- `DE14 A201 0200 010A 0102 6400 ...`
- `DE14 A201 0200 010A 0103 6400 ...`
- `DE14 A201 0201 010A 0001 5000 ...`

Working checksum rule for the visible mode/brightness pair:

- `CC = VV xor (MM + 0x04)`

Examples:

- steady `MM=01`, `VV=64` -> `0x64 xor 0x05 = 0x61`
- mode `MM=02`, `VV=64` -> `0x64 xor 0x06 = 0x62`
- mode `MM=03`, `VV=64` -> `0x64 xor 0x07 = 0x63`

## Known support frames

The original app also sends support/query frames:

- `DE06A100A7ED`
- `DE07A601EF4FED`
- `DE06A400A2ED`
- `DE06AB00ADED`
- `DE06AC00AAED`
- `DE06AD00ABED`

Important practical finding:

- `DE06A100A7ED` is followed by the `DE0DB1...` temperature reply.
- `DE06A400A2ED` is followed by the `DE13B4...` battery reply.

## Practical app implications

- The Karoo app can show temperature from `DE0DB1`.
- The Karoo app can keep battery from `DE13B4`.
- The Karoo app can expose two LED modules instead of the previous single-channel simplification.
- Module 2 brightness is now well enough understood for a proper command builder.
- Module 1 brightness is mostly understood, but the exact 10-step values in the original app were slightly uneven (`31`, `4F`, `63` instead of exact decimal tens in a few places), so current code should treat that mapping as best-effort rather than fully proven.

## Karoo connect regression note

- We already had working telemetry transport during manual light changes. The protocol itself was not missing.
- The later regression was on the Karoo connect path: the initial connect-time burst was being dropped before any ATT write actually went out.
- The fix that restored incoming notifications on the Karoo was:
  - wait until `FFE1/FFE0` is actually available after service discovery
  - enable notifications first
  - only then send the connect burst
  - retry the writes instead of silently returning when the characteristic is not ready yet
- Verified in Android system log on the Karoo:
  - `registerForNotification() - address=02:04:00:00:56:FF enable: true`
  - afterwards incoming GATT notifications appear again (`gatt_data_process op_code = 27`)
- Important reminder for future work:
  - this was not a new protocol discovery
  - it was reconnecting the same notification path that had already worked after manual light commands
  - do not reinvent the telemetry protocol when the actual issue is just lost writes on connect
