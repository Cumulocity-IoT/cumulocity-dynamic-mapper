#!/bin/bash
#
# test-inbound-json-smartfunction: Inbound JSON → C8Y Measurement (SMART_FUNCTION/EXTENSION_JAVA)
#
# Publishes a JSON MQTT message and verifies a C8Y measurement is created using
# a Smart Function (JavaScript executed in GraalVM) for transformation.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub and jq installed
#
# Usage:
#   ./test-inbound-json-smartfunction.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
EXT_ID="dmtest-sf-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
}

[[ "${1:-}" == "--cleanup" ]] && trap cleanup EXIT

# ── Smart Function code (base64-encoded for safe embedding) ────────────────────
# The function extracts temperature from the payload and creates one measurement.
# It uses the last topic level as external device id.
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, ctx) {
    const sourceObject = msg.getPayload();

    // Determine external id from runtime topic (preferred), fallback to patched topic levels.
    const config = ctx.getConfig ? ctx.getConfig() : {};
    const topicLevels = typeof config.topic === 'string'
        ? config.topic.split('/')
        : (Array.isArray(sourceObject['_TOPIC_LEVEL_']) ? sourceObject['_TOPIC_LEVEL_'] : []);
    const externalId = topicLevels.length > 0 ? topicLevels[topicLevels.length - 1] : null;
    if (!externalId) {
        return [];
    }

    return [{
        cumulocityType: 'measurement',
        action: 'create',
        payload: {
            time: new Date().toISOString(),
            type: 'c8y_TemperatureMeasurement',
            c8y_TemperatureMeasurement: {
                T: {
                    value: sourceObject['temperature'],
                    unit: 'C'
                }
            }
        },
        externalSource: [{ type: 'c8y_Serial', externalId: externalId }]
    }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")

SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Inbound JSON Smart Function Transformation (MEASUREMENT)"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker

MAPPING_JSON=$(jq -cn \
    --arg name       "test-inbound-sf-$$" \
    --arg identifier "ibs$$" \
    --arg extId      "$EXT_ID" \
    --arg code       "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/sf/+",
      mappingTopicSample: ("dmtest/sf/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{\"temperature\":25.0}",
      targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      updateExistingDevice: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
    genericDeviceIdentifier: "_IDENTITY_.externalId",
      supportsMessageContext: true,
      qos: "AT_LEAST_ONCE",
      snoopStatus: "NONE",
      snoopedTemplates: []
    }')

dm_step "Creating and activating Smart Function mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Recording test start time ..."
TEST_START=$(dm_now -10)

dm_step "Publishing MQTT message ..."
dm_mqtt_publish "dmtest/sf/${EXT_ID}" "{\"temperature\":55.5}"

dm_step "Waiting for processing ..."
dm_wait 8

dm_step "Looking up device by external id ..."
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
if [ -z "$DEVICE_ID" ]; then
    dm_fail "Device '$EXT_ID' not found — Smart Function did not create it"
    exit 1
fi
dm_info "Device id: $DEVICE_ID"

dm_step "Asserting at least 1 measurement was created ..."
dm_assert_measurement_count_gt "Measurement created by Smart Function" "$DEVICE_ID" "$TEST_START" 1

dm_done "Inbound JSON Smart Function Transformation (MEASUREMENT)"
dm_print_summary