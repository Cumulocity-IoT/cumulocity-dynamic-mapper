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

EXT_ID="dmtest-ext-alarm-$(date +%s)"
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

dm_banner "Test: Inbound Extension Custom Alarm (JSON → Cumulocity Alarm)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step 2 "Creating mapping with CustomAlarm extension"
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
      extension: {
        extensionName: "custom-alarm-extension",
        eventName: "CustomAlarm",
        fqnClassName: "dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomAlarm",
                extensionType: "EXTENSION_INBOUND",
        direction: "INBOUND"
      },
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

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active
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

dm_mqtt_publish "dmtest/ext/alarm/$EXT_ID" "$TEST_PAYLOAD" 1
dm_success "Alarm event published"

dm_step 6 "Waiting and verifying alarm creation"
# The mapper creates the device lazily; resolve it then poll for the alarm.
ALARM='[]'
ALARM_COUNT=0
for _attempt in 1 2 3 4 5; do
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" | head -1) || true
    if [ -n "${DEVICE_ID:-}" ]; then
        # c8y alarms list is a core C8Y endpoint (one JSON object per line) — slurp to count.
        ALARM=$(c8y alarms list --device "$DEVICE_ID" --type "c8y_TemperatureAlarm" \
            --pageSize 5 --output json 2>/dev/null || echo '[]')
        ALARM_COUNT=$(echo "$ALARM" | jq -s 'length')
        [ "$ALARM_COUNT" -ge 1 ] && break
    fi
    sleep 2
done
dm_assert_gt "Alarm created" "$ALARM_COUNT" 0

dm_step 7 "Verifying alarm content"
ALARM_TEXT=$(echo "$ALARM" | jq -rs '.[0].text // empty')
_text_match=false
[[ "$ALARM_TEXT" == *"Temperature"* ]] && _text_match=true
dm_assert_eq "Alarm text contains 'Temperature' ($ALARM_TEXT)" "true" "$_text_match"

dm_done "Inbound Extension Custom Alarm"
dm_print_summary
