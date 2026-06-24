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

TS=$(date +%s)
EXT_ID_A="dmtest-sf11-a-${TS}"
EXT_ID_B="dmtest-sf11-b-${TS}"
MAPPING_ID=""
DEVICE_ID_A=""
DEVICE_ID_B=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
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

# Publish with an explicit MQTT client ID so the Smart Function receives it via
# context.getClientId().  The standard dm_mqtt_publish omits -i in public mode,
# so we wrap mosquitto_pub directly here.
publish_with_cid() {  # <clientId> <topic> <payload> [qos=0]
    local _cid="$1" _topic="$2" _payload="$3" _qos="${4:-0}"
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -m "$_payload" -q "$_qos" -i "$_cid")
    [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
    [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    mosquitto_pub "${_args[@]}"
    dm_info "Published to $_topic as client '$_cid' (qos=$_qos)"
}

dm_banner "15. Pattern 11: Per-device running statistics (device ID from context)"

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
dm_success "Mapping deployed and activated"

dm_step 4 "Publishing 3 messages from device A (clientId=$EXT_ID_A)"
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":20.0}' 1
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":22.0}' 1
publish_with_cid "$EXT_ID_A" "perDevStats/$EXT_ID_A" '{"temperature":24.0}' 1
dm_success "3 messages published for device A"

dm_step 5 "Publishing 2 messages from device B (clientId=$EXT_ID_B)"
publish_with_cid "$EXT_ID_B" "perDevStats/$EXT_ID_B" '{"temperature":30.0}' 1
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

dm_done "15. Pattern 11: Per-device running statistics (device ID from context)"
dm_print_summary
