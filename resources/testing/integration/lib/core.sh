#!/bin/bash
#
# lib/core.sh — configuration, colours, output, tool validation, assertions, CLI flags, wait helpers
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── Configuration ──────────────────────────────────────────────────────────────
# DM_SERVICE_HOST (optional): talk to a mapper instance running on this host (e.g. localhost:8080)
# instead of the tenant's /service/dynamic-mapper-service route, which always reaches the
# *deployed* instance. When set, DM_SERVICE defaults to "" (a locally hosted microservice serves
# its API at the root). Unset = unchanged behaviour.
DM_SERVICE_HOST="${DM_SERVICE_HOST:-}"
if [ -n "$DM_SERVICE_HOST" ]; then
    DM_SERVICE="${DM_SERVICE-}"
else
    DM_SERVICE="${DM_SERVICE:-/service/dynamic-mapper-service}"
fi

# Reserved exit code a test uses to signal "skipped" (prerequisite absent) rather
# than passed or failed. run-tests.sh classifies this code as SKIP. Keep in sync
# with DM_SKIP_EXIT_CODE in run-tests.sh.
DM_EXIT_SKIP="${DM_EXIT_SKIP:-42}"
DM_DEFAULT_DISCOVERY_WAIT="${DM_DEFAULT_DISCOVERY_WAIT:-10}"
DM_DEFAULT_STARTUP_WAIT="${DM_DEFAULT_STARTUP_WAIT:-60}"
DM_DEFAULT_HEALTH_RETRIES="${DM_DEFAULT_HEALTH_RETRIES:-24}"
DM_DEFAULT_HEALTH_INTERVAL="${DM_DEFAULT_HEALTH_INTERVAL:-10}"

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

# The number shown in a test's banner is its position in the run-tests.sh catalogue (the same
# number the runner's menu uses), derived on the fly — adding or moving a test there renumbers
# everything, and the scripts themselves carry no number. Prints nothing when the running
# script is not in the catalogue.
dm_test_number() {
    local _name _runner
    _name=$(basename "${DM_TEST_SCRIPT:-$0}" .sh)
    _runner="${_DM_INTEGRATION_DIR}/run-tests.sh"
    [ -r "$_runner" ] || return 0
    awk -v name="$_name" '
        /^[[:space:]]+"[a-z]+\|[a-z]+\|test-[a-z0-9-]+\|/ {
            n++; split($0, f, "|"); if (f[3] == name) { print n; exit }
        }' "$_runner"
}

# "<title>" -> "<n>. <title>" (unless the title already starts with a number)
dm_numbered_title() {   # <title>
    local _n
    case "$1" in [0-9]*.\ *) printf '%s' "$1"; return ;; esac
    _n=$(dm_test_number)
    if [ -n "$_n" ]; then printf '%s. %s' "$_n" "$1"; else printf '%s' "$1"; fi
}

dm_banner() {   # <title>
    echo ""
    printf "${_C_BOLD}%s${_C_RESET}\n" "=============================================="
    printf "${_C_BOLD} %s${_C_RESET}\n" "$(dm_numbered_title "$1")"
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
    printf "${_C_BOLD} %s: DONE${_C_RESET}\n" "$(dm_numbered_title "$1")"
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

# Assert numeric equality after normalizing both values to a fixed decimal scale.
# This avoids false negatives from JSON number formatting differences (e.g. 42 vs 42.0).
dm_assert_num_eq() {   # <label> <expected_num> <actual_num> [scale=1]
    local label=$1 expected_raw=$2 actual_raw=$3 scale=${4:-1}
    local expected_fmt actual_fmt

    expected_fmt=$(printf "%.*f" "$scale" "$expected_raw" 2>/dev/null || printf '')
    actual_fmt=$(printf "%.*f" "$scale" "$actual_raw" 2>/dev/null || printf '')

    if [ -z "$expected_fmt" ] || [ -z "$actual_fmt" ]; then
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$label] non-numeric input (expected='$expected_raw' actual='$actual_raw')"
        return 0
    fi

    dm_assert_eq "$label" "$expected_fmt" "$actual_fmt"
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
    if [ "${_DM_DO_CLEANUP}" = "true" ]; then
        [ -n "${_DM_CLEANUP_FN}" ] && { "$_DM_CLEANUP_FN" || true; }
        # Remove a provisioned MQTT Service trust anchor — but only when it was
        # provisioned by this test (not shared by run-tests.sh run).
        [ "${_DM_MQTT_CERT_SUITE_OWNED:-false}" = "false" ] && \
            [ -n "${_DM_MQTT_CERT_NAME:-}" ] && { dm_cleanup_mqtt_service_cert || true; }
    elif [ -n "${_DM_CLEANUP_FN}" ] || [ -n "${_DM_MQTT_CERT_NAME:-}" ]; then
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
        if c8y api --method GET ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}/mapping" \
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
