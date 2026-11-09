#!/bin/bash
#
# test-inbound-source-c8y-id: Inbound mapping with useExternalId=false
#
# The device is addressed by its internal Cumulocity id (_IDENTITY_.c8ySourceId) taken
# from the topic, not by an external id. The id is passed straight through, so:
#   - the device must already exist
#   - NO external id is looked up and NO implicit device is created
# Asserts a measurement lands on the pre-created device, and that a message for an id
# that does not exist creates no device and no identity.
#
# Usage:
#   ./test-inbound-source-c8y-id.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Inbound useExternalId=false → c8ySourceId (internal id)"

DEVICE_NAME="dmtest-c8yid-$(date +%s)"
DEVICE_ID=""
MAPPING_ID=""
GHOST_ID="999999999$RANDOM"

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

dm_step "Creating device WITHOUT any external id ..."
dm_create_device "$DEVICE_NAME" "dmtest_c8yid_type"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

MAPPING_JSON=$(cat <<EOM
{
  "name": "test-inbound-c8yid-$$",
  "identifier": "ibc$$",
  "mappingTopic": "dmtest/c8yid/+",
  "mappingTopicSample": "dmtest/c8yid/${DEVICE_ID}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"temperature\":25.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.c8ySourceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": false,
  "updateExistingDevice": false,
  "useExternalId": false,
  "genericDeviceIdentifier": "_IDENTITY_.c8ySourceId",
  "qos": "AT_LEAST_ONCE"
}
EOM
)

dm_step "Creating and activating mapping (useExternalId=false) ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

BASELINE=$(dm_now -10)

dm_step "Publishing for the internal device id ${DEVICE_ID} ..."
dm_mqtt_publish "dmtest/c8yid/${DEVICE_ID}" '{"temperature":31.5}'

dm_step "Asserting the measurement landed on the device ..."
FOUND=0
for _ in $(seq 1 10); do
    FOUND=$(dm_count_measurements_since "$DEVICE_ID" "$BASELINE")
    [ "${FOUND:-0}" -ge 1 ] && break
    sleep 2
done
dm_assert_gt "Measurement created via internal id" "${FOUND:-0}" 0

dm_step "Message for a non-existent internal id must not create anything ..."
ERRORS_BEFORE=$(dm_mapping_stat "$MAPPING_ID" errors)
dm_mqtt_publish "dmtest/c8yid/${GHOST_ID}" '{"temperature":1.0}'
dm_wait 6 "letting the mapper reject the unknown id"
ERRORS_AFTER=$(dm_mapping_stat "$MAPPING_ID" errors)
dm_assert_gt "Unknown id is counted as an error" "${ERRORS_AFTER:-0}" "${ERRORS_BEFORE:-0}"
GHOST_MO=$(c8y inventory get --id "$GHOST_ID" --output json 2>/dev/null | jq -r '.id // empty' || true)
dm_assert_eq "No device created for unknown id" "" "${GHOST_MO:-}"
dm_assert_eq "Device still has no external id (no identity was created)" "0" \
    "$(c8y identity list --device "$DEVICE_ID" --output json 2>/dev/null | jq -s 'flatten | length' 2>/dev/null || echo 0)"

dm_done "$TEST_TITLE"
dm_print_summary
