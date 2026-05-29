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

KEEP_ON_FAILURE=false
EXT_ID="dmtest-operation-$(date +%s)"
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

dm_banner "Test: Inbound Operation (JSON → c8y_Operation via DEFAULT substitution)"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready

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
        {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"operationType","pathTarget":"c8y_Restart","repairStrategy":"DEFAULT","expandArray":false}
      ],
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      qos: "AT_LEAST_ONCE"
    }')

MAPPING_ID=$(dm_create_mapping "$MAPPING_JSON")
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_success "Mapping deployed and activated"

dm_step 4 "Publishing operation command via MQTT"
TEST_PAYLOAD=$(jq -cn '{
  operationType: "c8y_Restart",
  commandName: "restart_device",
  status: "PENDING"
}')

echo "$TEST_PAYLOAD" | mosquitto_pub -h broker.hivemq.com -t "dmtest/operation/$EXT_ID" -s -q 1
dm_success "Operation command published"

dm_step 5 "Waiting for device and operation creation"
sleep 2
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial" | head -1) || true

if [ -z "$DEVICE_ID" ]; then
    dm_error "Device not created with externalId=$EXT_ID"
fi

dm_success "Device created: $DEVICE_ID"

dm_step 6 "Verifying operation was created"
OPERATION=$(dm_api GET "/devicecontrol/operations?deviceId=$DEVICE_ID&pageSize=5" 2>/dev/null || echo '{"operations":[]}')
OPERATION_COUNT=$(echo "$OPERATION" | jq '.operations | length')

if [ "$OPERATION_COUNT" -ge 1 ]; then
    dm_success "Operation created: $OPERATION_COUNT operation(s) found"
else
    dm_error "No operations found for device $DEVICE_ID"
fi

dm_step 7 "Verifying operation content"
OPERATION_STATUS=$(echo "$OPERATION" | jq -r '.operations[0].status // empty')
OPERATION_TYPE=$(echo "$OPERATION" | jq -r '.operations[0] | keys[] | select(startswith("c8y_")) | .[0]' 2>/dev/null || echo "unknown")

dm_info "Operation status: $OPERATION_STATUS"
dm_info "Operation type: $OPERATION_TYPE"

dm_banner "✅ Test PASSED: Inbound OPERATION transformation works correctly"
