# SRAM ANT Sniffer Notes

Last updated: 2026-06-22

## Core Findings

- No useful public BLE opcodes for SRAM AXS `shift up` / `shift down` were found.
- SRAM AXS shifting uses SRAM's proprietary encrypted wireless stack for drivetrain control. Do not assume that shift button intent is visible as simple BLE command bytes.
- The promising head-unit path is ANT+ controller/function events, not BLE shift commands.
- Hammerhead and Wahoo expose AXS controller behavior to head units as user-configurable ANT+ function sets.
- Working mapping to verify with the sniffer:
  - AXS left/easier/shift-down style action -> ANT+ Function Set 1
  - AXS right/harder/shift-up style action -> ANT+ Function Set 2
  - both buttons together -> ANT+ Function Set 3
- Exact SRAM byte-level Function Set payload values were not found in public docs. They need to be captured on-device.

## Ki2 Findings

- Ki2 is useful as an ANT/Karoo implementation reference, but its button parsing is Shimano-specific.
- Ki2 requests ANT through Karoo using `RequestAnt(extension)`.
- Ki2 uses Dynastream `android_antlib_4-16-0.aar`.
- Ki2 opens ANT channels through `com.dsi.ant.channel.AntChannelProvider`.
- Ki2's Shimano switch parser is not directly reusable for SRAM AXS.
- Shimano-specific details found in Ki2:
  - Shimano switch status is page `4`.
  - Ki2 maps page `4` payload bytes as `CH2=payload[1]`, `CH1=payload[2]`, `CH4=payload[3]`, `CH3=payload[4]`.
  - Shimano command high nibble is `value & 0xF0`.
  - Shimano sequence low nibble is `value & 0x0F`.
  - Shimano `SINGLE_CLICK` is `0x10`.
  - Shimano `LONG_PRESS_DOWN` is `0x20`.
  - Shimano `LONG_PRESS_CONTINUE` is `0x30`.
  - Shimano `LONG_PRESS_UP` is `0x00`.
  - Shimano `DOUBLE_CLICK` is `0x40`.
  - Shimano `NO_SWITCH` is `0xF0`.

## Separate Sniffer App

- Sniffer source lives in `sram-sniffer/`.
- Sniffer package is `com.lenne0815.karoosramsniffer`.
- Sniffer Karoo extension id is `karoo-sram-sniffer`.
- The sniffer is intentionally separate from the Magicshine control app so diagnostic ANT work cannot destabilize light control.
- The module depends on:
  - `io.hammerhead:karoo-ext:1.1.8`
  - `sram-sniffer/libs/android_antlib_4-16-0.aar`
- The app requests Karoo ANT access from both:
  - `MainActivity`
  - `SramSnifferKarooExtension`
- `RawAntSniffer` opens an ANT+ background scan channel with:
  - network `PredefinedNetwork.ANT_PLUS`
  - RF frequency `57`
  - channel type `SLAVE_RECEIVE_ONLY`
  - scan channel id `0/0/0`
  - background scanning enabled
  - extended data enabled through `LibConfig(true, true, false)`
- The sniffer logs:
  - broadcast frames
  - acknowledged frames
  - channel events
  - channel responses
  - channel status messages
  - ANT device number/type/transmission type when extended data is present
  - payload hex
  - RSSI when present
  - changed byte positions compared with the previous frame from the same device key
- The UI has marker buttons:
  - `MARK SHIFT UP`
  - `MARK SHIFT DOWN`
  - `MARK BOTH`
- Marker rows are important because the raw ANT stream may include unrelated nearby ANT+ devices.

## Build State

- Local Mac currently cannot build Android because no Java runtime is installed.
- Use the Ryzen build host for Android builds:
  - host `192.168.178.93`
  - user `lenne`
  - repo `/home/lenne/karoo-magicshine-controls`
- Be gentle with SSH. Prefer one bundled sync/build/copy flow over many small probes.
- Verified build commands on the Ryzen host:
  - `./gradlew :sram-sniffer:assembleDebug`
  - `./gradlew assembleDebug`
- Both builds succeeded after the sniffer module was added.
- Current local debug APK:
  - `.artifacts/karoo-sram-sniffer-debug.apk`
  - SHA-256 `28b67426a36c19cd8e4701f717939691b7bb225d0a8b4f29142276955d253504`
- `.artifacts/` is ignored and is for local build/test outputs only.

## On-Device Test Plan

1. Install the APK once the Karoo is connected:

```bash
adb install -r /Users/lennartthomsen/Documents/karoo-magicshine-controls/.artifacts/karoo-sram-sniffer-debug.apk
```

2. Launch the sniffer:

```bash
adb shell monkey -p com.lenne0815.karoosramsniffer -c android.intent.category.LAUNCHER 1
```

3. Watch live logs:

```bash
adb logcat -s SramAntSniffer SramSnifferExt
```

4. For each SRAM action:

- tap `MARK SHIFT UP`, then press the SRAM shift-up/right/harder action
- tap `MARK SHIFT DOWN`, then press the SRAM shift-down/left/easier action
- tap `MARK BOTH`, then press both buttons together

5. Pull the recorded file from the debug app sandbox if `run-as` is available:

```bash
adb shell run-as com.lenne0815.karoosramsniffer ls files/ant-sniffer
adb exec-out run-as com.lenne0815.karoosramsniffer cat files/ant-sniffer/REPLACE_WITH_FILE_NAME.txt > /Users/lennartthomsen/Documents/karoo-magicshine-controls/.artifacts/sram-ant-sniffer-capture.txt
```

6. Compare frames around the marker rows and look for stable payload deltas by device id/type.

## Risks And Next Adjustments

- ANT+ background scan will capture unrelated devices too. If the log is noisy, first identify the SRAM device key, then add a temporary filter.
- If no SRAM frames appear, make sure the SRAM controller is awake and configured for head-unit control/function sets in the SRAM AXS app.
- If the Karoo native SRAM integration consumes or transforms the event before the scan sees it, inspect Karoo logs for exposed controller events as a fallback.
- Once raw Function Set bytes are known, wire only the identified events into Magicshine control logic. Do not copy Shimano Ki2 switch codes into the SRAM path.
