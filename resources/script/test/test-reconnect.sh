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

CONNECTOR_ID="${DM_CONNECTOR_ID:-}"

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Connector Disconnect / Reconnect Cycle"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service

if [ -z "$CONNECTOR_ID" ]; then
    dm_step "DM_CONNECTOR_ID not set — discovering first MQTT connector ..."
    CONNECTORS_JSON=$(dm_api GET /monitoring/status/connectors)
    # Prefer connectors with "MQTT" in type or name
    CONNECTOR_ID=$(printf '%s' "$CONNECTORS_JSON" \
        | jq -r '[.[] | select(
                (.connectorType // "" | ascii_upcase | contains("MQTT")) or
                (.name // "" | ascii_upcase | contains("MQTT"))
              )][0] | .connectorIdentifier // .name // empty' 2>/dev/null || echo "")
    # Fall back to first connector of any type
    if [ -z "$CONNECTOR_ID" ]; then
        CONNECTOR_ID=$(printf '%s' "$CONNECTORS_JSON" \
            | jq -r 'first | .connectorIdentifier // .name // empty' 2>/dev/null || echo "")
    fi
    if [ -z "$CONNECTOR_ID" ]; then
        dm_warn "No connectors found — cannot run reconnect test"
        exit 0
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

dm_done "Connector Disconnect / Reconnect Cycle"
dm_print_summary
