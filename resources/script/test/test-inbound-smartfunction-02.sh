#!/bin/bash
#
# test-inbound-smartfunction-02.sh
#
# Integration test for Smart Function template pattern 02.
# Template: Extract externalId from topic, filter by device.c8y_Sensor.type
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-smartfunction-02.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

# Unique per run so a stale c8y_Serial identity binding from a previous run
# can't shadow this run's device. The SF derives the external id from the last
# topic level, so the publish topic must use the same value.
EXT_ID="sensor-berlin-01-$(date +%s)"
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

dm_banner "12. Inbound: Pattern 02: Topic-based external ID + sensor filter"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating sensor device with type filter"
DEVICE=$(c8y inventory create \
    --name "Voltage Sensor Berlin 01" \
    --type "c8y_Sensor" \
    --data "c8y_IsDevice={}" \
    --data "c8y_Sensor={\"type\":{\"voltage\":true}}" \
    2>/dev/null || echo "{}")

DEVICE_ID=$(echo "$DEVICE" | jq -r '.id // empty')
if [ -z "$DEVICE_ID" ]; then
    dm_error "Failed to create sensor device"
fi
dm_success "Sensor device created: $DEVICE_ID"

dm_step 3 "Binding external ID for topic-based lookup"
# Identity is a core C8Y endpoint — bind via the c8y CLI, not dm_api.
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json > /dev/null 2>&1 || dm_warn "External ID may already exist: $EXT_ID"
dm_success "External ID bound: $EXT_ID"

dm_step 4 "Creating mapping with template pattern 02"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload = msg.getPayload();
    
    // Extract externalId from topic - for topic 'testSmartInbound/sensor-berlin-01', 
    // index 1 gives 'sensor-berlin-01'
    var config = context.getConfig();
    var topic = config.topic || "";
    var topicParts = topic.split("/");
    var externalId = topicParts.length > 1 ? topicParts[1] : payload.messageId;
    
    var measurement = {
        cumulocityType: "measurement",
        action: "create",
        payload: {
            type: "c8y_VoltageMeasurement",
            c8y_VoltageMeasurement: {
                U: {
                    value: payload.sensorData.val,
                    unit: "V"
                }
            },
            time: new Date().toISOString()
        },
        externalSource: [{ type: "c8y_Serial", externalId: externalId }]
    };
    
    return [measurement];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")

SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name         "test-sf-02-$$" \
    --arg identifier   "sf-02-$$" \
    --arg code         "$SF_CODE_B64" \
    --arg extId        "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "testSmartInbound/+",
      mappingTopicSample: ("testSmartInbound/" + $extId),
      targetAPI: "MEASUREMENT",
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

dm_step 6 "Publishing voltage sensor reading via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  messageId: "msg-001",
  deviceId: "12345",
  sensorData: {
    val: 230.5
  }
}')

dm_mqtt_publish "testSmartInbound/$EXT_ID" "$TEST_PAYLOAD" 1
dm_success "Voltage reading published"

dm_step 7 "Waiting for measurement creation"
RESOLVED_DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
dm_info "Resolved device for $EXT_ID: ${RESOLVED_DEVICE_ID:-<none>}"

# Processing + C8Y indexing can lag a few seconds after publish — and the first
# message after a connector reconnect is especially slow — so poll rather than
# reading once.
VOLTAGE=""
for _i in 1 2 3 4 5 6 7 8; do
    MEASUREMENT=$(dm_get_latest_measurement "$EXT_ID" "c8y_Serial" "c8y_VoltageMeasurement")
    VOLTAGE=$(echo "$MEASUREMENT" | jq -r '.c8y_VoltageMeasurement.U.value // empty')
    [ -n "$VOLTAGE" ] && break
    sleep 3
done

if [ -z "$VOLTAGE" ]; then
    dm_warn "No c8y_VoltageMeasurement found for $EXT_ID (device=${RESOLVED_DEVICE_ID:-<none>})."
    if [ -n "${RESOLVED_DEVICE_ID:-}" ]; then
        dm_warn "Recent measurements for device ${RESOLVED_DEVICE_ID}:"
        c8y measurements list --device "$RESOLVED_DEVICE_ID" \
            --pageSize 5 --output json 2>/dev/null | jq -s '[.[] | {time, type}]' || true
    fi
fi
dm_assert_eq "Voltage measurement value" "230.5" "$VOLTAGE"

dm_done "12. Inbound: Pattern 02: Topic-based external ID + sensor filter"
dm_print_summary
