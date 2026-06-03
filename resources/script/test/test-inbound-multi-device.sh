#!/bin/bash
#
# test-inbound-multi-device: Inbound array payload with expandArray → multiple C8Y objects
#
# Publishes a JSON array payload. The mapper expands the array and creates
# one C8Y measurement per array element, each for a different device.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-multi-device.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
TS=$(date +%s)
EXT_ID_1="dmtest-multi-01-${TS}"
EXT_ID_2="dmtest-multi-02-${TS}"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    for EID in "$EXT_ID_1" "$EXT_ID_2"; do
        DID=$(dm_lookup_device_by_ext_id "$EID" "c8y_Serial") || true
        if [ -n "${DID:-}" ]; then
            c8y identity delete --name "$EID" --type "c8y_Serial" 2>/dev/null || true
            c8y inventory delete --id "$DID" 2>/dev/null || true
        fi
    done
}

[[ "${1:-}" == "--cleanup" ]] && trap cleanup EXIT

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Inbound Array Payload → Multiple Devices (expandArray)"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker

MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-multi-$$",
  "identifier": "ibm$$",
  "mappingTopic": "dmtest/multi",
  "mappingTopicSample": "dmtest/multi",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
    "sourceTemplate": "{\"deviceId\":\"dev01\",\"temperature\":20}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"\$[].deviceId","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":true},
    {"pathSource":"\$[].temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":true},
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

dm_step "Creating and activating multi-device mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Recording test start time ..."
TEST_START=$(dm_now -10)

# Publish array payload with our two unique device ids
PAYLOAD=$(cat <<EOF
[{"deviceId":"${EXT_ID_1}","temperature":25.5},{"deviceId":"${EXT_ID_2}","temperature":26.0}]
EOF
)

dm_step "Publishing array payload ..."
dm_mqtt_publish "dmtest/multi" "$PAYLOAD"

dm_step "Waiting for processing ..."
dm_wait 10

dm_step "Asserting device 1 was auto-created and has a measurement ..."
DEV1=$(dm_lookup_device_by_ext_id "$EXT_ID_1" "c8y_Serial")
if [ -z "$DEV1" ]; then
    dm_fail "Device '$EXT_ID_1' was not created"
    exit 1
fi
dm_assert_measurement_count_gt "Measurement for device 1" "$DEV1" "$TEST_START" 1

dm_step "Asserting device 2 was auto-created and has a measurement ..."
DEV2=$(dm_lookup_device_by_ext_id "$EXT_ID_2" "c8y_Serial")
if [ -z "$DEV2" ]; then
    dm_fail "Device '$EXT_ID_2' was not created"
    exit 1
fi
dm_assert_measurement_count_gt "Measurement for device 2" "$DEV2" "$TEST_START" 1

dm_done "Inbound Array Payload → Multiple Devices (expandArray)"
dm_print_summary
