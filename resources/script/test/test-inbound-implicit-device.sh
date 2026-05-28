#!/bin/bash
#
# test-inbound-implicit-device: Inbound mapping with createNonExistingDevice=true
#
# Verifies that when a message arrives for an unknown device external id,
# the Dynamic Mapper automatically creates the device in C8Y.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-implicit-device.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
# Use a unique external id that certainly does not exist yet
EXT_ID="dmtest-newdevice-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
}

[[ "${1:-}" == "--cleanup" ]] && trap cleanup EXIT

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Inbound Implicit Device Creation"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker

MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-implicit-device-$$",
  "identifier": "ibi$$",
  "mappingTopic": "dmtest/newdev/+",
  "mappingTopicSample": "dmtest/newdev/${EXT_ID}",
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
  "qos": "AT_LEAST_ONCE",
  "snoopStatus": "NONE",
  "snoopedTemplates": []
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
dm_activate_mapping "$MAPPING_ID"

dm_step "Publishing MQTT message for unknown device ..."
dm_mqtt_publish "dmtest/newdev/${EXT_ID}" '{"temperature":18.0}'

dm_step "Waiting for processing ..."
dm_wait 10

dm_step "Asserting device was auto-created ..."
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
if [ -z "$DEVICE_ID" ]; then
    dm_fail "Device '$EXT_ID' was NOT auto-created by the mapper"
  exit 1
fi
dm_success "Device auto-created: id=$DEVICE_ID"

dm_done "Inbound Implicit Device Creation"
dm_print_summary
