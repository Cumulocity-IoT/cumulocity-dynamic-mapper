#!/bin/bash
#
# test-inbound-rest-polling: REST_POLLING connector → C8Y Measurement
#
# Creates a REST_POLLING connector pointed at the http-polling-mock microservice
# (resources/testing/environments/http-polling), deploys an inbound mapping on
# topic "measurements" (→ GET <url>/measurements) and verifies that the connector
# polls the mock and that a measurement is created from the response.
#
# Prerequisites:
#   - http-polling-mock deployed (environments/http-polling/build.sh --push)
#   - c8y session exporting C8Y_HOST and C8Y_TOKEN (or C8Y_USER/C8Y_PASSWORD)
#   - Note: the connector enforces a 30s minimum poll interval, so this test takes ~1-2 min
#
# Usage:
#   ./test-inbound-rest-polling.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="15. REST_POLLING connector → MEASUREMENT"

CONNECTOR_ID="testrestpolling$$"
MOCK_DEVICE_EXT_ID="poll-sensor-01"   # DEVICE_ID default of the mock
MAPPING_ID=""
CONNECTOR_CREATED=false
DEVICE_PREEXISTED=false

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ "$CONNECTOR_CREATED" = "true" ] && dm_delete_connector "$CONNECTOR_ID" || true
    # Only remove the device if this run created it (the mock's device id is fixed).
    if [ "$DEVICE_PREEXISTED" = "false" ]; then
        local _dev
        _dev=$(dm_lookup_device_by_ext_id "$MOCK_DEVICE_EXT_ID" "c8y_Serial") || true
        if [ -n "${_dev:-}" ]; then
            c8y identity delete --name "$MOCK_DEVICE_EXT_ID" --type "c8y_Serial" --force </dev/null 2>/dev/null || true
            dm_delete_device "$_dev"
        fi
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Validating environment ..."
dm_validate_tools
dm_wait_for_service
dm_require_mock
dm_validate_only_exit

[ -n "$(dm_lookup_device_by_ext_id "$MOCK_DEVICE_EXT_ID" "c8y_Serial")" ] && DEVICE_PREEXISTED=true

dm_step "Creating REST_POLLING connector against the mock ..."
PROPS=$(jq -cn --arg url "$(dm_mock_base_url)" --argjson auth "$(dm_mock_auth_props)" \
    '{url:$url, pollIntervalSeconds:30, headers:{}} + $auth')
dm_create_connector_with_props "$CONNECTOR_ID" "REST_POLLING" "Test REST Polling" "$PROPS"
CONNECTOR_CREATED=true
dm_connect_connector "$CONNECTOR_ID"
dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 30 3 >/dev/null || true
dm_assert_connector_status "REST_POLLING connector connected" "$CONNECTOR_ID" CONNECTED

dm_step "Creating, deploying and activating the inbound mapping (topic 'measurements') ..."
MAPPING_JSON=$(jq -cn --arg name "test-rest-polling-$$" --arg id "restpoll$$" --arg ext "$MOCK_DEVICE_EXT_ID" '{
  name: $name,
  identifier: $id,
  mappingTopic: "measurements",
  mappingTopicSample: "measurements",
  targetAPI: "MEASUREMENT",
  direction: "INBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  sourceTemplate: "{\"deviceId\":\"poll-sensor-01\",\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"temperature\":23.47}",
  targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  substitutions: [
    {"pathSource":"deviceId","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"timestamp","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  active: false,
  debug: false,
  createNonExistingDevice: true,
  updateExistingDevice: false,
  useExternalId: true,
  externalIdType: "c8y_Serial",
  genericDeviceIdentifier: "_IDENTITY_.externalId",
  qos: "AT_LEAST_ONCE"
}')
dm_mock_clear /requests
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_connector "$MAPPING_ID" "$CONNECTOR_ID"
dm_activate_mapping "$MAPPING_ID"

# The first poll fires right after the job is scheduled, then every 30s.
dm_step "Waiting for the connector to poll the mock (up to 75s) ..."
POLLS=0
for _i in $(seq 1 25); do
    POLLS=$(dm_mock_get_array /requests | jq '[.[] | select(.path == "/measurements")] | length')
    [ "${POLLS:-0}" -ge 1 ] && break
    sleep 3
done
dm_assert_gt "Mock received a /measurements poll" "${POLLS:-0}" 0

dm_step "Asserting the polled response became a measurement ..."
dm_assert_measurement_present "Measurement from polled response" "$MOCK_DEVICE_EXT_ID" "c8y_Serial" 1 30

dm_step "Asserting the connector keeps polling (second cycle, ~30s) ..."
for _i in $(seq 1 25); do
    POLLS=$(dm_mock_get_array /requests | jq '[.[] | select(.path == "/measurements")] | length')
    [ "${POLLS:-0}" -ge 2 ] && break
    sleep 3
done
dm_assert_gt "Second poll cycle observed" "${POLLS:-0}" 1

dm_step "Asserting un-deploying the mapping stops the polling ..."
dm_undeploy_mapping "$MAPPING_ID"
sleep 5
BEFORE=$(dm_mock_get_array /requests | jq '[.[] | select(.path == "/measurements")] | length')
dm_wait 40 "more than one poll interval"
AFTER=$(dm_mock_get_array /requests | jq '[.[] | select(.path == "/measurements")] | length')
dm_assert_eq "No polls after un-deploy" "$BEFORE" "$AFTER"

dm_done "$TEST_TITLE"
dm_print_summary
