#!/bin/bash
#
# test-outbound-webhook: Outbound C8Y Measurement → WEB_HOOK connector → HTTP receiver
#
# Creates a WEB_HOOK connector whose baseUrl is the receiver endpoint of the
# http-polling-mock microservice (resources/testing/environments/http-polling),
# an outbound MEASUREMENT mapping, and verifies the mock RECEIVED the HTTP request
# with the expected path and JSON body (not just that the mapping saw the event).
#
# Prerequisites:
#   - http-polling-mock deployed in a version with the /webhook receiver
#     (environments/http-polling/build.sh --push)
#   - c8y session exporting C8Y_HOST and C8Y_TOKEN (or C8Y_USER/C8Y_PASSWORD)
#   - outbound mapping enabled in the service configuration
#
# Usage:
#   ./test-outbound-webhook.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="35. C8Y Measurement → WEB_HOOK connector"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
CONNECTOR_ID="testwebhook$$"
DEVICE_NAME="dmtest-out-webhook-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
PUBLISH_TOPIC="dmtest/webhook/$$"
TEMP_VALUE="7331.5"
DEVICE_ID=""
MAPPING_ID=""
CONNECTOR_CREATED=false

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
    [ "$CONNECTOR_CREATED" = "true" ] && dm_delete_connector "$CONNECTOR_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Validating environment ..."
dm_validate_tools
dm_wait_for_service
dm_require_mock
dm_validate_only_exit

dm_step "Creating WEB_HOOK connector pointing at the mock receiver ..."
PROPS=$(jq -cn --arg url "$(dm_mock_base_url)/webhook" --argjson auth "$(dm_mock_auth_props)" \
    '{cumulocityInternal:false, baseUrl:$url, headerAccept:"application/json", headers:{}} + $auth')
dm_create_connector_with_props "$CONNECTOR_ID" "WEB_HOOK" "Test Webhook" "$PROPS"
CONNECTOR_CREATED=true
dm_connect_connector "$CONNECTOR_ID"
dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 30 3 >/dev/null || true
dm_assert_connector_status "WEB_HOOK connector connected" "$CONNECTOR_ID" CONNECTED

dm_step "Creating test device with c8y_Serial and static subscription ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
c8y identity create --name "$EXT_ID" --type "c8y_Serial" --device "$DEVICE_ID" \
    --output json </dev/null >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"
dm_create_static_subscription_must "MEASUREMENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 5 "subscription propagation"

dm_step "Creating, deploying and activating the outbound mapping ..."
MAPPING_JSON=$(jq -cn --arg name "test-outbound-webhook-$$" --arg id "webhook$$" --arg topic "$PUBLISH_TOPIC" '{
  name: $name,
  identifier: $id,
  mappingTopic: "dmtest/out/webhook",
  mappingTopicSample: "dmtest/out/webhook",
  publishTopic: $topic,
  publishTopicSample: $topic,
  targetAPI: "MEASUREMENT",
  direction: "OUTBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  filterMapping: "true",
  sourceTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  targetTemplate: "{\"temperature\":110,\"deviceId\":\"source-id\"}",
  substitutions: [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"c8y_TemperatureMeasurement.T.value","pathTarget":"temperature","repairStrategy":"DEFAULT","expandArray":false}
  ],
  active: false,
  debug: false,
  useExternalId: true,
  externalIdType: "c8y_Serial",
  qos: "AT_LEAST_ONCE"
}')
dm_mock_clear /received
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_connector "$MAPPING_ID" "$CONNECTOR_ID"
dm_activate_mapping "$MAPPING_ID"
BASELINE=$(dm_mapping_received_count "$MAPPING_ID")

dm_step "Creating a measurement (value ${TEMP_VALUE}) to trigger the webhook ..."
c8y measurements create --device "$DEVICE_ID" --type "c8y_TemperatureMeasurement" \
    --data "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":${TEMP_VALUE},\"unit\":\"C\"}}}" \
    --output json </dev/null >/dev/null

dm_step "Waiting for the mock to receive the request (up to 45s) ..."
HIT='[]'
for _i in $(seq 1 15); do
    HIT=$(dm_mock_get_array /received | jq -c --arg p "/${PUBLISH_TOPIC}" '[.[] | select(.path == $p)]')
    [ "$(printf '%s' "$HIT" | jq length)" -ge 1 ] && break
    sleep 3
done

dm_assert_mapping_received_gt "Mapping processed the notification" "$MAPPING_ID" "$BASELINE"
dm_assert_gt "Mock received a request on /${PUBLISH_TOPIC}" "$(printf '%s' "$HIT" | jq length)" 0
dm_assert_eq "HTTP method" "POST" "$(printf '%s' "$HIT" | jq -r '.[0].method // empty')"
BODY=$(printf '%s' "$HIT" | jq -r '.[0].body // "{}"')
dm_assert_eq "Body temperature" "$TEMP_VALUE" "$(printf '%s' "$BODY" | jq -r '.temperature // empty' 2>/dev/null)"
dm_assert_eq "Body deviceId (external id)" "$EXT_ID" "$(printf '%s' "$BODY" | jq -r '.deviceId // empty' 2>/dev/null)"

dm_done "$TEST_TITLE"
dm_print_summary
