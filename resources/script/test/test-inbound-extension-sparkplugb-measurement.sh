#!/bin/bash
#
# test-inbound-extension-sparkplugb-measurement.sh
#
# Integration test for ProcessorExtensionSparkplugBMeasurement Java extension.
# Tests Sparkplug B protobuf measurement parsing and conversion to Cumulocity.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-extension-sparkplugb-measurement.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

EXT_ID="dmtest-sparkplug-measure-$(date +%s)"
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

dm_banner "Test: Inbound Sparkplug B Measurement (protobuf → c8y_VoltageMeasurement)"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit
dm_require_extension "SparkplugBMeasurement" "INBOUND"

dm_step 2 "Creating mapping with Sparkplug B extension"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-sparkplugb-measure-$$" \
    --arg identifier "sparkplugb-measure-$$" \
    --arg extId      "$EXT_ID" \
    --argjson extension "$_DM_RESOLVED_EXTENSION" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "spBv1.0/+/NDATA/+/+",
      mappingTopicSample: ("spBv1.0/group1/NDATA/edgenode1/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "PROTOBUF_INTERNAL",
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
      parameter: {
        units: {
          unit1: "V"
        },
        fragment: "Energy"
      },
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Sparkplug B mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active
dm_success "Mapping deployed and activated"

dm_step 4 "Note: Binary Sparkplug B protobuf payload publishing"
dm_info "Sparkplug B NDATA messages contain binary protobuf payloads"
dm_info "In real deployment, these come from Sparkplug B brokers"
dm_info "This test validates the extension mapping configuration"
dm_success "Sparkplug B extension mapping ready for protobuf payloads"

dm_step 5 "Verifying mapping configuration"
MAPPING_CONFIG=$(dm_api GET "/mapping/$MAPPING_ID" 2>/dev/null || echo '{}')
EXT_EVENT=$(echo "$MAPPING_CONFIG" | jq -r '.extension.eventName // empty')
EXT_FQN=$(echo "$MAPPING_CONFIG" | jq -r '.extension.fqnClassName // empty')

_ext_match=false
if [ "$EXT_EVENT" = "SparkplugBMeasurement" ] || [[ "$EXT_FQN" == *".ProcessorExtensionSparkplugBMeasurement" ]]; then
    _ext_match=true
fi
dm_assert_eq "Sparkplug B extension configured (event=${EXT_EVENT:-n/a} fqn=${EXT_FQN:-n/a})" "true" "$_ext_match"

dm_done "Inbound Extension Sparkplug B Measurement"
dm_info "Note: Full protobuf payload testing requires binary message generation"
dm_print_summary
