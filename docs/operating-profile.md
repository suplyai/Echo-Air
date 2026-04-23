# Echo Air — device configuration operating profile

This note is the single authoritative source for the state an S23H must be in
when it leaves origin, and for the contract between origin activation (web
platform) and destination collection (this Android app).

## The clock-drift discovery

Factory-default S23H devices ship with no UTC set on their internal RTC.
Real-hardware confirmation on serial 633640 (April 2026 firmware):
`phone_utc - device_utc = 1,769,303,558s` — i.e. the device's RTC reads near
Unix zero. This is the factory state and is the same for every new device
out of the box, not a one-off on this unit.

Every sensor record the device writes to flash is stamped with `utcTime` from
the device's own RTC. If the clock was never set, every `utcTime` is relative
to unix-zero, not real wall clock. Without correction a 5-minute log looks
like it happened in 1970 and the fusion layer against waybill milestones and
Hubble scans has nothing to correlate against.

kbeaconlib2 has the hook to fix this: `KBConnPara.syncUtcTime = true` on
`connectEnhanced` causes the library to push real UTC to the device during
the connection handshake. Subsequent
`KBRecordHumidity.parseSensorDataResponse` applies the stored `utcOffset`
when it encounters timestamps below year 2000
(`KBRecordBase.MIN_UTC_TIME_SECONDS = 946080000`).

## Origin activation (web-platform responsibility)

Origin activation is owned by the Suply web platform — likely the existing
web UI with a small BLE capability added via the existing native Android
shell, or an equivalent desktop tool. It is **not** the Android Echo Air
app's job.

Required steps when the operator activates a device against a shipment:

1. **Sync UTC.** BLE-connect to the device with
   `KBConnPara.syncUtcTime = true` via `connectEnhanced`. This writes real
   wall-clock UTC to the device's RTC.
2. **Configure the KSensor advertisement profile:**
   - Adv Type: KSensor
   - Adv Interval: 3000 ms
   - Connectable: Yes
   - Trigger-Only Adv: No
   - Sensor H&T: Yes
   - New Log Count: Yes
3. **Configure the log profile:**
   - 5-minute sample interval for temperature + humidity
   - Log enable: on
   - Log trigger: always (no conditional triggers)
4. **Clear pre-existing records** with `clearSensorRecord`. Safety step
   against reusing a device that wasn't wiped from a previous mission.
5. **Verify and record.** Read back `readSensorDataInfo` and persist an
   `activated_at_origin` timestamp on the shipment only if the two go/no-go
   checks below pass.

Only after step 5 may the device leave origin. The cost of letting a
bad-state device leave origin is high — we don't get a second chance to fix
it once it's in the air.

### Go/no-go verification

Before the UI allows the operator to mark activation complete, both checks
must pass:

- `abs(phone_utc - readInfoUtcSeconds) < 10` seconds — clock successfully synced
- `totalRecordNumber == 0` — device is clean

If either fails, surface an explicit error and block the shipment from
being marked activated. Retry from step 1.

## Destination collection (this Android app)

At destination the Echo Air app must **not** re-sync the clock. It reads
records as-timestamped-by-the-device, captures the measured
`device_clock_offset_seconds` on every `/api/echo-scan` call (already
implemented — see `BleConnectionManager.attemptDownload`), and lets the
backend correlate against independent evidence streams.

This preserves the independence of the device's internal timeline as
evidence, which matters for claims and audit — the device's record stream
and the external evidence streams (waybill, Hubble) must remain separately
auditable.

In kbeaconlib2 terms:

| Flow         | connectEnhanced                 |
|--------------|---------------------------------|
| Origin       | `syncUtcTime = true`            |
| Destination  | `syncUtcTime = false` (enforced in `BleConnectionManager.connect`) |

If a device reaches destination with `readInfoUtcSeconds` near 1970, origin
activation was skipped or failed. The app will still download the records
successfully, but the backend should surface this as a prominent warning
on the attestation pack rather than letting it through as a silent
data-quality issue.

## Factory-default checklist

Anything shipped by KKM without provisioning will have:

- Password `0000000000000000`
- RTC reading near 1970 (unset)
- Empty log (no records)
- Advertising as `KBPRO_<serial>` on service UUID 0xAAFE (Eddystone)

Origin activation is what turns this into a configured Echo Air. A device
arriving at destination without real UTC set is a product failure at
origin, not an app bug at destination.
