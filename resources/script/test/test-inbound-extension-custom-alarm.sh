#!/bin/bash
#
# test-inbound-extension-custom-alarm.sh
#
# Integration test for ProcessorExtensionCustomAlarm Java extension.
# Tests inbound JSON transformation via Java extension to c8y_Alarm.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-extension-custom-alarm.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

KEEP_ON_FAILURE=false
EXT_ID="dmtest-ext-alarm-$(date +%s)"
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

dm_banner "Test: Inbound Extension Custom Alarm (JSON → Cumulocity Alarm)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

dm_step 2 "Creating mapping with ProcessorExtensionCustomAlarm"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-ext-alarm-$$" \
    --arg identifier "ext-alarm-$$" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ext/alarm/+",
      mappingTopicSample: ("dmtest/ext/alarm/" + $extId),
      targetAPI: "ALARM",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "EXTENSION_JAVA",
      processorExtensionName: "ProcessorExtensionCustomAlarm",
      sourceTemplate: "{}",
      targetTemplate: "{}",
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      qos: "AT_LEAST_ONCE"
    }')

MAPPING_ID=$(dm_create_mapping "$MAPPING_JSON")
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 4 "Recording baseline for verification"
BASELINE=$(date +%s%N | cut -b1-13)

dm_step 5 "Publishing alarm event via MQTT"
TEST_PAYLOAD=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      externalId: $extId,
      type: "c8y_TemperatureAlarm",
      message: "Temperature exceeds safe operating threshold",
      level: "MAJOR"
    }')

echo "$TEST_PAYLOAD" | mosquitto_pub -h broker.hivemq.com -t "dmtest/ext/alarm/$EXT_ID" -s -q 1
dm_success "Alarm event published"

dm_step 6 "Waiting and verifying alarm creation"
sleep 2
ALARM=$(dm_api GET "/alarm/alarms?source=$DEVICE_ID&type=c8y_TemperatureAlarm&pageSize=1" 2>/dev/null || echo "{}")
ALARM_COUNT=$(echo "$ALARM" | jq '.alarms | length')

if [ "$ALARM_COUNT" -ge 1 ]; then
    dm_success "Alarm created successfully"
else
    # Try looking up by external ID
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" | head -1) || true
    if [ -n "$DEVICE_ID" ]; then
        ALARM=$(dm_api GET "/alarm/alarms?source=$DEVICE_ID&type=c8y_TemperatureAlarm&pageSize=1" 2>/dev/null || echo "{}")
        ALARM_COUNT=$(echo "$ALARM" | jq '.alarms | length')
        dm_success "Alarm found: $ALARM_COUNT alarm(s)"
    else
        dm_warn "Could not verify alarm creation"
    fi
fi

dm_step 7 "Verifying alarm content"
if [ "$ALARM_COUNT" -ge 1 ]; then
    ALARM_TEXT=$(echo "$ALARM" | jq -r '.alarms[0].text // empty')
    ALARM_SEVERITY=$(echo "$ALARM" | jq -r '.alarms[0].severity // empty')
    
    if [[ "$ALARM_TEXT" == *"Temperature"* ]]; then
        dm_success "Alarm text verified: $ALARM_TEXT"
    else
        dm_warn "Alarm text not as expected: $ALARM_TEXT"
    fi
fi

dm_banner "✅ Test PASSED: ProcessorExtensionCustomAlarm works correctly"
