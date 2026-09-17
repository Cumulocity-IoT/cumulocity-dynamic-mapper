#!/bin/bash
#
# test-outbound-event: Outbound C8Y Event → MQTT broker
#
# Creates an outbound EVENT mapping, triggers a C8Y event, and verifies the
# mapping's messagesReceived count increased.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-event.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="23. C8Y Event → MQTT broker"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-evt-$(date +%s)"
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

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Creating test device ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

dm_step "Binding c8y_Serial external id ..."
c8y identity create \
  --name "$EXT_ID" \
  --type "c8y_Serial" \
  --device "$DEVICE_ID" \
  --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

dm_step "Creating static subscription for device ..."
dm_create_static_subscription_must "EVENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

dm_step "Creating outbound EVENT mapping ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-event-$$",
  "identifier": "evt$$",
  "mappingTopic": "dmtest/out/event",
  "mappingTopicSample": "dmtest/out/event",
  "publishTopic": "dmtest/out/event",
  "publishTopicSample": "dmtest/out/event",
  "targetAPI": "EVENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"text\":\"event text\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
  "targetTemplate": "{\"type\":\"c8y_TestEvent\",\"text\":\"hello\",\"deviceId\":\"source-id\"}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false}
  ],
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

dm_step "Creating C8Y event to trigger outbound notification ..."
c8y events create \
    --device "$DEVICE_ID" \
    --type "c8y_TestEvent" \
    --text "Outbound event test" \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 8

dm_step "Asserting messagesReceived increased ..."
dm_assert_mapping_received_gt "Outbound event processed" "$MAPPING_ID" "$BASELINE"

dm_done "$TEST_TITLE"
dm_print_summary
