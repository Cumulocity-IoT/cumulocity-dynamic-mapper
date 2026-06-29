#!/bin/bash
#
# test-reconnect: Connector disconnect + reconnect cycle
#
# Verifies that an MQTT connector can be disconnected via the operations API
# and then successfully reconnected, ending in CONNECTED state.
#
# The connector to use is controlled by the DM_CONNECTOR_ID env var.
# If not set, the first MQTT connector found in the connector list is used.
#
# Prerequisites:
#   - Dynamic mapper service running with at least one MQTT connector
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   DM_CONNECTOR_ID=my-mqtt-connector ./test-reconnect.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="38. Connector disconnect / reconnect cycle"

CONNECTOR_ID="${DM_CONNECTOR_ID:-}"

dm_parse_args "$@"   # supports --validate-only (this test creates no persistent data)

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_validate_only_exit

if [ -z "$CONNECTOR_ID" ]; then
    dm_step "DM_CONNECTOR_ID not set — discovering first MQTT connector ..."
    # /monitoring/status/connectors may be a JSON array, a single status object,
    # or a map keyed by connector id — and c8y may emit it as NDJSON. Slurp (-s)
    # and normalize to a flat array of status objects before selecting.
    CONNECTORS=$(dm_api GET /monitoring/status/connectors | jq -s '
        [ .[]
          | if type == "array" then .[]
            elif (type == "object" and (.connectorIdentifier // "") != "" and (.status // "") != "") then .
            elif type == "object" then (to_entries[] | .value)
            else empty end ]' 2>/dev/null || echo '[]')
    # Prefer a connector whose type/name looks like MQTT; else take the first one.
    CONNECTOR_ID=$(printf '%s' "$CONNECTORS" | jq -r '
        ( [ .[] | select(
              ((.connectorType // "") | ascii_upcase | contains("MQTT"))
              or ((.name // .connectorName // "") | ascii_upcase | contains("MQTT"))
          ) ][0] // .[0] )
        | (.connectorIdentifier // .name // .connectorName // empty)' 2>/dev/null || echo "")
    if [ -z "$CONNECTOR_ID" ]; then
        dm_skip_exit "No connectors found — cannot run reconnect test."
    fi
    dm_info "Using connector: $CONNECTOR_ID"
fi

dm_step "Asserting connector is initially CONNECTED ..."
dm_assert_connector_status "Initially CONNECTED" "$CONNECTOR_ID" "CONNECTED"

dm_step "Disconnecting connector '$CONNECTOR_ID' ..."
dm_disconnect_connector "$CONNECTOR_ID"
dm_wait 5

dm_step "Asserting connector is NOT CONNECTED after disconnect ..."
STATUS_AFTER_DISCONNECT=$(dm_get_connector_status "$CONNECTOR_ID")
dm_info "Status after disconnect: $STATUS_AFTER_DISCONNECT"
if [ "$STATUS_AFTER_DISCONNECT" = "CONNECTED" ]; then
    dm_fail "Expected connector to be disconnected, but status is still CONNECTED"
fi
dm_success "Connector is no longer CONNECTED (status=$STATUS_AFTER_DISCONNECT)"

dm_step "Reconnecting connector '$CONNECTOR_ID' ..."
dm_connect_connector "$CONNECTOR_ID"
dm_wait 10

dm_step "Asserting connector is CONNECTED after reconnect ..."
dm_assert_connector_status "Reconnected" "$CONNECTOR_ID" "CONNECTED"

dm_done "$TEST_TITLE"
dm_print_summary
