#!/bin/bash
#
# test-inbound-smartfunction-05.sh
#
# Integration test for Smart Function template pattern 11.
# Template: Per-device running statistics using device ID from context (MQTT client ID).
#
# Verifies that two distinct devices accumulate independent statistics in the
# shared per-mapping state store by publishing messages with different MQTT
# client IDs and checking the resulting measurement counts per device.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated,
#               mosquitto_pub and jq installed
# Usage: ./test-inbound-smartfunction-05.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="16. Inbound: Pattern 11: Per-device running statistics (device ID from context)"

TS=$(date +%s)
EXT_ID_A="dmtest-sf11-a-${TS}"
EXT_ID_B="dmtest-sf11-b-${TS}"
MAPPING_ID=""
DEVICE_ID_A=""
DEVICE_ID_B=""

# Per-device cert state (populated in c8y-mqtt-service mode only)
_CERT_A="" _KEY_A="" _CERTDIR_A=""
_CERT_B="" _KEY_B="" _CERTDIR_B=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    # Delete per-device trusted certs uploaded in c8y-mqtt-service mode
    [ -n "${_CERT_A:-}" ] && c8y devicemanagement certificates delete --id "$EXT_ID_A" --force </dev/null >/dev/null 2>&1 || true
    [ -n "${_CERT_B:-}" ] && c8y devicemanagement certificates delete --id "$EXT_ID_B" --force </dev/null >/dev/null 2>&1 || true
    [ -n "${_CERTDIR_A:-}" ] && rm -rf "$_CERTDIR_A" 2>/dev/null || true
    [ -n "${_CERTDIR_B:-}" ] && rm -rf "$_CERTDIR_B" 2>/dev/null || true
    for _eid in "$EXT_ID_A" "$EXT_ID_B"; do
        local _did
        _did=$(dm_lookup_device_by_ext_id "$_eid" "c8y_Serial" 2>/dev/null) || true
        if [ -n "${_did:-}" ]; then
            c8y identity delete --name "$_eid" --type "c8y_Serial" 2>/dev/null || true
            c8y inventory delete --id "$_did" 2>/dev/null || true
        fi
    done
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

# Generate a self-signed cert, upload it as a trusted certificate, and store
# paths in the three caller-supplied variable names.
# In c8y-mqtt-service mode the cert CN must equal the MQTT client ID.
_provision_device_cert() {   # <dir_var> <cert_var> <key_var> <clientId>
    local _dir_var="$1" _cert_var="$2" _key_var="$3" _cid="$4"
    local _dir; _dir=$(mktemp -d)
    local _key="${_dir}/${_cid}.key" _cert="${_dir}/${_cid}.pem"
    openssl req -new -x509 -nodes -days 2 -newkey rsa:2048 \
        -keyout "$_key" -out "$_cert" -subj "/CN=${_cid}" >/dev/null 2>&1 \
        || { rm -rf "$_dir"; dm_error "Failed to generate cert for ${_cid}"; }
    c8y devicemanagement certificates create --name "$_cid" --file "$_cert" \
        --autoRegistrationEnabled true --status ENABLED </dev/null >/dev/null 2>&1 \
        || { rm -rf "$_dir"; dm_error "Failed to upload cert for ${_cid}. Ensure the user has the 'Mqtt service' permission."; }
    eval "${_dir_var}='${_dir}'"
    eval "${_cert_var}='${_cert}'"
    eval "${_key_var}='${_key}'"
    dm_info "Uploaded trusted cert: CN=${_cid}"
}

# Publish with an explicit MQTT client ID so the Smart Function receives it via
# context.getClientId().
#
# In public mode mosquitto_pub sets the client ID directly with -i.
# In c8y-mqtt-service mode the client ID is dictated by the X.509 cert CN, so
# each device needs its own pre-provisioned cert (_CERT_A / _CERT_B).
publish_with_cid() {  # <clientId> <topic> <payload> [qos=0]
    local _cid="$1" _topic="$2" _payload="$3" _qos="${4:-0}"
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -m "$_payload" -q "$_qos" -i "$_cid")
    if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
        local _cert _key
        if [ "$_cid" = "$EXT_ID_A" ]; then
            _cert="$_CERT_A" _key="$_KEY_A"
        elif [ "$_cid" = "$EXT_ID_B" ]; then
            _cert="$_CERT_B" _key="$_KEY_B"
        else
            dm_error "No provisioned cert for device: $_cid"
        fi
        _args+=(-u "${C8Y_TENANT:-}")
        _dm_mqtt_append_tls_args _args
        _args+=(--cert "$_cert" --key "$_key")
    else
        [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
        [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    fi
    mosquitto_pub "${_args[@]}"
    dm_info "Published to $_topic as client '$_cid' (qos=$_qos)"
}

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating Smart Function mapping (per-device running statistics)"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var temperature = payload["temperature"];

    if (temperature === undefined || temperature === null) {
        console.log("Missing temperature in payload — skipping");
        return [];
    }

    // Device identifier comes from context, never from the payload.
    // Primary: MQTT client ID set by the connecting device.
    // Fallback: last segment of the resolved topic.
    var deviceId = context.getClientId();
    if (!deviceId) {
        var config = context.getConfig();
        var topic = config ? config["topic"] : null;
        if (topic) {
            var parts = topic.split("/");
            deviceId = parts[parts.length - 1];
        }
    }

    if (!deviceId) {
        console.log("Cannot determine deviceId from context — skipping");
        return [];
    }

    // Composite state keys scope statistics per device within the mapping store.
    var countKey = deviceId + ":messageCount";
    var totalKey = deviceId + ":temperatureSum";
    var minKey   = deviceId + ":minTemperature";
    var maxKey   = deviceId + ":maxTemperature";

    var count   = context.getState(countKey) || 0;
    var total   = context.getState(totalKey) || 0;
    var minTemp = context.getState(minKey);
    var maxTemp = context.getState(maxKey);

    count   = count + 1;
    total   = total + temperature;
    minTemp = (minTemp === null || minTemp === undefined) ? temperature : Math.min(minTemp, temperature);
    maxTemp = (maxTemp === null || maxTemp === undefined) ? temperature : Math.max(maxTemp, temperature);
    var avg = total / count;

    context.setState(countKey, count);
    context.setState(totalKey, total);
    context.setState(minKey,   minTemp);
    context.setState(maxKey,   maxTemp);

    console.log("Device " + deviceId + " message #" + count +
                ": avg=" + avg.toFixed(2) + ", min=" + minTemp + ", max=" + maxTemp);

    var time = payload["time"] ? payload["time"] : new Date().toISOString();

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            time: time,
            type: "c8y_TemperatureMeasurement",
            c8y_Temperature: {
                T: { value: temperature, unit: "C" }
            },
            c8y_TemperatureStatistics: {
                average:      { value: avg,     unit: "C" },
                minimum:      { value: minTemp,  unit: "C" },
                maximum:      { value: maxTemp,  unit: "C" },
                messageCount: { value: count,    unit: "#" }
            }
        },
        externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
    }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name       "test-sf-11-$$" \
    --arg identifier "sf-11-$$" \
    --arg extIdA     "$EXT_ID_A" \
    --arg code       "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "perDevStats/+",
      mappingTopicSample: ("perDevStats/" + $extIdA),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      supportsMessageContext: true,
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active
if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
    # In c8y-mqtt-service mode each simulated device needs its own X.509 cert so
    # the MQTT Service exposes the correct client ID to context.getClientId().
    # Upload both certs first, then wait once for registration.
    _provision_device_cert _CERTDIR_A _CERT_A _KEY_A "$EXT_ID_A"
    _provision_device_cert _CERTDIR_B _CERT_B _KEY_B "$EXT_ID_B"
    dm_info "Waiting 5s for device certs to be registered ..."
    sleep 5
fi
dm_success "Mapping deployed and activated"

dm_step 4 "Publishing 3 messages from device A (clientId=$EXT_ID_A)"
# Publish sequentially with a gap between each message.  The Smart Function
# accumulates running statistics via context.getState/setState, which are
# persisted in FlowStateStore at the END of each invocation (in close()).
# If messages arrive faster than one invocation completes, the next invocation
# loads a stale snapshot and the counter resets to 1.  A 5 s delay covers the
# worst case: first message triggers device auto-creation (~2 s) + SmartFunction
# execution (~2 s) + flush to FlowStateStore.
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":20.0}' 1
sleep 5
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":22.0}' 1
sleep 5
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":24.0}' 1
dm_success "3 messages published for device A"

dm_step 5 "Publishing 2 messages from device B (clientId=$EXT_ID_B)"
publish_with_cid "$EXT_ID_B" "perDevStats/$EXT_ID_B" '{"temperature":30.0}' 1
sleep 5
publish_with_cid "$EXT_ID_B" "perDevStats/$EXT_ID_B" '{"temperature":32.0}' 1
dm_success "2 messages published for device B"

dm_step 6 "Waiting for measurements to be processed"
dm_assert_measurement_present "Device A: 3 measurements" "$EXT_ID_A" "c8y_Serial" 3 20
dm_assert_measurement_present "Device B: 2 measurements" "$EXT_ID_B" "c8y_Serial" 2 20

dm_step 7 "Verifying per-device statistics are independent"
DEVICE_ID_A=$(dm_lookup_device_by_ext_id "$EXT_ID_A" "c8y_Serial")
DEVICE_ID_B=$(dm_lookup_device_by_ext_id "$EXT_ID_B" "c8y_Serial")

COUNT_A=$(c8y measurements list --device "$DEVICE_ID_A" --type "c8y_TemperatureMeasurement" \
    --pageSize 10 --output json 2>/dev/null | jq -s 'length')
COUNT_B=$(c8y measurements list --device "$DEVICE_ID_B" --type "c8y_TemperatureMeasurement" \
    --pageSize 10 --output json 2>/dev/null | jq -s 'length')

dm_assert_eq "Device A has exactly 3 measurements" "3" "${COUNT_A:-0}"
dm_assert_eq "Device B has exactly 2 measurements" "2" "${COUNT_B:-0}"

# Verify the last measurement for device A carries the correct running statistics
# (avg of 20, 22, 24 = 22.0; min=20, max=24, count=3)
LAST_A=$(c8y measurements list --device "$DEVICE_ID_A" --type "c8y_TemperatureMeasurement" \
    --pageSize 1 --output json 2>/dev/null | jq -s '.[0]')

MSG_COUNT_A=$(printf '%s' "$LAST_A" | jq -r '.c8y_TemperatureStatistics.messageCount.value // empty')
dm_assert_eq "Device A messageCount=3 in statistics" "3" "${MSG_COUNT_A:-0}"

dm_done "$TEST_TITLE"
dm_print_summary
