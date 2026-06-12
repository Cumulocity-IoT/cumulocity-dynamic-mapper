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
# Validation
#   dm_validate_tools                   — verify all required tools are installed
#   dm_test_setup_and_validate          — complete setup validation (session, tools, service, mqtt)
#   dm_verify_mqtt_connector_ready      — check MQTT connector is CONNECTED
#   dm_get_support_esm                  — fetch supportESM from service configuration
#   dm_wrap_onmessage_code              — append export { onMessage } only when supportESM=true
#
# CLI flags / lifecycle
#   dm_parse_args        "$@"             — parse --cleanup / --keep / --validate-only
#   dm_register_cleanup  <function>       — run <function> on exit unless --keep
#   dm_validate_only_exit                 — exit 0 here when --validate-only was passed
#
# Assertions  (update _DM_PASS_COUNT / _DM_FAIL_COUNT)
#   dm_assert_eq      <label> <expected> <actual>
#   dm_assert_gt      <label> <value>    <than>
#   dm_assert_eq_zero <label> <value>
#   dm_assert_measurement_present <label> <ext_id> <ext_id_type> [min] [timeout]
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
# Connectors
#   dm_require_mqtt_broker          — validate broker reachability; set _DM_MQTT_CONNECTOR_ID
#   dm_setup_mqtt_test_connector    — create a new MQTT connector config (disabled)
#   dm_enable_connector             — enable a connector (required before connect)
#   dm_setup_and_connect_mqtt_connector — complete setup: create, enable, and connect
#   dm_connect_connector            <connectorIdentifier>
#   dm_disconnect_connector         <connectorIdentifier>
#   dm_get_connector_status         <connectorIdentifier>
#   dm_assert_connector_status      <label> <connectorIdentifier> <expected_status>
#
# MQTT Publish/Subscribe
#   dm_mqtt_publish                 <topic> <payload> [qos=0]
#   dm_mqtt_subscribe_one           <topic> [timeout_secs=10]
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

# Reserved exit code a test uses to signal "skipped" (prerequisite absent) rather
# than passed or failed. run-tests.sh classifies this code as SKIP. Keep in sync
# with DM_SKIP_EXIT_CODE in run-tests.sh.
DM_EXIT_SKIP="${DM_EXIT_SKIP:-42}"
DM_DEFAULT_DISCOVERY_WAIT="${DM_DEFAULT_DISCOVERY_WAIT:-10}"
DM_DEFAULT_STARTUP_WAIT="${DM_DEFAULT_STARTUP_WAIT:-60}"
DM_DEFAULT_HEALTH_RETRIES="${DM_DEFAULT_HEALTH_RETRIES:-24}"
DM_DEFAULT_HEALTH_INTERVAL="${DM_DEFAULT_HEALTH_INTERVAL:-10}"

# ── MQTT Broker Defaults ────────────────────────────────────────────────────────
export MQTT_HOST="${MQTT_HOST:-broker.hivemq.com}"
export MQTT_PORT="${MQTT_PORT:-1883}"
export MQTT_TLS="${MQTT_TLS:-false}"
export MQTT_INSECURE="${MQTT_INSECURE:-false}"

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
dm_error()   { printf "${_C_RED}ERROR: %s${_C_RESET}\n" "$1" >&2; exit 1; }

# ── Tool validation ────────────────────────────────────────────────────────────

# Validate that required tools are installed and available
dm_validate_tools() {
    local _missing=0
    local _tools=("jq" "mosquitto_pub" "c8y" "nc")
    
    for _tool in "${_tools[@]}"; do
        if ! command -v "$_tool" >/dev/null 2>&1; then
            dm_fail "Required tool not found: $_tool"
            _missing=1
        fi
    done
    
    if [ $_missing -eq 1 ]; then
        printf "\n%sRequired tools to install:%s\n" "${_C_RED}" "${_C_RESET}"
        printf "  macOS:  brew install jq mosquitto-clients c8y\n"
        printf "  Linux:  apt-get install jq mosquitto-clients (c8y from https://github.com/reubenmiller/go-c8y-cli)\n"
        exit 1
    fi
    
    dm_info "All required tools found ✓"
}

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

dm_assert_ne() {    # <label> <not_expected> <actual>
    local label=$1 not_expected=$2 actual=$3
    if [ "$not_expected" != "$actual" ]; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$label] actual='$actual' (not '$not_expected')"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail    "[$label] actual='$actual' should not equal '$not_expected'"
    fi
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

# Print a SKIP reason and exit the test with the reserved skip code so the runner
# tallies it as SKIP (not PASS). Use for "prerequisite absent" bail-outs.
dm_skip_exit() {   # <reason...>
    local _r
    for _r in "$@"; do dm_skip "$_r"; done
    exit "$DM_EXIT_SKIP"
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
        dm_skip_exit "No connector is CONNECTED — outbound tests require a connected broker connector." \
                     "Configure and connect an MQTT (or other) connector, then re-run."
    fi
    dm_info "Found ${_connected} CONNECTED connector(s) — proceeding."
}

# ── CLI flag parsing (shared by every test script) ────────────────────────────
# Tests clean up their data by default. Pass --keep to retain it for debugging,
# or --validate-only to run setup/validation checks and then exit 0.
#
#   --cleanup        Delete created test data on exit (default; accepted for
#                    backward compatibility).
#   --keep           Keep created test data on exit (for post-mortem debugging).
#   --validate-only  Run environment validation only, then exit before mutating.
#
# Usage in a test script:
#   dm_parse_args "$@"
#   dm_register_cleanup cleanup       # 'cleanup' is a function the script defines
#   ...
#   dm_validate_only_exit             # call right after the validation block
_DM_DO_CLEANUP=true
_DM_VALIDATE_ONLY=false
_DM_CLEANUP_FN=""

dm_parse_args() {   # "$@"
    local _arg
    for _arg in "$@"; do
        case "$_arg" in
            --cleanup)       _DM_DO_CLEANUP=true ;;
            --keep)          _DM_DO_CLEANUP=false ;;
            --validate-only) _DM_VALIDATE_ONLY=true ;;
            "" ) ;;
            *) dm_warn "Ignoring unknown argument: $_arg" ;;
        esac
    done
}

# Internal: invoked on EXIT. Runs the registered cleanup unless --keep was given.
_dm_on_exit() {
    local _rc=$?
    # Validation-only runs created no data — nothing to clean up or warn about.
    [ "${_DM_VALIDATE_ONLY}" = "true" ] && return "$_rc"
    if [ "${_DM_DO_CLEANUP}" = "true" ] && [ -n "${_DM_CLEANUP_FN}" ]; then
        "$_DM_CLEANUP_FN" || true
    elif [ -n "${_DM_CLEANUP_FN}" ]; then
        dm_warn "Skipping cleanup (--keep set) — test data retained."
    fi
    return "$_rc"
}

# Register a cleanup function to run on exit (honours --keep / --cleanup).
dm_register_cleanup() {   # <function_name>
    _DM_CLEANUP_FN=$1
    trap _dm_on_exit EXIT
}

# Exit early (success) when --validate-only was passed. Call after the
# validation/setup block and before creating any test data.
dm_validate_only_exit() {
    [ "${_DM_VALIDATE_ONLY}" = "true" ] || return 0
    dm_success "Validation-only run — environment OK, exiting before test data is created."
    # Skip the registered cleanup: nothing was created yet.
    _DM_DO_CLEANUP=false
    exit 0
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
#
# Two non-obvious requirements for c8y calls here:
#   - </dev/null : when a c8y call without --data runs inside a `while read` loop,
#     it inherits the loop's piped stdin. go-c8y-cli then enters "pipeline mode",
#     tries to read items from stdin, finds none ("something is not being
#     detected"), and SKIPS the request entirely (exit 0, silent no-op). Feeding
#     /dev/null keeps it out of pipeline mode so the request is always sent.
#   - --force : skip go-c8y-cli's confirmation prompt for destructive methods
#     (DELETE) in non-interactive runs. Harmless for GET/POST/PUT.
dm_api() {      # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --force \
                --output json </dev/null 2>"$_err"); then
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
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
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
                --force \
                --output json </dev/null 2>"$_err"); then
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
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '[]'
        else
            rm -f "$_err"
            printf '[]'
        fi
    fi
}

# Strict API call helper. Exits on any API error to avoid false-positive test progress.
dm_api_must() {     # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if printf '%s' "$_body" | grep -Eq '^[[:space:]]*\['; then
            if _out=$(printf '%s\n' "$_body" | c8y api --method "$_method" \
                    --url "${DM_SERVICE}${_path}" \
                    --template "input.value" \
                    --header 'Content-Type: application/json' \
                    --output json 2>"$_err"); then
                rm -f "$_err"
                [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
            else
                local _msg=""
                [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
                rm -f "$_err"
                dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
            fi
        elif _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            local _msg=""
            [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
            rm -f "$_err"
            dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
        fi
    else
        if _out=$(c8y api --method "$_method" \
                --url "${DM_SERVICE}${_path}" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            local _msg=""
            [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
            rm -f "$_err"
            dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
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
    _DM_LAST_SUB_COUNT=$(printf '%s' "$_subs" | jq -r '
        if type == "array" then
            length
        elif type == "object" then
            (.subscriptions // .data // [] | length)
        else
            0
        end
    ' 2>/dev/null || printf '0')
    printf '%s' "$_subs"
}

# Print a summary line listing subscription names for a device.
dm_show_subscriptions() {   # <device_id>
    dm_count_subscriptions "$1" \
        | jq -r '
            if type == "array" then
                .[] | (.subscription // .subscriptionName // .id // empty)
            elif type == "object" then
                (.subscriptions // .data // [])[] | (.subscription // .subscriptionName // .id // empty)
            else
                empty
            end
        ' 2>/dev/null \
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

# Wait until at least one notification2 subscription exists for <device_id>.
dm_wait_for_subscription_present() {  # <device_id> [timeout_secs=30] [interval_secs=1]
    local _device=$1 _timeout=${2:-30} _interval=${3:-1} _elapsed=0
    while [ "$_elapsed" -lt "$_timeout" ]; do
        dm_count_subscriptions "$_device" >/dev/null
        if [ "${_DM_LAST_SUB_COUNT:-0}" -gt 0 ]; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    dm_count_subscriptions "$_device" >/dev/null
    return 1
}

# Wait until no notification2 subscriptions exist for <device_id>.
dm_wait_for_subscription_absent() {  # <device_id> [timeout_secs=30] [interval_secs=1]
    local _device=$1 _timeout=${2:-30} _interval=${3:-1} _elapsed=0
    while [ "$_elapsed" -lt "$_timeout" ]; do
        dm_count_subscriptions "$_device" >/dev/null
        if [ "${_DM_LAST_SUB_COUNT:-0}" -eq 0 ]; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    dm_count_subscriptions "$_device" >/dev/null
    return 1
}

# Create a static subscription for a single device and fail hard on API errors.
dm_create_static_subscription_must() {  # <api> <device_id> <device_name>
    local _api=$1 _id=$2 _name=$3
    dm_api_must POST /subscription \
        "{\"api\": \"${_api}\", \"devices\": [{\"id\": \"${_id}\", \"name\": \"${_name}\"}]}" >/dev/null
    dm_info "Created static subscription (api=${_api}, device=${_id})"
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
    dm_api_must PUT /subscription/type \
        "{\"api\": \"${_api}\", \"types\": ${_types}}" >/dev/null
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

# Verify that an MQTT connector is available and connected (for INBOUND tests)
# Returns 0 if ready, exits with 1 if not
dm_verify_mqtt_connector_ready() {
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    
    if [ -z "${_DM_MQTT_CONNECTOR_ID:-}" ]; then
        dm_fail "No MQTT connector configured — call dm_setup_and_connect_mqtt_connector first"
        return 1
    fi
    
    local _status
    _status=$(dm_get_connector_status "$_DM_MQTT_CONNECTOR_ID" | jq -r '.status // "UNKNOWN"' 2>/dev/null)
    
    if [ "$_status" != "CONNECTED" ]; then
        dm_fail "MQTT connector $_DM_MQTT_CONNECTOR_ID is not CONNECTED (status: $_status)"
        return 1
    fi
    
    dm_success "MQTT connector ready at ${_host}:${_port} (status: CONNECTED)"
    return 0
}

# Comprehensive test setup validation (call at start of every test)
# Validates: session, tools, service, and optionally mqtt connector
dm_test_setup_and_validate() {     # [require_mqtt_connector=true]
    local _require_mqtt=${1:-true}
    
    dm_validate_tools
    dm_wait_for_service
    
    if [ "$_require_mqtt" = "true" ]; then
        dm_require_mqtt_broker
        dm_verify_mqtt_connector_ready
    fi
}

# ── Smart Function helpers ────────────────────────────────────────────────────
_DM_SUPPORT_ESM=""

# Read supportESM from the tenant service configuration.
# Prints 'true' or 'false'. Falls back to 'false' when unavailable.
dm_get_support_esm() {
    if [ -n "${_DM_SUPPORT_ESM:-}" ]; then
        printf '%s\n' "$_DM_SUPPORT_ESM"
        return 0
    fi

    local _cfg _esm
    _cfg=$(dm_api GET /configuration/service)
    _esm=$(printf '%s' "$_cfg" | jq -r '.supportESM // false' 2>/dev/null || printf 'false')

    case "$_esm" in
        true|false) _DM_SUPPORT_ESM="$_esm" ;;
        *) _DM_SUPPORT_ESM="false" ;;
    esac

    printf '%s\n' "$_DM_SUPPORT_ESM"
}

# Wrap Smart Function source code for runtime mode.
# In ESM mode we require explicit export.
dm_wrap_onmessage_code() { # <code_without_export>
    local _base_code=$1
    if [ "$(dm_get_support_esm)" = "true" ]; then
        printf '%s\n\nexport { onMessage };\n' "$_base_code"
    else
        printf '%s\n' "$_base_code"
    fi
}

# ── Mapping helpers ────────────────────────────────────────────────────────────
_DM_LAST_MAPPING_ID=""
_DM_MQTT_CONNECTOR_ID=""  # Will be set by dm_require_mqtt_broker

# Resolve a processor extension class/name to a full extension entry payload.
# Output: JSON object containing extensionName, eventName, fqnClassName, extensionType, direction
dm_resolve_extension_entry() {   # <processor_extension_name_or_fqn> [direction]
    local _needle=${1:-}
    local _direction=${2:-}
    local _all _entry

    [ -z "$_needle" ] && return 1
    _all=$(dm_api GET /extension)

    _entry=$(printf '%s' "$_all" | jq -cer --arg n "$_needle" --arg d "$_direction" '
        to_entries
        | map(
            .key as $extName
            | (.value.extensionEntries // {})
            | to_entries
            | map(
                .value
                | . + { extensionName: $extName }
            )
        )
        | add // []
        | map(
            select(
                (.extensionName == $n)
                or (.eventName == $n)
                or (.fqnClassName == $n)
                or ((.fqnClassName // "") | endswith("." + $n))
            )
            | if ($d == "") then . else select((.direction // "") == $d) end
        )
        | .[0]
        | {
            extensionName: .extensionName,
            eventName: .eventName,
            fqnClassName: .fqnClassName,
            extensionType: .extensionType,
            direction: .direction
        }
    ' 2>/dev/null || printf '')

    [ -n "$_entry" ] && printf '%s\n' "$_entry"
}

# Require a processor extension to be registered on the tenant, resolving it by
# eventName/fqn (NOT by a hardcoded extensionName — that is assigned at upload
# time and is environment-specific). On success stores the resolved entry in
# _DM_RESOLVED_EXTENSION; build the mapping's `extension` field from it via:
#     jq -cn --argjson extension "$_DM_RESOLVED_EXTENSION" '{ ..., extension: $extension }'
# On absence the test exits with DM_EXIT_SKIP (default 42), which run-tests.sh
# classifies as SKIP (not PASS). These tests need the dynamic-mapper-extension
# JAR uploaded to the tenant.
_DM_RESOLVED_EXTENSION=""
dm_require_extension() {   # <eventName_or_fqn> [direction]
    local _needle=$1 _direction=${2:-} _entry _evt
    _entry=$(dm_resolve_extension_entry "$_needle" "$_direction" 2>/dev/null || true)
    _evt=$(printf '%s' "$_entry" | jq -r '.eventName // empty' 2>/dev/null || printf '')
    if [ -z "$_entry" ] || [ -z "$_evt" ]; then
        dm_skip_exit "Processor extension '${_needle}'${_direction:+ (${_direction})} is not registered on this tenant." \
                     "Upload the dynamic-mapper-extension JAR (see EXTENSIONS.md), then re-run."
    fi
    _DM_RESOLVED_EXTENSION="$_entry"
    dm_info "Resolved extension '${_needle}' -> $(printf '%s' "$_entry" | jq -r '.extensionName + ":" + .eventName' 2>/dev/null || printf '%s' "$_needle")"
}

# Normalize legacy mapping payloads used by tests to current backend contract.
dm_normalize_mapping_payload() {   # <json_body>
    local _raw=${1:-}
    local _normalized _has_extension _legacy_ext _direction _resolved

    _normalized="$_raw"

    # Backward compatibility: BINARY was removed, use PROTOBUF_INTERNAL.
    _normalized=$(printf '%s' "$_normalized" | jq -c '
        if .mappingType == "BINARY" then .mappingType = "PROTOBUF_INTERNAL" else . end
    ' 2>/dev/null || printf '%s' "$_normalized")

    # Backward compatibility: processorExtensionName -> extension object.
    # Skip if extension object is already present (new API contract)
    _has_extension=$(printf '%s' "$_normalized" | jq -r '.extension | if . == null then "no" else "yes" end' 2>/dev/null || printf 'no')
    if [ "$_has_extension" = "no" ]; then
        _legacy_ext=$(printf '%s' "$_normalized" | jq -r '.processorExtensionName // empty' 2>/dev/null || printf '')
        if [ -n "$_legacy_ext" ]; then
            _direction=$(printf '%s' "$_normalized" | jq -r '.direction // empty' 2>/dev/null || printf '')
            _resolved=$(dm_resolve_extension_entry "$_legacy_ext" "$_direction" 2>/dev/null || true)

            if [ -n "$_resolved" ]; then
                _normalized=$(printf '%s' "$_normalized" | jq -c --argjson ext "$_resolved" '
                    .extension = $ext
                    | del(.processorExtensionName)
                ' 2>/dev/null || printf '%s' "$_normalized")
                dm_info "Resolved legacy extension reference: $_legacy_ext -> $(printf '%s' "$_resolved" | jq -r '.extensionName + ":" + .eventName' 2>/dev/null || printf 'extension')"
            else
                dm_warn "Could not resolve legacy extension reference: $_legacy_ext (extension might not be registered)"
            fi
        fi
    fi

    printf '%s\n' "$_normalized"
}

# Create a mapping via POST /mapping. The raw JSON body is the only argument.
# Stores the new mapping id in _DM_LAST_MAPPING_ID.
dm_create_mapping() {   # <json_body>
    local _json _payload
    _payload=$(dm_normalize_mapping_payload "$1")
    _json=$(dm_api POST /mapping "$_payload")
    _DM_LAST_MAPPING_ID=$(printf '%s' "$_json" | jq -er '.id // empty' 2>/dev/null || printf '')
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

# Deploy a mapping to an arbitrary connector by identifier. Inbound mappings are
# only processed by a connector they are deployed to (the deployment map is keyed
# by mapping identifier and lists the connector identifiers). Resolves the mapping
# identifier from its id when needed. Fails hard on API error.
dm_deploy_mapping_to_connector() {  # <mapping_id> <connector_identifier>
    local _mapping_ref=$1 _conn=$2 _mapping_json _deployment_key _resolved
    [ -z "${_conn:-}" ] && dm_error "dm_deploy_mapping_to_connector: connector identifier required"
    _mapping_json=$(dm_api GET "/mapping/${_mapping_ref}" 2>/dev/null || printf '{}')
    _resolved=$(printf '%s' "$_mapping_json" | jq -r '.identifier // empty' 2>/dev/null || printf '')
    _deployment_key="${_resolved:-$_mapping_ref}"
    dm_api_must PUT "/deployment/defined/${_deployment_key}" "[\"${_conn}\"]" >/dev/null
    dm_info "Deployed mapping ${_mapping_ref} (key=${_deployment_key}) -> ${_conn}"
}

# Deploy a mapping to the MQTT connector (uses _DM_MQTT_CONNECTOR_ID set by dm_require_mqtt_broker).
# Requires: dm_require_mqtt_broker must be called first to initialize the connector ID.
dm_deploy_mapping_to_mqtt_connector() {  # <mapping_id>
    if [ -z "${_DM_MQTT_CONNECTOR_ID:-}" ]; then
        dm_fail "MQTT connector ID not set — call dm_require_mqtt_broker first"
        return 1
    fi
    local _mapping_ref _deployment_key _mapping_json _resolved_identifier
    _mapping_ref="$1"
    _deployment_key="$_mapping_ref"

    # Deployment map is keyed by mapping identifier (not inventory id).
    # Tests pass mapping id from create response, so resolve it when possible.
    _mapping_json=$(dm_api GET "/mapping/${_mapping_ref}" 2>/dev/null || printf '{}')
    _resolved_identifier=$(printf '%s' "$_mapping_json" | jq -r '.identifier // empty' 2>/dev/null || printf '')
    if [ -n "${_resolved_identifier:-}" ]; then
        _deployment_key="$_resolved_identifier"
    fi

    dm_api_must PUT "/deployment/defined/${_deployment_key}" \
        "[\"${_DM_MQTT_CONNECTOR_ID}\"]" >/dev/null

    # Verify deployment assignment is persisted before test publish.
    local _assigned _deployment _retry_err
    _deployment=$(dm_api_must GET "/deployment/defined/${_deployment_key}")
    _assigned=$(printf '%s' "$_deployment" | jq -r --arg cid "${_DM_MQTT_CONNECTOR_ID}" '
        if type == "string" then
            . == $cid
        elif type == "array" then
            (index($cid) != null)
            or (map(select(type == "object") | (.identifier // .connectorIdentifier // .id // "")) | index($cid) != null)
        elif type == "object" then
            (.identifier // .connectorIdentifier // .id // "") == $cid
            or (.connectors // [] | if type == "array" then (index($cid) != null) else false end)
        else
            false
        end' 2>/dev/null || printf 'false')

    # Some c8y api versions serialize top-level array bodies differently with --template input.value.
    # If deployment did not stick, retry with a literal template expression body.
    if [ "${_assigned:-false}" != "true" ]; then
        _retry_err=$(mktemp)
        if ! c8y api --method PUT \
                --url "${DM_SERVICE}/deployment/defined/${_deployment_key}" \
                --template "[\"${_DM_MQTT_CONNECTOR_ID}\"]" \
                --header 'Content-Type: application/json' \
                --output json > /dev/null 2>"$_retry_err"; then
            local _retry_msg=""
            [ -s "$_retry_err" ] && _retry_msg="$(tr '\n' ' ' <"$_retry_err" | head -c 400)"
            rm -f "$_retry_err"
            dm_error "Deployment retry failed for key=${_deployment_key}${_retry_msg:+: $_retry_msg}"
        fi
        rm -f "$_retry_err"

        _deployment=$(dm_api_must GET "/deployment/defined/${_deployment_key}")
        _assigned=$(printf '%s' "$_deployment" | jq -r --arg cid "${_DM_MQTT_CONNECTOR_ID}" '
            if type == "string" then
                . == $cid
            elif type == "array" then
                (index($cid) != null)
                or (map(select(type == "object") | (.identifier // .connectorIdentifier // .id // "")) | index($cid) != null)
            elif type == "object" then
                (.identifier // .connectorIdentifier // .id // "") == $cid
                or (.connectors // [] | if type == "array" then (index($cid) != null) else false end)
            else
                false
            end' 2>/dev/null || printf 'false')
    fi

    if [ "${_assigned:-false}" != "true" ]; then
        dm_error "Deployment verification failed for mapping ${_mapping_ref} (key=${_deployment_key}): connector ${_DM_MQTT_CONNECTOR_ID} not assigned; response=${_deployment}"
    fi
    dm_info "Deployed mapping to MQTT connector: ${_mapping_ref} (key=${_deployment_key}) -> ${_DM_MQTT_CONNECTOR_ID}"
}

# Assert that connector runtime has at least one active subscribed topic.
# Call this after activating mapping and before publishing test payload.
dm_assert_mqtt_topics_active() {   # [connector_identifier]
    local _cid="${1:-${_DM_MQTT_CONNECTOR_ID:-}}"
    [ -z "${_cid:-}" ] && dm_error "Connector ID not set for active topic assertion"

    local _connected
    _connected=$(dm_get_connector_status "$_cid" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
    if [ "$_connected" != "CONNECTED" ]; then
        dm_error "Connector $_cid is not CONNECTED (status=$_connected)"
    fi

        # Runtime subscription updates are asynchronous. Poll briefly before failing.
        local _topic_count=0 _sub_map='{}' _attempt
        for _attempt in 1 2 3 4 5 6 7 8; do
                _sub_map=$(dm_api_must GET "/monitoring/subscription/${_cid}")
                _topic_count=$(printf '%s' "$_sub_map" | jq -r '
                        if type == "object" then
                            (to_entries | map(select((.value | tonumber? // 0) > 0)) | length)
                        else
                            0
                        end' 2>/dev/null || printf '0')

                if [ "${_topic_count:-0}" -gt 0 ]; then
                        break
                fi
                sleep 1
        done

        if [ "${_topic_count:-0}" -lt 1 ]; then
                local _deploy _mapping_stats
                _deploy=$(dm_api_must GET "/deployment/defined" | jq -c . 2>/dev/null || printf '{}')
                _mapping_stats=$(dm_api GET "/monitoring/status/mapping/statistic" | jq -c . 2>/dev/null || printf '[]')
                dm_warn "Connector $_cid has 0 active inbound subscriptions after activation. subscriptionMap=${_sub_map}"
                dm_warn "Deployment map snapshot: ${_deploy}"
                dm_warn "Mapping statistics snapshot: ${_mapping_stats}"
                dm_error "No active MQTT topic subscriptions detected for connector $_cid after mapping activation"
        fi

        dm_info "Connector $_cid active inbound topic subscriptions: $_topic_count"
}

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

# Enable a connector configuration
dm_enable_connector() {     # <connectorIdentifier>
    dm_api PUT "/configuration/connector/instance/$1" "{\"enabled\": true}" >/dev/null || true
    dm_info "Enabled connector: $1"
}

# Disable a connector configuration
dm_disable_connector() {    # <connectorIdentifier>
    dm_api PUT "/configuration/connector/instance/$1" "{\"enabled\": false}" >/dev/null || true
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

# Create (or replace) a Cumulocity MQTT Service connector configuration (disabled).
# The Cumulocity MQTT Service is the Pulsar-based singleton
# CUMULOCITY_MQTT_SERVICE_PULSAR, whose connection parameters (service URL /
# credentials) are derived from the microservice credentials at connect time
# (copied from the connector specification via copyPredefinedValues), so only the
# identifier and name need to be supplied here. NOTE: the identifier must be
# alphanumeric — it becomes part of the reliable-notification subscriber name.
dm_setup_c8y_mqtt_service_connector() {     # [identifier] [name] [connectorType]
    local _identifier="${1:-testc8ymqttservice}"
    local _name="${2:-Test Cumulocity MQTT Service}"
    local _type="${3:-CUMULOCITY_MQTT_SERVICE_PULSAR}"

    dm_api POST /configuration/connector/instance "{
      \"identifier\": \"$_identifier\",
      \"connectorType\": \"$_type\",
      \"name\": \"$_name\",
      \"description\": \"Auto-configured Cumulocity MQTT Service connector for integration tests\",
      \"enabled\": false,
      \"properties\": {}
    }" >/dev/null || true

    dm_info "Created Cumulocity MQTT Service connector: $_identifier (type=$_type)"
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

# ── MQTT helpers ───────────────────────────────────────────────────────────────
# Environment variables:
#   MQTT_HOST      (default broker.hivemq.com)
#   MQTT_PORT      (default 1883)
#   MQTT_USER      (optional)
#   MQTT_PASS      (optional)
#   MQTT_TLS       (optional, true/false, default false)
#   MQTT_CAFILE    (optional path to CA certificate)
#   MQTT_INSECURE  (optional, true/false, default false)

# Skip the calling test if the MQTT broker is unreachable or mosquitto_pub is
# not installed.  Call this once, right after dm_wait_for_service.
dm_require_mqtt_broker() {
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _cfg _match_count _matching_ids _statuses _connected_count _first_id _first_match _proto _user
    if ! command -v mosquitto_pub >/dev/null 2>&1; then
        dm_skip_exit "mosquitto_pub not installed — install mosquitto-clients to run MQTT tests."
    fi
    # Quick TCP reachability check (3-second timeout)
    if ! nc -z -w 3 "$_host" "$_port" >/dev/null 2>&1; then
        dm_skip_exit "MQTT broker not reachable at ${_host}:${_port}." \
                     "Set MQTT_HOST / MQTT_PORT / MQTT_USER / MQTT_PASS and retry."
    fi

    # Ensure the selected publish target matches at least one enabled MQTT
    # connector in Dynamic Mapper. Otherwise tests publish to the wrong broker
    # and inbound mappings never receive any message.
    _cfg=$(dm_api_json_array GET /configuration/connector/instance | jq -rs 'map(select(type=="object"))' 2>/dev/null || printf '[]')
    _match_count=$(printf '%s' "$_cfg" | jq -r --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
        ] | length' 2>/dev/null || printf '0')
    if [ "${_match_count:-0}" -lt 1 ]; then
        dm_skip_exit "No enabled MQTT connector is configured for ${_host}:${_port}." \
                     "Update MQTT_HOST/MQTT_PORT to match the mapper connector configuration (or update connector config)."
    fi

    _matching_ids=$(printf '%s' "$_cfg" | jq -r --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
          | (.identifier // empty)
        ] | .[]' 2>/dev/null || true)
    
    # Store the first matching connector ID for deployment operations
    _DM_MQTT_CONNECTOR_ID=$(printf '%s\n' "$_matching_ids" | head -n 1)

    _first_match=$(printf '%s' "$_cfg" | jq -c --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
        ] | first // {}' 2>/dev/null || printf '{}')

    # If the connector config specifies MQTT auth/protocol and the test env does not,
    # inherit sensible defaults to avoid publish/subscribe mismatches.
    _user=$(printf '%s' "$_first_match" | jq -r '.properties.user // empty' 2>/dev/null || printf '')
    if [ -n "$_user" ] && [ -z "${MQTT_USER:-}" ]; then
        export MQTT_USER="$_user"
        dm_info "Using MQTT_USER from connector configuration for test publish/sub: ${MQTT_USER}"
    fi

    _proto=$(printf '%s' "$_first_match" | jq -r '.properties.protocol // empty' 2>/dev/null || printf '')
    if [ "$_proto" = "mqtts://" ] && [ -z "${MQTT_TLS:-}" ]; then
        export MQTT_TLS=true
        dm_info "Using MQTT_TLS=true from connector protocol mqtts://"
    fi

    _statuses=$(dm_api GET /monitoring/status/connectors | jq -c '
        if type == "array" then
            .
        elif type == "object" then
            if ((.connectorIdentifier // empty) != "" and (.status // empty) != "") then
                [.]  # single status object
            else
                [to_entries[] | .value]  # map keyed by connector id
            end
        else
            []
        end' 2>/dev/null || printf '[]')
    [ -z "${_statuses:-}" ] && _statuses='[]'
    _connected_count=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
        | jq -r --argjson statuses "$_statuses" '
            [ .[] as $id
              | $statuses[]
              | select((.connectorIdentifier // "") == $id)
              | select((.status // "") == "CONNECTED")
            ] | length' 2>/dev/null || printf '0')
    [ -z "${_connected_count:-}" ] && _connected_count=0

    if [ "${_connected_count:-0}" -lt 1 ]; then
        _first_id=$(printf '%s\n' "$_matching_ids" | head -n 1)
        if [ -n "${_first_id:-}" ]; then
            dm_info "Matching MQTT connector is not CONNECTED; attempting connect: ${_first_id}"
            dm_connect_connector "$_first_id" || true
            dm_wait 8 "waiting for connector to connect"

            _statuses=$(dm_api GET /monitoring/status/connectors | jq -c '
                if type == "array" then
                    .
                elif type == "object" then
                    if ((.connectorIdentifier // empty) != "" and (.status // empty) != "") then
                        [.]  # single status object
                    else
                        [to_entries[] | .value]  # map keyed by connector id
                    end
                else
                    []
                end' 2>/dev/null || printf '[]')
            [ -z "${_statuses:-}" ] && _statuses='[]'
            _connected_count=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
                | jq -r --argjson statuses "$_statuses" '
                    [ .[] as $id
                      | $statuses[]
                      | select((.connectorIdentifier // "") == $id)
                      | select((.status // "") == "CONNECTED")
                    ] | length' 2>/dev/null || printf '0')
            [ -z "${_connected_count:-}" ] && _connected_count=0

            # Fallback to direct endpoint when aggregate status payload is empty
            # or parsing failed transiently.
            if [ "${_connected_count:-0}" -lt 1 ] && [ -n "${_first_id:-}" ]; then
                if [ "$(dm_get_connector_status "$_first_id" | jq -r '.status // empty' 2>/dev/null || printf '')" = "CONNECTED" ]; then
                    _connected_count=1
                    dm_info "Confirmed CONNECTED via direct connector status: ${_first_id}"
                fi
            fi
        fi
    fi

    if [ "${_connected_count:-0}" -lt 1 ]; then
        local _matching_statuses
        _matching_statuses=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
            | jq -c --argjson statuses "$_statuses" '
                [ .[] as $id
                  | { id: $id, status: (($statuses[] | select((.connectorIdentifier // "") == $id) | .status) // "UNKNOWN") }
                ]' 2>/dev/null || printf '[]')
        dm_warn "Matching connector statuses for ${_host}:${_port}: ${_matching_statuses}"
        dm_skip_exit "No matching MQTT connector for ${_host}:${_port} is CONNECTED." \
                     "Connect the matching connector in Dynamic Mapper and retry."
    fi

    dm_info "MQTT broker reachable at ${_host}:${_port} — proceeding."
}

_dm_mqtt_append_tls_args() {  # <array_name>
    # NOTE: appends to the named array via eval rather than a `local -n` nameref —
    # namerefs require bash 4.3+, and macOS ships bash 3.2 where `local -n` fails
    # with "invalid option" (exit 2), aborting the caller under `set -e`.
    local _arr_name=$1
    local _tls="${MQTT_TLS:-false}"
    local _insecure="${MQTT_INSECURE:-false}"
    local _cafile="${MQTT_CAFILE:-}"

    [ "$_tls" = "true" ] || return 0

    eval "${_arr_name}+=(--tls-version tlsv1.2)"
    if [ -n "$_cafile" ]; then
        eval "${_arr_name}+=(--cafile \"\$_cafile\")"
    fi
    if [ "$_insecure" = "true" ]; then
        eval "${_arr_name}+=(--insecure)"
    fi
}

dm_mqtt_publish() {     # <topic> <payload> [qos=0]
    local _topic=$1 _payload=$2 _qos=${3:-0}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -m "$_payload" -q "$_qos")
    [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
    [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    _dm_mqtt_append_tls_args _args
    mosquitto_pub "${_args[@]}"
    dm_info "Published to $_topic (broker=$_host:$_port, qos=$_qos)"
}

# Subscribe and capture at most 1 message with a timeout.
# Prints the received message to stdout.  Returns 1 on timeout.
dm_mqtt_subscribe_one() {   # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -C 1 -W "$_timeout")
    [ -n "${MQTT_USER:-}" ] && _args+=(-u "$MQTT_USER")
    [ -n "${MQTT_PASS:-}" ] && _args+=(-P "$MQTT_PASS")
    # Unique client id avoids any collision with the broker connector or other
    # clients on a shared public broker (a collision shows up as MOSQ_ERR_PROTOCOL).
    _args+=(-i "dmtest-sub-$$-${RANDOM}")
    _dm_mqtt_append_tls_args _args
    # Do NOT swallow stderr — callers capture it for diagnostics (e.g. exit 2 =
    # MOSQ_ERR_PROTOCOL, 27 = timeout). Suppressing it hid the real failure.
    mosquitto_sub "${_args[@]}"
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
    --name "$1" --type "$2" \
        --output json 2>/dev/null \
    | jq -r '.managedObject.id // empty' 2>/dev/null \
    | head -n 1 || printf ''
}

dm_count_measurements_since() {     # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y measurements list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].measurements // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_count_events_since() {   # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y events list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].events // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_count_alarms_since() {   # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y alarms list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].alarms // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
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

# Poll until a device (resolved by external id) has at least <min_count>
# measurements, or until <timeout> seconds elapse. Returns 0 on success, 1 on
# timeout. Tolerates the device not existing yet (mapper may create it lazily).
dm_wait_for_measurement_count() {  # <ext_id> <ext_id_type> <min_count> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _min=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _devid _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _count=$(c8y measurements list --device "$_devid" \
                --pageSize 200 --output json 2>/dev/null \
                | jq -s 'length' 2>/dev/null || printf '0')
            [ "${_count:-0}" -ge "$_min" ] 2>/dev/null && return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

# Poll for a measurement and record a pass/fail assertion (for use with
# dm_print_summary). Wraps dm_wait_for_measurement_count.
dm_assert_measurement_present() {  # <label> <ext_id> <ext_id_type> [min=1] [timeout=30]
    local _label=$1 _extid=$2 _type=$3 _min=${4:-1} _timeout=${5:-30}
    if dm_wait_for_measurement_count "$_extid" "$_type" "$_min" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] >= $_min measurement(s) found"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] no measurement found after ${_timeout}s"
    fi
}

dm_get_latest_measurement() {  # <ext_id> <ext_id_type> <measurement_type>
    local _extid=$1 _extidtype=$2 _meastype=$3
    local _device_id _resp _row
    [ -z "$_extid" ] && { printf '{}'; return 0; }
    _device_id=$(dm_lookup_device_by_ext_id "$_extid" "$_extidtype")
    [ -z "$_device_id" ] && { printf '{}'; return 0; }

    # Prefer a deterministic single-record fetch from the measurements REST API.
    _resp=$(c8y api --method GET \
        --url "/measurement/measurements?source=${_device_id}&type=${_meastype}&pageSize=1&revert=true" \
        --output json 2>/dev/null || printf '{}')
    _row=$(printf '%s' "$_resp" | jq -c '
        if (.measurements // null) != null and ((.measurements | type) == "array") then
            (.measurements[0] // {})
        elif (.data // null) != null and ((.data | type) == "array") then
            (.data[0] // {})
        elif type == "array" then
            (.[0] // {})
        elif type == "object" then
            .
        else
            {}
        end
    ' 2>/dev/null || printf '{}')

    # Fallback for environments where query handling differs.
    if [ "$_row" = "{}" ]; then
        _row=$(c8y measurements list \
            --device "$_device_id" \
            --type "$_meastype" \
            --pageSize 1 --output json 2>/dev/null \
            | jq -s '
                def rows:
                    if length == 0 then
                        []
                    elif length == 1 then
                        if (.[0] | type) == "array" then
                            .[0]
                        elif (.[0] | type) == "object" then
                            (.[0].data // .[0].measurements // [.[0]])
                        else
                            []
                        end
                    else
                        .
                    end;
                (rows[0] // {})
            ' 2>/dev/null || printf '{}')
    fi

    printf '%s\n' "$_row"
}
