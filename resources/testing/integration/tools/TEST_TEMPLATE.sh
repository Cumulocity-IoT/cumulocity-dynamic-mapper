#!/bin/bash
#
# TEST_TEMPLATE.sh — Template for creating new integration tests
#
# Copy this file into the right folder and customize it for your test case:
#
#   tests/mqtt/<inbound|outbound|extension|smartfunction>/   needs the MQTT broker, so it
#                                                            runs against g or m (DM_BROKER_MODE)
#   tests/independent/<api|connectors|platform>/             ignores g/m: it creates its own
#                                                            connector type or needs no broker
#
# Then register it in run-tests.sh (TESTS catalogue: "category|scope|name|description",
# scope = mqtt | own | none) — the file name must be unique across tests/.
#
# Shows best practices: tool validation, proper cleanup, error handling.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector (scope "mqtt" only)
#   - c8y CLI authenticated, mosquitto_pub, jq installed
#
# Exit codes: 0 = pass, 1 = fail, 42 (dm_skip_exit "<reason>") = skipped, prerequisite absent.
#
# Usage:
#   bash <script> [--cleanup]       — run test with automatic cleanup (default)
#   bash <script> --keep            — keep test data (for debugging; sweep with run-tests.sh --cleanup-only)
#   bash <script> --validate-only   — check the environment, create nothing

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# INTEGRATION_DIR = the folder holding test-harness.sh. A test in tests/<scope>/<group>/ sets it
# with "${SCRIPT_DIR}/../../.."; this template walks up so it also runs from tools/.
INTEGRATION_DIR="$SCRIPT_DIR"
while [ ! -f "${INTEGRATION_DIR}/test-harness.sh" ] && [ "$INTEGRATION_DIR" != "/" ]; do
    INTEGRATION_DIR="$(dirname "$INTEGRATION_DIR")"
done
# shellcheck source=../test-harness.sh
source "${INTEGRATION_DIR}/test-harness.sh"

# ── Script-specific configuration ──────────────────────────────────────────────
EXT_ID="dmtest-template-$(date +%s)"
MAPPING_ID=""
DEVICE_ID=""

dm_parse_args "$@"

# ── Cleanup function (always runs unless --keep is set) ───────────────────────
cleanup() {
    
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

dm_register_cleanup cleanup

# ── Main test flow ────────────────────────────────────────────────────────────
dm_banner "Test Template: Inbound JSON Example"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
dm_validate_only_exit

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
# Verify the connector really subscribed THIS mapping's topic before publishing —
# without the mapping id this only checks that some topic is subscribed.
dm_assert_mqtt_topics_active "$MAPPING_ID"

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
