#!/bin/bash
#
# test-api-mapping-dry-run: POST /test/mapping and REST validation / error paths
#
# No MQTT broker needed. Covers endpoints no other integration test touches:
#   - POST /test/mapping with send=false returns the transformed request(s) and has
#     NO side effects (no device is created)
#   - POST /test/mapping without a payload is rejected
#   - GET /mapping/{unknown id} is rejected (404)
#   - POST /mapping with an invalid body is rejected (422 / 400) and nothing is stored
#
# Usage:
#   ./test-api-mapping-dry-run.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="REST: /test/mapping dry-run and validation errors"

EXT_ID="dmtest-dryrun-$(date +%s)"
INVALID_NAME="test-api-invalid-$$"

cleanup() {
    dm_info "Cleaning up ..."
    # If the invalid mapping was wrongly accepted, do not leave it behind.
    dm_api_json_array GET /mapping \
        | jq -rs --arg n "$INVALID_NAME" 'flatten(1) | map(select(.name == $n) | .id) | .[]' 2>/dev/null \
        | while read -r _id; do [ -n "$_id" ] && dm_delete_mapping "$_id" || true; done
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate false
dm_validate_only_exit

MAPPING_JSON=$(jq -n --arg ext "$EXT_ID" '{
  name: "test-api-dryrun",
  identifier: "tad",
  mappingTopic: "dmtest/dryrun/+",
  mappingTopicSample: ("dmtest/dryrun/" + $ext),
  targetAPI: "MEASUREMENT",
  direction: "INBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  sourceTemplate: "{\"temperature\":25.0}",
  targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  substitutions: [
    {pathSource:"_TOPIC_LEVEL_[2]", pathTarget:"_IDENTITY_.externalId", repairStrategy:"DEFAULT", expandArray:false},
    {pathSource:"temperature", pathTarget:"c8y_TemperatureMeasurement.T.value", repairStrategy:"DEFAULT", expandArray:false},
    {pathSource:"$now()", pathTarget:"time", repairStrategy:"DEFAULT", expandArray:false}
  ],
  active: false, debug: false,
  createNonExistingDevice: true, updateExistingDevice: false,
  useExternalId: true, externalIdType: "c8y_Serial",
  genericDeviceIdentifier: "_IDENTITY_.externalId",
  qos: "AT_LEAST_ONCE"
}')

dm_step "Dry run (send=false) returns the transformed request ..."
BODY=$(jq -n --argjson m "$MAPPING_JSON" '{mapping:$m, payload:"{\"temperature\":42.5}", send:false}')
RESULT=$(dm_api POST /test/mapping "$BODY")
dm_assert_eq "Dry run succeeded" "true" "$(printf '%s' "$RESULT" | jq -r '.success // false')"
dm_assert_gt "Dry run produced at least one request" "$(printf '%s' "$RESULT" | jq -r '(.requests // []) | length')" 0
dm_assert_eq "Dry run reported no errors" "0" "$(printf '%s' "$RESULT" | jq -r '(.errors // []) | length')"

dm_step "Dry run has no side effects ..."
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
dm_assert_eq "No device created by send=false" "" "${DEVICE_ID:-}"

dm_step "Request without payload is rejected ..."
dm_assert_rejected "Missing payload" POST /test/mapping "$(jq -n --argjson m "$MAPPING_JSON" '{mapping:$m, send:false}')"

dm_step "Unknown mapping id is rejected ..."
dm_assert_rejected "GET unknown mapping" GET "/mapping/does-not-exist-$$"

dm_step "Invalid mapping body is rejected and not stored ..."
dm_assert_rejected "Invalid mapping" POST /mapping \
    "$(jq -n --arg n "$INVALID_NAME" '{name:$n, direction:"INBOUND", mappingTopic:"", substitutions:[]}')"
STORED=$(dm_api_json_array GET /mapping | jq -rs --arg n "$INVALID_NAME" 'flatten(1) | map(select(.name == $n)) | length')
dm_assert_eq "Invalid mapping not persisted" "0" "$STORED"

dm_done "$TEST_TITLE"
dm_print_summary
