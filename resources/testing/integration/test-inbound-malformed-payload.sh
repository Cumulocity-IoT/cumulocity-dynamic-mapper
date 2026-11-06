#!/bin/bash
#
# test-inbound-malformed-payload: A malformed payload must not break the mapping
#
# Negative / resilience scenario. Publishes, in order, on one JSON mapping:
#   1. a valid message      -> creates the device and 1 measurement
#   2. malformed JSON       -> must create nothing and must not stop the subscription
#   3. valid JSON, wrong shape (no "temperature" field) -> must not create a measurement
#   4. a valid message      -> must still be processed (no poison-message effect)
#
# Asserts the measurement count (1 after the bad messages, 2 at the end) and that
# the mapping still counts received messages. The "errors" counter is reported but
# only warned about, because whether a parse failure is counted there is an
# implementation detail.
#
# Usage:
#   ./test-inbound-malformed-payload.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Malformed payload does not break the mapping"

DEVICE_NAME="dmtest-malformed-$(date +%s)"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

MAPPING_JSON=$(cat <<EOM
{
  "name": "test-inbound-malformed-$$",
  "identifier": "ibm$$",
  "mappingTopic": "dmtest/malformed/+",
  "mappingTopicSample": "dmtest/malformed/${EXT_ID}",
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
EOM
)

dm_step "Creating and activating inbound mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

BASELINE=$(dm_now -10)
TOPIC="dmtest/malformed/${EXT_ID}"

dm_step "1/4 Publishing a valid message (creates the device) ..."
dm_mqtt_publish "$TOPIC" '{"temperature":21.5}'
dm_assert_measurement_present "Valid message processed" "$EXT_ID" "c8y_Serial" 1 20
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")

ERRORS_BEFORE=$(dm_mapping_stat "$MAPPING_ID" errors)
RECEIVED_BEFORE=$(dm_mapping_received_count "$MAPPING_ID")

dm_step "2/4 Publishing malformed JSON ..."
dm_mqtt_publish "$TOPIC" '{"temperature": 4'

dm_step "3/4 Publishing valid JSON without the mapped field ..."
dm_mqtt_publish "$TOPIC" '{"humidity":50}'

dm_wait 8 "letting the mapper process (and reject) the bad messages"

COUNT=$(dm_count_measurements_since "$DEVICE_ID" "$BASELINE")
dm_assert_eq "No measurement created by the bad messages" "1" "$COUNT"
dm_assert_mapping_received_gt "Mapping still receives messages after bad input" "$MAPPING_ID" "$RECEIVED_BEFORE" \
    || true

ERRORS_AFTER=$(dm_mapping_stat "$MAPPING_ID" errors)
if [ "${ERRORS_AFTER:-0}" -gt "${ERRORS_BEFORE:-0}" ] 2>/dev/null; then
    dm_info "errors counter rose ${ERRORS_BEFORE} -> ${ERRORS_AFTER}"
else
    dm_warn "errors counter did not rise (${ERRORS_BEFORE} -> ${ERRORS_AFTER}); bad input was dropped without being counted as an error"
fi

dm_step "4/4 Publishing a valid message after the bad ones ..."
dm_mqtt_publish "$TOPIC" '{"temperature":23.0}'
dm_wait_for_measurement_count "$EXT_ID" "c8y_Serial" 2 20 2 || true
COUNT=$(dm_count_measurements_since "$DEVICE_ID" "$BASELINE")
dm_assert_eq "Mapping processed the valid message after the bad ones" "2" "$COUNT"

dm_done "$TEST_TITLE"
dm_print_summary
