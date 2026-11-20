#!/bin/bash
#
# test-outbound-extension-anypayload.sh
#
# Integration test for an outbound mapping of type ANY_PAYLOAD with a Java extension
# (ProcessorExtensionSmartOutbound07, event "OperationToTextCommand").
# A Cumulocity operation carries a device command; the extension returns it as a RAW TEXT string
# (a Queclink "AT+GTUPD=...$"). The broker must receive the exact bytes: no quotes and no JSON
# wrapping. The payload is compared as hex (mosquitto_sub -F %x), so any added quote, newline or
# re-encoding fails the test.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated,
#                mosquitto_sub, jq, and the dynamic-mapper-extension package deployed with the
#                OperationToTextCommand extension (the test is skipped if it is missing).
# Usage: ./test-outbound-extension-anypayload.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Any Payload outbound Java extension: Operation → raw text"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-anyp-ext-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_ID=""
TEXT_FILE=""
ERR_FILE=""
TEXT_PID=""

# A command that would be damaged by any JSON handling: it ends with "$", has commas and a URL.
# The extension adds the "AT+" prefix.
COMMAND_BODY='GTUPD=gv300,0,,,http://example.com/fw.bin,,,$'
EXPECTED_TEXT="AT+${COMMAND_BODY}"

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$TEXT_PID" ] && kill "$TEXT_PID" 2>/dev/null || true
    [ -n "$TEXT_FILE" ] && rm -f "$TEXT_FILE" || true
    [ -n "$ERR_FILE" ] && rm -f "$ERR_FILE" || true
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    if [ -n "${EXT_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    fi
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
    dm_info "Cleanup complete"
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit
dm_require_extension "OperationToTextCommand" "OUTBOUND"

dm_step 2 "Creating test device (with agent fragment for operations)"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"
# Operations require the com_cumulocity_model_Agent fragment on the device
c8y inventory update --id "$DEVICE_ID" \
    --data '{"com_cumulocity_model_Agent":{}}' \
    --output json >/dev/null

dm_step 3 "Creating static OPERATION subscription for the device"
dm_create_static_subscription_must "OPERATION" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

dm_step 4 "Creating the outbound ANY_PAYLOAD Java extension mapping"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-outbound-anyp-ext-$$" \
    --arg identifier "obape$$" \
    --argjson extension "$_DM_RESOLVED_EXTENSION" \
    '{
      name: $name,
      identifier: $identifier,
      publishTopic: "command/+",
      publishTopicSample: "command/berlin_01",
      targetAPI: "OPERATION",
      direction: "OUTBOUND",
      mappingType: "ANY_PAYLOAD",
      transformationType: "EXTENSION_JAVA",
      filterMapping: "$exists(c8y_Command)",
      extension: $extension,
      sourceTemplate: "{}",
      targetTemplate: "{}",
      substitutions: [],
      active: false,
      debug: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Outbound ANY_PAYLOAD Java extension mapping created: $MAPPING_ID"

dm_step 5 "Deploying and activating the mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step 6 "Verifying the mapping configuration"
MAPPING_CONFIG=$(dm_api GET "/mapping/$MAPPING_ID" 2>/dev/null || echo '{}')
EXT_EVENT=$(echo "$MAPPING_CONFIG" | jq -r '.extension.eventName // empty')
EXT_FQN=$(echo "$MAPPING_CONFIG" | jq -r '.extension.fqnClassName // empty')
_ext_match=false
if [ "$EXT_EVENT" = "OperationToTextCommand" ] || [[ "$EXT_FQN" == *".ProcessorExtensionSmartOutbound07" ]]; then
    _ext_match=true
fi
dm_assert_eq "Text-command extension configured (event=${EXT_EVENT:-n/a} fqn=${EXT_FQN:-n/a})" "true" "$_ext_match"
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")
dm_info "Baseline messagesReceived=$BASELINE"

TEXT_TOPIC="command/$EXT_ID"

# In C8Y MQTT Service mode the connector and mosquitto_sub share one certificate CN (one connection
# allowed), so a subscriber would disconnect the connector. Only messagesReceived is verified there.
_SKIP_MQTT_CHECK=false
if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
    dm_info "MQTT Service mode: skipping mosquitto_sub (cert-CN conflict); will verify via messagesReceived only"
    _SKIP_MQTT_CHECK=true
fi

TEXT_FILE=$(mktemp)
ERR_FILE=$(mktemp)

dm_step 7 "Subscribing to the output topic"
if [ "$_SKIP_MQTT_CHECK" = "false" ]; then
    # Hex output: the exact bytes of the payload, whatever it contains.
    ( dm_mqtt_subscribe_one_hex "$TEXT_TOPIC" 20 > "$TEXT_FILE" 2>>"$ERR_FILE" ) &
    TEXT_PID=$!
    sleep 2
fi

dm_step 8 "Creating the operation that carries the command"
c8y operations create \
    --device "$DEVICE_ID" \
    --description "Any Payload command (Java extension)" \
    --data "$(jq -cn --arg text "$COMMAND_BODY" '{c8y_Command:{text:$text}}')" \
    --output json >/dev/null
dm_success "Operation created"

dm_step 9 "Verifying the outbound result"
if [ "$_SKIP_MQTT_CHECK" = "true" ]; then
    dm_wait 8 "outbound notification processing"
    dm_assert_mapping_received_gt "Outbound mapping processed the operation" "$MAPPING_ID" "$BASELINE"
    dm_info "MQTT Service mode: payload content check skipped (cert-CN conflict with connector)"
else
    set +e
    wait "$TEXT_PID"; TEXT_RC=$?
    set -e
    TEXT_PID=""
    if [ "$TEXT_RC" -ne 0 ]; then
        dm_warn "mosquitto_sub exit code $TEXT_RC; stderr: $(tr '\n' ' ' < "$ERR_FILE" 2>/dev/null | head -c 400)"
    fi
    dm_assert_eq "Text subscriber exit code" "0" "$TEXT_RC"

    dm_assert_mapping_received_gt "Outbound mapping processed the operation" "$MAPPING_ID" "$BASELINE"

    ACTUAL_TEXT_HEX=$(tr -d '[:space:]' < "$TEXT_FILE")
    dm_info "Text payload (hex): $ACTUAL_TEXT_HEX"

    # The command must arrive byte for byte: a JSON-wrapped string would start with a quote (0x22),
    # and a payload serialized by the mapper would differ in length.
    dm_assert_eq "Raw text command arrives exactly as returned" "$(dm_hex_of "$EXPECTED_TEXT")" "$ACTUAL_TEXT_HEX"
    dm_assert_ne "Text payload is not JSON-quoted" "22" "${ACTUAL_TEXT_HEX:0:2}"
fi

dm_done "$TEST_TITLE"
dm_print_summary
