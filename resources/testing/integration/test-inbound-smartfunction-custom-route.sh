#!/bin/bash
#
# test-inbound-smartfunction-custom-route: Smart Function cumulocityType "custom"
#
# A Smart Function can return { cumulocityType: "custom", targetPath: "/service/..." } to call a
# tenant microservice endpoint instead of writing a Cumulocity object (API.CUSTOM). Device
# resolution is bypassed. Uses the mapper's own echo endpoint
# (/service/dynamic-mapper-service/webhook/echo/...) as the target.
#   1. valid /service/ path  -> processed, no error counted, no device created
#   2. path not under /service/ -> rejected, error counted
# The function chooses the path from the payload ("path" field).
#
# Usage:
#   ./test-inbound-smartfunction-custom-route.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Smart Function → CUSTOM route to /service/..."

EXT_ID="dmtest-custom-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, ctx) {
    const p = msg.getPayload();
    return [{
        cumulocityType: 'custom',
        action: 'create',
        targetPath: p['path'],
        payload: { source: 'dm-integration-test', value: p['value'] }
    }];
}
JSCODE
)
SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

MAPPING_JSON=$(jq -cn --arg name "test-inbound-custom-$$" --arg identifier "ibu$$" --arg code "$SF_CODE_B64" '{
  name:$name, identifier:$identifier,
  mappingTopic:"dmtest/custom/+", mappingTopicSample:"dmtest/custom/x",
  targetAPI:"MEASUREMENT", direction:"INBOUND", mappingType:"JSON", transformationType:"SMART_FUNCTION",
  sourceTemplate:"{\"path\":\"/service/x\",\"value\":1}", targetTemplate:"{}",
  substitutions:[], code:$code, active:false, debug:false,
  createNonExistingDevice:true, updateExistingDevice:false, useExternalId:true,
  externalIdType:"c8y_Serial", genericDeviceIdentifier:"_IDENTITY_.externalId",
  supportsMessageContext:true, qos:"AT_LEAST_ONCE"}')

dm_step "Creating and activating Smart Function mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

PROC_BASE=$(dm_mapping_stat "$MAPPING_ID" messagesProcessed)
ERR_BASE=$(dm_mapping_stat "$MAPPING_ID" errors)

dm_step "1/2 Valid /service/ target path ..."
dm_mqtt_publish "dmtest/custom/${EXT_ID}" '{"path":"/service/dynamic-mapper-service/webhook/echo/dmtest","value":42}'
dm_wait 8
dm_assert_gt "Custom request processed" "$(dm_mapping_stat "$MAPPING_ID" messagesProcessed)" "$PROC_BASE"
dm_assert_eq "No error for a valid custom route" "$ERR_BASE" "$(dm_mapping_stat "$MAPPING_ID" errors)"
dm_assert_eq "Custom route does not create a device" "" "$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")"

dm_step "2/2 Path outside /service/ is rejected ..."
dm_mqtt_publish "dmtest/custom/${EXT_ID}" '{"path":"/inventory/managedObjects","value":1}'
dm_wait 8
dm_assert_gt "Invalid custom path counted as an error" "$(dm_mapping_stat "$MAPPING_ID" errors)" "$ERR_BASE"

dm_done "$TEST_TITLE"
dm_print_summary
