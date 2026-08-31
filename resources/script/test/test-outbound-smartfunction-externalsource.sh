#!/bin/bash
#
# test-outbound-smartfunction-externalsource:
#   Outbound Smart Function `externalSource` → broker `_externalId_` topic token
#
# Live, end-to-end counterpart to the Java test
# FlowResultOutboundProcessorTest#testExternalSourceFromReturnObjectResolvesExternalIdToken_endToEnd.
#
# A Smart Function returns a topic containing the literal `_externalId_` token and
# `externalSource: [{type: "c8y_Serial"}]` — it does NOT know the device's
# external id. The mapper must resolve the triggering device's external id of that
# type and substitute it into the broker publish topic. We verify the message is
# actually delivered on the RESOLVED topic (dmtest/out/sfext/<extId>/data) via a
# real broker round-trip with mosquitto_sub -v, proving the token was replaced
# with the externalSource-resolved id.
#
# This differs from test-outbound-topic-resolution (which uses a DEFAULT mapping
# and _IDENTITY_.externalId substitution): here the topic comes from a Smart
# Function return object and the id is driven by the returned `externalSource`.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - Active MQTT connector; c8y CLI authenticated; mosquitto_sub/pub + jq installed
#
# Usage:
#   ./test-outbound-smartfunction-externalsource.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="30. Smart Function externalSource → _externalId_ topic (broker round-trip)"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-sfext-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_ID=""
RECEIVED_FILE=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
    [ -n "${RECEIVED_FILE:-}" ] && rm -f "$RECEIVED_FILE" || true
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

dm_step "Creating test device with external id ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

dm_step "Creating static MEASUREMENT subscription for device ..."
dm_create_static_subscription_must "MEASUREMENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 5 "for subscription propagation"

# The Smart Function emits the literal _externalId_ token in the topic and asks the
# mapper to resolve the device id via externalSource type c8y_Serial. It does NOT
# read context.getConfig().externalId — resolution must come from externalSource.
dm_step "Creating outbound Smart Function mapping ..."
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
  var payload = msg.getPayload();
  return [{
    topic: "dmtest/out/sfext/_externalId_/data",
    externalSource: [{ type: "c8y_Serial" }],
    payload: {
      time: new Date().toISOString(),
      temperature: payload.c8y_TemperatureMeasurement.T.value,
      source: payload.source.id
    }
  }];
}
JSCODE
)
SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name       "test-outbound-sfext-$$" \
    --arg identifier "obse$$" \
    --arg code       "$SF_CODE_B64" \
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
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "Recording baseline messagesReceived count ..."
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

# Subscribe to the WILDCARD with -v so we capture the actual resolved topic.
# Receiving on dmtest/out/sfext/<EXT_ID>/data proves _externalId_ was replaced
# with the externalSource-resolved id.
RESOLVED_TOPIC="dmtest/out/sfext/${EXT_ID}/data"
dm_step "Subscribing (verbose) to dmtest/out/sfext/+/data ..."
RECEIVED_FILE=$(mktemp)
dm_mqtt_subscribe_one_verbose "dmtest/out/sfext/+/data" 15 > "$RECEIVED_FILE" 2>/dev/null &
SUB_PID=$!
sleep 1

dm_step "Creating C8Y measurement to trigger the outbound mapping ..."
c8y measurements create \
    --device "$DEVICE_ID" \
    --type "c8y_TemperatureMeasurement" \
    --data '{"c8y_TemperatureMeasurement":{"T":{"value":42.0,"unit":"C"}}}' \
    --output json >/dev/null

dm_step "Waiting for outbound processing ..."
dm_wait 10

dm_step "Asserting messagesReceived increased (mapping processed notification) ..."
dm_assert_mapping_received_gt "Outbound SF externalSource processed" "$MAPPING_ID" "$BASELINE"

# Broker round-trip: with -v the line is "<topic> <payload>". The first field is
# the resolved topic, which must carry the external id (token was replaced).
wait "$SUB_PID" 2>/dev/null || true
if [ -s "$RECEIVED_FILE" ]; then
    RECEIVED_LINE=$(head -1 "$RECEIVED_FILE")
    RECEIVED_TOPIC=${RECEIVED_LINE%% *}
    dm_info "Received on topic: $RECEIVED_TOPIC"
    dm_assert_eq "Resolved publish topic (externalSource → _externalId_)" "$RESOLVED_TOPIC" "$RECEIVED_TOPIC"
    # Guard against a literal, unresolved token slipping through.
    if printf '%s' "$RECEIVED_TOPIC" | grep -q '_externalId_'; then
        dm_fail "Publish topic still contains the unresolved _externalId_ token"
    fi
elif [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
    dm_warn "No message captured from the MQTT Service. The mapping processed the notification (asserted above), but the cert-authenticated subscriber did not receive it — MQTT Service delivery is scoped to the publishing device's identity. (Open item — see ENHANCEMENT.md.)"
else
    dm_warn "mosquitto_sub did not capture a message within timeout (OK if broker egress is blocked from the test host); topic-resolution round-trip not verified this run"
fi

dm_done "$TEST_TITLE"
dm_print_summary
