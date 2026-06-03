#!/bin/bash
#
# test-inbound-extension-custom-measurement.sh
#
# Integration test for ProcessorExtensionCustomMeasurement Java extension.
# Tests inbound JSON transformation via Java extension to c8y_Temperature measurement.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-extension-custom-measurement.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

KEEP_ON_FAILURE=false
EXT_ID="dmtest-ext-measurement-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""

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
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

trap cleanup EXIT

dm_banner "Test: Inbound Extension Custom Measurement (JSON → c8y_Temperature)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

dm_step 2 "Creating mapping with CustomMeasurement extension"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-ext-measurement-$$" \
    --arg identifier "ext-meas-$$" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ext/measurement/+",
      mappingTopicSample: ("dmtest/ext/measurement/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "EXTENSION_JAVA",
      extension: {
        extensionName: "custom-measurement-extension",
        eventName: "CustomMeasurement",
        fqnClassName: "dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomMeasurement",
                extensionType: "EXTENSION_INBOUND",
        direction: "INBOUND"
      },
      sourceTemplate: "{}",
      targetTemplate: "{}",
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
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

dm_step 4 "Recording baseline for verification"
BASELINE=$(date +%s%N | cut -b1-13)

dm_step 5 "Publishing test message via MQTT"
TEST_PAYLOAD=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      externalId: $extId,
      time: now | todate,
      temperature: 25.5,
      unit: "C"
    }' | tr -d '\n')

mosquitto_pub -h broker.hivemq.com -t "dmtest/ext/measurement/$EXT_ID" -m "$TEST_PAYLOAD" -q 1
dm_success "Test message published"

dm_step 6 "Waiting and verifying measurement creation"
dm_wait_for_measurement_count "$EXT_ID" "c8y_Serial" 1 30
dm_success "Measurement created successfully"

dm_step 7 "Verifying measurement content"
MEASUREMENT=$(dm_get_latest_measurement "$EXT_ID" "c8y_Serial" "c8y_TemperatureMeasurement")
TEMP_VALUE=$(echo "$MEASUREMENT" | jq -r '.c8y_TemperatureMeasurement.T.value // empty')
TEMP_UNIT=$(echo "$MEASUREMENT" | jq -r '.c8y_TemperatureMeasurement.T.unit // empty')

if [ "$TEMP_VALUE" = "25.5" ] && [ "$TEMP_UNIT" = "C" ]; then
    dm_success "Temperature value and unit verified: $TEMP_VALUE $TEMP_UNIT"
else
    dm_error "Temperature verification failed. Expected: 25.5 C, Got: $TEMP_VALUE $TEMP_UNIT"
fi

dm_done "Inbound Extension Custom Measurement"
