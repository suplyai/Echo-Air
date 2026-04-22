# Echo Air — device configuration operating profile

The Suply backend and the Android app together expect an S23H to be in a
specific configured state when it first advertises from origin. This note
captures what has to happen at origin activation, based on findings from
first-run spike testing.

## The clock-drift discovery

Real-hardware spike against a factory-default S23H (serial 633640, April 2026
firmware) showed `readSensorDataInfo` returning a `readInfoUtcSeconds` value
corresponding to `~1970` — i.e. the device's internal RTC was never set. The
phone's UTC minus the device's UTC was ~56 years. If a device is shipped from
origin in that state, every record timestamp in the log is relative to the
device-zero epoch and cannot be reconciled against waybill milestones or
Hubble scans.

kbeaconlib2 has a mechanism for this: `KBConnPara.syncUtcTime = true` on
`connectEnhanced` causes the library to push real UTC to the device during
connection. Subsequent `KBRecordHumidity.parseSensorDataResponse` applies a
`utcOffset` when it encounters timestamps below year 2000
(`KBRecordBase.MIN_UTC_TIME_SECONDS = 946080000`, i.e. 2000-01-01 UTC).

## Required origin-activation steps

When a consignor activates an Echo Air at origin (web-platform responsibility,
not the Android app), the activation flow must:

1. Connect to the device via BLE with `KBConnPara.syncUtcTime = true`. This
   writes real wall-clock UTC to the device's RTC.
2. Configure the KSensor advertisement profile:
   - Adv Type: KSensor
   - Adv Interval: 3000 ms
   - Connectable: Yes
   - Trigger-Only Adv: No
   - Sensor H&T: Yes
   - New Log Count: Yes
3. Configure the log profile:
   - 5-minute sample interval for temperature + humidity
   - Log enable: on
   - Log trigger: always (no conditional triggers)
4. Clear any pre-existing log records with `clearSensorRecord`.
5. Verify by reading back `readSensorDataInfo` and checking that
   `readInfoUtcSeconds` is within a few seconds of real UTC, and that
   `totalRecordNumber == 0`.

Step 5 is the single go/no-go check. If `readInfoUtcSeconds` is near zero,
the activation was not successful and the device must be reconnected with
`syncUtcTime = true` before it leaves origin.

## Destination collection (the Android app's job)

For destination readout — which is what this app does — we **do not** want
`syncUtcTime = true`. We want to preserve the drifted device clock so we can
capture `device_clock_offset_seconds` as metadata for the backend's fusion
layer. `BleConnectionManager.connect` sets `syncUtcTime = false` for exactly
this reason.

If the origin-activation step was skipped and a device arrives at destination
with `readInfoUtcSeconds` near 1970, the app will still download the records
successfully, but the backend fusion against waybill + Hubble scans will have
nothing reliable to correlate against. The backend should surface this as a
warning on the attestation pack rather than a silent data-quality issue.

## Factory-default checklist

Anything shipped by KKM without provisioning will:

- Have default password `0000000000000000`
- Have no real UTC set (RTC reads near 1970)
- Have an empty log
- Advertise as `KBPRO_<serial>` with service UUID 0xAAFE (Eddystone)

The origin-activation flow is what turns this into a configured Echo Air.
Anything that comes in without real UTC set is a product bug, not an app bug.
