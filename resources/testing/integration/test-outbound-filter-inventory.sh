#!/bin/bash
#
# test-outbound-filter-inventory: Outbound filterInventory and filterMapping on inventory changes
#
# One outbound INVENTORY mapping with
#   filterInventory = "type = 'dmtest-ofi-match'"   (evaluated against the device's cached managed object)
#   filterMapping   = "$exists(dmtest_Trigger)"     (evaluated against the notification itself)
# and two devices that both have a static INVENTORY subscription:
#   device A: type 'dmtest-ofi-match'    device B: type 'dmtest-ofi-other'
#
# Flow:
#   1. Change dmtest_Trigger on B   → inventory filter does not match  → not processed
#   2. Change c8y_Notes on A        → notification carries only the changed fragment, so
#                                     filterMapping does not match      → not processed
#   3. Change dmtest_Trigger on A   → both filters match                → processed
#
# Steps 1 and 2 are negative checks; step 3 proves the pipeline is live, so a mapping that
# never fires cannot pass. An outbound mapping whose filter does not match is not selected at
# all, therefore the check is that messagesReceived does not move.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - An active MQTT connector; c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-filter-inventory.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="filterInventory / filterMapping — outbound inventory"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
STAMP="$(date +%s)"
NAME_A="dmtest-ofi-match-${STAMP}"
NAME_B="dmtest-ofi-other-${STAMP}"
DEVICE_A_ID=""
DEVICE_B_ID=""
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    local _id
    for _id in "$DEVICE_A_ID" "$DEVICE_B_ID"; do
        [ -n "$_id" ] && dm_delete_static_subscription "$_id" "$SUBSCRIPTION_NAME" 2>/dev/null || true
        [ -n "$_id" ] && dm_delete_device "$_id" || true
    done
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Creating device A (type matches the inventory filter) and device B (type does not) ..."
dm_create_device "$NAME_A" "dmtest-ofi-match"
DEVICE_A_ID=$_DM_LAST_DEVICE_ID
dm_create_device "$NAME_B" "dmtest-ofi-other"
DEVICE_B_ID=$_DM_LAST_DEVICE_ID

dm_step "Binding c8y_Serial external ids ..."
for _pair in "$NAME_A:$DEVICE_A_ID" "$NAME_B:$DEVICE_B_ID"; do
    c8y identity create --name "${_pair%%:*}" --type "c8y_Serial" --device "${_pair#*:}" \
        --output json >/dev/null 2>&1 || dm_warn "External id may already exist: ${_pair%%:*}"
done

dm_step "Creating static INVENTORY subscriptions for both devices ..."
dm_create_static_subscription_must "INVENTORY" "$DEVICE_A_ID" "$NAME_A"
dm_create_static_subscription_must "INVENTORY" "$DEVICE_B_ID" "$NAME_B"
dm_wait 5

dm_step "Creating outbound INVENTORY mapping with filterInventory and filterMapping ..."
MAPPING_JSON=$(cat <<EOF2
{
  "name": "test-outbound-filter-inv-$$",
  "identifier": "ofi$$",
  "mappingTopic": "dmtest/out/filterinv",
  "mappingTopicSample": "dmtest/out/filterinv",
  "publishTopic": "dmtest/out/filterinv",
  "publishTopicSample": "dmtest/out/filterinv",
  "targetAPI": "INVENTORY",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "JSONATA",
  "filterMapping": "\$exists(dmtest_Trigger)",
  "filterInventory": "type = 'dmtest-ofi-match'",
  "sourceTemplate": "{\"type\":\"dmtest-ofi-match\",\"name\":\"device name\",\"dmtest_Trigger\":{\"rev\":1}}",
  "targetTemplate": "{\"deviceId\":\"source-id\",\"rev\":0}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"dmtest_Trigger.rev","pathTarget":"rev","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF2
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "Recording baseline ..."
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

dm_step "1. Changing dmtest_Trigger on device B (inventory filter does not match) ..."
c8y inventory update --id "$DEVICE_B_ID" --data '{"dmtest_Trigger":{"rev":1}}' --output json >/dev/null
dm_wait 12
AFTER_B=$(dm_mapping_received_count "$MAPPING_ID")
dm_assert_eq "Device of another type is not processed (filterInventory)" "$BASELINE" "$AFTER_B"

dm_step "2. Changing c8y_Notes on device A (filterMapping needs dmtest_Trigger) ..."
c8y inventory update --id "$DEVICE_A_ID" --data '{"c8y_Notes":"no trigger fragment in this change"}' --output json >/dev/null
dm_wait 12
AFTER_NOTES=$(dm_mapping_received_count "$MAPPING_ID")
dm_assert_eq "Change without dmtest_Trigger is not processed (filterMapping)" "$BASELINE" "$AFTER_NOTES"

dm_step "3. Changing dmtest_Trigger on device A (both filters match) ..."
c8y inventory update --id "$DEVICE_A_ID" --data '{"dmtest_Trigger":{"rev":2}}' --output json >/dev/null
dm_wait 12
dm_assert_mapping_received_gt "Matching device and fragment is processed" "$MAPPING_ID" "$BASELINE"

dm_done "$TEST_TITLE"
dm_print_summary
