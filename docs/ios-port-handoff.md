# Echo Air — iOS port handoff

Anchor: Android repo `suplyai/echo-air` at v0.6.1 (commit `c642866`).
Target: native iOS, Swift + SwiftUI, iOS 16+, single iPhone target,
identical Suply backend, identical UX, identical vocabulary discipline
across en / es / zh / ja.

This document exists so the new Claude Code session can start with one
artifact instead of grepping the Android repo for context. Read this
top-to-bottom before scaffolding the iOS project.

---

## 1. KKM iOS SDK scan

KKM ships an iOS port of `kbeaconlib2`. Same vendor, same API
philosophy, same wire protocol. The load-bearing BLE risk we worried
about on Android — reimplementing GATT auth + paged sensor reads
against CoreBluetooth — collapses to "vendor or pod-install their
SDK," same as on Android.

### Scan findings (May 2026)

| Aspect | Finding | Impact |
|---|---|---|
| Library repo | https://github.com/kkmhogen/kbeaconlib2 — Swift, 98.9% Swift code | Modern Swift, not bridged Objective-C |
| Demo project | https://github.com/kkmhogen/KBeaconProDemo_Ios — reference for usage patterns | Clone for spike harness |
| License | MIT (both repos) | Commercial use permitted, no concerns |
| iOS minimum | 13.0 | Generous — we can comfortably target iOS 16 |
| Distribution | CocoaPods only: `pod 'kbeaconlib2', '~> 1.2'` (currently 1.2.1). **No SPM `Package.swift`.** | See the deprecation flag below |
| Maintenance | 28 commits on main, MIT-licensed, demo last updated within the past year | Not prolific but not abandoned |
| API surface verified | `KBeaconsMgr`, `KBeacon`, `KBConnPara` (with `connectEnhanced`), `KBSensorType.HTHumidity`, `KBSensorReadOption` (`NewRecord` / `NormalOrder` exist), `KBRecordDataRsp.INVALID_DATA_RECORD_POS`, `KBRecordHumidity.{utcTime, temperature, humidity}`, `readSensorDataInfo`, `readSensorRecord` | All Android operations have iOS counterparts |
| Async style | Closure-based callbacks, same shape as Android (`(Bool, Response?, Exception?) -> Void`) | Wrap with `withCheckedContinuation` for `async/await` interop, same pattern as Kotlin's `suspendCancellableCoroutine` |

### Concerns to flag

1. **CocoaPods Trunk is going read-only** — the central registry that
   serves `pod install` is being deprecated (notice on the
   [pod listing](https://cocoapods.org/pods/kbeaconlib2): "moving to
   be read-only" with ~8 months remaining as of May 2026). Short-term
   `pod install` works fine. Long-term we may need to either:
   - Vendor `kbeaconlib2` source directly into the iOS project
     (mirror of how we vendored at `libs/kbeaconlib2/` on Android), or
   - Wrap the pod source in a local SPM package, or
   - Push KKM to ship a `Package.swift` upstream
   Recommend: start with `pod install` for the spike, plan to migrate
   to vendored source before the App Store submission. The vendoring
   is mechanical once the spike is working.

2. **No SPM support** — Apple's preferred dependency manager. Same
   workaround paths as above. Do not block on this; CocoaPods works
   today.

3. **Demo's example uses `KBSensorReadOption.NewRecord`** — that's the
   "advance the device's unread pointer" mode, NOT what we want. Echo
   Air uses `NormalOrder` (read everything from the beginning, leave
   the pointer alone). The Android comment in `BleConnectionManager`
   line 41–45 explains why — Echo Air devices are single-use, we
   always want full history. The iOS port must use `NormalOrder` for
   identical behaviour. Same constant name confirmed present in the
   Swift enum.

4. **Initial cursor for `NormalOrder`** — Android spike confirmed that
   `INVALID_DATA_RECORD_POS` does NOT work as the initial cursor,
   despite the library's constant naming suggesting it should. KKM's
   own demo uses `0L` as the initial position. The iOS port MUST do
   the same — start at `0`, not `INVALID_DATA_RECORD_POS`. See the
   long comment in `BleConnectionManager.attemptDownload` (Step 2)
   for the full firmware quirk note. Treat this as a known-working
   workaround, not a bug to fix.

5. **No iOS Honor/MagicOS equivalent** — the Android code has multiple
   defensive paths for Honor/Huawei/Xiaomi quirks (silently requiring
   `ACCESS_FINE_LOCATION`, eating `setApplicationLocales`, dropping
   `EFFECT_CLICK` haptics). iOS is one platform, one OEM. Most of
   that defensive code disappears.

6. **iOS BLE permission model is different** — there is no separate
   `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` split. One permission
   (`NSBluetoothAlwaysUsageDescription`) covers central role
   operations. The runtime gate is `CBManagerAuthorization` /
   `CBCentralManager.state`. Plan for one permission prompt, not two.

7. **iOS background BLE is its own subject** — CoreBluetooth has
   "background modes" capabilities (`bluetooth-central`) and state
   restoration. The Android app uses a `ForegroundService` to keep
   the BLE scan alive when backgrounded; iOS's equivalent is enabling
   the `bluetooth-central` background mode in the Xcode capabilities
   pane plus opting into state restoration. Behaviour is similar but
   the configuration is different. Background scans on iOS have
   tighter limits — out of scope for v1, but flag it.

---

## 2. Build sequence

Recommend seven sessions / phases, in this order. Each one can be a
focused commit. Estimating roughly 2–4 days each on a focused track,
3–6 weeks end to end, with iOS app review buffer on top.

1. **Repo scaffold + signing.** Empty `echo-air-ios` repo, Xcode
   project, bundle IDs (`app.suply.echoair` release / `.debug`
   debug-scheme suffix), git init, README, .gitignore. Apple Developer
   account opted in. Automatic signing for development; manual
   provisioning profiles for distribution come later.
2. **KKM SDK integration + spike.** `pod install` the library. Build a
   minimal harness (mirror of `app/src/debug/kotlin/.../SpikeActivity.kt`
   on Android — call `connectEnhanced`, `readSensorDataInfo`,
   `readSensorRecord` against a real S23H, log to console, confirm
   `NormalOrder` + `0` initial cursor + paged reads work). **Do this
   before scaffolding any UI.** If the SDK has an iOS-specific gotcha,
   we want to discover it now, not three weeks in.
3. **Static UI + DTOs + networking.** SwiftUI screens for Home,
   AwbEntry, Capture, ConfirmSheet, Collection (no BLE wiring yet,
   stub data). Networking layer: URLSession + JSONDecoder against the
   existing Suply API, DTO structs translated 1:1 from Kotlin.
   Networking config mirrors `NetworkModule.kt` (60s read/write
   timeout, no body logging in release).
4. **AWB validation + IATA lookup + locale system.** Translate
   `Awb.kt` (mod-7 check digit) and `IataCarriers` (244-prefix bundled
   asset) to Swift. Localizable.xcstrings catalog generated from the
   four `strings.xml` files — keys identical, values copied. Locale
   picker + first-launch detection + per-app locale via
   `Bundle.main.preferredLocalizations` overrides.
5. **BLE collection.** The orchestrator + connection manager,
   translated from Kotlin. Same state machine
   (`SEARCHING/IN_RANGE/SYNCING/COLLECTED/MISSING/ERROR`), same retry
   logic, same parallel-location-capture pattern, same
   `submitRecords` → `/api/echo-scan` flow.
6. **MPS support + finalize hooks + system gates.** Multi-unit
   shipment rendering on Confirm sheet + Collection screen + bottom
   bar (per v0.4.9 Android). Finalize + stale-session check (per
   v0.5.8). Bluetooth / Location-services reactive gates (per
   v0.5.9 — uses Combine/`@Published` instead of Kotlin `StateFlow`,
   same shape).
7. **App Store cleanup pass.** ProGuard equivalent on iOS is mostly
   unnecessary (no Java reflection issues), but: ensure `print`/`os_log`
   calls don't leak PII in release, set `OS_ACTIVITY_MODE=disable` for
   release schemes, App Transport Security configured (ATS allowed
   exemptions only for the API base URL, none for advertising
   networks because we don't have any). Privacy manifest
   (`PrivacyInfo.xcprivacy`) listing required reasons API access (none
   required as of iOS 17 unless we use UserDefaults/file timestamps in
   tracked ways — most of our usage is exempt).

---

## 3. Invariants the iOS port MUST preserve

These are the things where "subtly different on iOS" would create
data drift, support burden, or backend confusion. Translate exactly.

### 3.1 Backend DTO contract

Every wire-format struct lives in
`app/src/main/kotlin/app/suply/echoair/data/api/Dtos.kt`. Translate
1:1 to Swift `Codable` structs with `CodingKeys` mapping snake_case
JSON to Swift camelCase. In particular:

- `ShipmentDto` — root-level `commodity_name`, `air_origin_iata`,
  `air_origin_city`, `air_dest_iata`, `air_dest_city`,
  `transport_mode`, `cargo_profile` (nested), `devices[]` (flat
  list), `units[]` (MPS bucketed view of the same devices).
- `ShipmentDeviceDto` — `device_id`, `mac_address` (NOT `mac` — this
  rename was a backend flag we had to track), `serial`, `model`,
  `status`, `echo_scanned`, `scan_sequence`, `scanned_at` (ISO-8601
  string, parse with `ISO8601DateFormatter`), `inferred_position`,
  `unit_id`.
- `UnitDto` — `id`, `label`, `position`, `sequence_index`,
  `commodity_override`, `devices[]`.
- `EchoScanRequest` — `device_id`, `temperature_records[]`,
  `device_clock_offset_seconds`, optional `location` block.
- `LocationDto` — `latitude`, `longitude`, `accuracy_m`,
  `captured_at` (ISO-8601 UTC).
- `EchoScanResponse` — same fields as Android.
- `VisionRequest` — only `awb_number` (the `image_base64` field was
  removed in v0.6.1; do not add it back).

JSON config: `ignoreUnknownKeys = true` equivalent (Swift: don't
override the default; `JSONDecoder` ignores unknown keys by default).
Omit nulls on the wire (Swift: `JSONEncoder.OutputFormatting`
default + `Optional` fields encode as missing when nil — but verify;
some configs encode `null` explicitly).

### 3.2 AWB validation

`Awb.kt` is a 60-line file. Translate verbatim. The mod-7 check digit
is `firstSevenDigits % 7`, applied to the 8-digit serial only. The
3-digit airline prefix is independent of the check. Both must be
present and the check must pass for `isValid` to return true.
`expectedCheckDigit(serial)` returns the expected 8th digit while
the user is typing — used for the inline "expected %d" hint.

Test cases: `145-12863723` is valid (1286372 % 7 = 3, matches). The
Android repo has unit tests at... actually, the Android repo doesn't
have AWB unit tests. The new session should add a few on the iOS
side because Awb is the kind of pure logic that benefits from tests
and the cost is trivial.

### 3.3 IATA carrier prefix lookup

Bundled JSON asset, ~244 prefixes mapping 3-digit codes to airline
names (e.g. `057` → `Air France`). Used to surface the carrier name
when the user enters a valid 3-digit prefix on the AWB screen.
Source: `app/src/main/assets/iata_carriers.json` on Android. Copy
the file as-is to the iOS project's resource bundle. Loader code is
`IataCarriers.kt` — translates trivially (in-memory map, warmed at
app launch from `EchoAirApp.onCreate`).

### 3.4 Locale system

Four locales: en, es (LATAM, air-freight industry voice), zh
(Simplified Chinese), ja. **Customer-supplied unit labels render
verbatim — never translated.** Industry vocabulary discipline
preserved across all four (e.g. "guía aérea" for Spanish AWB,
"航空运单" for Chinese, "航空運送状" for Japanese).

String keys in `app/src/main/res/values/strings.xml` are the master
list. The other three `values-*/strings.xml` are full mirrors. A few
plurals use Android's plurals-resource format with `quantity="one"`
/ `quantity="other"` — translate to iOS Localizable.xcstrings
plurals (CLDR rules: en/es have `one` + `other`; zh and ja have only
`other`).

`AppLocale` enum in
`app/src/main/kotlin/app/suply/echoair/ui/locale/AppLocale.kt` has
`tag` (en/es/zh/ja) and `displayCode` (EN / ES / 中 / 日 — the chip
in the top-right). Same structure on iOS.

First-launch detection: detect device locale, map to one of the
four supported, show a one-time confirmation dialog ("We've set
your language to <native name>. Is this correct?") in the detected
language. iOS equivalent: read `Locale.preferredLanguages` (or
`Locale.current.identifier`), prefix-match to one of our four
supported tags, render the dialog, write to `UserDefaults`.

### 3.5 Multi-unit shipment (MPS) rendering

When `units.size > 1`:
- **Confirm sheet** shows an "MPS" badge, then the count line
  becomes "N units · M devices total" (string keys
  `confirm_mps_badge`, `confirm_mps_summary`), then a brief
  breakdown of unit labels separated by `·` (verbatim; fallback to
  localised "Unattributed" string when `label` is null/blank).
- **Collection screen** groups device rows under per-unit headers
  in `sequence_index` order. Each header shows the customer's
  label + per-unit progress count (`X of Y collected`).
- **Bottom bar** prefixes the in-flight status with the unit label
  when *every* still-in-flight device belongs to the same unit
  ("ULD 3 · 1 of 2 collected"). Mixed-unit residuals or finalised
  state drop the prefix.
- Devices with `unit_id` not matching any `unit.id` (server drift
  edge case) fall through to a localised "Unattributed" group at
  the end of the Collection screen, NOT silently dropped.

Single-unit (`units.size <= 1`) renders byte-identical to the
non-MPS case — flat list, no badge, no headers, no prefix. The
v0.4.9 commit message has the regression matrix.

### 3.6 Location capture privacy model

Two-gate consent. Both gates required:

1. **OS permission**: `NSLocationWhenInUseUsageDescription` granted.
   On iOS, this is requested via `CLLocationManager.requestWhenInUseAuthorization()`.
2. **Explicit opt-in** stored in `UserDefaults` (Android stores in
   `SharedPreferences`). Set true only when the user accepts the
   `LocationRationaleDialog` on first Collection-screen entry.
   Acknowledged but declined → false; never re-prompted.

Constraints (asserted in `LocationCapture.kt` comments, must
mirror in Swift):
- Captured ONLY at moment of successful GATT collection. Never
  continuously, never on app launch, never in background.
- Transmitted ONLY on `/api/echo-scan`. Not logged, not cached, not
  sent anywhere else.
- 8-second timeout. Any failure (declined opt-in, revoked
  permission, location services off, GPS off, no fix) returns nil
  silently. Scan proceeds without it. No retry, no error, no
  re-prompt.
- iOS equivalent of FusedLocation high-accuracy: `CLLocationManager`
  with `desiredAccuracy = kCLLocationAccuracyBest` and
  `requestLocation()` (one-shot). Uses cached fix when available,
  falls back to fresh acquisition under the 8s ceiling.

Rationale dialog copy is in strings.xml under
`location_rationale_*` keys (title, body, accept, decline).

### 3.7 Finalize + stale-session thresholds

From v0.5.8. Hardcoded constants in
`CollectionViewModel.kt`:

- `STALE_SESSION_MS = 1 hour` — orchestrator state untouched for
  this long → next reopen routes to home.
- `AUTO_FINALIZE_AFTER_MS = 5 minutes` — when all devices are in a
  final state but the user never tapped Finish, treat as
  implicitly finalised after this idle.

Behavioural matrix (preserve exactly):

| Action | Reopen lands on |
|---|---|
| Tap Finish | Home (always) |
| Confirm close-with-partial | Home (always) |
| Background mid-collection, return < 1 h | Collection (resume in flight) |
| All-collected, return < 5 min | Collection (Finish still tappable) |
| All-collected, return > 5 min | Home (auto-finalize) |
| Mid-collect, return > 1 h | Home (stale) |

State fields: `lastInteractionAt: Long` (bumped on every meaningful
mutation), `finalizedAt: Long?` (stamped on explicit finalize).
`shouldResumeOrFinish(shipmentId)` is the read-only gate — see
Kotlin source for exact logic.

### 3.8 Bluetooth + Location-services reactive gates

From v0.5.9. Reactive — not just one-shot at screen entry. When BT
or location services flip off mid-collection, an alert dialog
appears immediately with a one-tap recovery action.

iOS equivalent:
- BT state: `CBCentralManager.state` published via Combine; bind
  to a `@Published var bluetoothEnabled: Bool` in the ViewModel.
- Location services state: `CLLocationManager.locationServicesEnabled()`
  + `CLLocationManagerDelegate.locationManagerDidChangeAuthorization`.
- Recovery actions: `UIApplication.shared.open(URL(string:
  UIApplication.openSettingsURLString)!)` — there's no in-app BT
  enable equivalent of Android's `ACTION_REQUEST_ENABLE` on iOS,
  the user is sent to Settings. Different UX from Android but
  unavoidable.

The BT gate's strictness (block forward progress, show modal
dialog, cancel routes to home) is the operational fix for the field
report — preserve it.

### 3.9 Per-device proximity hints

From v0.5.5. Time-staged guidance text appearing inline on each
SEARCHING device row:

| Elapsed | Hint level | String key |
|---|---|---|
| 0s+ | L0 ambient (always visible while searching) | `collection_hint_ambient` |
| 5s | L1 escalation | `collection_hint_get_closer` |
| 20s | L2 escalation | `collection_hint_check_device` |
| 60s | L3 final escalation | `collection_hint_contact_shipper` |

L0 is muted grey, no animation. L ≥ 1 shifts to a warmer accent
colour and the lightbulb icon pulses. State survives rotation
because it's keyed on a per-device `searchStartedAt` timestamp set
when the device entered SEARCHING, not on a transient timer.

### 3.10 BLE + GATT operational constants

Hard-won via real-hardware spike. **Do not change without spiking
on real hardware first.**

| Constant | Value | Why |
|---|---|---|
| `MAX_CONCURRENT` | 4 | Android platform GATT cap is typically 4–7. iOS may have different limits — verify in spike. |
| `MAX_ATTEMPTS` | 3 | Per-device retry on failure |
| `CONNECT_TIMEOUT_MS` | 20_000 | 20s ceiling per attempt |
| `BATCH_SIZE` | 200 | Records per `readSensorRecord` call. Tuned 100–500 range. |
| Sensor type | `KBSensorType.HTHumidity` | S23/S23H both use this; humidity is 0/null on temp-only S23 variants but record class is the same |
| Read option | `KBSensorReadOption.NormalOrder` | Single-use devices, always read full history, never advance the unread pointer |
| Initial cursor | `0` (long zero), NOT `INVALID_DATA_RECORD_POS` | KKM demo confirms; firmware-level quirk |
| `syncUtcTime` | `false` | Critical: must NOT push phone UTC into device RTC; we want the drift preserved so the backend's `device_clock_offset_seconds` field is meaningful |
| `readCommPara` | `true` | Triggers MTU negotiation + common-cfg read at connect time |
| `readSensorPara` | `true` | Required for sensor-history reads to work |
| `readTriggerPara` / `readSlotPara` | `false` | Not needed for collection |

The KKM device password is a hardcoded constant
`KBeaconIds.DEFAULT_PASSWORD` — same value on iOS as on Android.
Read it from the Android source; do not paste it into this doc.

### 3.11 Local persistence + offline upload queue

Android uses Room. iOS equivalent: Core Data, SwiftData, or GRDB.

- **CachedShipment** (id, awbNumber, originIata, destIata,
  originCity, destCity, commodityName, commodityMinTemp,
  commodityMaxTemp, status, updatedAt)
- **CachedDevice** (deviceId PK, mac, shipmentId, status,
  lastSeenAt, unitId)
- **CachedUnit** (id PK, shipmentId, label, position,
  sequenceIndex, commodityOverride)
- **TemperatureRecord** (deviceId, timestamp, temperature, humidity,
  uploaded)
- **PendingUpload** (id auto, deviceId, payloadJson, attempts,
  lastAttemptAt, createdAt)

The offline upload pattern: records are persisted to local DB the
moment they leave the BLE manager — before the HTTP POST attempt.
Local persistence happens in `submitRecords` BEFORE the network
call. If the upload fails, the records are already safe and a
`PendingUpload` row is enqueued for retry by the equivalent of
Android's `UploadWorker` (iOS: `BGAppRefreshTask` / `URLSession`
background sessions).

This pattern is not optional — it's why the v0.5.6 backend incident
didn't lose data. The records had committed to local DB before the
30s gateway timeout fired.

---

## 4. Build configuration

| Setting | Value |
|---|---|
| Bundle ID (release) | `app.suply.echoair` |
| Bundle ID (debug) | `app.suply.echoair.debug` (matches Android's `applicationIdSuffix = ".debug"`) |
| Display name | "Echo Air" |
| Marketing version | Start at `0.6.1` to match Android, OR jump to `1.0.0` for the App Store debut. **Caller's choice.** Android v0.6.1 is the parity target. |
| Build number | `1` initially; monotonically increment on every TestFlight upload |
| iOS deployment target | 16.0 (matches modern SwiftUI features without dropping any phones the pilot would actually use) |
| Supported devices | iPhone only (no iPad) for parity with Android `uses-feature` constraints |
| Orientation | Portrait only |
| Background modes | `bluetooth-central` for BLE-while-backgrounded |

API base URL is in Android's `app/build.gradle.kts` as a build
config field (`API_BASE_URL`, defaults to `https://suply.app/`).
Mirror via Xcconfig file or a Swift build-config struct. Same value.

---

## 5. Privacy strings (`Info.plist`)

Apple's reviewers read these. Specific is better than generic.

| Key | Value (suggested) |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | "Echo Air uses Bluetooth to read temperature data from cargo sensors during pickup at destination." |
| `NSLocationWhenInUseUsageDescription` | "Echo Air can attach the scan location to the shipment audit trail when you collect a device. Location is captured only at the moment of a successful scan, never continuously." |
| `NSCameraUsageDescription` | "Echo Air uses the camera to scan QR codes on cargo sensors and air waybill labels." |

These mirror the rationale dialog copy in
`location_rationale_body` and the manifest comment block on
`ACCESS_FINE_LOCATION` in the Android repo. Keep them honest — the
review process flags vague "for app functionality" strings.

---

## 6. Privacy manifest (`PrivacyInfo.xcprivacy`)

iOS 17+ requires a privacy manifest declaring required-reasons API
access. Echo Air's reads:

- `UserDefaults`: required reason `CA92.1` ("Access info from same
  app, per documentation") — used for locale persistence + location
  opt-in flag.
- `FileTimestamp` / `DiskSpace` / `SystemBootTime`: not used.
- `ActiveKeyboards`: not used.
- Tracking domains: none.
- Data types collected: `Coarse Location` (linked to user, opt-in),
  `Other Device IDs` (BLE MAC of the cargo sensor — argue this is
  not user-linked because it identifies a sensor, not a person, but
  declare anyway).

---

## 7. App Store data-safety form

| Question | Answer |
|---|---|
| Does the app collect data? | Yes |
| Data types | Coarse Location (opt-in, only at scan moment); Device or Other IDs (BLE sensor MAC, not the user's phone IDs); Photos (camera capture, transient, not stored — used only for QR processing) |
| Linked to user? | Location: yes, in the sense that it ties to the consignee's account on the Suply backend. BLE MAC: no, that's a cargo sensor identifier. |
| Used for tracking? | No |
| Shared with third parties? | "Shared with parties on this shipment" — same vocabulary as the Home screen footer copy |
| Encryption in transit | Yes (HTTPS, no cleartext) |
| Data deletion mechanism | Described in privacy policy |

Privacy policy URL: `https://suply.io/privacy` (must be live before
first submission per Android setup notes).

---

## 8. Things the Android port got wrong initially — pre-empt on iOS

A short retrospective of the bugs we discovered late and would have
saved time on if we'd known up front.

1. **`?attr/colorOnSurface` on a `<vector>` tinted at draw time
   crashes the app when the parent theme is `Theme.Material.Light`,
   not Material3** (v0.5.0 → v0.5.1 hotfix on Android). iOS doesn't
   have this exact issue, but the equivalent gotcha: if you use
   `.tint(Color.primary)` and the asset is a multi-colour PNG, the
   tint applies as a flat overlay. Use SF Symbols or vector
   templates with explicit single-channel masks.

2. **Vendor SVGs pasted by hand drop characters**. Don't try to
   recreate raster artwork from path coordinates verbatim. Either
   use Apple's Vector Asset Studio equivalent (`Assets.xcassets`
   accepts SVG natively as of Xcode 12) or get the source files
   from the freelancer.

3. **Location instrumentation that measures wrap-of-async time
   instead of actual capture time** (v0.5.4 → v0.5.6 fix). When
   measuring an `async` operation, the timing must be captured
   INSIDE the async block, not from outside the launch to the
   await. Trivial mistake, hard to spot in the dialog readout.

4. **Backend 500 looks like client timeout** (v0.5.6). The
   diagnostic surface initially recorded only `outcome=queued`
   without the HTTP status code or response body. When the backend
   returned 500, the field saw a 30-second wait and assumed it was
   a network timeout. Always surface the actual exception class +
   HTTP code + body in failure paths. Echo Air's iOS equivalent:
   the `URLSessionDataTask` completion handler's `URLResponse` cast
   to `HTTPURLResponse`; status code; `Data` body if present.

5. **Foreground-service equivalent state retention bug** (v0.5.8).
   On iOS, the equivalent is BLE state restoration + the
   `bluetooth-central` background mode. When the user finishes a
   shipment but doesn't tap Finish, returning to the app later
   showed stale device cards on Android because the @Singleton
   orchestrator survived process backgrounding. iOS will have the
   equivalent issue if the orchestrator state isn't cleared on
   explicit finalize. Same fix shape applies — finalize hook +
   stale-session check on screen mount.

6. **Debug surfaces that "don't ship to release"** because the
   `if (BuildConfig.DEBUG)` block hides them, but the COMPILED CODE
   ships in the binary. We removed OCR + sync timing entirely in
   v0.6.1 because gating wasn't enough — the user wanted them gone,
   not hidden. iOS equivalent: use `#if DEBUG` for code that should
   compile out of release builds. Don't rely on conditional
   visibility alone.

---

## 9. Scope clarifications

These decisions were made on Android and should NOT be revisited on
iOS without a deliberate product conversation:

- **No login, no account.** Stateless app. Device + shipment are
  the credentials.
- **No analytics SDK** (no Firebase Analytics, no AppsFlyer, no
  Mixpanel, etc.). The Home footer says "Your data is only shared
  with parties on this shipment" — adding analytics breaks that
  promise.
- **No crash reporting** in v1. Could add `MetricKit` (Apple's
  built-in, no third-party SDK) post-launch if the operational need
  emerges.
- **Single iPhone target, no iPad**. The form factor doesn't fit
  the warehouse use case.
- **iOS 16+ deployment target.** Drops a slice of older devices but
  enables modern SwiftUI navigation + observation. Pilot devices
  are expected to be modern.
- **No watchOS, no widgets, no app intents** in v1.

---

## 10. Opening message for the new session

Paste this verbatim as the first message in the new Claude Code
session in the empty `echo-air-ios` repo:

> Porting Echo Air's Android app at v0.6.1 (commit c642866 in
> github.com/suplyai/echo-air) to native iOS. Same backend (Suply
> API, identical wire-format DTOs), same UX, same vocabulary.
> Swift + SwiftUI, iOS 16+, single iPhone target, no iPad. Bundle
> ID `app.suply.echoair` release, `app.suply.echoair.debug` debug.
> Locales en / es / zh / ja, industry-vocabulary discipline.
>
> The handoff document at docs/ios-port-handoff.md in the Android
> repo is the source of truth for invariants — DTO shapes, AWB
> validation, IATA lookup, locale system, MPS rendering rules,
> location capture privacy model, finalize/stale thresholds,
> BT/location reactive gates, BLE operational constants, build
> configuration, App Store data-safety answers. Read it first.
>
> KKM ships an iOS Swift SDK at github.com/kkmhogen/kbeaconlib2
> (CocoaPods: pod 'kbeaconlib2', '~> 1.2'). Same vendor as the
> Android library we vendored. License MIT, iOS 13+ floor, all
> the API operations we relied on (KBeaconsMgr, KBeacon,
> connectEnhanced, readSensorDataInfo, readSensorRecord,
> KBSensorType.HTHumidity, KBSensorReadOption.NormalOrder,
> KBRecordHumidity, KBRecordDataRsp.INVALID_DATA_RECORD_POS) are
> present. Demo project at github.com/kkmhogen/KBeaconProDemo_Ios
> for reference patterns.
>
> First task: scaffold the empty Xcode project (Swift + SwiftUI,
> iOS 16, single iPhone target, bundle IDs as above), git init,
> .gitignore. No UI yet, no SDK integration yet — just the empty
> shell with signing wired through automatic in development.
> Second task: pod install kbeaconlib2 + build a spike harness
> mirroring the Android src/debug/.../SpikeActivity that connects
> to a real S23H, reads sensor info + 200 records, prints to
> console. Confirm NormalOrder + initial cursor 0 work as on
> Android before scaffolding any further app structure.

---

## 11. Open decisions

For the new session to flag back rather than guess:

- **CocoaPods vs vendored source vs SPM wrapper for kbeaconlib2.**
  Recommend pod for spike, migrate to vendored source before App
  Store submission. Confirm before deciding.
- **Marketing version**: `0.6.1` (parity with Android) or `1.0.0`
  (App Store debut narrative). User's choice.
- **Persistence layer**: Core Data, SwiftData, or GRDB. SwiftData
  is the modern Apple-blessed choice but is iOS 17+; we're at iOS
  16 floor. Core Data works on iOS 16, has the broadest tooling
  support, but is verbose. GRDB is third-party but the cleanest
  API. Recommend GRDB unless there's a reason not to.
- **BLE state restoration**: opt in with `restoreIdentifier` set, or
  not? Affects whether mid-flight scans survive process kill. Not
  needed for v1; flag for v2.
- **TestFlight vs Internal Testing first**: TestFlight is App Store
  Connect's internal testing track; equivalent to Google Play
  Internal Testing. Do this first.
- **Apple Developer account ownership**: which legal entity? Same
  as the Play Store account or different? Affects how upload keys
  are managed.

---

## 12. References (Android repo)

When in doubt, these are the canonical Android sources to translate:

- `app/src/main/kotlin/app/suply/echoair/data/api/Dtos.kt` — every wire-format struct
- `app/src/main/kotlin/app/suply/echoair/domain/Awb.kt` — mod-7 validation
- `app/src/main/kotlin/app/suply/echoair/domain/IataCarriers.kt` — bundled-asset loader
- `app/src/main/assets/iata_carriers.json` — the 244-prefix list
- `app/src/main/res/values/strings.xml` — master string catalog (en)
- `app/src/main/res/values-{es,zh,ja}/strings.xml` — locale mirrors
- `app/src/main/kotlin/app/suply/echoair/ui/locale/` — locale system + first-launch dialog
- `app/src/main/kotlin/app/suply/echoair/ble/CollectionOrchestrator.kt` — state machine
- `app/src/main/kotlin/app/suply/echoair/ble/BleConnectionManager.kt` — GATT flow + the firmware-quirk comments
- `app/src/main/kotlin/app/suply/echoair/data/ShipmentRepository.kt` — networking + cache + offline queue
- `app/src/main/kotlin/app/suply/echoair/location/LocationCapture.kt` — privacy-by-design constraints
- `app/src/main/kotlin/app/suply/echoair/ui/collection/CollectionViewModel.kt` — finalize + stale-session logic
- `app/src/main/kotlin/app/suply/echoair/ui/collection/CollectionScreen.kt` — MPS rendering, hint levels, BT/location gates
- `app/src/main/AndroidManifest.xml` — permission rationale comments worth porting

The git log is also a useful resource — most non-trivial decisions
have a commit message explaining the why. `git log --oneline` from
the Android repo's main branch gives a chronological design history
in ~50 lines.
