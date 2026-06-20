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

EXT_ID="dmtest-alarm-$(date +%s)"
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

dm_banner "Test: Inbound Alarm (JSON → c8y_Alarm via DEFAULT substitution)"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

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
      sourceTemplate: "{\"externalId\":\"\",\"alarmType\":\"\",\"severity\":\"\",\"text\":\"\",\"time\":\"\"}",
            targetTemplate: "{\"type\":\"c8y_SystemAlarm\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"text\":\"Device alarm triggered\",\"time\":\"1970-01-01T00:00:00Z\"}",
      substitutions: [
        {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"alarmType","pathTarget":"type","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"severity","pathTarget":"severity","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"text","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"time","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
      ],
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
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

dm_step 4 "Publishing alarm JSON via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  alarmType: "c8y_TemperatureAlarm",
  severity: "CRITICAL",
  text: "Temperature sensor malfunction detected",
  time: (now | todate)
}')

dm_mqtt_publish "dmtest/alarm/$EXT_ID" "$TEST_PAYLOAD" 1
dm_success "Alarm JSON published"

dm_step 5 "Waiting for device and alarm creation"
if dm_wait_for_device_by_ext_id "$EXT_ID" "c8y_Serial" 20 2; then
  DEVICE_ID="$_DM_LAST_DEVICE_ID"
else
  DEVICE_ID=""
fi

if [ -z "$DEVICE_ID" ]; then
    dm_error "Device not created with externalId=$EXT_ID"
fi

dm_success "Device created: $DEVICE_ID"

dm_step 6 "Verifying alarm was created"
dm_assert_alarm_present "Alarm created" "$EXT_ID" "c8y_Serial" 1 20
ALARM=$(c8y alarms list --device "$DEVICE_ID" --pageSize 5 --output json 2>/dev/null || echo '[]')

dm_step 7 "Verifying alarm content"
ALARM_TEXT=$(echo "$ALARM" | jq -rs '.[0].text // empty')
ALARM_SEVERITY=$(echo "$ALARM" | jq -rs '.[0].severity // empty')
dm_info "Alarm text: $ALARM_TEXT"
dm_info "Alarm severity: $ALARM_SEVERITY"
dm_assert_eq "Alarm severity" "CRITICAL" "$ALARM_SEVERITY"

dm_done "Inbound ALARM Transformation"
dm_print_summary
