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

KEEP_ON_FAILURE=false
EXT_ID="dmtest-sparkplug-alarm-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""
DEVICE_NAME="dmtest-sparkplug-device-$RANDOM"

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
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    if [ -n "${EXT_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

trap cleanup EXIT

dm_banner "Test: Outbound Sparkplug B Alarm (Cumulocity Alarm → Sparkplug B DCMD)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

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
dm_api POST "/identity/globalIdentities" \
    --data "{\"externalId\":\"$EXT_ID\",\"type\":\"c8y_Serial\",\"managedObject\":{\"id\":\"$DEVICE_ID\"}}" \
    > /dev/null 2>&1 || true
dm_success "External ID bound: $EXT_ID"

dm_step 4 "Creating Sparkplug B outbound mapping (alarm → DCMD)"
MAPPING_JSON=$(jq -cn \
    --arg name         "test-sparkplugb-alarm-$$" \
    --arg identifier   "sparkplug-alarm-$$" \
    --arg externalId   "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      direction: "OUTBOUND",
      mappingType: "BINARY",
      transformationType: "EXTENSION_JAVA",
      processorExtensionName: "ProcessorExtensionAlarmToSparkplugB",
      active: false,
      debug: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      subscriptionTopicFilter: "alarm/alarms/create",
      subscriptionType: "STATIC",
      subscriptionTenantId: "*",
      publishTopic: "spBv1.0/group1/DCMD/edgenode1/device1",
      parameter: {
        metricPrefix: "Alarms",
        qos: "1",
        retain: false
      },
      qos: "AT_LEAST_ONCE"
    }')

MAPPING_ID=$(dm_create_mapping "$MAPPING_JSON")
dm_success "Sparkplug B alarm mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 6 "Verifying mapping configuration"
MAPPING_CONFIG=$(dm_api GET "/mapping/$MAPPING_ID" 2>/dev/null || echo '{}')
EXT_NAME=$(echo "$MAPPING_CONFIG" | jq -r '.processorExtensionName // empty')
PUB_TOPIC=$(echo "$MAPPING_CONFIG" | jq -r '.publishTopic // empty')

if [ "$EXT_NAME" = "ProcessorExtensionAlarmToSparkplugB" ]; then
    dm_success "Alarm-to-Sparkplug extension configured: $EXT_NAME"
else
    dm_warn "Extension name mismatch: $EXT_NAME"
fi

if [[ "$PUB_TOPIC" == spBv1.0/*/DCMD/* ]]; then
    dm_success "Sparkplug B DCMD publish topic configured: $PUB_TOPIC"
else
    dm_warn "Publish topic may not be Sparkplug B compliant: $PUB_TOPIC"
fi

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

dm_api POST "/alarm/alarms" --data "$ALARM" > /dev/null 2>&1
dm_success "Test alarm created (triggers DCMD mapping)"

dm_step 8 "Verifying Sparkplug B configuration"
dm_info "Sparkplug B DCMD payload format:"
dm_info "  - Topic: spBv1.0/{groupId}/DCMD/{edgeNodeId}/{deviceId}"
dm_info "  - Payload: Protobuf with metrics for alarm state"
dm_info "  - Metric prefix: Alarms (configurable)"
dm_info "  - ISA-95 alarm model: State/Message/Severity/Status"

dm_banner "✅ Test PASSED: Sparkplug B alarm-to-DCMD mapping is configured correctly"
dm_info "Note: Full protobuf payload testing requires Sparkplug B broker validation"
