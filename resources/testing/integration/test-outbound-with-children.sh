#!/bin/bash
#
# test-outbound-with-children: EVENT_WITH_CHILDREN and ALARM_WITH_CHILDREN (outbound)
#
# A static subscription on a PARENT device with api=<X>_WITH_CHILDREN also delivers
# notifications for CHILD devices. Outbound mappings select notifications by targetAPI
# equality, so:
#   - a mapping with targetAPI=<X>_WITH_CHILDREN fires for events/alarms on parent AND child
#     (at least once each: Notification 2.0 may redeliver, so the count is >= 2, not == 2)
#   - a mapping with the plain targetAPI=<X> must NOT fire for them (different API)
# Runs the scenario for EVENT and for ALARM.
#
# Usage:
#   ./test-outbound-with-children.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Outbound EVENT/ALARM_WITH_CHILDREN subscriptions"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
TS=$(date +%s)
PARENT_EXT="dmtest-wc-parent-$TS"
CHILD_EXT="dmtest-wc-child-$TS"
PARENT_ID=""
CHILD_ID=""
MAPPING_IDS=()

cleanup() {
    dm_info "Cleaning up ..."
    for _m in "${MAPPING_IDS[@]:-}"; do [ -n "$_m" ] && dm_delete_mapping "$_m" || true; done
    [ -n "$PARENT_ID" ] && dm_delete_static_subscription "$PARENT_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    for _e in "$PARENT_EXT" "$CHILD_EXT"; do c8y identity delete --name "$_e" --type c8y_Serial 2>/dev/null || true; done
    [ -n "$CHILD_ID" ] && dm_delete_device "$CHILD_ID" || true
    [ -n "$PARENT_ID" ] && dm_delete_device "$PARENT_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Creating parent and child device with external ids ..."
dm_create_device "$PARENT_EXT" "dmtest-wc-type"; PARENT_ID=$_DM_LAST_DEVICE_ID
dm_create_device "$CHILD_EXT"  "dmtest-wc-type"; CHILD_ID=$_DM_LAST_DEVICE_ID
for pair in "$PARENT_EXT:$PARENT_ID" "$CHILD_EXT:$CHILD_ID"; do
    c8y identity create --name "${pair%%:*}" --type c8y_Serial --device "${pair##*:}" --output json >/dev/null 2>&1 \
        || dm_warn "External id may already exist: ${pair%%:*}"
done
c8y devices children assign --id "$PARENT_ID" --child "$CHILD_ID" --childType device --output json >/dev/null \
    || dm_error "Could not assign child device"

mk_mapping() {   # <suffix> <targetAPI> <topic> <sourceTemplate> <targetTemplate>
    jq -cn --arg n "test-outbound-wc-$1-$$" --arg i "wc$1$$" --arg api "$2" --arg t "$3" \
        --arg st "$4" --arg tt "$5" '{
      name:$n, identifier:$i, mappingTopic:$t, mappingTopicSample:$t,
      publishTopic:$t, publishTopicSample:$t, targetAPI:$api, direction:"OUTBOUND",
      mappingType:"JSON", transformationType:"DEFAULT", filterMapping:"true",
      sourceTemplate:$st, targetTemplate:$tt,
      substitutions:[{pathSource:"_IDENTITY_.externalId",pathTarget:"deviceId",repairStrategy:"DEFAULT",expandArray:false}],
      active:false, debug:false, useExternalId:true, externalIdType:"c8y_Serial", qos:"AT_LEAST_ONCE"}'
}

run_case() {   # <KIND: EVENT|ALARM>
    local kind=$1 src tgt
    if [ "$kind" = EVENT ]; then
        src='{"type":"c8y_WcEvent","text":"t","time":"2022-08-05T00:14:49.389+02:00"}'
        tgt='{"type":"c8y_WcEvent","text":"t","deviceId":"source-id"}'
    else
        src='{"type":"c8y_WcAlarm","text":"t","severity":"MAJOR","status":"ACTIVE","time":"2022-08-05T00:14:49.389+02:00"}'
        tgt='{"type":"c8y_WcAlarm","severity":"MAJOR","deviceId":"source-id"}'
    fi

    dm_step "[$kind] Subscription on parent with api=${kind}_WITH_CHILDREN ..."
    dm_create_static_subscription_must "${kind}_WITH_CHILDREN" "$PARENT_ID" "$PARENT_EXT"
    dm_wait 5

    dm_step "[$kind] Creating WITH_CHILDREN and plain mappings ..."
    dm_create_mapping "$(mk_mapping "${kind}wc" "${kind}_WITH_CHILDREN" "dmtest/out/wc/${kind}/children" "$src" "$tgt")"
    local wc_id="$_DM_LAST_MAPPING_ID"; MAPPING_IDS+=("$wc_id")
    dm_create_mapping "$(mk_mapping "${kind}pl" "$kind" "dmtest/out/wc/${kind}/plain" "$src" "$tgt")"
    local pl_id="$_DM_LAST_MAPPING_ID"; MAPPING_IDS+=("$pl_id")
    for _m in "$wc_id" "$pl_id"; do dm_deploy_mapping_to_mqtt_connector "$_m"; dm_activate_mapping "$_m"; done

    local wc_base pl_base
    wc_base=$(dm_mapping_received_count "$wc_id"); pl_base=$(dm_mapping_received_count "$pl_id")

    dm_step "[$kind] Creating one $kind on the PARENT and one on the CHILD ..."
    for _d in "$PARENT_ID" "$CHILD_ID"; do
        if [ "$kind" = EVENT ]; then
            c8y events create --device "$_d" --type c8y_WcEvent --text "wc" --output json >/dev/null
        else
            c8y alarms create --device "$_d" --type "c8y_WcAlarm_$_d" --text "wc" --severity MAJOR --output json >/dev/null
        fi
    done
    dm_wait 10

    local wc_now pl_now
    wc_now=$(dm_mapping_received_count "$wc_id"); pl_now=$(dm_mapping_received_count "$pl_id")
    # Notification 2.0 delivers at-least-once: the platform may redeliver the same notification
    # (same message id) and the mapper processes each delivery, so the count can exceed 2.
    # Assert "at least parent + child", and only warn about redelivery.
    dm_assert_gt "[$kind] WITH_CHILDREN mapping fired for parent and child" "$wc_now" "$((wc_base + 1))"
    if [ "$wc_now" -gt "$((wc_base + 2))" ] 2>/dev/null; then
        dm_warn "[$kind] $((wc_now - wc_base)) deliveries for 2 events: the platform redelivered a notification (at-least-once)"
    fi
    dm_assert_num_eq "[$kind] plain mapping did not fire for the WITH_CHILDREN subscription" "$pl_base" "$pl_now"

    dm_delete_static_subscription "$PARENT_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
}

run_case EVENT
run_case ALARM

dm_done "$TEST_TITLE"
dm_print_summary
