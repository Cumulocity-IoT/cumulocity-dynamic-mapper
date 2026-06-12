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

SUBSCRIPTION_NAME=""
EXT_ID="dmtest-sf-out-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""
DEVICE_NAME="dmtest-sf-device-$RANDOM"

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ] && [ -n "${SUBSCRIPTION_NAME:-}" ]; then
        dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    fi
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    if [ -n "${DEVICE_NAME:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "Test: Outbound Smart Function (C8Y Measurement → MQTT JSON)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

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

dm_step "Creating static subscription for device"
# Without a notification subscription the outbound dispatcher never receives the
# device's measurement, so the Smart Function never runs and nothing is published.
_before_subs=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || printf '[]')
_before_names_json=$(printf '%s' "$_before_subs" | jq -c '
    def rows:
      if type == "array" then .
      elif type == "object" then
        if ((.subscriptions // null) != null and ((.subscriptions | type) == "array")) then .subscriptions
        elif ((.data // null) != null and ((.data | type) == "array")) then .data
        elif ((.subscription // .subscriptionName // .id // empty) | tostring | length) > 0 then [.]
        else [] end
      else [] end;
    rows
    | map(.subscription // .subscriptionName // .id // empty)
    | map(select(length > 0))
    | unique
' 2>/dev/null || printf '[]')
dm_create_static_subscription_must "MEASUREMENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 5 "for subscription propagation"
_after_subs=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || printf '[]')
_after_names_json=$(printf '%s' "$_after_subs" | jq -c '
    def rows:
      if type == "array" then .
      elif type == "object" then
        if ((.subscriptions // null) != null and ((.subscriptions | type) == "array")) then .subscriptions
        elif ((.data // null) != null and ((.data | type) == "array")) then .data
        elif ((.subscription // .subscriptionName // .id // empty) | tostring | length) > 0 then [.]
        else [] end
      else [] end;
    rows
    | map(.subscription // .subscriptionName // .id // empty)
    | map(select(length > 0))
    | unique
' 2>/dev/null || printf '[]')
SUBSCRIPTION_NAME=$(jq -nr \
    --argjson before "$_before_names_json" \
    --argjson after "$_after_names_json" \
    '($after - $before | .[0]) // ($after[0] // "")')

if [ -z "${SUBSCRIPTION_NAME:-}" ]; then
    dm_warn "Could not resolve created static subscription name; cleanup may skip explicit subscription deletion."
else
    dm_info "Resolved static subscription name for cleanup: $SUBSCRIPTION_NAME"
fi

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

BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

dm_step 6 "Subscribing to MQTT output topic"
MQTT_TOPIC="measurements/outbound/$EXT_ID"
dm_info "Subscribing to: $MQTT_TOPIC"

# Background MQTT subscriber (collects messages for verification).
# Uses the harness helper so MQTT_HOST/PORT/USER/PASS/TLS overrides apply.
TEMP_FILE=$(mktemp)
TEMP_ERR_FILE=$(mktemp)
( dm_mqtt_subscribe_one "$MQTT_TOPIC" 15 > "$TEMP_FILE" 2>"$TEMP_ERR_FILE" ) &
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

# /measurement/measurements is a core C8Y endpoint, not a Dynamic Mapper one —
# create it through the c8y CLI rather than dm_api (which prefixes DM_SERVICE).
printf '%s' "$MEASUREMENT" | c8y measurements create --template "input.value" \
    --output json > /dev/null 2>&1 || dm_warn "Measurement creation may have failed"
dm_success "Test measurement created"

dm_step 8 "Waiting for MQTT message"
# Block until the background subscriber gets a message (it exits on the first
# one via -C 1) or its 15s window elapses — don't read the temp file early.
set +e
wait "$MQTT_PID"
MQTT_SUB_RC=$?
set -e

dm_assert_eq "MQTT subscriber exit code" "0" "$MQTT_SUB_RC"

# Reliable signal: confirm the outbound mapping actually processed the measurement.
dm_assert_mapping_received_gt "Outbound mapping processed measurement" "$MAPPING_ID" "$BASELINE"

# Check if the transformed message was published to the broker.
MQTT_MSG=""
if [ -f "$TEMP_FILE" ] && [ -s "$TEMP_FILE" ]; then
    MQTT_MSG=$(head -1 "$TEMP_FILE")
    dm_info "MQTT message received: $MQTT_MSG"
fi
_received=false
[ -n "$MQTT_MSG" ] && _received=true
dm_assert_eq "Outbound MQTT message received" "true" "$_received"

_json_payload=false
if printf '%s' "$MQTT_MSG" | jq -e . >/dev/null 2>&1; then
  _json_payload=true
fi
dm_assert_eq "Outbound MQTT payload is valid JSON" "true" "$_json_payload"

# Verify transformed content
TEMP_VALUE=$(echo "$MQTT_MSG" | jq -r '.temperature // empty' 2>/dev/null || echo "")
dm_assert_eq "Transformed temperature value" "22.5" "$TEMP_VALUE"

# Cleanup
kill "$MQTT_PID" 2>/dev/null || true
rm -f "$TEMP_FILE"
rm -f "$TEMP_ERR_FILE"

dm_done "Outbound Smart Function"
dm_print_summary
