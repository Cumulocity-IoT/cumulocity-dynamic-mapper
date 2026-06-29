#!/bin/bash
#
# test-cumulocity-mqtt-service: Cumulocity MQTT Service connector lifecycle
#
# Verifies the full lifecycle of the (singleton) Cumulocity MQTT Service
# connector:
#   1. Delete any pre-existing Cumulocity MQTT Service connector(s).
#   2. Create a fresh Cumulocity MQTT Service connector and enable it.
#   3. Connect it and assert it reaches CONNECTED.
#   4. Disconnect it and assert it is no longer CONNECTED.
#
# The Cumulocity MQTT Service connector is the Pulsar-based singleton
# CUMULOCITY_MQTT_SERVICE_PULSAR (spec name "Cumulocity MQTT-Service"). The
# pre-delete step removes any existing connector of this type, so the tenant's
# existing one is cleaned up before a fresh one is created.
#
# Connection parameters (service URL / credentials) are derived from the
# microservice credentials at connect time (copied from the connector
# specification via copyPredefinedValues), so the create body only needs the
# connector type, identifier and name.
#
# The connector identifier can be overridden with DM_C8Y_MQTT_CONNECTOR_ID.
#
# Prerequisites:
#   - Dynamic mapper service running
#   - Tenant has the Cumulocity MQTT Service (Pulsar) available and reachable
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-cumulocity-mqtt-service.sh                 # run + cleanup (default)
#   ./test-cumulocity-mqtt-service.sh --keep          # retain created connector
#   ./test-cumulocity-mqtt-service.sh --validate-only # validate env, then exit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="39. Cumulocity MQTT Service connector lifecycle"

CONNECTOR_TYPE="CUMULOCITY_MQTT_SERVICE_PULSAR"

# NOTE: the identifier MUST be alphanumeric (no hyphens/underscores). On connect
# the notification layer builds a reliable-notification subscriber name by
# concatenating a prefix with this identifier (e.g.
# "DynamicMapperStaticDeviceSubscriber<identifier>"), and Cumulocity rejects any
# non-alphanumeric subscriber with HTTP 422 "has to be alphanumeric!".
CONNECTOR_ID="${DM_C8Y_MQTT_CONNECTOR_ID:-testc8ymqttservice}"
CONNECTOR_NAME="Test Cumulocity MQTT Service"

dm_parse_args "$@"

# Set to true once we have created our own connector, so cleanup only deletes
# what actually exists (deleting a non-existent connector triggers a backend NPE).
_CONNECTOR_CREATED=false

# Delete every existing Cumulocity MQTT Service connector. Sets the global
# _DELETED_COUNT to the number of connectors found (deletion attempted). Progress
# is printed normally to stdout, so the count is returned via the global rather
# than captured from stdout.
#
# IMPORTANT (Pulsar delete ordering): DELETE the connector directly while it is
# still ENABLED. Do NOT disconnect or disable it first.
#   - The DISCONNECT operation sets enabled=false (OperationController#handleDisconnect),
#     and DELETE then sees `wasDisabled && type==PULSAR`
#     (ConfigurationController#deleteConnectionConfiguration) → it takes the
#     "temporarily RE-ENABLE + reconnect (10s)" branch, which re-saves and
#     resurrects the connector (back to CONNECTED) instead of removing it.
#   - Deleting while enabled takes the clean path: unsubscribe → disable →
#     shutdown → delete config.
_DELETED_COUNT=0
delete_existing_c8y_mqtt_service_connectors() {
    local _id
    _DELETED_COUNT=0
    while IFS= read -r _id; do
        [ -z "$_id" ] && continue
        _DELETED_COUNT=$((_DELETED_COUNT + 1))
        dm_info "Found existing $CONNECTOR_TYPE connector: $_id — deleting (while enabled)"
        dm_delete_connector "$_id"
    done <<< "$(dm_list_connector_ids_by_type "$CONNECTOR_TYPE")"
}

# Count how many connectors of CONNECTOR_TYPE currently exist.
count_c8y_mqtt_service_connectors() {
    dm_list_connector_ids_by_type "$CONNECTOR_TYPE" | grep -c . || true
}

# ── Cleanup (always runs unless --keep is set) ───────────────────────────────────
cleanup() {
    dm_info "Cleaning up test resources ..."
    if [ "$_CONNECTOR_CREATED" = "true" ]; then
        dm_disconnect_connector "$CONNECTOR_ID" 2>/dev/null || true
        dm_delete_connector "$CONNECTOR_ID" 2>/dev/null || true
    else
        dm_info "Test connector was never created — nothing to delete."
    fi
    dm_info "Cleanup complete"
}
dm_register_cleanup cleanup

# ── Test ─────────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate false
dm_validate_only_exit

dm_step "Deleting any existing Cumulocity MQTT Service connector(s) ..."
delete_existing_c8y_mqtt_service_connectors
if [ "${_DELETED_COUNT:-0}" -eq 0 ]; then
    dm_info "No existing Cumulocity MQTT Service connector found — nothing to delete."
fi

# A Cumulocity MQTT Service connector is a singleton: there must be exactly zero
# of this type before we create a fresh one, otherwise we would end up with
# duplicates. Deletion (especially of a Pulsar connector) can take a few seconds,
# so poll until the type is gone. Fail loudly if any remain after the timeout.
REMAINING=$(count_c8y_mqtt_service_connectors)
_elapsed=0
while [ "${REMAINING:-0}" -ne 0 ] && [ "$_elapsed" -lt 45 ]; do
    dm_wait 3 "for deletion to complete (${REMAINING} remaining)"
    _elapsed=$((_elapsed + 3))
    REMAINING=$(count_c8y_mqtt_service_connectors)
done
if [ "${REMAINING:-0}" -ne 0 ]; then
    # Diagnostics: show the surviving connector's config + live status so we can
    # see whether the DELETE was rejected or the connector was re-created/reconnected.
    while IFS= read -r _surv; do
        [ -z "$_surv" ] && continue
        dm_warn "Surviving connector $_surv config: $(dm_api GET "/configuration/connector/instance/$_surv" | tr -d '\n')"
        dm_warn "Surviving connector $_surv status: $(dm_get_connector_status "$_surv" | tr -d '\n')"
    done <<< "$(dm_list_connector_ids_by_type "$CONNECTOR_TYPE")"
    dm_error "Expected 0 existing $CONNECTOR_TYPE connectors after cleanup, but $REMAINING remain: $(dm_list_connector_ids_by_type "$CONNECTOR_TYPE" | tr '\n' ' ')"
fi
dm_success "No $CONNECTOR_TYPE connector exists — safe to create a fresh one."

dm_step "Creating a new Cumulocity MQTT Service connector '$CONNECTOR_ID' (type=$CONNECTOR_TYPE) ..."
dm_setup_c8y_mqtt_service_connector "$CONNECTOR_ID" "$CONNECTOR_NAME" "$CONNECTOR_TYPE"
_CONNECTOR_CREATED=true
dm_enable_connector "$CONNECTOR_ID"

dm_step "Connecting connector '$CONNECTOR_ID' ..."
dm_connect_connector "$CONNECTOR_ID"

dm_step "Asserting connector is CONNECTED ..."
STATUS_AFTER_CONNECT=$(dm_wait_for_connector_status "$CONNECTOR_ID" "CONNECTED" 45 3)
dm_info "Status after connect: $STATUS_AFTER_CONNECT"
dm_assert_eq "Connector CONNECTED after connect" "CONNECTED" "$STATUS_AFTER_CONNECT"

dm_step "Disconnecting connector '$CONNECTOR_ID' ..."
dm_disconnect_connector "$CONNECTOR_ID"
dm_wait 5 "for disconnect to settle"

dm_step "Asserting connector is no longer CONNECTED ..."
STATUS_AFTER_DISCONNECT=$(dm_get_connector_status "$CONNECTOR_ID" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
dm_info "Status after disconnect: $STATUS_AFTER_DISCONNECT"
dm_assert_ne "Connector not CONNECTED after disconnect" "CONNECTED" "$STATUS_AFTER_DISCONNECT"

dm_done "$TEST_TITLE"
dm_print_summary
