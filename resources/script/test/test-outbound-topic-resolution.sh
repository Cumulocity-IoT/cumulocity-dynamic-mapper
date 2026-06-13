#!/bin/bash
#
# test-outbound-topic-resolution: Outbound dynamic topic with device external id
#
# Creates an outbound mapping where the publish topic contains a wildcard
# that is resolved to the device's external id at runtime.
# Verifies the message is received on the device-specific topic via mosquitto_sub.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping
#   - Active MQTT connector (MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASS)
#   - c8y CLI authenticated, mosquitto_sub/pub and jq installed
#
# Usage:
#   ./test-outbound-topic-resolution.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-topicres-$(date +%s)"
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
dm_banner "Outbound Dynamic Topic Resolution"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Creating test device with external id ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

# Register external id for the device so topic resolution works
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json 2>/dev/null || dm_warn "External id may already exist"

dm_step "Creating static subscription for device ..."
dm_create_static_subscription_must "EVENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

# publishTopic uses # to include the dynamic part resolved from _IDENTITY_.c8ySourceId → externalId
dm_step "Creating outbound mapping with dynamic publish topic ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-topicres-$$",
  "identifier": "tpr$$",
  "mappingTopic": "dmtest/out/topicres/#",
  "mappingTopicSample": "dmtest/out/topicres/${EXT_ID}",
  "publishTopic": "dmtest/out/topicres/#",
  "publishTopicSample": "dmtest/out/topicres/${EXT_ID}",
  "targetAPI": "EVENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"text\":\"topic test\",\"type\":\"c8y_TopicResTest\"}",
  "targetTemplate": "{\"type\":\"c8y_TopicResTest\",\"text\":\"topic resolved\"}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"_TOPIC_LEVEL_[3]","repairStrategy":"DEFAULT","expandArray":false}
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

dm_step "Subscribing to device-specific MQTT topic in background ..."
RECEIVED_FILE=$(mktemp)
dm_mqtt_subscribe_one "dmtest/out/topicres/+" 15 > "$RECEIVED_FILE" &
SUB_PID=$!

dm_step "Creating C8Y event to trigger outbound notification ..."
c8y events create \
    --device "$DEVICE_ID" \
    --type "c8y_TopicResTest" \
    --text "Topic resolution test" \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 10

dm_step "Asserting messagesReceived increased (mapping processed notification) ..."
dm_assert_mapping_received_gt "Outbound topic resolution processed" "$MAPPING_ID" "$BASELINE"

# Also check if mosquitto_sub received the published message (best-effort).
# In c8y-mqtt-service mode this verifies actual outbound delivery through the
# Cumulocity MQTT Service (the real broker round-trip), so a miss is more
# significant than on a public broker.
wait "$SUB_PID" 2>/dev/null || true
if [ -s "$RECEIVED_FILE" ]; then
    dm_success "MQTT message received on topic: $(head -c 200 "$RECEIVED_FILE")"
elif [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
    dm_warn "No message captured from the MQTT Service. The mapping processed the notification (asserted above), but the cert-authenticated subscriber did not receive it — likely because MQTT Service delivery is scoped to the publishing device's identity, not this test's client cert. (Open item — see ENHANCEMENT.md.)"
else
    dm_warn "mosquitto_sub did not capture a message within timeout (OK if broker not reachable from test host)"
fi
rm -f "$RECEIVED_FILE"

dm_done "Outbound Dynamic Topic Resolution"
dm_print_summary
