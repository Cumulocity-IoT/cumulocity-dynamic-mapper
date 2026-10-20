#!/bin/bash
#
# test-inbound-filter: Inbound filterMapping and filterInventory drop non-matching messages
#
# Part 1 — filterMapping (JSONata on the message payload)
#   Mapping: filterMapping = "temperature > 30", createNonExistingDevice = true
#   1. Publish temperature 20 → filtered: counted in messagesFiltered, no measurement,
#      and the device is NOT created (a filtered message must not have side effects)
#   2. Publish temperature 40 → processed: measurement created, device created
#
# Part 2 — filterInventory (JSONata on the target device's managed object)
#   Mapping: filterInventory = "type = 'dmtest-fi-match'", for two existing devices
#   3. Publish for the device of another type → filtered, no measurement
#   4. Publish for the device of the matching type → measurement created
#
# Each negative check is paired with a positive one in the same run, so a pipeline that
# is simply not running cannot make the test pass.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed, jq installed
#
# Usage:
#   ./test-inbound-filter.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="filterMapping / filterInventory — inbound"

# ── Config ─────────────────────────────────────────────────────────────────────
STAMP="$(date +%s)"
EXT_PAYLOAD="dmtest-fm-${STAMP}"          # created implicitly by part 1
EXT_MATCH="dmtest-fi-match-${STAMP}"      # existing device, type matches the inventory filter
EXT_OTHER="dmtest-fi-other-${STAMP}"      # existing device, type does not match
MAPPING_A_ID=""
MAPPING_B_ID=""
DEVICE_MATCH_ID=""
DEVICE_OTHER_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_A_ID" ] && dm_delete_mapping "$MAPPING_A_ID" || true
    [ -n "$MAPPING_B_ID" ] && dm_delete_mapping "$MAPPING_B_ID" || true
    local _id _ext
    _id=$(dm_lookup_device_by_ext_id "$EXT_PAYLOAD" "c8y_Serial") || true
    if [ -n "${_id:-}" ]; then
        c8y identity delete --name "$EXT_PAYLOAD" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$_id" 2>/dev/null || true
    fi
    for _ext in "$EXT_MATCH" "$EXT_OTHER"; do
        c8y identity delete --name "$_ext" --type "c8y_Serial" 2>/dev/null || true
    done
    [ -n "$DEVICE_MATCH_ID" ] && dm_delete_device "$DEVICE_MATCH_ID" || true
    [ -n "$DEVICE_OTHER_ID" ] && dm_delete_device "$DEVICE_OTHER_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

# Builds an inbound measurement mapping; the filters are the only difference between parts.
# $1 name  $2 identifier  $3 topic pattern  $4 filterMapping  $5 filterInventory ("" = none)
# $6 createNonExistingDevice
build_mapping() {
    jq -cn \
        --arg name "$1" --arg identifier "$2" --arg topic "$3" \
        --arg filterMapping "$4" --arg filterInventory "$5" --argjson create "$6" \
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
          filterInventory: (if $filterInventory == "" then null else $filterInventory end),
          sourceTemplate: "{\"temperature\":25.0}",
          targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
          substitutions: [
            {pathSource: "_TOPIC_LEVEL_[2]", pathTarget: "_IDENTITY_.externalId", repairStrategy: "DEFAULT", expandArray: false},
            {pathSource: "temperature", pathTarget: "c8y_TemperatureMeasurement.T.value", repairStrategy: "DEFAULT", expandArray: false},
            {pathSource: "$now()", pathTarget: "time", repairStrategy: "DEFAULT", expandArray: false}
          ],
          active: false,
          debug: false,
          createNonExistingDevice: $create,
          updateExistingDevice: false,
          useExternalId: true,
          externalIdType: "c8y_Serial",
          qos: "AT_LEAST_ONCE"
        }'
}

# ── Part 1: filterMapping ──────────────────────────────────────────────────────
dm_step "Part 1: creating mapping with filterMapping 'temperature > 30' ..."
dm_create_mapping "$(build_mapping "test-inbound-filter-A-$$" "ifa$$" "dmtest/fm/+" "temperature > 30" "" true)"
MAPPING_A_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_A_ID"
dm_activate_mapping "$MAPPING_A_ID"
dm_assert_mqtt_topics_active "$MAPPING_A_ID"

FILTERED_BEFORE=$(dm_mapping_stat "$MAPPING_A_ID" messagesFiltered)
PROCESSED_BEFORE=$(dm_mapping_stat "$MAPPING_A_ID" messagesProcessed)

dm_step "Publishing temperature 20 (filter does not match) ..."
dm_mqtt_publish "dmtest/fm/${EXT_PAYLOAD}" '{"temperature":20}'
dm_wait 8

dm_assert_gt "Filtered message is counted in messagesFiltered" \
    "$(dm_mapping_stat "$MAPPING_A_ID" messagesFiltered)" "$FILTERED_BEFORE"
dm_assert_eq "Filtered message is not counted as processed" \
    "$PROCESSED_BEFORE" "$(dm_mapping_stat "$MAPPING_A_ID" messagesProcessed)"
dm_assert_eq "Filtered message did not create the device" \
    "" "$(dm_lookup_device_by_ext_id "$EXT_PAYLOAD" "c8y_Serial")"

dm_step "Publishing temperature 40 (filter matches) ..."
dm_mqtt_publish "dmtest/fm/${EXT_PAYLOAD}" '{"temperature":40}'
dm_assert_measurement_present "Matching message creates a measurement" "$EXT_PAYLOAD" "c8y_Serial" 1 20
dm_assert_gt "Matching message is counted as processed" \
    "$(dm_mapping_stat "$MAPPING_A_ID" messagesProcessed)" "$PROCESSED_BEFORE"

# ── Part 2: filterInventory ────────────────────────────────────────────────────
dm_step "Part 2: creating two existing devices (matching and non-matching type) ..."
dm_create_device "$EXT_MATCH" "dmtest-fi-match"
DEVICE_MATCH_ID=$_DM_LAST_DEVICE_ID
dm_create_device "$EXT_OTHER" "dmtest-fi-other"
DEVICE_OTHER_ID=$_DM_LAST_DEVICE_ID
for _pair in "$EXT_MATCH:$DEVICE_MATCH_ID" "$EXT_OTHER:$DEVICE_OTHER_ID"; do
    c8y identity create --name "${_pair%%:*}" --type "c8y_Serial" --device "${_pair#*:}" \
        --output json >/dev/null 2>&1 || dm_warn "External id may already exist: ${_pair%%:*}"
done

dm_step "Creating mapping with filterInventory \"type = 'dmtest-fi-match'\" ..."
dm_create_mapping "$(build_mapping "test-inbound-filter-B-$$" "ifb$$" "dmtest/fi/+" "true" "type = 'dmtest-fi-match'" false)"
MAPPING_B_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_B_ID"
dm_activate_mapping "$MAPPING_B_ID"
dm_assert_mqtt_topics_active "$MAPPING_B_ID"

SINCE=$(dm_now -10)
FILTERED_BEFORE=$(dm_mapping_stat "$MAPPING_B_ID" messagesFiltered)

dm_step "Publishing for the device of the other type (inventory filter does not match) ..."
dm_mqtt_publish "dmtest/fi/${EXT_OTHER}" '{"temperature":21}'
dm_wait 8

dm_assert_gt "Inventory-filtered message is counted in messagesFiltered" \
    "$(dm_mapping_stat "$MAPPING_B_ID" messagesFiltered)" "$FILTERED_BEFORE"
dm_assert_eq "No measurement for the non-matching device" \
    "0" "$(dm_count_measurements_since "$DEVICE_OTHER_ID" "$SINCE")"

dm_step "Publishing for the device of the matching type ..."
dm_mqtt_publish "dmtest/fi/${EXT_MATCH}" '{"temperature":22}'
dm_assert_measurement_present "Matching device gets a measurement" "$EXT_MATCH" "c8y_Serial" 1 20

dm_done "$TEST_TITLE"
dm_print_summary
