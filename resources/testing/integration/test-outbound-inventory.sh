#!/bin/bash
#
# test-outbound-inventory: Outbound C8Y INVENTORY (managed-object change) → MQTT
#
# Verifies that a change to a device's *metadata* (its managed object) is picked
# up by an outbound INVENTORY mapping and forwarded to the broker. This is the
# outbound counterpart to test-inbound-inventory and the only outbound test that
# exercises targetAPI INVENTORY (managedobjects notifications).
#
# Flow:
#   1. Create a device, bind a c8y_Serial external id, and create a static
#      INVENTORY subscription for it.
#   2. Deploy an outbound INVENTORY mapping (managed object → broker JSON).
#   3. Record the mapping's baseline messagesReceived.
#   4. Update the device metadata in C8Y (PUT a custom fragment) to trigger a
#      managedobjects notification.
#   5. Assert the mapping processed it (messagesReceived increased).
#
# As with the other outbound tests we assert on the mapper's processing counter
# rather than a broker round-trip, so the test is independent of broker-side
# delivery specifics.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - An active MQTT connector; c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-inventory.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="26. C8Y managed-object change → MQTT broker (metadata)"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-inv-$(date +%s)"
DEVICE_TYPE="dmtest-out-inv-type"
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

dm_step "Creating static INVENTORY subscription for device ..."
dm_create_static_subscription_must "INVENTORY" "$DEVICE_ID" "$DEVICE_NAME"

dm_step "Waiting for subscription propagation ..."
dm_wait 5

dm_step "Creating outbound INVENTORY mapping ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-inv-$$",
  "identifier": "oinv$$",
  "mappingTopic": "dmtest/out/inventory",
  "mappingTopicSample": "dmtest/out/inventory",
  "publishTopic": "dmtest/out/inventory",
  "publishTopicSample": "dmtest/out/inventory",
  "targetAPI": "INVENTORY",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"type\":\"dmtest-out-inv-type\",\"name\":\"device name\",\"dmtest_Trigger\":{\"rev\":1}}",
  "targetTemplate": "{\"deviceId\":\"source-id\",\"deviceType\":\"a-type\",\"rev\":0}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"type","pathTarget":"deviceType","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"dmtest_Trigger.rev","pathTarget":"rev","repairStrategy":"DEFAULT","expandArray":false}
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

dm_step "Updating device metadata in C8Y to trigger an inventory notification ..."
c8y inventory update \
    --id "$DEVICE_ID" \
    --data '{"dmtest_Trigger":{"rev":42}}' \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 12

dm_step "Asserting messagesReceived increased ..."
dm_assert_mapping_received_gt "Outbound inventory change processed" "$MAPPING_ID" "$BASELINE"

dm_done "$TEST_TITLE"
dm_print_summary
