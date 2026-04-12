# Karoo Magicshine Controls

Karoo app and Karoo extension for controlling a Magicshine light over BLE.

![Karoo Magicshine Controls UI](docs/images/karoo-ui.png)

## Beta 1.1

This release expands the tested light family while keeping the same Karoo-first control flow.

- stable BLE discovery and lamp selection gate
- built-in support for additional known EVO name variants
- local approval flow for other nearby `M2-B0...` models
- reliable connect and disconnect flow
- `LOW`, `HIGH`, and `OFF` output controls
- fixed brightness presets: `25`, `50`, `75`, `100`
- repeating `SOS` and `FLASH`
- battery and temperature telemetry in the Karoo UI

## Supported Light

The current implementation is currently tested against:

- `M2-B0 EVO_1700`
- `M2-B0 EVO1300`
- `M2-B0 EVO_1300`

Discovery uses an allowlist for known supported names and the app lets you choose the exact lamp when multiple matching devices are nearby.
If another `M2-B0...` model shows up in the chooser, you can approve it locally on the Karoo without changing the app build.

## Karoo Flow

1. Open the app on the Karoo.
2. Choose the lamp once in the selection gate.
3. If your model is shown as another `M2-B0...` variant, approve it locally in the gate first.
4. Press `CONNECT`.
5. Wait for the short connect blink.
6. Choose `LOW`, `HIGH`, or `OFF`.
7. Use `25`, `50`, `75`, `100`, `SOS`, or `FLASH`.
8. Press `DISCONNECT` when finished.

## Current Caveats

- The current implementation is tuned to the tested light variant, not the full Magicshine product line.
- The Karoo UI is the primary target; generic phone layouts are not a focus yet.

## License

MIT License
