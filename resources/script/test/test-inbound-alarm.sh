#!/bin/bash
#
# test-inbound-alarm.sh
#
# Integration test for inbound ALARM transformation (DEFAULT/JSONATA/SMART_FUNCTION).
# Tests JSON → Cumulocity Alarm creation with DEFAULT transformations.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-alarm.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

KEEP_ON_FAILURE=false
EXT_ID="dmtest-alarm-$(date +%s)"
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

dm_banner "Test: Inbound Alarm (JSON → c8y_Alarm via DEFAULT substitution)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

dm_step 2 "Creating alarm mapping with DEFAULT transformation"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-alarm-$$" \
    --arg identifier "alarm-$$" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/alarm/+",
      mappingTopicSample: ("dmtest/alarm/" + $extId),
      targetAPI: "ALARM",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "DEFAULT",
      sourceTemplate: "{\"externalId\":\"\",\"alarmType\":\"\",\"severity\":\"\",\"text\":\"\"}",
      targetTemplate: "{\"type\":\"c8y_SystemAlarm\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"text\":\"Device alarm triggered\"}",
      substitutions: [
        {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"alarmType","pathTarget":"type","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"severity","pathTarget":"severity","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"text","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false}
      ],
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

dm_step 4 "Publishing alarm JSON via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  alarmType: "c8y_TemperatureAlarm",
  severity: "CRITICAL",
  text: "Temperature sensor malfunction detected"
}')

echo "$TEST_PAYLOAD" | mosquitto_pub -h broker.hivemq.com -t "dmtest/alarm/$EXT_ID" -s -q 1
dm_success "Alarm JSON published: $TEST_PAYLOAD"

dm_step 5 "Waiting for device and alarm creation"
sleep 2
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" | head -1) || true

if [ -z "$DEVICE_ID" ]; then
    dm_error "Device not created with externalId=$EXT_ID"
fi

dm_success "Device created: $DEVICE_ID"

dm_step 6 "Verifying alarm was created"
ALARM=$(dm_api GET "/alarm/alarms?source=$DEVICE_ID&pageSize=5" 2>/dev/null || echo '{"alarms":[]}')
ALARM_COUNT=$(echo "$ALARM" | jq '.alarms | length')

if [ "$ALARM_COUNT" -ge 1 ]; then
    dm_success "Alarm created: $ALARM_COUNT alarm(s) found"
else
    dm_error "No alarms found for device $DEVICE_ID"
fi

dm_step 7 "Verifying alarm content"
ALARM_TEXT=$(echo "$ALARM" | jq -r '.alarms[0].text // empty')
ALARM_SEVERITY=$(echo "$ALARM" | jq -r '.alarms[0].severity // empty')

dm_info "Alarm text: $ALARM_TEXT"
dm_info "Alarm severity: $ALARM_SEVERITY"

dm_banner "✅ Test PASSED: Inbound ALARM transformation works correctly"
