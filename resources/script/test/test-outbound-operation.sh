#!/bin/bash
#
# test-outbound-operation: Outbound C8Y Operation → MQTT broker
#
# Creates an outbound OPERATION mapping, creates a C8Y operation for a device,
# and verifies the mapping's messagesReceived count increased.
#
# Note: The Dynamic Mapper listens to CREATE operations only.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-operation.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-op-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
}

[[ "${1:-}" == "--cleanup" ]] && trap cleanup EXIT

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Outbound C8Y Operation → MQTT Broker"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

dm_step "Creating test device (with agent fragment for operations) ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

dm_step "Binding c8y_Serial external id ..."
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

# Operations require com_cumulocity_model_Agent fragment on the device
c8y inventory update --id "$DEVICE_ID" \
    --data '{"com_cumulocity_model_Agent":{}}' \
    --output json >/dev/null

dm_step "Creating static subscription for device ..."
dm_create_static_subscription_must "OPERATION" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

dm_step "Creating outbound OPERATION mapping ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-op-$$",
  "identifier": "op$$",
  "mappingTopic": "dmtest/out/operation",
  "mappingTopicSample": "dmtest/out/operation",
  "publishTopic": "dmtest/out/operation",
  "publishTopicSample": "dmtest/out/operation",
  "targetAPI": "OPERATION",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"description\":\"restart\",\"type\":\"maker_Vibration_Sensor\"}",
  "targetTemplate": "{\"command\":\"restart\"}",
  "substitutions": [],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "Recording baseline messagesReceived count ..."
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

dm_step "Creating C8Y operation for device ..."
c8y operations create \
    --device "$DEVICE_ID" \
    --description "Test restart operation" \
    --data '{"c8y_Restart":{}}' \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 8

dm_step "Asserting messagesReceived increased ..."
dm_assert_mapping_received_gt "Outbound operation processed" "$MAPPING_ID" "$BASELINE"

dm_done "Outbound C8Y Operation → MQTT Broker"
dm_print_summary
