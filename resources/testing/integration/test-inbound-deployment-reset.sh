#!/bin/bash
#
# test-inbound-deployment-reset: RESET_DEPLOYMENT_MAP undeploys everything, live
#
# The RESET_DEPLOYMENT_MAP operation empties the deployment map of the WHOLE TENANT. It must
# take effect immediately — connectors drop their inbound subscriptions without a reconnect or
# reload — and leave a service event behind. Steps:
#   1. mapping deployed + active    -> message creates a measurement; /deployment/effective lists it
#   2. POST /operation RESET_DEPLOYMENT_MAP
#                                   -> topic unsubscribed, next message is NOT processed,
#                                      the deployment map is empty, effective view has no entry
#   3. a "Deployment map reset" service event was written
#   4. re-deploy                    -> message flow resumes
#
# DESTRUCTIVE for shared tenants: every mapping of the tenant is undeployed until the test
# restores the snapshot it takes first (also on failure, via the cleanup trap; a copy is kept in
# tmp/dm-deployment-snapshot-<pid>.json until the restore succeeded). Skipped unless explicitly
# allowed:
#
#   DM_ALLOW_DEPLOYMENT_RESET=true ./test-inbound-deployment-reset.sh
#
# Prerequisites: MQTT connector (public broker or MQTT Service), mosquitto_pub, c8y CLI.
#
# Usage: DM_ALLOW_DEPLOYMENT_RESET=true ./test-inbound-deployment-reset.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Deployment map reset (live)"

EXT_ID="dmtest-reset-$(date +%s)"
IDENTIFIER="reset$$"
TOPIC="dmtest/reset/${EXT_ID}"
MAPPING_ID=""
DEVICE_ID=""
SNAPSHOT_FILE="${SCRIPT_DIR}/tmp/dm-deployment-snapshot-$$.json"
SNAPSHOT_TAKEN=false

# Put every deployment of the snapshot back (PUT /deployment/defined/{mapping}); removes the
# snapshot file only when all PUTs succeeded.
restore_snapshot() {
    [ "$SNAPSHOT_TAKEN" = "true" ] && [ -s "$SNAPSHOT_FILE" ] || return 0
    local _key _body _failed=0
    while IFS= read -r _key; do
        _body=$(jq -c --arg k "$_key" '.[$k]' "$SNAPSHOT_FILE")
        [ "$_body" = "[]" ] && continue
        if c8y api --method PUT --url "${DM_SERVICE}/deployment/defined/${_key}" \
                --template "$_body" --header 'Content-Type: application/json' \
                --force --output rawResponse </dev/null >/dev/null 2>&1; then
            dm_info "Restored deployment of ${_key}: ${_body}"
        else
            _failed=1
            dm_warn "Could not restore deployment of ${_key} (${_body}) — snapshot kept in ${SNAPSHOT_FILE}"
        fi
    done < <(jq -r 'keys[]' "$SNAPSHOT_FILE")
    [ "$_failed" -eq 0 ] && rm -f "$SNAPSHOT_FILE"
    return 0
}

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" --force </dev/null 2>/dev/null || true
        dm_delete_device "$DEVICE_ID"
    fi
    restore_snapshot
}

# ── Local helpers ──────────────────────────────────────────────────────────────

measurement_count() {
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
    [ -n "$_id" ] && dm_count_measurements_since "$_id" "$START" || printf '0'
}

topic_subscribed() {   # prints true/false for this mapping's topic on the MQTT connector
    dm_api GET "/monitoring/subscription/${_DM_MQTT_CONNECTOR_ID}" \
        | jq -r --arg t "dmtest/reset/+" 'if type=="object" then (((.[$t] // 0)|tonumber? // 0) > 0) else false end'
}

# GET with the JSON content type the deployment endpoints require; prints the raw body.
deployment_get() {   # <path>
    c8y api --method GET --url "${DM_SERVICE}$1" \
        --header 'Content-Type: application/json' --force --raw </dev/null 2>/dev/null || printf 'null'
}

dm_parse_args "$@"

if [ "${DM_ALLOW_DEPLOYMENT_RESET:-false}" != "true" ]; then
    dm_skip_exit \
        "RESET_DEPLOYMENT_MAP undeploys every mapping of the tenant (the test restores them afterwards)." \
        "Re-run with DM_ALLOW_DEPLOYMENT_RESET=true to enable this test."
fi

dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

START=$(dm_now -10)

dm_step 2 "Snapshotting the tenant's deployment map"
mkdir -p "$(dirname "$SNAPSHOT_FILE")"
deployment_get /deployment/defined | jq -c 'if type == "object" then . else {} end' >"$SNAPSHOT_FILE"
chmod 600 "$SNAPSHOT_FILE"
SNAPSHOT_TAKEN=true
dm_info "Snapshot (${SNAPSHOT_FILE}): $(jq -c . "$SNAPSHOT_FILE" | head -c 300)"

dm_step 3 "Creating, deploying and activating the mapping"
MAPPING_JSON=$(jq -cn --arg name "test-reset-$$" --arg id "$IDENTIFIER" --arg ext "$EXT_ID" '{
  name: $name,
  identifier: $id,
  mappingTopic: "dmtest/reset/+",
  mappingTopicSample: ("dmtest/reset/" + $ext),
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

dm_step 4 "Deployed: a message is processed and the effective view lists the mapping"
dm_mqtt_publish "$TOPIC" '{"temperature":21.0}' 1
dm_assert_measurement_present "Measurement while deployed" "$EXT_ID" "c8y_Serial" 1 25
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
M1=$(measurement_count)
dm_assert_eq "Effective view lists a connector for the mapping" "true" \
    "$(deployment_get /deployment/effective | jq -r --arg id "$IDENTIFIER" '((.[$id].connectors // []) | length) > 0' 2>/dev/null || printf false)"

dm_step 5 "RESET_DEPLOYMENT_MAP — no reconnect, no reload"
dm_api_must POST /operation '{"operation":"RESET_DEPLOYMENT_MAP"}' >/dev/null
if [ "${_DM_MQTT_SVC_MODE:-false}" != "true" ]; then
    for _i in 1 2 3 4 5 6 7 8; do
        [ "$(topic_subscribed)" = "false" ] && break
        sleep 1
    done
    dm_assert_eq "Topic unsubscribed after the reset" "false" "$(topic_subscribed)"
fi
dm_mqtt_publish "$TOPIC" '{"temperature":22.0}' 1
dm_wait 12 "give a (wrongly) still-subscribed connector time to process"
dm_assert_eq "No new measurement after the reset" "$M1" "$(measurement_count)"
dm_assert_eq "Deployment map is empty" "0" "$(deployment_get /deployment/defined | jq 'if type == "object" then length else -1 end' 2>/dev/null || printf -1)"
dm_assert_eq "Effective view has no entry for the mapping" "0" \
    "$(deployment_get /deployment/effective | jq -r --arg id "$IDENTIFIER" '((.[$id].connectors // []) | length)' 2>/dev/null || printf -1)"

dm_step 6 "The reset left a service event"
EVENT_COUNT=0
for _i in 1 2 3 4 5; do
    EVENT_COUNT=$(c8y api --method GET \
        --url "/event/events?type=d11r_mappingChangedEvent&dateFrom=${START}&pageSize=50" \
        --force --raw </dev/null 2>/dev/null \
        | jq '[.events[]? | select((.text // "") | startswith("Deployment map reset"))] | length' 2>/dev/null || printf 0)
    [ "${EVENT_COUNT:-0}" -gt 0 ] && break
    sleep 2
done
dm_assert_gt "'Deployment map reset' service event written" "${EVENT_COUNT:-0}" 0

dm_step 7 "Re-deploying — message flow resumes live"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"
dm_mqtt_publish "$TOPIC" '{"temperature":23.0}' 1
for _i in $(seq 1 12); do
    [ "$(measurement_count)" -gt "$M1" ] && break
    sleep 2
done
dm_assert_gt "New measurement after re-deploy" "$(measurement_count)" "$M1"

dm_done "$TEST_TITLE"
dm_print_summary
