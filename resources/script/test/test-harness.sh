#!/bin/bash
#
# test-harness.sh — Common functions for Dynamic Mapper integration tests
#
# Source this file at the top of each test script:
#   source "$(dirname "$0")/test-harness.sh"
#
# Functions provided
# ──────────────────
# Output
#   dm_banner  <title>
#   dm_step    <description>           — no step number
#   dm_step    <n> <description>       — with step number prefix
#   dm_done    <title>
#   dm_info    <msg>
#   dm_success <msg>
#   dm_warn    <msg>
#   dm_fail    <msg>
#
# Assertions  (update _DM_PASS_COUNT / _DM_FAIL_COUNT)
#   dm_assert_eq      <label> <expected> <actual>
#   dm_assert_gt      <label> <value>    <than>
#   dm_assert_eq_zero <label> <value>
#   dm_print_summary  — prints pass/fail totals; exits 1 when any failure
#
# Devices
#   dm_create_device <name> <type>
#     → sets _DM_LAST_DEVICE_ID and _DM_LAST_DEVICE_NAME
#   dm_delete_device <id>
#   dm_send_measurement <device_id> <temp_value> [unit=C]
#
# Dynamic Mapper API
#   dm_api              <method> <path> [json_body]
#   dm_api_json_array   <method> <path> [json_body]   (returns [] on error)
#
# Subscriptions
#   dm_count_subscriptions   <device_id>
#     → sets _DM_LAST_SUB_COUNT; prints raw subscription JSON
#   dm_assert_has_subscription <label> <device_id>
#   dm_assert_no_subscription  <label> <device_id>
#   dm_show_subscriptions      <device_id>
#   dm_delete_static_subscription <device_id> <subscription_name>
#   dm_set_type_subscriptions     <api> <types_json_array>
#     e.g. dm_set_type_subscriptions MEASUREMENT '["auto-type"]'
#          dm_set_type_subscriptions MEASUREMENT '[]'   # clear
#
# Waiting
#   dm_wait             <seconds> <reason>
#   dm_wait_for_service [max_retries=24] [interval_secs=10]
#
# Environment overrides (set before sourcing or exporting)
#   DM_SERVICE                  (default /service/dynamic-mapper-service)
#   DM_DEFAULT_DISCOVERY_WAIT   (default 10)
#   DM_DEFAULT_STARTUP_WAIT     (default 60)
#   DM_DEFAULT_HEALTH_RETRIES   (default 24)
#   DM_DEFAULT_HEALTH_INTERVAL  (default 10)

# ── Guard against double-sourcing ─────────────────────────────────────────────
[ -n "${_DM_HARNESS_LOADED:-}" ] && return 0
_DM_HARNESS_LOADED=1

# ── Configuration ──────────────────────────────────────────────────────────────
DM_SERVICE="${DM_SERVICE:-/service/dynamic-mapper-service}"
DM_DEFAULT_DISCOVERY_WAIT="${DM_DEFAULT_DISCOVERY_WAIT:-10}"
DM_DEFAULT_STARTUP_WAIT="${DM_DEFAULT_STARTUP_WAIT:-60}"
DM_DEFAULT_HEALTH_RETRIES="${DM_DEFAULT_HEALTH_RETRIES:-24}"
DM_DEFAULT_HEALTH_INTERVAL="${DM_DEFAULT_HEALTH_INTERVAL:-10}"

# Run c8y CLI non-interactively: suppress all confirmation prompts and spinners.
export C8Y_SETTINGS_CI=true

# ── ANSI colours (disabled when stdout is not a terminal) ─────────────────────
if [ -t 1 ]; then
    _C_GREEN=$'\033[0;32m'
    _C_RED=$'\033[0;31m'
    _C_YELLOW=$'\033[1;33m'
    _C_CYAN=$'\033[0;36m'
    _C_BOLD=$'\033[1m'
    _C_RESET=$'\033[0m'
else
    _C_GREEN="" _C_RED="" _C_YELLOW="" _C_CYAN="" _C_BOLD="" _C_RESET=""
fi

# ── Output helpers ─────────────────────────────────────────────────────────────

dm_banner() {   # <title>
    echo ""
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
    printf "${_C_BOLD} %s${_C_RESET}\n" "$1"
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
}

dm_step() {     # <description>  OR  <n> <description>
    echo ""
    if [ -n "${2:-}" ]; then
        printf "%s--- Step %s: %s ---%s\n" "${_C_CYAN}" "$1" "$2" "${_C_RESET}"
    else
        printf "%s--- %s ---%s\n" "${_C_CYAN}" "$1" "${_C_RESET}"
    fi
}

dm_done() {     # <title>
    echo ""
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
    printf "${_C_BOLD} %s: DONE${_C_RESET}\n" "$1"
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
}

dm_info()    { printf "%s\n" "$1"; }
dm_success() { printf "${_C_GREEN}SUCCESS: %s${_C_RESET}\n" "$1"; }
dm_warn()    { printf "${_C_YELLOW}WARN: %s${_C_RESET}\n" "$1"; }
dm_fail()    { printf "${_C_RED}FAIL: %s${_C_RESET}\n" "$1"; }

# ── Assertion counters ─────────────────────────────────────────────────────────
_DM_PASS_COUNT=0
_DM_FAIL_COUNT=0

dm_assert_eq() {    # <label> <expected> <actual>
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$label] expected='$expected' actual='$actual'"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail    "[$label] expected='$expected' actual='$actual'"
    fi
}

dm_assert_eq_zero() {   # <label> <value>
    dm_assert_eq "$1" "0" "$2"
}

dm_assert_gt() {    # <label> <value> <than>
    local label=$1 value=$2 than=$3
    if [ "$value" -gt "$than" ] 2>/dev/null; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$label] $value > $than"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail    "[$label] $value is not > $than"
    fi
}

# Print summary and return exit code 1 if any assertion failed.
dm_print_summary() {
    echo ""
    local total=$((_DM_PASS_COUNT + _DM_FAIL_COUNT))
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
    if [ "$_DM_FAIL_COUNT" -eq 0 ]; then
        printf "${_C_GREEN}${_C_BOLD} Results: %d/%d passed${_C_RESET}\n" \
            "$_DM_PASS_COUNT" "$total"
    else
        printf "${_C_RED}${_C_BOLD} Results: %d/%d passed, %d FAILED${_C_RESET}\n" \
            "$_DM_PASS_COUNT" "$total" "$_DM_FAIL_COUNT"
    fi
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
    [ "$_DM_FAIL_COUNT" -eq 0 ]
}

# Print a SKIP line (does not increment failure count).
dm_skip() {     # <reason>
    printf "${_C_YELLOW}SKIP: %s${_C_RESET}\n" "$*" >&2
}

# Require at least one CONNECTED connector; skip this test if none are found.
# Call immediately after dm_wait_for_service in outbound tests.
# When no connector is connected the outbound dispatcher drops every
# notification before it reaches the enrichment processor and
# messagesReceived is never incremented, so the test would always fail.
dm_require_connected_connector() {
    local _statuses _connected
    _statuses=$(dm_api GET /monitoring/status/connectors)
    _connected=$(printf '%s\n' "$_statuses" \
        | jq '[.[] | select(.status == "CONNECTED")] | length' 2>/dev/null \
        || printf '0')
    if [ "${_connected:-0}" -lt 1 ]; then
        dm_skip "No connector is CONNECTED — outbound tests require a connected broker connector."
        dm_skip "Configure and connect an MQTT (or other) connector, then re-run."
        exit 0
    fi
    dm_info "Found ${_connected} CONNECTED connector(s) — proceeding."
}

# ── Device helpers ─────────────────────────────────────────────────────────────
_DM_LAST_DEVICE_ID=""
_DM_LAST_DEVICE_NAME=""

# Create a device.  Sets _DM_LAST_DEVICE_ID and _DM_LAST_DEVICE_NAME.
dm_create_device() {    # <name> <type>
    local _name=$1 _type=$2 _json
    _json=$(c8y devices create --name "$_name" --type "$_type" --force --output json)
    _DM_LAST_DEVICE_ID=$(printf '%s' "$_json" | jq -r '.id')
    _DM_LAST_DEVICE_NAME=$(printf '%s' "$_json" | jq -r '.name')
    dm_info "Created device: $_DM_LAST_DEVICE_NAME (id=$_DM_LAST_DEVICE_ID)"
}

# Delete a device; silently ignores missing devices.
dm_delete_device() {    # <id>
    local _id=$1
    [ -z "$_id" ] && return 0
    c8y devices delete --id "$_id" --force 2>/dev/null || true
    dm_info "Deleted device: $_id"
}

# Send a single temperature measurement for a device.
dm_send_measurement() {     # <device_id> <temp_value> [unit=C]
    local _device=$1 _value=$2 _unit=${3:-C}
    c8y measurements create \
        --device "$_device" \
        --data "c8y_TemperatureMeasurement.T.value=${_value},c8y_TemperatureMeasurement.T.unit=${_unit},type='c8y_TemperatureMeasurement'" \
        --force
    dm_info "Measurement sent (device=$_device, temp=${_value} ${_unit})"
}

# ── Dynamic Mapper API helpers ─────────────────────────────────────────────────

# Call the Dynamic Mapper REST API.
# Returns the JSON response, or '{}' on failure (error printed as warning).
dm_api() {      # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '{}'
        fi
    else
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            rm -f "$_err"
            printf '{}'
        fi
    fi
}

# Same as dm_api but returns '[]' on failure (useful for list endpoints).
dm_api_json_array() {   # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '[]'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '[]'
        fi
    else
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '[]'
        else
            rm -f "$_err"
            printf '[]'
        fi
    fi
}

# ── Subscription helpers ───────────────────────────────────────────────────────
_DM_LAST_SUB_COUNT=0

# Count notification2 subscriptions for a device.
# Sets _DM_LAST_SUB_COUNT and prints the raw subscription list JSON.
dm_count_subscriptions() {  # <device_id>
    local _device=$1 _subs
    _subs=$(c8y notification2 subscriptions list \
        --source "$_device" --output json 2>/dev/null || printf '[]')
    _DM_LAST_SUB_COUNT=$(printf '%s' "$_subs" | jq -s 'length' 2>/dev/null || printf '0')
    printf '%s' "$_subs"
}

# Print a summary line listing subscription names for a device.
dm_show_subscriptions() {   # <device_id>
    dm_count_subscriptions "$1" \
        | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null \
        || dm_warn "Could not retrieve subscription names for device $1"
}

# Assert that at least one notification2 subscription exists for <device_id>.
dm_assert_has_subscription() {  # <label> <device_id>
    dm_count_subscriptions "$2" >/dev/null
    dm_assert_gt "$1" "$_DM_LAST_SUB_COUNT" "0"
}

# Assert that NO notification2 subscription exists for <device_id>.
dm_assert_no_subscription() {   # <label> <device_id>
    dm_count_subscriptions "$2" >/dev/null
    dm_assert_eq_zero "$1" "$_DM_LAST_SUB_COUNT"
}

# Delete a named static subscription for a device; silently ignores errors.
dm_delete_static_subscription() {  # <device_id> <subscription_name>
    local _id=$1 _name=$2
    [ -z "$_id" ] && return 0
    dm_api DELETE "/subscription/${_id}?subscription=${_name}" >/dev/null 2>&1 || true
    dm_info "Deleted static subscription for device $_id (name=$_name)"
}

# Overwrite the type-subscription list for a given C8Y API.
# Pass '[]' to clear all type subscriptions.
# Example: dm_set_type_subscriptions MEASUREMENT '["auto-type"]'
dm_set_type_subscriptions() {   # <api> <types_json_array>
    local _api=$1 _types=$2
    dm_api PUT /subscription/type \
        "{\"api\": \"${_api}\", \"types\": ${_types}}" >/dev/null || true
    dm_info "Set type subscriptions (api=${_api}): ${_types}"
}

# ── Wait helpers ───────────────────────────────────────────────────────────────

# Sleep with an explanatory message.
dm_wait() {     # <seconds> [reason]
    dm_info "Waiting ${1}s${2:+ — ${2}} ..."
    sleep "$1"
}

# Poll the Dynamic Mapper health endpoint until it reports "UP".
# Returns 0 when UP, 1 after all retries exhausted.
# Verify that a c8y session or environment credentials are available.
# Exits immediately with a clear message when they are not.
dm_check_session() {
    # Fast path: explicit env-var credentials
    [ -n "${C8Y_HOST:-}" ] && return 0
    # Session-file path: parse the host from the active session.
    # 'c8y sessions current' may exit 0 even when no session is loaded, so
    # we check for an actual non-empty host value in the JSON output.
    local _host
    _host=$(c8y sessions current --output json 2>/dev/null \
        | jq -r '.host // empty' 2>/dev/null || true)
    [ -n "${_host:-}" ] && return 0
    printf '%sERROR: No active c8y session.%s\n' "${_C_RED}" "${_C_RESET}" >&2
    printf '  Activate one:  c8y sessions use <name>\n' >&2
    printf '  Or export:     C8Y_HOST / C8Y_USER / C8Y_PASSWORD\n' >&2
    exit 1
}

dm_wait_for_service() {     # [max_retries] [interval_secs]
    dm_check_session
    # When run-tests.sh has already verified the service, skip redundant polling.
    if [ -n "${DM_SKIP_HEALTH_CHECK:-}" ]; then
        dm_info "  Service health already verified — skipping."
        return 0
    fi

    local _retries=${1:-$DM_DEFAULT_HEALTH_RETRIES}
    local _interval=${2:-$DM_DEFAULT_HEALTH_INTERVAL}
    local _i

    # Use GET /mapping as the liveness probe: it returns HTTP 200 when the
    # Java process is up (unlike GET /health which aggregates connector state).
    # c8y api exits 0 on success, non-0 on any error — no jq needed.
    for _i in $(seq 1 "$_retries"); do
        if c8y api --method GET --url "${DM_SERVICE}/mapping" \
                --output json >/dev/null 2>&1; then
            dm_success "Service is UP."
            return 0
        fi
        dm_info "  Attempt $_i/$_retries: not ready, retrying in ${_interval}s ..."
        sleep "$_interval"
    done

    dm_fail "Service did not come UP after $_retries attempts."
    return 1
}

# ── Mapping helpers ────────────────────────────────────────────────────────────
_DM_LAST_MAPPING_ID=""

# Create a mapping via POST /mapping. The raw JSON body is the only argument.
# Stores the new mapping id in _DM_LAST_MAPPING_ID.
dm_create_mapping() {   # <json_body>
    local _json
    _json=$(dm_api POST /mapping "$1")
    _DM_LAST_MAPPING_ID=$(printf '%s' "$_json" | jq -r '.id // empty')
    if [ -z "${_DM_LAST_MAPPING_ID:-}" ]; then
        dm_fail "Mapping creation failed — API returned: $(printf '%s' "$_json" | head -c 200)"
        return 1
    fi
    dm_info "Created mapping: id=$_DM_LAST_MAPPING_ID"
}

# Deactivate then delete a mapping by id (silently ignores errors).
dm_delete_mapping() {   # <id>
    [ -z "$1" ] && return 0
    dm_deactivate_mapping "$1" 2>/dev/null || true
    dm_api DELETE "/mapping/$1" >/dev/null 2>&1 || true
    dm_info "Deleted mapping: $1"
}

dm_activate_mapping() {     # <id>
    dm_api POST /operation \
        "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"$1\",\"active\":\"true\"}}" >/dev/null
    dm_info "Activated mapping: $1"
}

dm_deactivate_mapping() {   # <id>
    dm_api POST /operation \
        "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"$1\",\"active\":\"false\"}}" >/dev/null
    dm_info "Deactivated mapping: $1"
}

# Return the messagesReceived count for a mapping id from monitoring stats.
# Uses jq -s (slurp) because dm_api_json_array may output NDJSON (one object
# per line) rather than a plain JSON array, so we collect all lines first.
dm_mapping_received_count() {   # <mapping_id>
    # Always outputs a number.  flatten(1) normalises both NDJSON (slurped to
    # [obj…]) and the [] fallback (slurped to [[]]). map()[0] is null when no
    # match; jq null-propagation makes .messagesReceived null → // 0 = 0.
    dm_api_json_array GET /monitoring/status/mapping/statistic \
        | jq -rs --arg id "$1" \
            'flatten(1) | (map(select(.id == $id))[0].messagesReceived) // 0' 2>/dev/null \
        || printf '0'
}

dm_assert_mapping_received_gt() {   # <label> <mapping_id> <baseline>
    local _count _baseline _stats
    _count=$(dm_mapping_received_count "$2")
    _count=${_count:-0}
    _baseline=${3:-0}
    dm_assert_gt "$1" "$_count" "$_baseline"
    # Print diagnostics whenever assertion failed (value not > baseline)
    if [ "$_count" -le "$_baseline" ] 2>/dev/null; then
        _stats=$(dm_api_json_array GET /monitoring/status/mapping/statistic \
            | jq -rs --arg id "$2" '.[] | select(.id == $id)' 2>/dev/null || true)
        if [ -n "$_stats" ]; then
            dm_warn "Mapping stats for id=$2: $_stats"
        else
            dm_warn "Mapping id=$2 not found in /monitoring/status/mapping/statistic (no messages received at all)"
        fi
    fi
}

# ── Connector helpers ──────────────────────────────────────────────────────────
dm_connect_connector() {    # <connectorIdentifier>
    dm_api POST /operation \
        "{\"operation\":\"CONNECT\",\"parameter\":{\"connectorIdentifier\":\"$1\"}}" >/dev/null
    dm_info "Connected connector: $1"
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

# ── MQTT helpers ───────────────────────────────────────────────────────────────
# Environment variables: MQTT_HOST (default localhost), MQTT_PORT (default 1883),
#   MQTT_USER (optional), MQTT_PASS (optional)

# Skip the calling test if the MQTT broker is unreachable or mosquitto_pub is
# not installed.  Call this once, right after dm_wait_for_service.
dm_require_mqtt_broker() {
    local _host="${MQTT_HOST:-localhost}" _port="${MQTT_PORT:-1883}"
    if ! command -v mosquitto_pub >/dev/null 2>&1; then
        dm_skip "mosquitto_pub not installed — install mosquitto-clients to run MQTT tests."
        exit 0
    fi
    # Quick TCP reachability check (3-second timeout)
    if ! nc -z -w 3 "$_host" "$_port" >/dev/null 2>&1; then
        dm_skip "MQTT broker not reachable at ${_host}:${_port}."
        dm_skip "Set MQTT_HOST / MQTT_PORT / MQTT_USER / MQTT_PASS and retry."
        exit 0
    fi
    dm_info "MQTT broker reachable at ${_host}:${_port} — proceeding."
}

dm_mqtt_publish() {     # <topic> <payload>
    local _topic=$1 _payload=$2
    local _host="${MQTT_HOST:-localhost}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -m "$_payload")
    [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
    [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    mosquitto_pub "${_args[@]}"
    dm_info "Published to $_topic (broker=$_host:$_port)"
}

# Subscribe and capture at most 1 message with a timeout.
# Prints the received message to stdout.  Returns 1 on timeout.
dm_mqtt_subscribe_one() {   # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _host="${MQTT_HOST:-localhost}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -C 1 -W "$_timeout")
    [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
    [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    mosquitto_sub "${_args[@]}" 2>/dev/null
}

# ── C8Y data helpers ───────────────────────────────────────────────────────────
# Return a UTC ISO-8601 timestamp. Pass a negative offset in seconds for the past.
# Examples: dm_now          -> now
#           dm_now -120     -> 2 minutes ago
dm_now() {  # [offset_seconds]
    local _offset=${1:-0}
    if [ "$_offset" -lt 0 ] 2>/dev/null; then
        date -u -v"${_offset}S" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null \
            || date -u -d "$(( -1 * _offset )) seconds ago" +"%Y-%m-%dT%H:%M:%SZ"
    else
        date -u +"%Y-%m-%dT%H:%M:%SZ"
    fi
}

# Look up the C8Y internal device id by external id / type.
dm_lookup_device_by_ext_id() {  # <externalId> <externalIdType>
    c8y identity get \
        --externalId "$1" --externalType "$2" \
        --output json 2>/dev/null \
        | jq -r '.managedObject.id // empty' 2>/dev/null || printf ''
}

dm_count_measurements_since() {     # <device_id> <since_iso8601>
    c8y measurements list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s 'length' 2>/dev/null || printf '0'
}

dm_count_events_since() {   # <device_id> <since_iso8601>
    c8y events list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s 'length' 2>/dev/null || printf '0'
}

dm_count_alarms_since() {   # <device_id> <since_iso8601>
    c8y alarms list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s 'length' 2>/dev/null || printf '0'
}

dm_assert_measurement_count_gt() {  # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_measurements_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

dm_assert_event_count_gt() {    # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_events_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

dm_assert_alarm_count_gt() {    # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_alarms_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}
