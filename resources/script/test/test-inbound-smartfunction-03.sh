#!/bin/bash
#
# test-inbound-smartfunction-03.sh
#
# Integration test for Smart Function template pattern 03.
# Template: context.getManagedObjectByExternalId — enrich event payload with
#           properties read from the device's managed object at runtime.
#
# A Smart Function resolves the triggering device by external ID, reads its
# c8y_Hardware fragment, then embeds the resolved name and serial number in the
# created event's text field. The test verifies those values appear in the
# persisted C8Y event, confirming the MO lookup reached the function.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-smartfunction-03.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

EXT_ID="dmtest-mo-lookup-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "Test: Smart Function Pattern 03 (getManagedObjectByExternalId enrichment)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step 2 "Creating sensor device"
DEVICE=$(c8y inventory create \
    --name "Sensor Berlin 03" \
    --type "c8y_Sensor" \
    --data "c8y_IsDevice={}" \
    2>/dev/null || echo "{}")

DEVICE_ID=$(echo "$DEVICE" | jq -r '.id // empty')
if [ -z "$DEVICE_ID" ]; then
    dm_error "Failed to create sensor device"
fi
dm_success "Sensor device created: $DEVICE_ID"

dm_step 3 "Binding external ID"
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json > /dev/null 2>&1 || dm_warn "External ID may already exist: $EXT_ID"
dm_success "External ID bound: $EXT_ID"

dm_step 4 "Creating mapping with getManagedObjectByExternalId in Smart Function"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var config = context.getConfig();
    var topicParts = (config.topic || "").split("/");
    var extId = topicParts[topicParts.length - 1];

    var device = context.getManagedObjectByExternalId({ externalId: extId, type: "c8y_Serial" });
    var deviceName  = device ? device.name : "unknown";
    var deviceType  = device ? device.type : "unknown";

    return [{
        cumulocityType: "event",
        action: "create",
        payload: {
            type: "dm_MoLookupTest",
            text: "device=" + deviceName + " type=" + deviceType,
            time: new Date().toISOString()
        },
        externalSource: [{ type: "c8y_Serial", externalId: extId }]
    }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name       "test-sf-03-$$" \
    --arg identifier "sf-03-$$" \
    --arg code       "$SF_CODE_B64" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "testSmartInbound/sf03/+",
      mappingTopicSample: ("testSmartInbound/sf03/" + $extId),
      targetAPI: "EVENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active
dm_success "Mapping deployed and activated"

dm_step 6 "Publishing inbound message"
dm_mqtt_publish "testSmartInbound/sf03/$EXT_ID" '{"trigger":"mo-lookup-test"}' 1
dm_success "Message published"

dm_step 7 "Waiting for enriched event"
RESOLVED_DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
dm_info "Resolved device: ${RESOLVED_DEVICE_ID:-<none>}"

EVENT_TEXT=""
for _i in 1 2 3 4 5 6 7 8; do
    EVENT_TEXT=$(c8y events list \
        --device "$RESOLVED_DEVICE_ID" \
        --type "dm_MoLookupTest" \
        --pageSize 10 --output json 2>/dev/null \
        | jq -rs '.[0].text // empty')
    [ -n "$EVENT_TEXT" ] && break
    sleep 3
done

if [ -z "$EVENT_TEXT" ]; then
    dm_warn "No dm_MoLookupTest event found for device ${RESOLVED_DEVICE_ID:-<none>}"
fi

dm_assert_eq "Event text contains MO-resolved device name and type" \
    "device=Sensor Berlin 03 type=c8y_Sensor" "$EVENT_TEXT"

dm_done "Inbound Smart Function Pattern 03 (getManagedObjectByExternalId)"
dm_print_summary
