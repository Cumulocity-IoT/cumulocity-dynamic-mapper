#!/bin/bash
#
# test-inbound-implicit-device-recreate-after-delete: Implicit device
# recreation after the original implicit device is deleted from inventory
#
# Regression test for the "inconsistent cache" bug (see
# attic/fix/inconsistant-cache/ISSUE.md): once a device was implicitly
# created for an external id (createNonExistingDevice=true), and that
# device was later deleted from inventory, the mapper kept resolving the
# external id to the stale, now-deleted internal id forever — instead of
# noticing it no longer exists and recreating it. This produced a permanent
# per-message 404 loop (failed inventory-cache subscription + notification
# API error) with no recovery.
#
# This test:
#   1. Publishes a message for a brand-new external id -> device #1 is
#      auto-created.
#   2. Deletes device #1 from inventory (its external-id mapping is
#      cascade-deleted with it).
#   3. Publishes a second message for the *same* external id.
#   4. Asserts a *new* device is auto-created for that external id, with a
#      *different* internal id than device #1.
#
# Without the fix, step 4 fails: the mapper keeps returning device #1's
# stale internal id from its in-memory cache without ever re-checking C8Y,
# so no new identity mapping / device is ever created and the lookup in
# step 4 times out.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-implicit-device-recreate-after-delete.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE=" 8. Implicit device recreation after delete (inconsistent-cache regression)"

# ── Config ─────────────────────────────────────────────────────────────────────
# Use a unique external id that certainly does not exist yet
EXT_ID="dmtest-recreate-$(date +%s)"
MAPPING_ID=""
DEVICE_ID_1=""
DEVICE_ID_2=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    # Device #1 was already deleted by the test itself, but be defensive.
    [ -n "$DEVICE_ID_1" ] && dm_delete_device "$DEVICE_ID_1" || true
    [ -n "$DEVICE_ID_2" ] && dm_delete_device "$DEVICE_ID_2" || true
    # In case device creation ever lands on a second call with a *reused* id,
    # or the identity mapping outlives a failed device delete.
    LEFTOVER_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${LEFTOVER_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$LEFTOVER_ID" 2>/dev/null || true
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

MAPPING_JSON=$(cat <<EOF
{
  "name": "test-implicit-device-recreate-$$",
  "identifier": "idr$$",
  "mappingTopic": "dmtest/recreate/+",
  "mappingTopicSample": "dmtest/recreate/${EXT_ID}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"temperature\":22.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOF
)

dm_step "Verifying device does not exist yet ..."
PRE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
if [ -n "${PRE_ID:-}" ]; then
    dm_warn "Device already existed (id=$PRE_ID) — test may be reusing an id"
fi

dm_step "Creating and activating mapping with createNonExistingDevice=true ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Publishing first MQTT message for unknown device ..."
dm_mqtt_publish "dmtest/recreate/${EXT_ID}" '{"temperature":18.0}'

dm_step "Asserting device #1 was auto-created ..."
if dm_wait_for_device_by_ext_id "$EXT_ID" "c8y_Serial" 30 2; then
    DEVICE_ID_1="$_DM_LAST_DEVICE_ID"
else
    DEVICE_ID_1=""
fi
dm_assert_gt "Device #1 auto-created (id=${DEVICE_ID_1:-none})" "${#DEVICE_ID_1}" 0

dm_step "Deleting device #1 (id=$DEVICE_ID_1) to simulate an external deletion ..."
dm_delete_device "$DEVICE_ID_1"
# Give C8Y's identity cascade-delete a moment to settle before we rely on it.
dm_wait 3 "letting identity cascade-delete settle"
STILL_THERE=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
dm_assert_eq "External id no longer resolves after device #1 delete" "" "${STILL_THERE:-}"

dm_step "Publishing a second MQTT message for the SAME external id ..."
dm_mqtt_publish "dmtest/recreate/${EXT_ID}" '{"temperature":19.5}'

dm_step "Asserting a NEW device was auto-created for the same external id ..."
# Without the fix, the mapper keeps resolving this external id to device #1's
# stale, deleted internal id (or its own resolution failing entirely) instead
# of ever re-issuing device creation — so this lookup times out and
# DEVICE_ID_2 stays empty.
if dm_wait_for_device_by_ext_id "$EXT_ID" "c8y_Serial" 30 2; then
    DEVICE_ID_2="$_DM_LAST_DEVICE_ID"
else
    DEVICE_ID_2=""
fi
dm_assert_gt "Device #2 auto-created (id=${DEVICE_ID_2:-none})" "${#DEVICE_ID_2}" 0
dm_assert_ne "Device #2 has a different id than the deleted device #1" "$DEVICE_ID_1" "${DEVICE_ID_2:-}"

dm_step "Asserting the measurement from the second message landed on device #2 ..."
dm_assert_measurement_present "Measurement recorded on recreated device" "$EXT_ID" "c8y_Serial" 1 20

dm_done "$TEST_TITLE"
dm_print_summary
