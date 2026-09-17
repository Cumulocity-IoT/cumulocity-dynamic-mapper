#!/bin/bash
#
# test-inbound-extension-custom-event.sh
#
# Integration test for ProcessorExtensionCustomEvent Java extension.
# Tests inbound protobuf event transformation via Java extension.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-extension-custom-event.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="19. Extension: Protobuf → Event"

EXT_ID="dmtest-ext-event-$(date +%s)"
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

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit
dm_require_extension "ProtobufEvent" "INBOUND"

dm_step 2 "Creating mapping with ProtobufEvent extension"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-ext-event-$$" \
    --arg identifier "ext-event-$$" \
    --arg extId      "$EXT_ID" \
    --argjson extension "$_DM_RESOLVED_EXTENSION" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ext/event/+",
      mappingTopicSample: ("dmtest/ext/event/" + $extId),
      targetAPI: "EVENT",
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

dm_step 4 "Note: Custom protobuf event payload publishing"
dm_info "ProcessorExtensionCustomEvent parses CustomEvent protobuf format"
dm_info "Expected protobuf fields: timestamp, txt, eventType, externalId"
dm_info "This test validates the extension mapping configuration"
dm_success "Custom event extension mapping ready for protobuf payloads"

dm_step 5 "Verifying mapping configuration"
MAPPING_CONFIG=$(dm_api GET "/mapping/$MAPPING_ID" 2>/dev/null || echo '{}')
EXT_EVENT=$(echo "$MAPPING_CONFIG" | jq -r '.extension.eventName // empty')
EXT_FQN=$(echo "$MAPPING_CONFIG" | jq -r '.extension.fqnClassName // empty')

_ext_match=false
if [ "$EXT_EVENT" = "ProtobufEvent" ] || [[ "$EXT_FQN" == *".ProcessorExtensionCustomEvent" ]]; then
    _ext_match=true
fi
dm_assert_eq "Custom event extension configured (event=${EXT_EVENT:-n/a} fqn=${EXT_FQN:-n/a})" "true" "$_ext_match"

dm_done "$TEST_TITLE"
dm_info "Note: Full protobuf payload testing requires binary message generation from custom protobuf schema"
dm_print_summary
