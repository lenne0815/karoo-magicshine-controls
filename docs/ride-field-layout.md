# Ride field layout (2026-11-13)

## Implemented and checked on Karoo

The field picker now lists Light Controls, Light On/Off, Flash, Light Battery
and Open App. The combined field retains its previous ID, order and styling.
Single tiles use the same cells and actions. Preview is static and noninteractive;
neither preview nor the standalone launcher sends field visibility events to BLE.
The service counts live lamp fields and delays last-field disconnect for 750 ms
so a short page handoff does not turn off the light.

Tested the debug APK in a temporary Karoo profile: added Flash next to a
standard speed field, then added the other three tiles, dragged them into a
2x2 grid, and added the full-width combined bar below. All five rendered.
The green HIGH battery in the editor is sample data, not a live measurement.
The temporary profile was removed; no ride was recorded.

## Layout limits

Karoo assigns the position and size of each field. Use the combined field for four controls in a single row, or use individual fields for arrangements such as two columns by two rows. The SDK's 60-unit grid describes the allocated space; it isn't a menu of 60 selectable columns.

Sources: [Profile editor](https://support.hammerhead.io/hc/en-us/articles/25601814671259-Karoo-OS-Customising-Profiles),
[profile limits](https://support.hammerhead.io/hc/en-us/articles/25529750399387-Karoo-OS-Creating-Profiles),
[ViewConfig](https://hammerheadnav.github.io/karoo-ext/karoo-ext/io.hammerhead.karooext.models/-view-config/index.html),
[multiple data type declarations](https://github.com/hammerheadnav/karoo-ext#hello-extension).
