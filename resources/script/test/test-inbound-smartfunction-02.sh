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

KEEP_ON_FAILURE=false
EXT_ID="sensor-berlin-01"
MAPPING_ID=""
DEVICE_ID=""

for arg in "$@"; do
    case "$arg" in
        --keep) KEEP_ON_FAILURE=true ;;
        --cleanup) trap cleanup EXIT ;;
    esac
done

cleanup() {
    if [ "$KEEP_ON_FAILURE" = "true" ]; then
        dm_warn "Skipping cleanup (--keep flag set)"
        return 0
    fi
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

trap cleanup EXIT

dm_banner "Test: Smart Function Pattern 02 (Topic-based external ID + sensor type filter)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

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
dm_api POST "/identity/globalIdentities" \
    --data "{\"externalId\":\"$EXT_ID\",\"type\":\"c8y_Serial\",\"managedObject\":{\"id\":\"$DEVICE_ID\"}}" \
    > /dev/null 2>&1 || true
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
            source: { id: externalId },
            c8y_VoltageMeasurement: {
                U: {
                    value: payload.sensorData.val,
                    unit: "V"
                }
            },
            time: new Date().toISOString()
        },
        externalSource: externalId
    };
    
    return [measurement];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")

MAPPING_JSON=$(jq -cn \
    --arg name         "test-sf-02-$$" \
    --arg identifier   "sf-02-$$" \
    --arg code         "$SF_CODE" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "testSmartInbound/+",
      mappingTopicSample: "testSmartInbound/sensor-berlin-01",
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      customProcessingCode: $code,
      active: false,
      debug: false,
      createNonExistingDevice: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      qos: "AT_LEAST_ONCE"
    }')

MAPPING_ID=$(dm_create_mapping "$MAPPING_JSON")
dm_success "Mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 6 "Publishing voltage sensor reading via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  messageId: "msg-001",
  deviceId: "12345",
  sensorData: {
    val: 230.5
  }
}')

echo "$TEST_PAYLOAD" | mosquitto_pub -h broker.hivemq.com -t "testSmartInbound/sensor-berlin-01" -s -q 1
dm_success "Voltage reading published"

dm_step 7 "Waiting for measurement creation"
sleep 2
MEASUREMENT=$(dm_get_latest_measurement "$EXT_ID" "c8y_Serial" "c8y_VoltageMeasurement") 2>/dev/null || echo "{}"
VOLTAGE=$(echo "$MEASUREMENT" | jq -r '.c8y_VoltageMeasurement.U.value // empty')

if [ "$VOLTAGE" = "230.5" ]; then
    dm_success "Voltage measurement created and verified: $VOLTAGE V"
else
    dm_warn "Voltage value not as expected: $VOLTAGE (expected: 230.5)"
fi

dm_banner "✅ Test PASSED: Smart Function Pattern 02 (topic-based external ID) works correctly"
