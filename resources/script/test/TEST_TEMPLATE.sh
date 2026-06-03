#!/bin/bash
#
# TEST_TEMPLATE.sh — Template for creating new integration tests
#
# Copy this file and customize for your specific test case.
# Shows best practices: tool validation, proper cleanup, error handling.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub, jq installed
#
# Usage:
#   ./TEST_TEMPLATE.sh [--cleanup]  — run test with automatic cleanup
#   ./TEST_TEMPLATE.sh --keep       — keep test data on failure (for debugging)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Script-specific configuration ──────────────────────────────────────────────
KEEP_ON_FAILURE=false
EXT_ID="dmtest-template-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""

# Parse command-line flags
for arg in "$@"; do
    case "$arg" in
        --keep) KEEP_ON_FAILURE=true ;;
        --cleanup) trap cleanup EXIT ;;
    esac
done

# ── Cleanup function (always runs unless --keep is set) ───────────────────────
cleanup() {
    if [ "$KEEP_ON_FAILURE" = "true" ]; then
        dm_warn "Skipping cleanup (--keep flag set)"
        return 0
    fi
    
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    elif [ -n "$EXT_ID" ]; then
        DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
        if [ -n "${DEVICE_ID:-}" ]; then
            c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
            c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
        fi
    fi
    
    dm_info "Cleanup complete"
}

trap cleanup EXIT

# ── Main test flow ────────────────────────────────────────────────────────────
dm_banner "Test Template: Inbound JSON Example"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker

dm_step 2 "Creating mapping"
MAPPING_JSON=$(jq -cn \
    --arg name       "test-template-$$" \
    --arg identifier "tmpl$$" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/template/+",
      mappingTopicSample: ("dmtest/template/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "DEFAULT",
      sourceTemplate: "{}",
      targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
      substitutions: [
        {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
        {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false}
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
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step 3 "Recording baseline metrics"
TEST_START=$(dm_now -10)

dm_step 4 "Publishing test message"
dm_mqtt_publish "dmtest/template/${EXT_ID}" '{"temperature": 22.5}'

dm_step 5 "Waiting for processing"
dm_wait 5 "for device creation and measurement processing"

dm_step 6 "Verifying results"
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
if [ -z "$DEVICE_ID" ]; then
    dm_fail "Device not found for external ID: $EXT_ID"
    exit 1
fi
dm_success "Device created: $DEVICE_ID"

dm_assert_measurement_count_gt "Measurement created" "$DEVICE_ID" "$TEST_START" 1

# ── Conclusion ─────────────────────────────────────────────────────────────────
dm_done "Test Template: Inbound JSON Example"
dm_print_summary
