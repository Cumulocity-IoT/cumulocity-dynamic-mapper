#!/bin/bash
#
# test-outbound-subscription-api-all: static subscription with api=ALL
#
# api=ALL is the subscription the mapper itself uses for dynamic (type/group) subscriptions.
# One subscription must deliver MEASUREMENT, EVENT and ALARM notifications for the device,
# each routed to the outbound mapping whose targetAPI matches. (ALL is a subscription
# scope, not a mapping targetAPI: an outbound mapping is matched by targetAPI equality
# with the notification's API, so a mapping with targetAPI=ALL would never fire.)
#
# Usage:
#   ./test-outbound-subscription-api-all.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Static subscription api=ALL → measurement, event and alarm mappings"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-all-$(date +%s)"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_IDS=()

cleanup() {
    dm_info "Cleaning up ..."
    for _m in "${MAPPING_IDS[@]:-}"; do [ -n "$_m" ] && dm_delete_mapping "$_m" || true; done
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    c8y identity delete --name "$EXT_ID" --type c8y_Serial 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_create_device "$DEVICE_NAME" "dmtest-all-type"; DEVICE_ID=$_DM_LAST_DEVICE_ID
c8y identity create --name "$EXT_ID" --type c8y_Serial --device "$DEVICE_ID" --output json >/dev/null 2>&1 \
    || dm_warn "External id may already exist: $EXT_ID"

dm_step "Creating ONE static subscription with api=ALL ..."
dm_create_static_subscription_must "ALL" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 5

mk_mapping() {   # <suffix> <targetAPI> <sourceTemplate> <targetTemplate>
    jq -cn --arg n "test-outbound-all-$1-$$" --arg i "all$1$$" --arg api "$2" \
        --arg t "dmtest/out/all/$1" --arg st "$3" --arg tt "$4" '{
      name:$n, identifier:$i, mappingTopic:$t, mappingTopicSample:$t,
      publishTopic:$t, publishTopicSample:$t, targetAPI:$api, direction:"OUTBOUND",
      mappingType:"JSON", transformationType:"DEFAULT", filterMapping:"true",
      sourceTemplate:$st, targetTemplate:$tt,
      substitutions:[{pathSource:"_IDENTITY_.externalId",pathTarget:"deviceId",repairStrategy:"DEFAULT",expandArray:false}],
      active:false, debug:false, useExternalId:true, externalIdType:"c8y_Serial", qos:"AT_LEAST_ONCE"}'
}

dm_step "Creating one outbound mapping per API ..."
dm_create_mapping "$(mk_mapping mea MEASUREMENT \
  '{"c8y_TemperatureMeasurement":{"T":{"value":1,"unit":"C"}},"time":"2022-08-05T00:14:49.389+02:00","type":"c8y_TemperatureMeasurement"}' \
  '{"temperature":1,"deviceId":"source-id"}')"
MEA_ID=$_DM_LAST_MAPPING_ID; MAPPING_IDS+=("$MEA_ID")
dm_create_mapping "$(mk_mapping evt EVENT \
  '{"type":"c8y_AllEvent","text":"t","time":"2022-08-05T00:14:49.389+02:00"}' \
  '{"type":"c8y_AllEvent","deviceId":"source-id"}')"
EVT_ID=$_DM_LAST_MAPPING_ID; MAPPING_IDS+=("$EVT_ID")
dm_create_mapping "$(mk_mapping alm ALARM \
  '{"type":"c8y_AllAlarm","text":"t","severity":"MAJOR","status":"ACTIVE","time":"2022-08-05T00:14:49.389+02:00"}' \
  '{"type":"c8y_AllAlarm","severity":"MAJOR","deviceId":"source-id"}')"
ALM_ID=$_DM_LAST_MAPPING_ID; MAPPING_IDS+=("$ALM_ID")
for _m in "$MEA_ID" "$EVT_ID" "$ALM_ID"; do dm_deploy_mapping_to_mqtt_connector "$_m"; dm_activate_mapping "$_m"; done

MEA_BASE=$(dm_mapping_received_count "$MEA_ID")
EVT_BASE=$(dm_mapping_received_count "$EVT_ID")
ALM_BASE=$(dm_mapping_received_count "$ALM_ID")

dm_step "Creating one measurement, one event and one alarm ..."
c8y measurements create --device "$DEVICE_ID" --type c8y_TemperatureMeasurement \
    --template '{c8y_TemperatureMeasurement:{T:{value:20,unit:"C"}}}' --output json >/dev/null
c8y events create --device "$DEVICE_ID" --type c8y_AllEvent --text "all" --output json >/dev/null
c8y alarms create --device "$DEVICE_ID" --type c8y_AllAlarm --text "all" --severity MAJOR --output json >/dev/null
dm_wait 10

dm_assert_mapping_received_gt "MEASUREMENT mapping reached via ALL" "$MEA_ID" "$MEA_BASE"
dm_assert_mapping_received_gt "EVENT mapping reached via ALL" "$EVT_ID" "$EVT_BASE"
dm_assert_mapping_received_gt "ALARM mapping reached via ALL" "$ALM_ID" "$ALM_BASE"
dm_assert_num_eq "Each mapping received exactly one message (no cross-delivery)" "3" \
    "$(( $(dm_mapping_received_count "$MEA_ID") - MEA_BASE + $(dm_mapping_received_count "$EVT_ID") - EVT_BASE + $(dm_mapping_received_count "$ALM_ID") - ALM_BASE ))"

dm_done "$TEST_TITLE"
dm_print_summary
