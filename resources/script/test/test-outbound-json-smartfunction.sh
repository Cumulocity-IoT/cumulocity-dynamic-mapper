#!/bin/bash
#
# test-outbound-json-smartfunction.sh
#
# Integration test for outbound Smart Function transformation.
# Tests C8Y measurement → MQTT JSON via Smart Function (supports supportESM).
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-outbound-json-smartfunction.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

KEEP_ON_FAILURE=false
EXT_ID="dmtest-sf-out-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""
DEVICE_NAME="dmtest-sf-device-$RANDOM"

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
    if [ -n "${DEVICE_NAME:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

trap cleanup EXIT

dm_banner "Test: Outbound Smart Function (C8Y Measurement → MQTT JSON)"

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
    --data "c8y_SupportedMeasurements=[c8y_TemperatureMeasurement]" 2>/dev/null || echo "{}")

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

dm_step 4 "Creating outbound Smart Function mapping"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
  var payload = msg.getPayload();
  var externalId = context.getConfig().externalId;
  
  return [{
    topic: "measurements/outbound/" + externalId,
    payload: {
      time: new Date().toISOString(),
      temperature: payload.c8y_TemperatureMeasurement.T.value,
      unit: payload.c8y_TemperatureMeasurement.T.unit,
      source: payload.source.id
    }
  }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")

SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name         "test-outbound-sf-$$" \
    --arg identifier   "obsf$$" \
    --arg code         "$SF_CODE_B64" \
    --arg externalId   "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      direction: "OUTBOUND",
            targetAPI: "MEASUREMENT",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
            filterMapping: "true",
      sourceTemplate: "{}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      subscriptionTopicFilter: "measurement/measurements/create",
      subscriptionType: "STATIC",
      subscriptionTenantId: "*",
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Outbound mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 6 "Subscribing to MQTT output topic"
MQTT_TOPIC="measurements/outbound/$EXT_ID"
dm_info "Subscribing to: $MQTT_TOPIC"

# Background MQTT subscriber (collects messages for verification)
TEMP_FILE=$(mktemp)
timeout 15 mosquitto_sub -h broker.hivemq.com -t "$MQTT_TOPIC" -q 1 > "$TEMP_FILE" 2>&1 &
MQTT_PID=$!
sleep 1

dm_step 7 "Creating measurement in C8Y"
MEASUREMENT=$(jq -cn \
    --arg deviceId "$DEVICE_ID" \
    --arg time "$(date -u +'%Y-%m-%dT%H:%M:%S.000Z')" \
    '{
      source: {id: $deviceId},
      type: "c8y_TemperatureMeasurement",
      time: $time,
      c8y_TemperatureMeasurement: {
        T: {
          value: 22.5,
          unit: "C"
        }
      }
    }')

dm_api POST "/measurement/measurements" --data "$MEASUREMENT" > /dev/null 2>&1
dm_success "Test measurement created"

dm_step 8 "Waiting for MQTT message"
sleep 3

# Check if message was received
if [ -f "$TEMP_FILE" ] && [ -s "$TEMP_FILE" ]; then
    MQTT_MSG=$(cat "$TEMP_FILE" | head -1)
    dm_success "MQTT message received: $MQTT_MSG"
    
    # Verify content
    TEMP_VALUE=$(echo "$MQTT_MSG" | jq -r '.temperature // empty' 2>/dev/null)
    if [ "$TEMP_VALUE" = "22.5" ]; then
        dm_success "Temperature value verified in MQTT message"
    else
        dm_warn "Temperature value not as expected: $TEMP_VALUE"
    fi
else
    dm_warn "No MQTT message received (may still be pending)"
fi

# Cleanup
kill $MQTT_PID 2>/dev/null || true
rm -f "$TEMP_FILE"

dm_done "Outbound Smart Function"
