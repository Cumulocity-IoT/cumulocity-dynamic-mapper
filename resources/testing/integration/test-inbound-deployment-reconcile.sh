#!/bin/bash
#
# test-inbound-deployment-reconcile: deployment changes take effect live
#
# Changing PUT /deployment/defined/{mapping} reconciles the connector's
# subscriptions without a reconnect or reload. Steps:
#   1. mapping deployed + active  -> message creates a measurement
#   2. un-deploy (PUT [])         -> topic unsubscribed, next message is NOT processed
#   3. re-deploy                  -> topic re-subscribed, next message creates a measurement
#   4. deploy to an unknown connector -> rejected, existing deployment untouched
#
# Prerequisites: MQTT connector (public broker or MQTT Service), mosquitto_pub, c8y CLI.
#
# Usage: ./test-inbound-deployment-reconcile.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="14. Deployment reconcile on change"

EXT_ID="dmtest-reconcile-$(date +%s)"
TOPIC="dmtest/reconcile/${EXT_ID}"
MAPPING_ID=""
DEVICE_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" --force </dev/null 2>/dev/null || true
        dm_delete_device "$DEVICE_ID"
    fi
}

# Number of measurements for the device (0 while the device does not exist yet).
measurement_count() {
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
    [ -n "$_id" ] && dm_count_measurements_since "$_id" "$START" || printf '0'
}

topic_subscribed() {   # prints true/false for this mapping's topic on the MQTT connector
    dm_api GET "/monitoring/subscription/${_DM_MQTT_CONNECTOR_ID}" \
        | jq -r --arg t "dmtest/reconcile/+" 'if type=="object" then (((.[$t] // 0)|tonumber? // 0) > 0) else false end'
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

START=$(dm_now -10)

dm_step 2 "Creating, deploying and activating the mapping"
MAPPING_JSON=$(jq -cn --arg name "test-reconcile-$$" --arg id "reconcile$$" --arg ext "$EXT_ID" '{
  name: $name,
  identifier: $id,
  mappingTopic: "dmtest/reconcile/+",
  mappingTopicSample: ("dmtest/reconcile/" + $ext),
  targetAPI: "MEASUREMENT",
  direction: "INBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  sourceTemplate: "{\"temperature\":20.0}",
  targetTemplate: "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  substitutions: [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
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
dm_assert_mqtt_topics_active "$MAPPING_ID"

dm_step 3 "Deployed: a message is processed"
dm_mqtt_publish "$TOPIC" '{"temperature":21.0}' 1
dm_assert_measurement_present "Measurement while deployed" "$EXT_ID" "c8y_Serial" 1 25
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
M1=$(measurement_count)

dm_step 4 "Un-deploying (PUT []) — no reconnect, no reload"
dm_undeploy_mapping "$MAPPING_ID"
if [ "${_DM_MQTT_SVC_MODE:-false}" != "true" ]; then
    for _i in 1 2 3 4 5 6 7 8; do
        [ "$(topic_subscribed)" = "false" ] && break
        sleep 1
    done
    dm_assert_eq "Topic unsubscribed after un-deploy" "false" "$(topic_subscribed)"
fi
dm_mqtt_publish "$TOPIC" '{"temperature":22.0}' 1
dm_wait 12 "give a (wrongly) still-subscribed connector time to process"
dm_assert_eq "No new measurement while un-deployed" "$M1" "$(measurement_count)"

dm_step 5 "Re-deploying — message flow resumes live"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"
dm_mqtt_publish "$TOPIC" '{"temperature":23.0}' 1
for _i in $(seq 1 12); do
    [ "$(measurement_count)" -gt "$M1" ] && break
    sleep 2
done
dm_assert_gt "New measurement after re-deploy" "$(measurement_count)" "$M1"

dm_step 6 "Deploying to an unknown connector is rejected and leaves the deployment intact"
if c8y api --method PUT --url "${DM_SERVICE}/deployment/defined/reconcile$$" \
        --template '["no-such-connector-xyz"]' --header 'Content-Type: application/json' \
        --force --output rawResponse </dev/null >/dev/null 2>&1; then
    _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
    dm_fail "[Unknown connector rejected] request unexpectedly succeeded"
else
    _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
    dm_success "[Unknown connector rejected]"
fi
RAW=$(c8y api --method GET --url "${DM_SERVICE}/deployment/defined/reconcile$$" \
    --header 'Content-Type: application/json' --force --raw </dev/null 2>/dev/null || printf '[]')
dm_assert_eq "Deployment still assigned to the MQTT connector" "true" \
    "$(printf '%s' "$RAW" | jq -r --arg c "$_DM_MQTT_CONNECTOR_ID" 'if type=="array" then (index($c) != null) else false end' 2>/dev/null)"

dm_done "$TEST_TITLE"
dm_print_summary
