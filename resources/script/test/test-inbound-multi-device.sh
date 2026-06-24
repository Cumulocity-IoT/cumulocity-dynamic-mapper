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

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner " 8. Array payload → multiple devices"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

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
  "qos": "AT_LEAST_ONCE"
}
EOF
)

dm_step "Creating and activating multi-device mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

# Publish array payload with our two unique device ids
PAYLOAD=$(cat <<EOF
[{"deviceId":"${EXT_ID_1}","temperature":25.5},{"deviceId":"${EXT_ID_2}","temperature":26.0}]
EOF
)

dm_step "Publishing array payload ..."
dm_mqtt_publish "dmtest/multi" "$PAYLOAD"

dm_step "Asserting device 1 was auto-created and has a measurement ..."
if dm_wait_for_device_by_ext_id "$EXT_ID_1" "c8y_Serial" 20 2; then
  DEV1="$_DM_LAST_DEVICE_ID"
else
  DEV1=""
fi
if [ -z "$DEV1" ]; then
    dm_fail "Device '$EXT_ID_1' was not created"
    exit 1
fi
dm_assert_measurement_present "Measurement for device 1" "$EXT_ID_1" "c8y_Serial" 1 20

dm_step "Asserting device 2 was auto-created and has a measurement ..."
if dm_wait_for_device_by_ext_id "$EXT_ID_2" "c8y_Serial" 20 2; then
  DEV2="$_DM_LAST_DEVICE_ID"
else
  DEV2=""
fi
if [ -z "$DEV2" ]; then
    dm_fail "Device '$EXT_ID_2' was not created"
    exit 1
fi
dm_assert_measurement_present "Measurement for device 2" "$EXT_ID_2" "c8y_Serial" 1 20

dm_done " 8. Array payload → multiple devices"
dm_print_summary
