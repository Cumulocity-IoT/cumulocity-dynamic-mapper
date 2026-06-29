#!/bin/bash
#
# test-c8y-mqtt-service-spike: Phase 0 spike — full inbound round-trip
#
# OPTIONAL standalone diagnostic — NOT part of the suite (run-tests.sh) and not
# required for regression. The same inbound path is covered by
#   ./run-tests.sh test-inbound-json-default m
# Keep this as a self-contained, verbose probe for first-time MQTT Service setup
# on a new tenant or when diagnosing cert/reachability/connector problems: it
# creates its own throwaway connector and tears everything down (use --keep to
# inspect). See ENHANCEMENT.md (Phase 0).
#
# Proves the gating end-to-end path: an X.509-cert-authenticated MQTT client
# publishes to the Cumulocity MQTT Service, the message flows through a
# CUMULOCITY_MQTT_SERVICE_PULSAR connector, and a deployed+active mapping
# produces a measurement on the resolved device.
#
# Flow:
#   1. Validate tools + reachability of :9883 + CA bundle.
#   2. Resolve/create a CONNECTED CUMULOCITY_MQTT_SERVICE_PULSAR connector.
#   3. Provision a self-signed X.509 client cert (CN == clientId), uploaded as a
#      trusted cert.
#   4. Create the device + register external id c8y_Serial == clientId.
#   5. Create + DEPLOY (verified) + activate the inbound mapping.
#   6. Publish via cert-authenticated mosquitto_pub to :9883.
#   7. Assert the measurement was created.
#
# Auth (per docs): TLS on port 9883; client cert with CN == MQTT clientId;
# tenant id in the MQTT username field. QoS 0/1 only; no retained; clean session.
#
# Config (defaults derived from the active c8y session):
#   DM_C8Y_MQTT_HOST   (default $C8Y_DOMAIN)
#   DM_C8Y_MQTT_PORT   (default 9883)
#   MQTT_CAFILE        (CA bundle for TLS server verification; auto-discovered)
#   MQTT_INSECURE      (true to skip server hostname verification)
#
# Usage:
#   ./test-c8y-mqtt-service-spike.sh                 # run + cleanup
#   ./test-c8y-mqtt-service-spike.sh --keep          # keep created artifacts
#   ./test-c8y-mqtt-service-spike.sh --validate-only # checks only, no data

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="MQTT Service Spike — cert-auth inbound round-trip"

CONNECTOR_TYPE="CUMULOCITY_MQTT_SERVICE_PULSAR"
MQTT_HOST="${DM_C8Y_MQTT_HOST:-${C8Y_DOMAIN:-}}"
MQTT_PORT="${DM_C8Y_MQTT_PORT:-9883}"
TENANT="${C8Y_TENANT:-}"

# Alphanumeric ids (connector id + cert CN become a notification subscriber name
# and the MQTT clientId respectively — both must be alphanumeric).
CLIENT_ID="dmtestmqtt$$"
EXT_ID="$CLIENT_ID"
TOPIC="dmtest/mqttsvc/${EXT_ID}"
PAYLOAD='{"temperature": 42.5}'

PULSAR_ID=""
_CREATED_CONNECTOR=false
MAPPING_ID=""
DEVICE_ID=""
_CREATED_DEVICE=false

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up spike resources ..."
    [ -n "${MAPPING_ID:-}" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ "${_CREATED_DEVICE}" = "true" ] && [ -n "${DEVICE_ID:-}" ]; then
        c8y inventory delete --id "$DEVICE_ID" --force </dev/null >/dev/null 2>&1 || true
    fi
    dm_cleanup_mqtt_service_cert
    if [ "${_CREATED_CONNECTOR}" = "true" ] && [ -n "${PULSAR_ID:-}" ]; then
        dm_disconnect_connector "$PULSAR_ID" 2>/dev/null || true
        dm_delete_connector "$PULSAR_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}
dm_register_cleanup cleanup

# ── Test ─────────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Validating environment ..."
dm_test_setup_and_validate false
command -v openssl >/dev/null 2>&1 || dm_error "openssl is required for this spike"
command -v mosquitto_pub >/dev/null 2>&1 || dm_error "mosquitto_pub is required for this spike"

[ -n "$MQTT_HOST" ] || dm_error "MQTT host unknown — set DM_C8Y_MQTT_HOST or ensure C8Y_DOMAIN is exported"
[ -n "$TENANT" ]    || dm_error "Tenant unknown — ensure C8Y_TENANT is exported by the c8y session"

dm_step "Checking reachability of ${MQTT_HOST}:${MQTT_PORT} ..."
nc -z -w 5 "$MQTT_HOST" "$MQTT_PORT" >/dev/null 2>&1 \
    || dm_error "MQTT Service endpoint ${MQTT_HOST}:${MQTT_PORT} is not reachable from this host."
dm_success "Endpoint reachable: ${MQTT_HOST}:${MQTT_PORT}"

dm_step "Resolving CA bundle for TLS server verification ..."
CA_BUNDLE="$(dm_ca_bundle || true)"
if [ -z "$CA_BUNDLE" ]; then
    dm_error "No CA bundle found. Set MQTT_CAFILE to your system CA bundle (e.g. /etc/ssl/cert.pem)."
fi
dm_info "Using CA bundle: $CA_BUNDLE"

dm_validate_only_exit

dm_step "Resolving (or creating) a CONNECTED ${CONNECTOR_TYPE} connector ..."
PULSAR_ID="$(dm_list_connector_ids_by_type "$CONNECTOR_TYPE" | head -n 1 || true)"
if [ -z "$PULSAR_ID" ]; then
    PULSAR_ID="spikec8ymqtt$$"
    dm_info "No existing ${CONNECTOR_TYPE} connector — creating ${PULSAR_ID}"
    dm_setup_c8y_mqtt_service_connector "$PULSAR_ID" "Spike Cumulocity MQTT Service" "$CONNECTOR_TYPE"
    _CREATED_CONNECTOR=true
else
    dm_info "Using existing connector: $PULSAR_ID"
fi
dm_enable_connector "$PULSAR_ID"
dm_connect_connector "$PULSAR_ID"
CONN_STATUS="$(dm_wait_for_connector_status "$PULSAR_ID" "CONNECTED" 45 3)"
[ "$CONN_STATUS" = "CONNECTED" ] || dm_error "Connector $PULSAR_ID is not CONNECTED (status=$CONN_STATUS)"
dm_success "Connector CONNECTED: $PULSAR_ID"

dm_step "Provisioning X.509 client certificate (CN=${CLIENT_ID}) ..."
dm_provision_mqtt_service_cert "$CLIENT_ID"
dm_wait 5 "for the trusted certificate to be registered"

dm_step "Creating the device (external id c8y_Serial=${EXT_ID}) ..."
DEVICE_ID="$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")"
if [ -n "$DEVICE_ID" ]; then
    dm_info "Device already exists for external id ${EXT_ID}: ${DEVICE_ID}"
else
    dm_create_device "spike-mqttsvc-${CLIENT_ID}" "c8y_MQTTService"
    DEVICE_ID="$_DM_LAST_DEVICE_ID"
    _CREATED_DEVICE=true
    c8y identity create --device "$DEVICE_ID" --type c8y_Serial --name "$EXT_ID" \
        --force --output json </dev/null >/dev/null 2>&1 \
        || dm_error "Failed to register external id ${EXT_ID} (c8y_Serial) on device ${DEVICE_ID}"
    dm_info "Registered external id c8y_Serial=${EXT_ID} → device ${DEVICE_ID}"
fi
dm_success "Device ready: $DEVICE_ID"

dm_step "Creating + deploying inbound mapping (JSON/DEFAULT → MEASUREMENT) ..."
# Non-empty sourceTemplate + a `$now()` → time substitution are both required
# (an empty sourceTemplate or a missing time breaks the legacy→JSONATA migration
# and no measurement is produced). Exact topic (no `+`); _TOPIC_LEVEL_[2] resolves
# the external id from the 3-level topic.
MAPPING_JSON=$(cat <<EOF
{
  "name": "spike-mqttsvc-$$",
  "identifier": "spikemqtt$$",
  "mappingTopic": "${TOPIC}",
  "mappingTopicSample": "${TOPIC}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"temperature\":25.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": true,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOF
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
# Deploy is verified (fails loudly if the connector isn't actually registered in
# the deployment map — the cause of the earlier "No active connector" symptom).
dm_deploy_mapping_to_connector "$MAPPING_ID" "$PULSAR_ID"
dm_activate_mapping "$MAPPING_ID"
# Order matters: deploy + activate while the connector is CONNECTED. Activate
# notifies the live connector to bind the mapping
# (OperationController#handleActivateMapping → updateSubscriptionForInbound →
# addSubscriptionInbound → effectiveMappingsInbound).
dm_wait 6 "for the connector to bind the activated mapping"
dm_success "Mapping ${MAPPING_ID} deployed + active on connector ${PULSAR_ID}"

dm_step "Publishing via cert-authenticated MQTT to the MQTT Service ..."
TEST_START="$(dm_now -10)"
PUB_ARGS=(-h "$MQTT_HOST" -p "$MQTT_PORT"
          -i "$CLIENT_ID" -u "$TENANT"
          -t "$TOPIC" -m "$PAYLOAD" -q 1
          --cert "$_DM_MQTT_CERT" --key "$_DM_MQTT_KEY"
          --cafile "$CA_BUNDLE" --tls-version tlsv1.2)
[ "${MQTT_INSECURE:-false}" = "true" ] && PUB_ARGS+=(--insecure)

# Publish twice with a gap: the first message right after a fresh Pulsar consumer
# subscription is occasionally not yet routed; a second confirms steady state.
dm_info "mosquitto_pub -h ${MQTT_HOST}:${MQTT_PORT} -i ${CLIENT_ID} -u ${TENANT} -t ${TOPIC} (cert auth, qos 1)"
for _attempt in 1 2; do
    if ! mosquitto_pub "${PUB_ARGS[@]}"; then
        dm_error "mosquitto_pub failed — verify cert trust registration, the 'Mqtt service' permission, and the CA bundle (${CA_BUNDLE})."
    fi
    dm_success "Published #${_attempt} to ${TOPIC}"
    dm_wait 6 "between/after publishes"
done

dm_step "Waiting for inbound processing ..."
dm_wait 8 "for MQTT Service → Pulsar → mapper → C8Y"

# ── Self-diagnostics (so we don't depend on truncated backend logs) ──────────────
dm_step "Diagnostics: mapping status + connector status ..."
MAPPING_STATUS="$(dm_api_json_array GET /monitoring/status/mapping/statistic \
    | jq -rs --arg id "$MAPPING_ID" 'flatten(1) | map(select(.id == $id))[0] // "NO STATUS ENTRY"' 2>/dev/null || echo '?')"
dm_info "Mapping ${MAPPING_ID} status: ${MAPPING_STATUS}"
dm_info "Connector ${PULSAR_ID} status: $(dm_get_connector_status "$PULSAR_ID" | jq -c '{status,message}' 2>/dev/null || echo '?')"

dm_step "Verifying the measurement was created ..."
dm_assert_measurement_count_gt "Inbound measurement via MQTT Service" "$DEVICE_ID" "$TEST_START" 0

dm_done "$TEST_TITLE"
dm_print_summary
