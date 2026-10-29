#!/bin/bash
#
# test-inbound-multilevel-wildcard: MQTT multi-level wildcard '#' in a mapping topic
#
# Regression test for the resolver tree: a mapping on "dmtest/hash/#" must also
# match the PARENT topic "dmtest/hash" (MQTT spec: "a/b/#" matches "a/b"), not
# only deeper topics such as "dmtest/hash/x/y". Previously the zero-level case
# never resolved, so messages on the parent topic were silently dropped.
#
# The device identity comes from the payload ("deviceId"), because the parent
# topic has no further topic levels to take it from.
#
# This test:
#   1. Creates, deploys and activates a mapping on dmtest/hash/#
#   2. Publishes on dmtest/hash/x/y  -> device #1 + measurement (deeper topic)
#   3. Publishes on dmtest/hash      -> device #2 + measurement (parent topic)
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-multilevel-wildcard.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Multi-level wildcard '#' matches parent and deeper topics"

# ── Config ─────────────────────────────────────────────────────────────────────
STAMP="$(date +%s)"
EXT_DEEP="dmtest-hash-deep-${STAMP}"
EXT_PARENT="dmtest-hash-parent-${STAMP}"
MAPPING_ID=""

delete_device_by_ext_id() {   # <ext_id>
    local _id
    _id=$(dm_lookup_device_by_ext_id "$1" "c8y_Serial") || true
    if [ -n "${_id:-}" ]; then
        c8y identity delete --name "$1" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$_id" 2>/dev/null || true
    fi
}

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    delete_device_by_ext_id "$EXT_DEEP"
    delete_device_by_ext_id "$EXT_PARENT"
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
  "name": "test-hash-wildcard-$$",
  "identifier": "hw$$",
  "mappingTopic": "dmtest/hash/#",
  "mappingTopicSample": "dmtest/hash/sample",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"deviceId\":\"sample\",\"temperature\":22.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"deviceId","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
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

dm_step "Creating and activating mapping on dmtest/hash/# ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

dm_step "Publishing on a deeper topic (dmtest/hash/x/y) ..."
dm_mqtt_publish "dmtest/hash/x/y" "{\"deviceId\":\"${EXT_DEEP}\",\"temperature\":18.0}"
dm_assert_measurement_present "Measurement from dmtest/hash/x/y" "$EXT_DEEP" "c8y_Serial" 1 30

dm_step "Publishing on the PARENT topic (dmtest/hash) ..."
dm_mqtt_publish "dmtest/hash" "{\"deviceId\":\"${EXT_PARENT}\",\"temperature\":19.5}"
dm_assert_measurement_present "Measurement from parent topic dmtest/hash" "$EXT_PARENT" "c8y_Serial" 1 30

dm_done "$TEST_TITLE"
dm_print_summary
