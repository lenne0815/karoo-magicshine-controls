# Karoo Magicshine Controls

Karoo app and Karoo extension for controlling a Magicshine light over BLE.

![Karoo Magicshine Controls UI](docs/images/karoo-ui.png)

## Beta 1.0

This release is the first feature-complete public beta for the tested light family.

- stable BLE discovery and lamp selection gate
- reliable connect and disconnect flow
- `LOW`, `HIGH`, and `OFF` output controls
- fixed brightness presets: `25`, `50`, `75`, `100`
- repeating `SOS` and `FLASH`
- battery and temperature telemetry in the Karoo UI

## Supported Light

The current implementation was reverse engineered and tested against:

- `M2-B0 EVO_1700`

Discovery currently supports the known Magicshine naming pattern and the app lets you choose the exact lamp when multiple matching devices are nearby.

## Karoo Flow

1. Open the app on the Karoo.
2. Choose the lamp once in the selection gate.
3. Press `CONNECT`.
4. Wait for the short connect blink.
5. Choose `LOW`, `HIGH`, or `OFF`.
6. Use `25`, `50`, `75`, `100`, `SOS`, or `FLASH`.
7. Press `DISCONNECT` when finished.

## Protocol Notes

The app talks to the light over the proprietary Magicshine BLE protocol on:

- service `FFE1`
- characteristic `FFE0`

Reverse-engineering notes are kept in [PROTOCOL_NOTES.md](PROTOCOL_NOTES.md).

## Build

Requirements:

- JDK 17
- Android SDK
- Gradle wrapper

Build debug APK:

```bash
./gradlew assembleDebug
```

Install on a Karoo with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lenne0815.karoomagicshine/.MainActivity
```

## Project Details

- Application ID: `com.lenne0815.karoomagicshine`
- Compile SDK: `35`
- Target SDK: `34`
- Min SDK: `26`

## Current Caveats

- The current implementation is tuned to the tested Magicshine protocol variant, not the full Magicshine product line.
- The Karoo UI is the primary target; generic phone layouts are not a focus yet.

## License

MIT License
