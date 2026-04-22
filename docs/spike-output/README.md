# kbeaconlib2 spike output

This directory holds reference spike logs captured from real KKM S23H hardware.

The spike harness is in [`app/src/debug/kotlin/app/suply/echoair/spike/`](../../app/src/debug/kotlin/app/suply/echoair/spike/). Running it produces a line-per-step log that proves every kbeaconlib2 symbol the production `BleConnectionManager` depends on resolves and behaves as expected.

## Why we keep these

- **Regression anchor.** If a later kbeaconlib2 bump changes a field name or return type, a diff against a known-good log tells us immediately.
- **Backend parsing sanity check.** The log includes sample `utcTime / temperature / humidity` triples and a real `device_clock_offset_seconds`. Useful when the backend team needs example payloads to validate fusion logic.
- **Paging reality check.** It records whether `startPos = INVALID_DATA_RECORD_POS (-1)` returns records on the first call, or whether the library needs `startPos = 0` instead. Production code carries a defensive fallback but the observed behaviour should be documented here.

## Capturing a fresh log

1. Install the debug APK on a test phone with Bluetooth enabled.
2. Power up a provisioned S23H and let it advertise for a few seconds.
3. Open the app → Settings → **Run kbeaconlib2 spike (debug)**.
4. Enter the device MAC (e.g. `BC:57:29:1C:D6:A6`) and password (default `0000000000000000`).
5. Tap **Run spike**. Wait for `SPIKE PASSED`.
6. Save the on-screen log, or `adb logcat -s EchoAirSpike > docs/spike-output/<handset>-<fw>.log` while it runs.

## Naming convention

`<handset-model>-<fw-version>-<yyyy-mm-dd>.log`

Examples:
- `pixel8-s23h-fw1.2.3-2026-04-22.log`
- `samsungA54-s23h-fw1.2.3-2026-04-22.log`

Capture at least one Samsung and one Xiaomi/Huawei log — throughput and GATT reliability diverge between OEMs.

## What a clean run looks like

See [`expected-output.template.log`](./expected-output.template.log).
