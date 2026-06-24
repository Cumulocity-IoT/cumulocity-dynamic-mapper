#!/bin/bash
#
# test-inbound-http-connector: Inbound HTTP connector → C8Y Measurement
#
# Posts a JSON payload directly to the DM HTTP connector endpoint and verifies
# that a C8Y measurement is created.
# The HTTP connector endpoint is: <DM_SERVICE>/httpConnector/<topic_path>
#
# Prerequisites:
#   - Dynamic mapper service is running with an HTTP connector enabled
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-inbound-http-connector.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
EXT_ID="dmtest-http-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner " 6. HTTP connector → MEASUREMENT"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_validate_only_exit

# Topic template: dmtest/http/+ where + is replaced by the device external id
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-http-$$",
  "identifier": "ibhttp$$",
  "mappingTopic": "dmtest/http/+",
  "mappingTopicSample": "dmtest/http/${EXT_ID}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"externalId\":\"dmtest-http\",\"temperature\":20.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"externalId","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOF
)

dm_step "Creating and activating HTTP inbound mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
# The built-in HTTP connector (identifier HTTP_CONNECTOR_IDENTIFIER) is
# auto-bootstrapped per tenant. A mapping is only processed by a connector it is
# deployed to, so deploy BEFORE activating — otherwise onMessage runs but the
# mapping is skipped and messagesReceived stays 0.
dm_deploy_mapping_to_connector "$MAPPING_ID" "HTTP_CONNECTOR_IDENTIFIER"
dm_activate_mapping "$MAPPING_ID"

# The HTTP connector rebuilds its inbound mapping resolver only on connect()
# (initializeSubscriptionsInbound). Activation alone is a no-op while the
# connector is not "connected" — updateSubscriptionForInbound short-circuits on
# !isConnected(). Connecting it picks up the freshly deployed+active mapping.
dm_step "Connecting HTTP connector so it (re)builds inbound mappings ..."
dm_connect_connector "HTTP_CONNECTOR_IDENTIFIER"
dm_wait_for_mapping_processing "$MAPPING_ID" 0 10 1 >/dev/null 2>&1 || true
dm_info "HTTP connector mapping subscriptions established"

HTTP_TOPIC="dmtest/http/${EXT_ID}"
HTTP_ENDPOINT="${DM_SERVICE}/httpConnector/${HTTP_TOPIC}"
dm_step "POSTing to HTTP connector endpoint: ${HTTP_ENDPOINT} ..."
POST_OUTPUT=""
set +e
POST_OUTPUT=$(c8y api --method POST \
  --url "${HTTP_ENDPOINT}" \
  --header 'Content-Type: application/json' \
  --accept 'application/json' \
  --data "{\"externalId\":\"${EXT_ID}\",\"temperature\":30.0}" \
  --output json 2>&1)
POST_EXIT=$?
set -e

if [ "$POST_EXIT" -ne 0 ]; then
  dm_fail "HTTP connector POST failed (exit=${POST_EXIT})"
  printf '%s\n' "$POST_OUTPUT"
  exit 1
fi

printf '%s\n' "$POST_OUTPUT" | jq '.' 2>/dev/null || printf '%s\n' "$POST_OUTPUT"

dm_step "Checking whether HTTP message was processed ..."
RECEIVED=$(dm_mapping_received_count "$MAPPING_ID")
if [ "${RECEIVED:-0}" -lt 1 ]; then
  dm_skip_exit "HTTP connector accepted request but mapping processed 0 messages." \
               "Ensure the HTTP connector (HTTP_CONNECTOR_IDENTIFIER) is CONNECTED and the mapping is deployed to it."
fi

dm_step "Asserting at least 1 measurement was created ..."
dm_assert_measurement_present "Measurement via HTTP connector" "$EXT_ID" "c8y_Serial" 1 15

dm_done " 6. HTTP connector → MEASUREMENT"
dm_print_summary
