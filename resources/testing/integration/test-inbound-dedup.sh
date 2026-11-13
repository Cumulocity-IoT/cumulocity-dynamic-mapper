#!/bin/bash
#
# test-inbound-dedup: Repeated inbound messages are dropped ($isDuplicate filter, context.isDuplicate in a Smart Function)
#
# Part 1 — JSONata mapping with the execution filter
#   $exists(time) and $not($isDuplicate(_TOPIC_LEVEL_[2] & "|" & time, 40))
#   1. Publish a message              → measurement created
#   2. Publish the same message again → dropped: no second measurement, counted in
#                                       messagesDuplicate (a part of messagesFiltered)
#   3. Publish another time           → processed (a different key is not a repeat)
#   4. Publish without time           → filtered by the $exists guard, NOT counted as repeated
#   5. After the 40 s window          → the same message is processed again
#   6. Editing the mapping:
#        a. a changed filter clears the keys: the message that was dropped is processed
#        b. a rename keeps the keys: the message is still dropped
#
# Part 2 — Smart Function mapping calling context.isDuplicate(key, 40)
#   7. Publish, publish again         → one measurement, the repeat counted in messagesDuplicate
#   8. Publish another time           → processed
#
# Each negative check is paired with a positive one in the same run, so a pipeline that is
# simply not running cannot make the test pass. Takes about three minutes (window wait).
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed, jq installed
#
# Usage:
#   ./test-inbound-dedup.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Message de-duplication — inbound"

# ── Config ─────────────────────────────────────────────────────────────────────
STAMP="$(date +%s)"
EXT_A="dmtest-dd-a-${STAMP}"
EXT_B="dmtest-dd-b-${STAMP}"
WINDOW=40                                  # seconds, in the filter and in the script; long enough for the edit steps
MAPPING_A_ID=""
MAPPING_B_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_A_ID" ] && dm_delete_mapping "$MAPPING_A_ID" || true
    [ -n "$MAPPING_B_ID" ] && dm_delete_mapping "$MAPPING_B_ID" || true
    local _ext _id
    for _ext in "$EXT_A" "$EXT_B"; do
        _id=$(dm_lookup_device_by_ext_id "$_ext" "c8y_Serial") || true
        if [ -n "${_id:-}" ]; then
            c8y identity delete --name "$_ext" --type "c8y_Serial" 2>/dev/null || true
            c8y inventory delete --id "$_id" 2>/dev/null || true
        fi
    done
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

FILTER_V1='$exists(time) and $not($isDuplicate(_TOPIC_LEVEL_[2] & "|" & time, '"$WINDOW"'))'
FILTER_V2='$exists(time) and $not($isDuplicate("v2|" & _TOPIC_LEVEL_[2] & "|" & time, '"$WINDOW"'))'

# $1 name  $2 identifier  $3 topic pattern  $4 filterMapping
build_jsonata_mapping() {
    jq -cn \
        --arg name "$1" --arg identifier "$2" --arg topic "$3" --arg filterMapping "$4" \
        '{
          name: $name,
          identifier: $identifier,
          mappingTopic: $topic,
          mappingTopicSample: ($topic | sub("\\+"; "sample")),
          targetAPI: "MEASUREMENT",
          direction: "INBOUND",
          mappingType: "JSON",
          transformationType: "JSONATA",
          filterMapping: $filterMapping,
          sourceTemplate: "{\"temperature\":25.0,\"time\":\"2026-01-01T00:00:00Z\"}",
          targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
          substitutions: [
            {pathSource: "_TOPIC_LEVEL_[2]", pathTarget: "_IDENTITY_.externalId", repairStrategy: "DEFAULT", expandArray: false},
            {pathSource: "temperature", pathTarget: "c8y_TemperatureMeasurement.T.value", repairStrategy: "DEFAULT", expandArray: false},
            {pathSource: "$now()", pathTarget: "time", repairStrategy: "DEFAULT", expandArray: false}
          ],
          active: false,
          debug: false,
          createNonExistingDevice: true,
          updateExistingDevice: false,
          useExternalId: true,
          externalIdType: "c8y_Serial",
          qos: "AT_LEAST_ONCE"
        }'
}

# Number of measurements of the device with this external id since the start of the test.
SINCE=$(dm_now -10)
measurements() {   # <ext_id>
    dm_count_measurements_since "$(dm_lookup_device_by_ext_id "$1" "c8y_Serial")" "$SINCE"
}

# $1 topic  $2 time ("" = payload without time)
publish() {
    if [ -n "$2" ]; then
        dm_mqtt_publish "$1" "{\"temperature\":21.5,\"time\":\"$2\"}"
    else
        dm_mqtt_publish "$1" '{"temperature":21.5}'
    fi
}

# Deactivate, replace the mapping with the jq-modified copy, activate again. The API refuses to
# update an active mapping, and the editor does the same.
# $1 mapping id  $2 jq filter applied to the stored mapping
edit_mapping() {
    dm_deactivate_mapping "$1"
    dm_api_must PUT "/mapping/$1" "$(dm_api GET "/mapping/$1" | jq -c "$2")" >/dev/null
    dm_activate_mapping "$1"
    dm_assert_mqtt_topics_active "$1"
}

# ── Part 1: $isDuplicate in the execution filter ───────────────────────────────
dm_step 1 "Creating the JSONata mapping with the de-duplication filter ..."
dm_create_mapping "$(build_jsonata_mapping "test-inbound-dedup-A-$$" "ida$$" "dmtest/dd/+" "$FILTER_V1")"
MAPPING_A_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_A_ID"
dm_activate_mapping "$MAPPING_A_ID"
dm_assert_mqtt_topics_active "$MAPPING_A_ID"

TOPIC_A="dmtest/dd/${EXT_A}"
DUP_BEFORE=$(dm_mapping_stat "$MAPPING_A_ID" messagesDuplicate)
FILTERED_BEFORE=$(dm_mapping_stat "$MAPPING_A_ID" messagesFiltered)

dm_step 2 "First message: processed"
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_assert_measurement_present "First message creates a measurement" "$EXT_A" "c8y_Serial" 1 20
dm_assert_eq "One measurement so far" "1" "$(measurements "$EXT_A")"

dm_step 3 "Same message again: dropped"
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_wait 8 "the repeat must not arrive as a measurement"
dm_assert_eq "The repeat created no second measurement" "1" "$(measurements "$EXT_A")"
dm_assert_eq "The repeat is counted in messagesDuplicate" \
    "$((DUP_BEFORE + 1))" "$(dm_mapping_stat "$MAPPING_A_ID" messagesDuplicate)"
dm_assert_eq "messagesFiltered includes the repeat" \
    "$((FILTERED_BEFORE + 1))" "$(dm_mapping_stat "$MAPPING_A_ID" messagesFiltered)"

dm_step 4 "Same device, other time: not a repeat"
publish "$TOPIC_A" "2026-11-12T10:00:01Z"
dm_wait_for_measurement_count "$EXT_A" "c8y_Serial" 2 20 >/dev/null || true
dm_assert_eq "A different key is processed" "2" "$(measurements "$EXT_A")"

dm_step 5 "Message without time: filtered by the guard, not counted as repeated"
DUP_BEFORE=$(dm_mapping_stat "$MAPPING_A_ID" messagesDuplicate)
publish "$TOPIC_A" ""
dm_wait 8
dm_assert_eq "No measurement without time" "2" "$(measurements "$EXT_A")"
dm_assert_eq "A message without time is not a repeat" \
    "$DUP_BEFORE" "$(dm_mapping_stat "$MAPPING_A_ID" messagesDuplicate)"

dm_step 6 "After the ${WINDOW} s window the same message is processed again"
dm_wait $((WINDOW + 4)) "waiting for the window to end"
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_wait_for_measurement_count "$EXT_A" "c8y_Serial" 3 20 >/dev/null || true
dm_assert_eq "The window ended, so the message is not a repeat" "3" "$(measurements "$EXT_A")"

dm_step 7 "Editing the mapping: a rename keeps the keys"
# The key of 10:00:00Z was recorded just now. A rename must not clear it.
edit_mapping "$MAPPING_A_ID" '.name = (.name + "-renamed")'
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_wait 8
dm_assert_eq "After a rename the message is still a repeat" "3" "$(measurements "$EXT_A")"

dm_step 8 "Editing the mapping: a changed filter clears the keys"
# Same key text, new filter: the message that was just dropped must pass once.
edit_mapping "$MAPPING_A_ID" ".filterMapping = $(jq -Rn --arg f "$FILTER_V2" '$f')"
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_wait_for_measurement_count "$EXT_A" "c8y_Serial" 4 20 >/dev/null || true
dm_assert_eq "After a filter change the message is processed" "4" "$(measurements "$EXT_A")"
publish "$TOPIC_A" "2026-11-12T10:00:00Z"
dm_wait 8
dm_assert_eq "and its repeat is dropped under the new filter" "4" "$(measurements "$EXT_A")"

# ── Part 2: context.isDuplicate in a Smart Function ────────────────────────────
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, ctx) {
    const sourceObject = msg.getPayload();
    const config = ctx.getConfig ? ctx.getConfig() : {};
    const topicLevels = typeof config.topic === 'string'
        ? config.topic.split('/')
        : (Array.isArray(sourceObject['_TOPIC_LEVEL_']) ? sourceObject['_TOPIC_LEVEL_'] : []);
    const externalId = topicLevels.length > 0 ? topicLevels[topicLevels.length - 1] : null;
    const time = sourceObject['time'];
    if (!externalId || !time) {
        return [];
    }

    // The key says what makes two messages the same; the separator keeps "a"+"bc" apart from "ab"+"c"
    if (ctx.isDuplicate(externalId + '|' + time, __WINDOW__)) {
        return [];
    }

    return [{
        cumulocityType: 'measurement',
        action: 'create',
        payload: {
            time: new Date().toISOString(),
            type: 'c8y_TemperatureMeasurement',
            c8y_TemperatureMeasurement: { T: { value: sourceObject['temperature'], unit: 'C' } }
        },
        externalSource: [{ type: 'c8y_Serial', externalId: externalId }]
    }];
}
JSCODE
)
SF_CODE=${SF_CODE//__WINDOW__/$WINDOW}
SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

dm_step 9 "Creating the Smart Function mapping that calls context.isDuplicate ..."
dm_create_mapping "$(jq -cn \
    --arg name "test-inbound-dedup-B-$$" --arg identifier "idb$$" --arg extId "$EXT_B" --arg code "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ddsf/+",
      mappingTopicSample: ("dmtest/ddsf/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{\"temperature\":25.0,\"time\":\"2026-01-01T00:00:00Z\"}",
      targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      updateExistingDevice: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      supportsMessageContext: true,
      qos: "AT_LEAST_ONCE"
    }')"
MAPPING_B_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_B_ID"
dm_activate_mapping "$MAPPING_B_ID"
dm_assert_mqtt_topics_active "$MAPPING_B_ID"

TOPIC_B="dmtest/ddsf/${EXT_B}"
DUP_BEFORE=$(dm_mapping_stat "$MAPPING_B_ID" messagesDuplicate)

dm_step 10 "Smart Function: first message processed, repeat dropped"
publish "$TOPIC_B" "2026-11-12T10:00:00Z"
dm_assert_measurement_present "Smart Function creates a measurement" "$EXT_B" "c8y_Serial" 1 30
publish "$TOPIC_B" "2026-11-12T10:00:00Z"
dm_wait 8
dm_assert_eq "The repeat created no second measurement" "1" "$(measurements "$EXT_B")"
dm_assert_eq "The repeat is counted in messagesDuplicate" \
    "$((DUP_BEFORE + 1))" "$(dm_mapping_stat "$MAPPING_B_ID" messagesDuplicate)"

dm_step 11 "Smart Function: other time is processed"
publish "$TOPIC_B" "2026-11-12T10:00:01Z"
dm_wait_for_measurement_count "$EXT_B" "c8y_Serial" 2 20 >/dev/null || true
dm_assert_eq "A different key is processed" "2" "$(measurements "$EXT_B")"

dm_done "$TEST_TITLE"
dm_print_summary
