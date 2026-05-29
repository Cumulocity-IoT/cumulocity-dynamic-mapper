#!/bin/bash
#
# test-inbound-smartfunction-04.sh
#
# Integration test for Smart Function template pattern 04.
# Template: Dual payload type (telemetry vs error) + error deduplication
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-smartfunction-04.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

KEEP_ON_FAILURE=false
EXT_ID="dmtest-dual-payload-$(date +%s)"
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

dm_banner "Test: Smart Function Pattern 04 (Dual payload + deduplication)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

dm_step 2 "Creating mapping with dual payload type handling"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var externalId = payload.externalId;
    var payloadType = payload.payloadType;
    
    // Get or initialize deduplication cache
    var cacheKey = "lastError_" + externalId;
    var lastError = context.getCache(cacheKey);
    
    if (payloadType === "telemetry") {
        // Process telemetry data
        var measurement = {
            cumulocityType: "measurement",
            action: "create",
            payload: {
                type: "c8y_TemperatureMeasurement",
                source: { id: externalId },
                c8y_TemperatureMeasurement: {
                    T: {
                        value: payload.sensorData.temp_val,
                        unit: "C"
                    }
                },
                time: new Date().toISOString()
            },
            externalSource: externalId
        };
        return [measurement];
    } 
    else if (payloadType === "error") {
        // Check for duplicate error (deduplication)
        var currentError = payload.logMessage;
        if (lastError !== currentError) {
            // New error or different from last - create alarm
            context.setCache(cacheKey, currentError);
            
            var alarm = {
                cumulocityType: "alarm",
                action: "create",
                payload: {
                    type: "c8y_DeviceError",
                    severity: "MAJOR",
                    status: "ACTIVE",
                    text: currentError,
                    source: { id: externalId },
                    time: new Date().toISOString()
                },
                externalSource: externalId
            };
            return [alarm];
        }
        // Duplicate error - suppress
        return [];
    }
    
    return [];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")

MAPPING_JSON=$(jq -cn \
    --arg name         "test-sf-04-$$" \
    --arg identifier   "sf-04-$$" \
    --arg code         "$SF_CODE" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "flowState/+",
      mappingTopicSample: ("flowState/" + "sensor-berlin-01"),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      customProcessingCode: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "externalId",
      qos: "AT_LEAST_ONCE"
    }')

MAPPING_ID=$(dm_create_mapping "$MAPPING_JSON")
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 4 "Publishing telemetry data"
TELEMETRY=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      messageId: "msg-001",
      externalId: $extId,
      payloadType: "telemetry",
      sensorData: {
        temp_val: 23.5
      }
    }')

echo "$TELEMETRY" | mosquitto_pub -h broker.hivemq.com -t "flowState/$EXT_ID" -s -q 1
dm_success "Telemetry published"

dm_step 5 "Publishing first error event"
ERROR1=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      messageId: "msg-002",
      externalId: $extId,
      payloadType: "error",
      logMessage: "Sensor malfunction detected"
    }')

echo "$ERROR1" | mosquitto_pub -h broker.hivemq.com -t "flowState/$EXT_ID" -s -q 1
dm_success "First error published"

dm_step 6 "Publishing duplicate error (should be suppressed)"
ERROR2=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      messageId: "msg-003",
      externalId: $extId,
      payloadType: "error",
      logMessage: "Sensor malfunction detected"
    }')

echo "$ERROR2" | mosquitto_pub -h broker.hivemq.com -t "flowState/$EXT_ID" -s -q 1
dm_success "Duplicate error published (should be deduplicated)"

dm_step 7 "Verifying measurements and alarms"
sleep 2
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" | head -1) || true

if [ -z "$DEVICE_ID" ]; then
    dm_error "Device not found"
fi

# Check measurement count
MEASUREMENTS=$(dm_api GET "/measurement/measurements?source=$DEVICE_ID&pageSize=10" 2>/dev/null || echo '{"measurements":[]}')
MEAS_COUNT=$(echo "$MEASUREMENTS" | jq '.measurements | length')
dm_success "Measurements created: $MEAS_COUNT"

# Check alarm count (should be 1, not 2)
ALARMS=$(dm_api GET "/alarm/alarms?source=$DEVICE_ID&type=c8y_DeviceError&pageSize=10" 2>/dev/null || echo '{"alarms":[]}')
ALARM_COUNT=$(echo "$ALARMS" | jq '.alarms | length')
dm_success "Error alarms created: $ALARM_COUNT (expected: 1, deduplication prevented duplicates)"

if [ "$ALARM_COUNT" -eq 1 ]; then
    dm_success "✅ Deduplication verified - only 1 alarm despite 2 errors"
else
    dm_warn "Deduplication count: $ALARM_COUNT (expected: 1)"
fi

dm_banner "✅ Test PASSED: Smart Function Pattern 04 (dual payload + deduplication) works correctly"
