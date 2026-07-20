# Karoo Magicshine Controls

Karoo app and Karoo extension for controlling a Magicshine light over BLE.

![Karoo Magicshine Controls UI](docs/images/karoo-ui.png)

## About

Magicshine Controls is a Karoo app plus ride-field extension for controlling supported Magicshine lights over BLE.

- Karoo-native control screen with telemetry
- four-part in-ride field for light toggle, five-second flash, battery, and app launch
- selection gate for supported lamp families

## Ride Screen Button

Add the Magicshine field to a Karoo ride page to get four in-ride segments:

- light state toggles between `OFF` and the remembered last output, level, and mode
- `FLASH` flashes the selected light output for five seconds, then restores its previous state
- battery shows `HIGH`, `MID`, or `LOW` for the tested EVO 1700, or a percentage on compatible continuous-telemetry firmware
- `APP` opens the full app
- the field disconnects again when you leave the ride screen

## Release Notes

### Beta 1.4.1

- maps the EVO 1700's confirmed A4 values 100, 50, and 30 to truthful `HIGH`, `MID`, and `LOW` battery states
- colors battery status green, orange, or red in the app and ride field
- falls back to an unavailable state for unknown EVO 1700 battery values instead of guessing
- adds the separate Magicshine BLE sniffer and documents the complete full-power discharge capture

### Beta 1.4.0

- expanded the ride field from two to four segments
- added a five-second `FLASH` action with automatic restoration of `OFF`, steady, `SOS`, or continuous flash state
- added live light battery percentage to the ride field
- added compact status, flash, battery, and app icons to the ride field

### Beta 1.3.5

- reconnects through Karoo Bluetooth startup instead of giving up while a ride is active
- fixes lamp discovery on Karoo by using an unfiltered BLE scan and advertisement names
- sanitizes Magicshine lamp names before saving them

### Beta 1.3.4

- hardened Karoo extension lifecycle against Appstore service restarts
- prevented unhandled Karoo binder disconnects during extension shutdown
- kept the standard Karoo Extensions Library update flow

### Beta 1.3.3

- removed the in-app `UPDATE` button
- restored the standard Karoo Extensions Library update flow
- kept live manifest metadata so Karoo can show its system `UPDATE` button

### Beta 1.3.2

- added in-app `UPDATE` button that opens the official Karoo Appstore detail page
- kept update handoff in Hammerhead Appstore instead of silent installing
- pointed future update checks at the live GitHub manifest

### Beta 1.3.1

- hardened app `CONNECT` recovery from stale `found` / disconnected states
- added short multi-attempt BLE reconnect after transient GATT failures
- avoided unnecessary reconnect work when already connected

### Beta 1.3

- reduced BLE scanning and retry churn when the lamp is not nearby
- added fresh `NO LAMP` retry from the ride button
- added fresh `CONNECT` retry from the app after a no-lamp miss
- improved ride disconnect and field state handling

### Beta 1.2.1

- hardened ride reconnect after Bluetooth dropouts
- reduced BLE scan churn in the ride extension
- improved app and ride button state sync
- improved disconnected and no-lamp field states

### Beta 1.2

- added ride screen button
- added lamp selector gate
- added support for `M2-B0` / `M2-BO` / `M1-B0` / `M1-BO` lamps
- improved ride auto-connect and disconnect
- improved app and ride state sync
- refreshed icon and app metadata

## Supported Light Families

The current selector gate looks for BLE names starting with:

- `M2-B0`
- `M2-BO`
- `M1-B0`
- `M1-BO`

## Karoo Flow

1. Open the app on the Karoo.
2. Choose the lamp once in the selection gate.
3. Press `CONNECT`.
4. Wait for the short connect blink.
5. Choose `LOW`, `HIGH`, or `OFF`.
6. Use `25`, `50`, `75`, `100`, `SOS`, or `FLASH`.
7. Optionally add the Magicshine field to a ride page for quick in-ride control.
8. Press `DISCONNECT` when finished.

## Current Caveats

- The current implementation is tuned to the tested light variant, not the full Magicshine product line.
- EVO 1700 firmware v1 exposes only coarse 100/50/30 battery states over BLE, remaining at 30 until shutdown. The app deliberately presents these as `HIGH`, `MID`, and `LOW`, not exact percentages.
- The Karoo UI is the primary target; generic phone layouts are not a focus yet.

## Diagnostics

- SRAM/ANT investigation notes live in `SRAM_SNIFFER_NOTES.md`.
- The separate diagnostic app lives in `sram-sniffer/` and is intentionally decoupled from the Magicshine control app.
- Magicshine protocol findings and the separate BLE diagnostic app are documented in `MAGICSHINE_BLE_SNIFFER_NOTES.md`.

## License

MIT License
