#!/bin/bash
#
# test-outbound-smartfunction-molookup.sh
#
# Integration test for outbound Smart Function pattern: getManagedObjectByExternalId.
# Template: C8Y measurement notification → MQTT JSON enriched with MO properties
#           read at runtime via context.getManagedObjectByExternalId.
#
# A Smart Function receives the C8Y measurement notification, calls
# getManagedObjectByExternalId to look up the triggering device's managed object
# (name and type — both in the default inventory cache), and embeds those values
# in the published MQTT payload. The test verifies that the received broker message
# contains the correct device name and type, proving the MO lookup reached the
# function.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-outbound-smartfunction-molookup.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="30. Smart Function outbound: Pattern 03 — getManagedObjectByExternalId — MO enrichment"

SUBSCRIPTION_NAME=""
EXT_ID="dmtest-out-molookup-$(date +%s)"
DEVICE_NAME="Sensor Berlin Out 03"
MAPPING_ID=""
DEVICE_ID=""
TEMP_FILE=""
TEMP_ERR_FILE=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ] && [ -n "${SUBSCRIPTION_NAME:-}" ]; then
        dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    fi
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    [ -n "${TEMP_FILE:-}"     ] && [ -f "$TEMP_FILE"     ] && rm -f "$TEMP_FILE"
    [ -n "${TEMP_ERR_FILE:-}" ] && [ -f "$TEMP_ERR_FILE" ] && rm -f "$TEMP_ERR_FILE"
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating test device"
DEVICE=$(c8y inventory create \
    --name "$DEVICE_NAME" \
    --type "c8y_TemperatureSensor" \
    --data "c8y_IsDevice={}" \
    --data "c8y_SupportedMeasurements=[c8y_TemperatureMeasurement]" \
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

dm_step 4 "Creating static MEASUREMENT subscription for device"
# Without a notification subscription the outbound dispatcher never receives the
# device's measurement, so the Smart Function never runs and nothing is published.
dm_create_static_subscription_resolve_name "MEASUREMENT" "$DEVICE_ID" "$DEVICE_NAME" 5
SUBSCRIPTION_NAME="${_DM_LAST_SUBSCRIPTION_NAME:-}"

dm_step 5 "Creating outbound Smart Function mapping with getManagedObjectByExternalId"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload    = msg.getPayload();
    var externalId = context.getConfig().externalId;

    var device     = context.getManagedObjectByExternalId({ externalId: externalId, type: "c8y_Serial" });
    var deviceName = device ? device.name : "unknown";
    var deviceType = device ? device.type : "unknown";

    return [{
        topic: "dmtest/out/molookup/" + externalId,
        payload: {
            time:        new Date().toISOString(),
            temperature: payload.c8y_TemperatureMeasurement.T.value,
            unit:        payload.c8y_TemperatureMeasurement.T.unit,
            deviceName:  deviceName,
            deviceType:  deviceType
        }
    }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name       "test-out-sf-molookup-$$" \
    --arg identifier "obml$$" \
    --arg code       "$SF_CODE_B64" \
    --arg externalId "$EXT_ID" \
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

dm_step 6 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

dm_step 7 "Subscribing to MQTT output topic"
MQTT_TOPIC="dmtest/out/molookup/$EXT_ID"
dm_info "Subscribing to: $MQTT_TOPIC"

TEMP_FILE=$(mktemp)
TEMP_ERR_FILE=$(mktemp)
dm_mqtt_probe_subscription "$MQTT_TOPIC" 10 || true
( dm_mqtt_subscribe_one "$MQTT_TOPIC" 15 > "$TEMP_FILE" 2>"$TEMP_ERR_FILE" ) &
MQTT_PID=$!
sleep 1

dm_step 8 "Creating C8Y measurement to trigger outbound mapping"
c8y measurements create \
    --device "$DEVICE_ID" \
    --type "c8y_TemperatureMeasurement" \
    --data '{"c8y_TemperatureMeasurement":{"T":{"value":42.0,"unit":"C"}}}' \
    --output json >/dev/null 2>&1 || dm_warn "Measurement creation may have failed"
dm_success "Test measurement created"

dm_step 9 "Waiting for MQTT message"
set +e
wait "$MQTT_PID"
MQTT_SUB_RC=$?
set -e

if [ "$MQTT_SUB_RC" -ne 0 ]; then
    dm_warn "mosquitto_sub exited $MQTT_SUB_RC; stderr: $(tr '\n' ' ' < "$TEMP_ERR_FILE" 2>/dev/null | head -c 400)"
fi
dm_assert_eq "MQTT subscriber exit code" "0" "$MQTT_SUB_RC"

dm_assert_mapping_received_gt "Outbound mapping processed measurement" "$MAPPING_ID" "$BASELINE"

MQTT_MSG=""
if [ -f "$TEMP_FILE" ] && [ -s "$TEMP_FILE" ]; then
    MQTT_MSG=$(head -1 "$TEMP_FILE")
    dm_info "MQTT message received: $MQTT_MSG"
fi
_received=false
[ -n "$MQTT_MSG" ] && _received=true
dm_assert_eq "Outbound MQTT message received" "true" "$_received"

_json_ok=false
if printf '%s' "$MQTT_MSG" | jq -e . >/dev/null 2>&1; then
    _json_ok=true
fi
dm_assert_eq "Outbound MQTT payload is valid JSON" "true" "$_json_ok"

TEMP_VALUE_NUM=$(printf '%s' "$MQTT_MSG" | jq -r '.temperature // empty | tonumber? // empty' 2>/dev/null || echo "")
dm_assert_num_eq "Transformed temperature value" "42.0" "$TEMP_VALUE_NUM" 1

RESOLVED_NAME=$(printf '%s' "$MQTT_MSG" | jq -r '.deviceName // empty' 2>/dev/null || echo "")
dm_assert_eq "MO-resolved device name in MQTT payload" "$DEVICE_NAME" "$RESOLVED_NAME"

RESOLVED_TYPE=$(printf '%s' "$MQTT_MSG" | jq -r '.deviceType // empty' 2>/dev/null || echo "")
dm_assert_eq "MO-resolved device type in MQTT payload" "c8y_TemperatureSensor" "$RESOLVED_TYPE"

kill "$MQTT_PID" 2>/dev/null || true
rm -f "$TEMP_FILE" "$TEMP_ERR_FILE"

dm_done "$TEST_TITLE"
dm_print_summary
