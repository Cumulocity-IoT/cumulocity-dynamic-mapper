#!/bin/bash
#
# test-inbound-operation.sh
#
# Integration test for inbound OPERATION transformation.
# Tests JSON → Cumulocity Operation creation with DEFAULT transformations.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-operation.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/test-harness.sh"

EXT_ID="dmtest-operation-$(date +%s)"
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

dm_banner "Test: Inbound Operation (JSON → c8y_Operation via DEFAULT substitution)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step 2 "Creating operation mapping with DEFAULT transformation"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-operation-$$" \
    --arg identifier "operation-$$" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/operation/+",
      mappingTopicSample: ("dmtest/operation/" + $extId),
      targetAPI: "OPERATION",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "DEFAULT",
      sourceTemplate: "{\"operationType\":\"\",\"commandName\":\"\",\"status\":\"\"}",
      targetTemplate: "{\"deviceId\":\"${DEVICE_ID}\",\"status\":\"PENDING\",\"c8y_Restart\":{}}",
      substitutions: [
                {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false}
      ],
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

dm_step 4 "Publishing operation command via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  operationType: "c8y_Restart",
  commandName: "restart_device",
  status: "PENDING"
}')

dm_mqtt_publish "dmtest/operation/$EXT_ID" "$TEST_PAYLOAD" 1
dm_success "Operation command published"

dm_step 5 "Waiting for device and operation creation"
sleep 8
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" 2>/dev/null | head -1) || true

if [ -z "$DEVICE_ID" ]; then
    dm_error "Device not created with externalId=$EXT_ID"
fi

dm_success "Device created: $DEVICE_ID"

dm_step 6 "Verifying operation was created"
OPERATION='[]'
OPERATION_COUNT=0
for _attempt in 1 2 3 4 5; do
    # c8y operations list emits one JSON object per line for --output json; slurp to count reliably.
    OPERATION=$(c8y operations list --device "$DEVICE_ID" --pageSize 5 --output json 2>/dev/null || echo '[]')
    OPERATION_COUNT=$(echo "$OPERATION" | jq -s 'length')
    if [ "$OPERATION_COUNT" -ge 1 ]; then
        break
    fi
    sleep 2
done

dm_assert_gt "Operation created" "$OPERATION_COUNT" 0

dm_step 7 "Verifying operation content"
OPERATION_STATUS=$(echo "$OPERATION" | jq -rs '.[0].status // empty')
OPERATION_TYPE=$(echo "$OPERATION" | jq -rs '.[0] | keys[] | select(startswith("c8y_"))' 2>/dev/null | head -n 1 || echo "unknown")
dm_info "Operation status: $OPERATION_STATUS"
dm_info "Operation type: $OPERATION_TYPE"

dm_done "Inbound OPERATION Transformation"
dm_print_summary
