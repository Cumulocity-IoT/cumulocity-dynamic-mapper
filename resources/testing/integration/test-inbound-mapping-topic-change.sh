#!/bin/bash
#
# test-inbound-mapping-topic-change: changing a mapping's topic
#
# Regression test for the mapping cache: when a mapping is updated with a
# different mappingTopic, the resolver tree must move it to the new topic and
# the old topic must stop producing data (no stale node, no duplicate).
#
# The API refuses to update an ACTIVE mapping (PUT answers 406 "deactivate
# before updating"), so the topic is changed on the deactivated mapping and the
# mapping is then reactivated.
#
# This test:
#   1. Creates, deploys and activates a mapping on dmtest/tc/old
#   2. Publishes on dmtest/tc/old -> device + measurement
#   3. Deactivates, PUT /mapping/{id} with mappingTopic = dmtest/tc/new, reactivates
#   4. Publishes on dmtest/tc/new -> device + measurement
#   5. Publishes on dmtest/tc/old -> NO device is created any more
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-mapping-topic-change.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Mapping topic change moves the mapping to the new topic"

# ── Config ─────────────────────────────────────────────────────────────────────
STAMP="$(date +%s)"
EXT_OLD1="dmtest-tc-old1-${STAMP}"
EXT_NEW="dmtest-tc-new-${STAMP}"
EXT_OLD2="dmtest-tc-old2-${STAMP}"
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
    delete_device_by_ext_id "$EXT_OLD1"
    delete_device_by_ext_id "$EXT_NEW"
    delete_device_by_ext_id "$EXT_OLD2"
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
  "name": "test-topic-change-$$",
  "identifier": "tc$$",
  "mappingTopic": "dmtest/tc/old",
  "mappingTopicSample": "dmtest/tc/old",
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

dm_step "Creating and activating mapping on dmtest/tc/old ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

dm_step "Publishing on the old topic before the change ..."
dm_mqtt_publish "dmtest/tc/old" "{\"deviceId\":\"${EXT_OLD1}\",\"temperature\":18.0}"
dm_assert_measurement_present "Measurement from dmtest/tc/old before the change" "$EXT_OLD1" "c8y_Serial" 1 30

dm_step "Deactivating, changing the mapping topic to dmtest/tc/new (PUT /mapping/{id}), reactivating ..."
dm_deactivate_mapping "$MAPPING_ID"
UPDATED=$(dm_api GET "/mapping/${MAPPING_ID}" \
    | jq -c '.mappingTopic = "dmtest/tc/new" | .mappingTopicSample = "dmtest/tc/new"')
dm_api_must PUT "/mapping/${MAPPING_ID}" "$UPDATED" >/dev/null
dm_assert_eq "Mapping topic updated" "dmtest/tc/new" \
    "$(dm_api GET "/mapping/${MAPPING_ID}" | jq -r '.mappingTopic')"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"
dm_wait 3 "letting the subscription change settle"

dm_step "Publishing on the NEW topic ..."
dm_mqtt_publish "dmtest/tc/new" "{\"deviceId\":\"${EXT_NEW}\",\"temperature\":19.5}"
dm_assert_measurement_present "Measurement from dmtest/tc/new after the change" "$EXT_NEW" "c8y_Serial" 1 30

dm_step "Publishing on the OLD topic again: it must no longer be mapped ..."
dm_mqtt_publish "dmtest/tc/old" "{\"deviceId\":\"${EXT_OLD2}\",\"temperature\":20.0}"
dm_wait 10 "giving the mapper time to (wrongly) process the old topic"
LEFTOVER=$(dm_lookup_device_by_ext_id "$EXT_OLD2" "c8y_Serial") || true
dm_assert_eq "No device created from the old topic after the change" "" "${LEFTOVER:-}"

dm_done "$TEST_TITLE"
dm_print_summary
