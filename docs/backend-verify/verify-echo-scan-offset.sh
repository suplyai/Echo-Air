#!/usr/bin/env bash
# Confirm that POST /api/echo-scan accepts and persists
# `device_clock_offset_seconds`.
#
# The Android app now sends this field on every submission (capturing
# phone UTC minus device UTC at the start of readout). If the backend
# silently drops it, fusion against waybill + Hubble timestamps will be
# less accurate for devices with significant clock drift. If the backend
# rejects it outright with a 400, we need to ship the app with the field
# omitted until the API is extended.
#
# This script probes three things and prints the exact responses so the
# team can read off which case we're in.
#
# Usage:
#   export SUPLY_TOKEN="<jwt>"
#   export SUPLY_DEVICE_ID="<a device assigned to a staging shipment>"
#   ./docs/backend-verify/verify-echo-scan-offset.sh
#
# Optional overrides:
#   SUPLY_API_BASE (default https://api.suply.app)
#   SUPLY_OFFSET   (default 7 — simulates a device running 7s slow)

set -euo pipefail

: "${SUPLY_TOKEN:?export SUPLY_TOKEN before running}"
: "${SUPLY_DEVICE_ID:?export SUPLY_DEVICE_ID before running}"
BASE="${SUPLY_API_BASE:-https://api.suply.app}"
OFFSET="${SUPLY_OFFSET:-7}"

NOW="$(date +%s)"
DEVICE_TS="$((NOW - OFFSET))"   # simulate device clock 7s behind

payload_with_offset="$(cat <<JSON
{
  "device_id": "${SUPLY_DEVICE_ID}",
  "temperature_records": [
    { "temperature": 3.2, "humidity": 91.4, "timestamp": ${DEVICE_TS} }
  ],
  "device_clock_offset_seconds": ${OFFSET}
}
JSON
)"

payload_without_offset="$(cat <<JSON
{
  "device_id": "${SUPLY_DEVICE_ID}",
  "temperature_records": [
    { "temperature": 3.2, "humidity": 91.4, "timestamp": ${DEVICE_TS} }
  ]
}
JSON
)"

hr() { printf -- '----------------------------------------\n'; }

hr
echo "Probe 1: POST /api/echo-scan WITH device_clock_offset_seconds=${OFFSET}"
hr
curl -sS -w '\nHTTP %{http_code}\n' \
  -H "Authorization: Bearer ${SUPLY_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "${payload_with_offset}" \
  "${BASE}/api/echo-scan" || true

hr
echo "Probe 2: POST /api/echo-scan WITHOUT the field (control)"
hr
curl -sS -w '\nHTTP %{http_code}\n' \
  -H "Authorization: Bearer ${SUPLY_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "${payload_without_offset}" \
  "${BASE}/api/echo-scan" || true

hr
echo "Probe 3: If the backend exposes it, fetch the shipment to check whether"
echo "the offset was persisted. Update this probe once the readback field name"
echo "is agreed with the backend team."
hr
# Placeholder — uncomment and adjust once the field name on the GET side is known:
# curl -sS -H "Authorization: Bearer ${SUPLY_TOKEN}" \
#   "${BASE}/api/shipments/<shipment_id>" | jq '.devices[] | {device_id, clock_offset_seconds}'

hr
cat <<'NOTES'
Interpretation:
  - Probe 1 returns 2xx                 → field accepted (silently or explicitly — check
                                          Probe 3 to distinguish).
  - Probe 1 returns 400 "unknown field" → backend needs to add the field before we can
                                          ship the app; either drop the field from the
                                          Android DTO or gate it behind a flag until the
                                          backend catches up.
  - Probe 1 and Probe 2 responses identical shape → field is being silently dropped;
                                          fusion won't get drift correction. Ask the
                                          backend team to persist it.
  - Probe 3 shows the field in the readback → end-to-end working.
NOTES
