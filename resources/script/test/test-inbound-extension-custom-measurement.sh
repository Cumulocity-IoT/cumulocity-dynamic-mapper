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

EXT_ID="dmtest-ext-measurement-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "Test: Inbound Extension Custom Measurement (JSON → c8y_Temperature)"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit
dm_require_extension "CustomMeasurement" "INBOUND"

dm_step 2 "Creating mapping with CustomMeasurement extension"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-ext-measurement-$$" \
    --arg identifier "ext-meas-$$" \
    --arg extId      "$EXT_ID" \
    --argjson extension "$_DM_RESOLVED_EXTENSION" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ext/measurement/+",
      mappingTopicSample: ("dmtest/ext/measurement/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "ANY_PAYLOAD",
      transformationType: "EXTENSION_JAVA",
      extension: $extension,
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

dm_step 4 "Publishing test message via MQTT"
TEST_PAYLOAD=$(jq -cn \
    --arg extId "$EXT_ID" \
    '{
      externalId: $extId,
      time: now | todate,
      temperature: 25.5,
      unit: "C"
    }' | tr -d '\n')

dm_mqtt_publish "dmtest/ext/measurement/$EXT_ID" "$TEST_PAYLOAD" 1
dm_success "Test message published"

dm_step 5 "Waiting and verifying measurement creation"
dm_assert_measurement_present "Measurement created" "$EXT_ID" "c8y_Serial" 1 30

dm_step 6 "Verifying measurement content"
# The extension sets type=c8y_TemperatureMeasurement but writes the series under
# the c8y_Temperature fragment (.fragment("c8y_Temperature","T",...)).
MEASUREMENT=$(dm_get_latest_measurement "$EXT_ID" "c8y_Serial" "c8y_TemperatureMeasurement")
TEMP_VALUE=$(echo "$MEASUREMENT" | jq -r '.c8y_Temperature.T.value // empty')
TEMP_UNIT=$(echo "$MEASUREMENT" | jq -r '.c8y_Temperature.T.unit // empty')
dm_assert_eq "Temperature value" "25.5" "$TEMP_VALUE"
dm_assert_eq "Temperature unit" "C" "$TEMP_UNIT"

dm_done "Inbound Extension Custom Measurement"
dm_print_summary
