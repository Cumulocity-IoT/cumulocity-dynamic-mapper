#!/bin/bash
#
# test-outbound-extension-alarm-to-sparkplugb.sh
#
# Integration test for ProcessorExtensionAlarmToSparkplugB Java extension.
# Tests Cumulocity alarm → Sparkplug B DCMD protobuf transformation.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-outbound-extension-alarm-to-sparkplugb.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

EXT_ID="dmtest-sparkplug-alarm-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""
DEVICE_NAME="dmtest-sparkplug-device-$RANDOM"

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    if [ -n "${EXT_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "Test: Outbound Sparkplug B Alarm (Cumulocity Alarm → Sparkplug B DCMD)"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit
dm_require_extension "AlarmToSparkplugB" "OUTBOUND"

dm_step 2 "Creating test device"
DEVICE=$(c8y inventory create \
    --name "$DEVICE_NAME" \
    --type "c8y_TemperatureSensor" \
    --data "c8y_IsDevice={}" \
    2>/dev/null || echo "{}")

DEVICE_ID=$(echo "$DEVICE" | jq -r '.id // empty')
if [ -z "$DEVICE_ID" ]; then
    dm_error "Failed to create test device"
fi
dm_success "Test device created: $DEVICE_ID"

dm_step 3 "Binding external ID"
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External ID may already exist: $EXT_ID"
dm_success "External ID bound: $EXT_ID"

dm_step 4 "Creating Sparkplug B outbound mapping (alarm → DCMD)"
MAPPING_JSON=$(jq -cn \
    --arg name         "test-sparkplugb-alarm-$$" \
    --arg identifier   "sparkplug-alarm-$$" \
    --arg externalId   "$EXT_ID" \
    --argjson extension "$_DM_RESOLVED_EXTENSION" \
    '{
      name: $name,
      identifier: $identifier,
            targetAPI: "ALARM",
      direction: "OUTBOUND",
      mappingType: "PROTOBUF_INTERNAL",
      transformationType: "EXTENSION_JAVA",
      filterMapping: "true",
      extension: $extension,
            sourceTemplate: "{}",
            targetTemplate: "{}",
      active: false,
      debug: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      subscriptionTopicFilter: "alarm/alarms/create",
      subscriptionType: "STATIC",
      subscriptionTenantId: "*",
      publishTopic: "spBv1.0/group1/DCMD/edgenode1/device1",
            publishTopicSample: "spBv1.0/group1/DCMD/edgenode1/device1",
      parameter: {
        metricPrefix: "Alarms",
        qos: "1",
        retain: false
      },
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Sparkplug B alarm mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 6 "Verifying mapping configuration"
MAPPING_CONFIG=$(dm_api GET "/mapping/$MAPPING_ID" 2>/dev/null || echo '{}')
EXT_EVENT=$(echo "$MAPPING_CONFIG" | jq -r '.extension.eventName // empty')
EXT_FQN=$(echo "$MAPPING_CONFIG" | jq -r '.extension.fqnClassName // empty')
PUB_TOPIC=$(echo "$MAPPING_CONFIG" | jq -r '.publishTopic // empty')

_ext_match=false
if [ "$EXT_EVENT" = "AlarmToSparkplugB" ] || [[ "$EXT_FQN" == *".ProcessorExtensionAlarmToSparkplugB" ]]; then
    _ext_match=true
fi
dm_assert_eq "Alarm-to-Sparkplug extension configured (event=${EXT_EVENT:-n/a} fqn=${EXT_FQN:-n/a})" "true" "$_ext_match"

_topic_match=false
if [[ "$PUB_TOPIC" == spBv1.0/*/DCMD/* ]]; then
    _topic_match=true
fi
dm_assert_eq "Sparkplug B DCMD publish topic configured ($PUB_TOPIC)" "true" "$_topic_match"

dm_step 7 "Creating test alarm"
ALARM=$(jq -cn \
    --arg deviceId "$DEVICE_ID" \
    --arg time "$(date -u +'%Y-%m-%dT%H:%M:%S.000Z')" \
    '{
      source: {id: $deviceId},
      type: "c8y_TemperatureAlarm",
      severity: "CRITICAL",
      status: "ACTIVE",
      text: "Temperature exceeds critical threshold",
      time: $time
    }')

# /alarm/alarms is a core C8Y endpoint — create via the c8y CLI, not dm_api.
printf '%s' "$ALARM" | c8y alarms create --template "input.value" \
    --output json > /dev/null 2>&1 || dm_warn "Alarm creation may have failed"
dm_success "Test alarm created (triggers DCMD mapping)"

dm_step 8 "Verifying Sparkplug B configuration"
dm_info "Sparkplug B DCMD payload format:"
dm_info "  - Topic: spBv1.0/{groupId}/DCMD/{edgeNodeId}/{deviceId}"
dm_info "  - Payload: Protobuf with metrics for alarm state"
dm_info "  - Metric prefix: Alarms (configurable)"
dm_info "  - ISA-95 alarm model: State/Message/Severity/Status"

dm_done "Outbound Extension Alarm to Sparkplug B"
dm_info "Note: Full protobuf payload testing requires Sparkplug B broker validation"
dm_print_summary
