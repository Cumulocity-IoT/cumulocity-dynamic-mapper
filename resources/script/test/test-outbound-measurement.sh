#!/bin/bash
#
# test-outbound-measurement: Outbound C8Y Measurement → MQTT broker
#
# Creates an outbound mapping for MEASUREMENT, creates a device with a static
# subscription, triggers a measurement in C8Y, and verifies the mapping
# processed the notification (messagesReceived increased).
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-measurement.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-mea-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
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
dm_banner "Outbound C8Y Measurement → MQTT Broker"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_connected_connector

dm_step "Creating test device ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

dm_step "Creating static subscription for device ..."
dm_api POST /subscription \
    "{\"api\": \"MEASUREMENT\", \"devices\": [{\"id\": \"${DEVICE_ID}\", \"name\": \"${DEVICE_NAME}\"}]}" \
    >/dev/null

dm_step "Waiting for subscription propagation ..."
dm_wait 5

dm_step "Creating outbound MEASUREMENT mapping ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-mea-$$",
  "identifier": "mea$$",
  "mappingTopic": "dmtest/out/measurement",
  "mappingTopicSample": "dmtest/out/measurement",
  "publishTopic": "dmtest/out/measurement",
  "publishTopicSample": "dmtest/out/measurement",
  "targetAPI": "MEASUREMENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "targetTemplate": "{\"temperature\":110,\"deviceId\":\"source-id\"}",
  "substitutions": [],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE",
  "snoopStatus": "NONE",
  "snoopedTemplates": []
}
EOF
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "Recording baseline messagesReceived count ..."
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

dm_step "Creating C8Y measurement to trigger outbound notification ..."
c8y measurements create \
    --device "$DEVICE_ID" \
    --type "c8y_TemperatureMeasurement" \
    --data '{"c8y_TemperatureMeasurement":{"T":{"value":42.0,"unit":"C"}}}' \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 12

dm_step "Asserting messagesReceived increased ..."
dm_assert_mapping_received_gt "Outbound measurement processed" "$MAPPING_ID" "$BASELINE"

dm_done "Outbound C8Y Measurement → MQTT Broker"
dm_print_summary
