#!/bin/bash
#
# test-inbound-smartfunction-uns-group: N:1 mapping, many single-value UNS messages become one measurement
#
# Sample under test: resources/samples/uns/uns-group-by-timestamp.js (context.collect), with EXPECTED_COUNT
# lowered from 100 to COUNT so the run stays short. A Unified Namespace publisher sends one MQTT message
# per value: topic uns/plant1/<device>/<name>, payload {"time": ..., "value": ...}.
#
#   1. COUNT values of timestamp T1, published at the same time → exactly ONE measurement with COUNT
#      fragments and the right values (a count kept with getState/setState never completes under load)
#   2. COUNT-1 values of timestamp T2                             → no measurement yet (paired with step 1,
#                                                                   so a stopped pipeline cannot pass)
#   3. The last value of T2                                       → a second measurement, time = T2
#   4. A value of T1 again                                        → opens a new group, no measurement
#
# In c8y-mqtt-service mode the client id is fixed by the certificate, so the values are published one after
# the other there; in public mode they are published in parallel. The connector processes them concurrently
# in both cases. The same race is covered without a broker by UnsGroupingConcurrencyTest.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated,
#                mosquitto_pub and jq installed
# Usage: ./test-inbound-smartfunction-uns-group.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEGRATION_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../test-harness.sh
source "${INTEGRATION_DIR}/test-harness.sh"

TEST_TITLE="Inbound: UNS N:1 — many single-value messages into one measurement (context.collect)"

STAMP="$(date +%s)"
EXT_ID="dmtest-uns-${STAMP}"
COUNT=20
SAMPLE="${INTEGRATION_DIR}/../../samples/uns/uns-group-by-timestamp.js"
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

dm_step 2 "Creating the mapping from the sample (EXPECTED_COUNT = ${COUNT})"
[ -f "$SAMPLE" ] || dm_error "Sample not found: $SAMPLE"
SF_CODE=$(sed "s/^var EXPECTED_COUNT = 100;/var EXPECTED_COUNT = ${COUNT};/" "$SAMPLE")
printf '%s' "$SF_CODE" | grep -q "^var EXPECTED_COUNT = ${COUNT};" \
    || dm_error "Could not set EXPECTED_COUNT in the sample"
SF_CODE_B64=$(dm_wrap_onmessage_code "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name "test-uns-group-$$" --arg identifier "uns-group-$$" \
    --arg ext "$EXT_ID" --arg code "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "uns/+/+/+",
      mappingTopicSample: ("uns/plant1/" + $ext + "/temperature"),
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

T1="$(date -u +%Y-%m-%dT%H:%M:%S).000Z"
T2="$(date -u +%Y-%m-%dT%H:%M:%S).500Z"

# $1 time  $2 first index  $3 last index. Parallel in public mode, in order where the client id is fixed.
publish_values() {
    local _time=$1 _i
    for _i in $(seq "$2" "$3"); do
        if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
            dm_mqtt_publish "uns/plant1/${EXT_ID}/m${_i}" "{\"time\":\"${_time}\",\"value\":${_i}}"
        else
            dm_mqtt_publish "uns/plant1/${EXT_ID}/m${_i}" "{\"time\":\"${_time}\",\"value\":${_i}}" >/dev/null &
        fi
    done
    wait
}

measurements() {   # number of grouped measurements of the device
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" 2>/dev/null) || true
    [ -z "${_id:-}" ] && { echo 0; return; }
    c8y measurements list --device "$_id" --type "uns_Measurement" --pageSize 50 --output json 2>/dev/null | jq -s 'length'
}

dm_step 4 "Publishing ${COUNT} values of ${T1}"
publish_values "$T1" 1 "$COUNT"
dm_assert_measurement_present "One measurement for the complete group" "$EXT_ID" "c8y_Serial" 1 40
sleep 5   # a duplicate flush, if there were one, has arrived by now
dm_assert_eq "Exactly one measurement, not one per value" "1" "$(measurements)"

dm_step 5 "Checking the fragments"
LATEST=$(dm_get_latest_measurement "$EXT_ID" "c8y_Serial" "uns_Measurement")
dm_assert_eq "All ${COUNT} values are fragments of the measurement" "$COUNT" \
    "$(printf '%s' "$LATEST" | jq '[keys[] | select(startswith("uns_"))] | length')"
dm_assert_eq "Fragment m7 carries its value" "7" "$(printf '%s' "$LATEST" | jq -r '.uns_m7.m7.value // empty')"
dm_assert_eq "The measurement carries the group's timestamp (to the second)" "${T1:0:19}" \
    "$(printf '%s' "$LATEST" | jq -r '.time // empty' | cut -c1-19)"

dm_step 6 "Publishing ${COUNT} minus one values of ${T2}"
publish_values "$T2" 1 $((COUNT - 1))
sleep 10
dm_assert_eq "An incomplete group creates no measurement" "1" "$(measurements)"

dm_step 7 "Publishing the last value of ${T2}"
publish_values "$T2" "$COUNT" "$COUNT"
dm_assert_measurement_present "Second measurement after the group completed" "$EXT_ID" "c8y_Serial" 2 40
dm_assert_eq "Two measurements in total" "2" "$(measurements)"

dm_step 8 "A value of the first timestamp again"
publish_values "$T1" 1 1
sleep 8
dm_assert_eq "A handed-out group is not completed again by one value" "2" "$(measurements)"

dm_done "$TEST_TITLE"
dm_print_summary
