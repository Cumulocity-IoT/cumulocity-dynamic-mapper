#!/bin/bash
#
# test-inbound-json-default: Inbound JSON → C8Y Measurement (DEFAULT transformation)
#
# Publishes a JSON MQTT message and verifies a C8Y measurement is created.
# The device identity is derived from the topic level wildcard.
#
# Prerequisites:
#   - Dynamic mapper service is running (DM_SERVICE env var or default)
#   - An active MQTT connector (MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASS)
#   - c8y CLI authenticated
#   - mosquitto_pub installed
#
# Usage:
#   ./test-inbound-json-default.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE=" 1. JSON / DEFAULT → MEASUREMENT"

# ── Config ─────────────────────────────────────────────────────────────────────
DEVICE_NAME="dmtest-json-default-$(date +%s)"
EXT_ID="$DEVICE_NAME"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
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
  "name": "test-inbound-json-default-$$",
  "identifier": "ibd$$",
  "mappingTopic": "dmtest/json/+",
  "mappingTopicSample": "dmtest/json/${EXT_ID}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"temperature\":25.0}",
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

dm_step "Creating and activating inbound mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Publishing MQTT message to dmtest/json/${EXT_ID} ..."
dm_mqtt_publish "dmtest/json/${EXT_ID}" '{"temperature":42.5}'

dm_step "Asserting at least 1 measurement was created ..."
dm_assert_measurement_present "Measurement created" "$EXT_ID" "c8y_Serial" 1 20

dm_done "$TEST_TITLE"
dm_print_summary
