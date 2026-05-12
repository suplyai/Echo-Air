# Echo Air — session notes

Running log of cross-cutting decisions and sync events that don't fit
cleanly into a single commit message. Newest entries on top.

---

## 2026-05-12 — iOS Phase 3 requirements synced from Android

Ocean-freight support, the three-card home screen, ISO 6346 container
validation, and the new two-paragraph home tagline all shipped on
Android in v0.7.0. Those changes have implications for iOS Phase 3
(Static UI + DTOs + networking) — if we let the iOS engineer build to
the v0.6.1 handoff-doc state and retrofit ocean later, we'd redo a
lot of UI.

Captured the requirements in `docs/ios-port-handoff.md` under a new
top-level section **"Phase 3 requirements — synced from Android
(2026-05)"**, positioned between §2 (build sequence) and §3
(invariants) so it's read in natural sequence before Phase 3 starts.

The new section covers:

- P3.1 Transport modes — accept both `air_freight` and `ocean_reefer`
  from day one; treat unrecognised modes as ocean defensively;
  nullable air-only DTO fields; new `container_number`, `pol`, `pod`,
  `eta` fields on `ShipmentDto`.
- P3.2 Three entry paths on home screen (Plane / Ship / QR), with
  icons matching the Confirm Sheet's route display.
- P3.3 ISO 6346 client-side validation — two-stage, with the full
  letter-value table and the spec example `EITU3171741` worked
  through. Recommends a Swift Package over rolling from scratch.
- P3.4 Confirm Sheet conditional rendering table.
- P3.5 New two-paragraph home tagline copy.
- P3.6 Localisation table — 18 new/updated keys with Android values
  as the canonical source for translation pull.
- P3.7 Cache schema additions (`containerNumber`, `transportMode`,
  nullable `awbNumber`).
- P3.8 QR scan path needs no behavioural change — ocean handling is
  data-driven via `shipment.isOcean`.

Android anchor moved from v0.6.1 (commit `c642866`) to **v0.7.0** on
branch `claude/echo-air-android-build-Y372i`. The handoff doc's §4
build-configuration target and §10 opening-message version should be
updated to v0.7.0 when iOS work resumes; flagged in the new Phase 3
section.
