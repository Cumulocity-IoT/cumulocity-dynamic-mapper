#!/bin/bash
#
# lib/connectors.sh — connector lifecycle helpers and http-polling-mock helpers (REST_POLLING / WEB_HOOK)
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── Connector helpers ──────────────────────────────────────────────────────────
dm_connect_connector() {    # <connectorIdentifier>
    dm_api POST /operation \
        "{\"operation\":\"CONNECT\",\"parameter\":{\"connectorIdentifier\":\"$1\"}}" >/dev/null
    dm_info "CONNECT requested for connector: $1"
}

dm_disconnect_connector() { # <connectorIdentifier>
    dm_api POST /operation \
        "{\"operation\":\"DISCONNECT\",\"parameter\":{\"connectorIdentifier\":\"$1\"}}" >/dev/null
    dm_info "Disconnected connector: $1"
}

dm_get_connector_status() {     # <connectorIdentifier>
    dm_api GET "/monitoring/status/connector/$1"
}

dm_assert_connector_status() {  # <label> <connectorIdentifier> <expected_status>
    local _status
    _status=$(dm_get_connector_status "$2" | jq -r '.status // empty' 2>/dev/null || printf '')
    dm_assert_eq "$1" "$3" "${_status:-UNKNOWN}"
}

# Create a test MQTT connector configuration
# Optional parameters: <identifier> [name] [mqtt_host] [mqtt_port]
# Defaults: identifier=test-mqtt-connector, mqtt_host=$MQTT_HOST, mqtt_port=$MQTT_PORT
dm_setup_mqtt_test_connector() {    # [identifier] [name] [mqtt_host] [mqtt_port]
    local _identifier="${1:-test-mqtt-connector}"
    local _name="${2:-Test MQTT Connector}"
    local _host="${3:-${MQTT_HOST:-broker.hivemq.com}}"
    local _port="${4:-${MQTT_PORT:-1883}}"
    
    dm_api POST /configuration/connector/instance "{
      \"identifier\": \"$_identifier\",
      \"connectorType\": \"MQTT\",
      \"name\": \"$_name\",
      \"description\": \"Auto-configured MQTT connector for integration tests\",
      \"enabled\": false,
      \"properties\": {
        \"mqttHost\": \"$_host\",
        \"mqttPort\": $_port,
        \"clientId\": \"dynamic_mapper_test\",
        \"cleanSession\": true,
        \"protocol\": \"mqtt://\",
        \"tls\": false,
        \"insecure\": true
      }
    }" >/dev/null || true
    
    _DM_MQTT_CONNECTOR_ID="$_identifier"
    dm_info "Created MQTT test connector: $_identifier (host=$_host:$_port)"
}

# Set a connector's `enabled` flag via a full-config round-trip.
# The update endpoint binds a @Valid ConnectorConfiguration whose identifier,
# connectorType, name and properties are all @NotNull — a partial PUT of just
# {"enabled": <bool>} fails validation (MethodArgumentNotValidException → 400).
# So GET the current config, flip `enabled`, and PUT the whole object back.
# Sensitive properties returned masked as "****" are restored to their original
# values server-side, so the round-trip is safe.
_dm_set_connector_enabled() {   # <connectorIdentifier> <true|false>
    local _id="$1" _enabled="$2" _cfg _body
    _cfg=$(dm_api GET "/configuration/connector/instance/$_id")
    if [ -z "$_cfg" ] || [ "$_cfg" = "{}" ]; then
        dm_warn "Cannot set enabled=$_enabled for connector $_id — configuration not found"
        return 1
    fi
    _body=$(printf '%s' "$_cfg" | jq -c --argjson e "$_enabled" '.enabled = $e')
    dm_api PUT "/configuration/connector/instance/$_id" "$_body" >/dev/null || true
}

# Enable a connector configuration
dm_enable_connector() {     # <connectorIdentifier>
    _dm_set_connector_enabled "$1" true
    dm_info "Enabled connector: $1"
}

# Disable a connector configuration
dm_disable_connector() {    # <connectorIdentifier>
    _dm_set_connector_enabled "$1" false
    dm_info "Disabled connector: $1"
}

# Delete a connector configuration by identifier. Goes through dm_api, which
# passes --force and feeds /dev/null to stdin (required inside `while read` loops
# — see dm_api). Without those, go-c8y-cli silently skips the DELETE.
dm_delete_connector() {     # <connectorIdentifier>
    dm_api DELETE "/configuration/connector/instance/$1" >/dev/null
    dm_info "Requested delete of connector: $1"
}

# Print the identifiers (one per line) of all connectors matching a connectorType.
# Robust to both response shapes c8y may emit for a list endpoint:
#   - NDJSON: one object per line  → slurp yields [ {..}, {..} ]
#   - a single JSON array          → slurp yields [ [ {..}, {..} ] ]
# The leading flatten step normalises both into a flat stream of objects.
dm_list_connector_ids_by_type() {   # <connectorType>
    dm_api_json_array GET /configuration/connector/instance \
        | jq -rs --arg t "$1" '
            [ .[] | if type == "array" then .[] else . end ]
            | map(select(type == "object"))
            | .[]
            | select((.connectorType // "") == $t)
            | (.identifier // empty)' 2>/dev/null || true
}

# Build the default `properties` object for a connector type from its
# specification (GET /connector/specifications), exactly as the UI pre-populates
# a new connector: every spec property that has a non-null defaultValue becomes
# {key: defaultValue}. Prints a JSON object ('{}' if the spec can't be resolved).
dm_connector_default_properties() {   # <connectorType>
    local _type="$1" _props
    _props=$(dm_api GET /configuration/connector/specifications \
        | jq -cs --arg t "$_type" '
            [ .[] | if type == "array" then .[] else . end ]
            | map(select(type == "object" and .connectorType == $t))
            | (.[0].properties // {})
            | with_entries(select(.value.defaultValue != null)
                           | {key: .key, value: .value.defaultValue})' 2>/dev/null)
    [ -z "$_props" ] && _props='{}'
    printf '%s' "$_props"
}

# Create (or replace) a Cumulocity MQTT Service connector configuration (disabled).
# The Cumulocity MQTT Service is the Pulsar-based singleton
# CUMULOCITY_MQTT_SERVICE_PULSAR. The create body is populated with the full set
# of default properties from the connector specification (like the UI does), not
# just an empty object — so the stored connector carries all properties. The
# readonly connection params (serviceUrl / credentials) are still refreshed from
# the microservice credentials at connect time via copyPredefinedValues.
# NOTE: the identifier must be alphanumeric — it becomes part of the
# reliable-notification subscriber name.
dm_setup_c8y_mqtt_service_connector() {     # [identifier] [name] [connectorType]
    local _identifier="${1:-testc8ymqttservice}"
    local _name="${2:-Test Cumulocity MQTT Service}"
    local _type="${3:-CUMULOCITY_MQTT_SERVICE_PULSAR}"
    local _props
    _props=$(dm_connector_default_properties "$_type")

    dm_api POST /configuration/connector/instance "{
      \"identifier\": \"$_identifier\",
      \"connectorType\": \"$_type\",
      \"name\": \"$_name\",
      \"description\": \"Auto-configured Cumulocity MQTT Service connector for integration tests\",
      \"enabled\": false,
      \"properties\": $_props
    }" >/dev/null || true

    dm_info "Created Cumulocity MQTT Service connector: $_identifier (type=$_type) with $(printf '%s' "$_props" | jq 'length') default properties"
}

# Poll a connector's status until it equals the expected value or a timeout
# elapses. Returns 0 (and prints the status) once matched, 1 on timeout.
# Useful for connectors that connect asynchronously (e.g. Pulsar with retry).
dm_wait_for_connector_status() {    # <connectorIdentifier> <expected_status> [timeout_secs=30] [interval_secs=3]
    local _id="$1" _expected="$2" _timeout="${3:-30}" _interval="${4:-3}"
    local _elapsed=0 _status
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _status=$(dm_get_connector_status "$_id" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
        if [ "$_status" = "$_expected" ]; then
            printf '%s\n' "$_status"
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    _status=$(dm_get_connector_status "$_id" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
    printf '%s\n' "$_status"
    [ "$_status" = "$_expected" ]
}

# Complete setup: create, enable, and connect MQTT test connector
# Optional parameters: <identifier> [name] [mqtt_host] [mqtt_port]
dm_setup_and_connect_mqtt_connector() {     # [identifier] [name] [mqtt_host] [mqtt_port]
    local _identifier="${1:-test-mqtt-connector}"
    dm_setup_mqtt_test_connector "$_identifier" "${2:-Test MQTT Connector}" "${3:-${MQTT_HOST:-broker.hivemq.com}}" "${4:-${MQTT_PORT:-1883}}"
    dm_enable_connector "$_DM_MQTT_CONNECTOR_ID"
    dm_connect_connector "$_DM_MQTT_CONNECTOR_ID"
    dm_wait 5 "waiting for MQTT connector to establish connection"
}

# ── http-polling-mock helpers (REST_POLLING inbound / WEB_HOOK outbound) ───────
# The mock (resources/testing/environments/http-polling) is deployed as a
# Cumulocity microservice, so every call to it passes the platform gateway and
# needs real Cumulocity credentials — for the connector under test as well as for
# the test's own inspection calls (which reuse the c8y session).
#
# Environment:
#   DM_MOCK_BASE_URL   Full base URL of the mock
#                      (default: ${C8Y_HOST}/service/http-polling-mock)
#   C8Y_TOKEN          Bearer token of the c8y session (preferred), or
#   C8Y_USER + C8Y_PASSWORD (+ C8Y_TENANT)  for Basic auth
DM_MOCK_PATH="${DM_MOCK_PATH:-/service/http-polling-mock}"

dm_mock_base_url() {
    if [ -n "${DM_MOCK_BASE_URL:-}" ]; then
        printf '%s' "${DM_MOCK_BASE_URL%/}"
    elif [ -n "${C8Y_HOST:-}" ]; then
        local _h="${C8Y_HOST%/}"
        case "$_h" in http*) ;; *) _h="https://${_h}" ;; esac
        printf '%s%s' "$_h" "$DM_MOCK_PATH"
    fi
}

# Connector auth properties (JSON object) the mapper needs to reach the mock.
dm_mock_auth_props() {
    if [ -n "${C8Y_TOKEN:-}" ]; then
        jq -cn --arg t "$C8Y_TOKEN" '{authentication:"Bearer", token:$t}'
    elif [ -n "${C8Y_USER:-}" ] && [ -n "${C8Y_PASSWORD:-}" ]; then
        jq -cn --arg u "${C8Y_TENANT:+${C8Y_TENANT}/}${C8Y_USER}" --arg p "$C8Y_PASSWORD" \
            '{authentication:"Basic", user:$u, password:$p}'
    else
        return 1
    fi
}

# Skip the test unless the mock is deployed and the credentials are available.
dm_require_mock() {
    [ -n "$(dm_mock_base_url)" ] \
        || dm_skip_exit "Cannot determine the mock URL — export C8Y_HOST or DM_MOCK_BASE_URL."
    dm_mock_auth_props >/dev/null \
        || dm_skip_exit "No Cumulocity credentials for the connector — export C8Y_TOKEN or C8Y_USER/C8Y_PASSWORD."
    c8y api --method GET --url "${DM_MOCK_PATH}/health" --force --output json </dev/null >/dev/null 2>&1 \
        || dm_skip_exit "http-polling-mock is not deployed/UP at ${DM_MOCK_PATH}." \
                        "Build and push it: resources/testing/environments/http-polling/build.sh --push"
}

# GET a JSON array from the mock, normalised to one array on stdout.
dm_mock_get_array() {   # <path e.g. /requests>
    c8y api --method GET --url "${DM_MOCK_PATH}$1" --force --output json </dev/null 2>/dev/null \
        | jq -sc '[ .[] | if type == "array" then .[] else . end ]' 2>/dev/null || printf '[]'
}

dm_mock_clear() {   # <path e.g. /requests>
    c8y api --method DELETE --url "${DM_MOCK_PATH}$1" --force --output json </dev/null >/dev/null 2>&1 || true
}

# Create a connector from its spec defaults + overrides, enable and connect it.
dm_create_connector_with_props() {   # <identifier> <type> <name> <props_override_json>
    local _id=$1 _type=$2 _name=$3 _over=$4 _props
    _props=$(dm_connector_default_properties "$_type" | jq -c --argjson o "$_over" '. + $o')
    dm_api_must POST /configuration/connector/instance "$(jq -cn \
        --arg id "$_id" --arg t "$_type" --arg n "$_name" --argjson p "$_props" \
        '{identifier:$id, connectorType:$t, name:$n, description:"integration test", enabled:true, properties:$p}')" >/dev/null
    dm_info "Created connector ${_id} (${_type})"
}

# Remove a mapping's deployment from every connector (live reconcile).
dm_undeploy_mapping() {   # <mapping_id_or_identifier>
    local _ref=$1 _key
    _key=$(dm_api GET "/mapping/${_ref}" 2>/dev/null | jq -r '.identifier // empty' 2>/dev/null)
    _key=${_key:-$_ref}
    c8y api --method PUT ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}/deployment/defined/${_key}" \
        --template '[]' --header 'Content-Type: application/json' \
        --force --output rawResponse </dev/null >/dev/null \
        || dm_error "Undeploy PUT failed for ${_key}"
    dm_info "Undeployed mapping ${_ref} (key=${_key})"
}

# Stop before an action that discards the microservice's logs (disable/enable restarts the
# service and the previous run's log is lost) so the user can save them first.
# Reads from the terminal (/dev/tty) because tests run with stdin redirected.
# Without a terminal (CI) it only warns and continues; DM_SKIP_LOG_PAUSE=1 skips it entirely.
dm_pause_for_log_save() {   # [reason]
    local _reason="${1:-the microservice is about to be restarted}"
    [ "${DM_SKIP_LOG_PAUSE:-0}" = "1" ] && return 0
    printf "\n${_C_YELLOW}══ SAVE THE LOGS NOW ══${_C_RESET}\n" >&2
    printf "%s — the microservice log of this and earlier runs will be lost.\n" "$_reason" >&2
    printf "Download it first (Administration → Ecosystem → Microservices → %s → Logs).\n" \
        "${DM_MICROSERVICE_NAME:-dynamic-mapper-service}" >&2
    if [ -r /dev/tty ] && { : </dev/tty; } 2>/dev/null; then
        printf "Press ENTER to continue (Ctrl-C to abort) ... " >&2
        read -r _ </dev/tty || true
        printf "\n" >&2
    else
        dm_warn "No terminal available — continuing without waiting (set DM_SKIP_LOG_PAUSE=1 to silence)"
    fi
}

# Restart the dynamic-mapper microservice for the current tenant, in either setup:
#   * single-tenant: the app is owned by the current tenant -> disable/enable the app itself.
#   * multi-tenant : the app is owned by the management tenant and only SUBSCRIBED here. A
#     subtenant user cannot unsubscribe it (DELETE /tenant/tenants/<t>/applications/<id> -> 403).
#     Then the restart needs a management-tenant session, or a manual restart:
#       DM_RESTART_USER + DM_RESTART_PASSWORD [+ DM_RESTART_TENANT = management tenant id]
#            [+ DM_RESTART_HOST = management tenant URL, if it differs from the subtenant's]
#         -> management-tenant admin credentials, applied to the two subscribe calls only
#            (the active session is untouched; C8Y_TOKEN is ignored for those calls).
#       DM_RESTART_SESSION=<go-c8y-cli session file of a management-tenant admin>
#         -> unsubscribe/re-subscribe THIS tenant ("$C8Y_TENANT") with that session.
#       otherwise -> ask the user to restart the service by hand (needs a terminal), or
#         skip the test (exit 42) when there is none.
dm_restart_microservice() {   # [microservice-name]
    local _name="${1:-${DM_MICROSERVICE_NAME:-dynamic-mapper-service}}"
    local _app _app_id _owner _tenant="${C8Y_TENANT:-}"
    _app=$(c8y microservices get --id "$_name" --output json </dev/null 2>/dev/null) \
        || dm_error "Microservice '$_name' not found for tenant '${_tenant}'"
    _app_id=$(printf '%s' "$_app" | jq -r '.id // empty')
    _owner=$(printf '%s' "$_app" | jq -r '.owner.tenant.id // empty')

    if [ -z "$_owner" ] || [ "$_owner" = "$_tenant" ]; then
        dm_info "Single-tenant: '$_name' is owned by '${_tenant}' - disabling/re-enabling it"
        c8y microservices disable --id "$_name" --force </dev/null
        dm_wait 5 "service shutdown"
        c8y microservices enable --id "$_name" --force </dev/null
        return 0
    fi

    dm_info "Multi-tenant: '$_name' is owned by '$_owner' and subscribed by '${_tenant}'"
    if [ -n "${DM_RESTART_USER:-}" ] && [ -n "${DM_RESTART_PASSWORD:-}" ]; then
        local _mgmt="${DM_RESTART_TENANT:-$_owner}"
        dm_info "Using credentials of '${DM_RESTART_USER}' (tenant '$_mgmt'${DM_RESTART_HOST:+, host $DM_RESTART_HOST}) to unsubscribe/re-subscribe '${_tenant}'"
        env -u C8Y_TOKEN C8Y_USER="$DM_RESTART_USER" C8Y_PASSWORD="$DM_RESTART_PASSWORD" C8Y_TENANT="$_mgmt" ${DM_RESTART_HOST:+C8Y_HOST="$DM_RESTART_HOST"} \
            c8y tenants applications disable --tenant "$_tenant" --application "$_app_id" --force </dev/null \
            || dm_error "Unsubscribe of '$_name' from '${_tenant}' failed - are the credentials those of a '$_mgmt' admin?"
        dm_wait 5 "service shutdown"
        env -u C8Y_TOKEN C8Y_USER="$DM_RESTART_USER" C8Y_PASSWORD="$DM_RESTART_PASSWORD" C8Y_TENANT="$_mgmt" ${DM_RESTART_HOST:+C8Y_HOST="$DM_RESTART_HOST"} \
            c8y tenants applications enable --tenant "$_tenant" --application "$_app_id" --force </dev/null \
            || dm_error "Re-subscribe of '$_name' to '${_tenant}' failed"
        return 0
    fi
    if [ -n "${DM_RESTART_SESSION:-}" ]; then
        dm_info "Using management session '${DM_RESTART_SESSION}' to unsubscribe/re-subscribe '${_tenant}'"
        c8y tenants applications disable --session "$DM_RESTART_SESSION" \
            --tenant "$_tenant" --application "$_app_id" --force </dev/null \
            || dm_error "Unsubscribe of '$_name' from '${_tenant}' failed - does the session belong to '$_owner'?"
        dm_wait 5 "service shutdown"
        c8y tenants applications enable --session "$DM_RESTART_SESSION" \
            --tenant "$_tenant" --application "$_app_id" --force </dev/null \
            || dm_error "Re-subscribe of '$_name' to '${_tenant}' failed"
        return 0
    fi

    if [ -r /dev/tty ] && { : </dev/tty; } 2>/dev/null; then
        printf "\n${_C_YELLOW}Manual restart needed${_C_RESET}: a '%s' user cannot unsubscribe '%s'.\n" "$_tenant" "$_name" >&2
        printf "As '%s' unsubscribe and re-subscribe tenant '%s' (or restart the service),\n" "$_owner" "$_tenant" >&2
        printf "or rerun with DM_RESTART_USER/DM_RESTART_PASSWORD (management tenant admin).\n" >&2
        printf "Press ENTER once the service has been restarted (Ctrl-C to abort) ... " >&2
        read -r _ </dev/tty || true
        printf "\n" >&2
        return 0
    fi
    dm_skip_exit "Multi-tenant setup: restart needs '$_owner' rights. Set DM_RESTART_USER/DM_RESTART_PASSWORD (or DM_RESTART_SESSION) or run with a terminal."
}

# Notification 2.0 subscriptions of a device via the raw REST response ({"subscriptions":[...]}).
# Prints a JSON array of {id, subscription, context}; '[]' on error. Unlike
# dm_count_subscriptions it does not depend on how the c8y CLI renders a list
# (array vs. NDJSON vs. a single bare object).
dm_device_subscriptions_json() {   # <device_id>
    c8y api --method GET --url "/notification2/subscriptions?source=$1&pageSize=100" \
        --force --raw </dev/null 2>/dev/null \
        | jq -c '[ .subscriptions[]? | {id, subscription, context} ]' 2>/dev/null || printf '[]'
}

dm_device_subscription_count() {   # <device_id>
    dm_device_subscriptions_json "$1" | jq 'length' 2>/dev/null || printf '0'
}
