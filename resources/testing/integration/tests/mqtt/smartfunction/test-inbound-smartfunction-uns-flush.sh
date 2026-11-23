#!/bin/bash
#
# test-inbound-smartfunction-uns-flush: N:1 mapping without a fixed count, a newer timestamp flushes the previous one
#
# Sample under test: resources/samples/uns/uns-flush-on-next-timestamp.js (context.openGroups + context.take).
# A Unified Namespace publisher sends one MQTT message per value: topic unsflush/plant1/<device>/<name>,
# payload {"time": ..., "value": ...}. The number of values differs per timestamp.
#
#   1. 7 values of T1                    → no measurement yet (the group may still grow)
#   2. 1 value of T2                     → ONE measurement for T1 with 7 fragments
#   3. 3 more values of T2, 1 of T3      → ONE measurement for T2 with 4 fragments (a different count)
#   4. Wait                              → still 2 measurements: T3 is buffered until the next timestamp
#
# The values of one timestamp are published together and processed concurrently. The groups are published one
# after the other with a pause, because the sample assumes that a timestamp is complete before the next starts.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated,
#                mosquitto_pub and jq installed
# Usage: ./test-inbound-smartfunction-uns-flush.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEGRATION_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../test-harness.sh
source "${INTEGRATION_DIR}/test-harness.sh"

TEST_TITLE="Inbound: UNS N:1 — a newer timestamp flushes the previous group (context.take)"

STAMP="$(date +%s)"
EXT_ID="dmtest-unsflush-${STAMP}"
SAMPLE="${INTEGRATION_DIR}/../../samples/uns/uns-flush-on-next-timestamp.js"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${_id:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$_id" 2>/dev/null || true
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating the mapping from the sample"
[ -f "$SAMPLE" ] || dm_error "Sample not found: $SAMPLE"
SF_CODE_B64=$(dm_wrap_onmessage_code "$(cat "$SAMPLE")" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name "test-uns-flush-$$" --arg identifier "uns-flush-$$" \
    --arg ext "$EXT_ID" --arg code "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "unsflush/+/+/+",
      mappingTopicSample: ("unsflush/plant1/" + $ext + "/temperature"),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{\"time\":\"2026-01-01T10:00:00.000Z\",\"value\":21.5}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      supportsMessageContext: true,
      qos: "AT_LEAST_ONCE"
    }')
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"

dm_step 3 "Deploying and activating"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

# ISO 8601 timestamps that compare correctly as text
T1="$(date -u -v-2M +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d '-2 minutes' +%Y-%m-%dT%H:%M:%S).000Z"
T2="$(date -u -v-1M +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%S).000Z"
T3="$(date -u +%Y-%m-%dT%H:%M:%S).000Z"

# $1 time  $2 first index  $3 last index. Parallel in public mode, in order where the client id is fixed.
publish_values() {
    local _time=$1 _i
    for _i in $(seq "$2" "$3"); do
        if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
            dm_mqtt_publish "unsflush/plant1/${EXT_ID}/m${_i}" "{\"time\":\"${_time}\",\"value\":${_i}}"
        else
            dm_mqtt_publish "unsflush/plant1/${EXT_ID}/m${_i}" "{\"time\":\"${_time}\",\"value\":${_i}}" >/dev/null &
        fi
    done
    wait
}

measurements() {   # all grouped measurements of the device, one JSON object per line
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" 2>/dev/null) || true
    [ -z "${_id:-}" ] && return 0
    c8y measurements list --device "$_id" --type "uns_Measurement" --pageSize 50 --output json 2>/dev/null
}
count_measurements() { measurements | jq -s 'length'; }
fragments_at() {   # <iso time> → number of uns_ fragments of the measurement with that time (to the second)
    measurements | jq -s --arg t "${1:0:19}" \
        '[.[] | select((.time | .[0:19]) == $t) | [keys[] | select(startswith("uns_") and . != "uns_Measurement")] | length] | first // 0'
}

dm_step 4 "Publishing 7 values of ${T1}"
publish_values "$T1" 1 7
sleep 8
dm_assert_eq "The newest group is buffered, not sent" "0" "$(count_measurements)"

dm_step 5 "Publishing the first value of ${T2}"
sleep 3   # the next timestamp must not overtake the last value of the previous one
publish_values "$T2" 1 1
dm_assert_measurement_present "Measurement for the first timestamp" "$EXT_ID" "c8y_Serial" 1 40
dm_assert_eq "T1 has its 7 values" "7" "$(fragments_at "$T1")"

dm_step 6 "Publishing 3 more values of ${T2}, then 1 value of ${T3}"
publish_values "$T2" 2 4
sleep 5
dm_assert_eq "Still one measurement: T2 is not flushed by its own values" "1" "$(count_measurements)"
publish_values "$T3" 1 1
dm_assert_measurement_present "Measurement for the second timestamp" "$EXT_ID" "c8y_Serial" 2 40
dm_assert_eq "T2 has its 4 values (a different count than T1)" "4" "$(fragments_at "$T2")"

dm_step 7 "The newest group stays buffered"
sleep 8
dm_assert_eq "Two measurements in total, T3 is not sent" "2" "$(count_measurements)"

dm_done "$TEST_TITLE"
dm_print_summary
