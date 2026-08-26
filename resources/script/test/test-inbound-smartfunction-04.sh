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

TEST_TITLE="15. Inbound: Pattern 04: Dual payload type + deduplication"

EXT_ID="dmtest-dual-payload-$(date +%s)"
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

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating mapping with dual payload type handling"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var externalId = payload.externalId;
    var payloadType = payload.payloadType;
    
    // Get or initialize deduplication state (persisted across invocations
    // via the FlowStateStore). getState returns undefined for an unset key.
    var cacheKey = "lastError_" + externalId;
    var lastError = context.getState(cacheKey);
    
    if (payloadType === "telemetry") {
        // Process telemetry data
        var measurement = {
            cumulocityType: "measurement",
            action: "create",
            payload: {
                type: "c8y_TemperatureMeasurement",
                c8y_TemperatureMeasurement: {
                    T: {
                        value: payload.sensorData.temp_val,
                        unit: "C"
                    }
                },
                time: new Date().toISOString()
            },
            externalSource: [{ type: "c8y_Serial", externalId: externalId }]
        };
        return [measurement];
    } 
    else if (payloadType === "error") {
        // Check for duplicate error (deduplication)
        var currentError = payload.logMessage;
        if (lastError !== currentError) {
            // New error or different from last - create alarm
            context.setState(cacheKey, currentError);
            
            var alarm = {
                cumulocityType: "alarm",
                action: "create",
                payload: {
                    type: "c8y_DeviceError",
                    severity: "MAJOR",
                    status: "ACTIVE",
                    text: currentError,
                    time: new Date().toISOString()
                },
                externalSource: [{ type: "c8y_Serial", externalId: externalId }]
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

SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name         "test-sf-04-$$" \
    --arg identifier   "sf-04-$$" \
    --arg code         "$SF_CODE_B64" \
    --arg extId        "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "flowState/+",
      mappingTopicSample: ("flowState/" + $extId),
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
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "externalId",
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active
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

dm_mqtt_publish "flowState/$EXT_ID" "$TELEMETRY" 1
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

dm_mqtt_publish "flowState/$EXT_ID" "$ERROR1" 1
dm_success "First error published"

# Wait for first measurement to be created, ensuring the mapping is processing
# messages. This also gives the dedup state time to persist before the duplicate arrives.
dm_wait_for_measurement_count "$EXT_ID" "c8y_Serial" 1 10 1 >/dev/null || true

dm_step 6 "Publishing duplicate error (should be suppressed)"
ERROR2=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      messageId: "msg-003",
      externalId: $extId,
      payloadType: "error",
      logMessage: "Sensor malfunction detected"
    }')

dm_mqtt_publish "flowState/$EXT_ID" "$ERROR2" 1
dm_success "Duplicate error published (should be deduplicated)"

dm_step 7 "Verifying measurements and alarms"
dm_assert_measurement_present "Telemetry measurement created" "$EXT_ID" "c8y_Serial" 1 15
dm_assert_alarm_present "Error alarm present" "$EXT_ID" "c8y_Serial" 1 15

# Post-check: verify dedup worked (exactly 1 alarm, not 2)
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
if [ -n "$DEVICE_ID" ]; then
    ALARM_COUNT=$(c8y alarms list --device "$DEVICE_ID" --type "c8y_DeviceError" \
        --pageSize 10 --output json 2>/dev/null | jq -s 'length')
    dm_assert_eq "Error alarm deduplicated to single alarm" "1" "${ALARM_COUNT:-0}"
fi

dm_done "$TEST_TITLE"
dm_print_summary
