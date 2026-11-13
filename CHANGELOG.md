# Changelog

## 1.1 - 2026-11-13

- Add **Light On/Off**, **Flash**, **Light Battery** and **Open App** as independent ride fields. The existing **Light Controls** strip keeps its original ID and layout.
- Profile previews show sample values without requesting Bluetooth. The standalone app launcher does not keep a lamp connection open.
- Moving between lamp fields no longer restarts the connection attempt. Opening the control app from the last visible field no longer triggers a delayed disconnect.
- Android version code 32 updates version 1.0 in place.

## 1.0 - 2026-09-12

First release without the Beta label. Android version code 31 remains higher than
Beta 1.4.1's code 30, so the normal Karoo Extensions Library update flow applies.

### Fixed

- Connecting no longer changes the beam/mode or sends OFF as part of telemetry.
- Battery and temperature are queried with read-only A4/A1 requests every 30
  seconds while connected. Values expire after 90 seconds without a response,
  checked at each poll; repeated identical responses still count as fresh.
- Remote BLE disconnections stop polling and repeating commands and clear the
  connection/telemetry display. CONNECT checks the actual connection, not a
  cached label.
- App and ride-field controls share the same output state; the redundant
  persisted toggle flag is no longer read or written.
- Failed or stalled OFF writes no longer prevent bounded GATT cleanup.
- Shortens the ride-field FLASH to two seconds at the user's request.
- Stopping FLASH lets an in-flight GATT write finish before restoring the prior
  mode. Its timer starts after the first flash write, not during BLE
  connection startup.
- Service destruction cancels flash, connection retries, and controller jobs.
- Listener registration is safe across UI and Bluetooth callback threads.

### Verification

- Regression tests cover periodic polling, link loss, session replacement,
  cancellation, failed/stalled disconnects, shared output state, battery mapping,
  and lengths/checksums of generated protocol frames.
- APK signing identity is retained from previous releases for in-place updates.

## Beta releases

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
