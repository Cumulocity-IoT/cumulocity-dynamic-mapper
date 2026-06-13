#!/bin/bash
#
# run-tests.sh — Dynamic Mapper integration test runner
#
# Usage:  ./run-tests.sh [SUITE ...] [CONNECTOR]
#   SUITE      a/all i/inbound o/outbound e/extension s/smartfunc r/reliability,
#             a <script-name>, menu number(s), or omit for the interactive menu.
#   CONNECTOR  g = generic MQTT (public broker, default)
#             m = Cumulocity MQTT Service (CUMULOCITY_MQTT_SERVICE_PULSAR, cert auth)
#   The g/m token may appear in any position. Examples:
#     ./run-tests.sh                 # interactive (prompts for suite + connector)
#     ./run-tests.sh inbound m       # inbound suite against the MQTT Service
#     ./run-tests.sh 1 3 5 g         # menu items 1/3/5 against the public broker
#     ./run-tests.sh all             # every test, generic MQTT
#
# Environment:
#   DM_SERVICE          Base path to dynamic mapper (default /service/dynamic-mapper-service)
#   MQTT_HOST           MQTT broker host  (default broker.hivemq.com)
#   MQTT_PORT           MQTT broker port  (default 1883)
#   MQTT_USER           MQTT username     (optional)
#   MQTT_PASS           MQTT password     (optional)
#   MQTT_TLS            Enable TLS for MQTT publish/subscribe (true/false, default false)
#   MQTT_CAFILE         CA certificate path for MQTT TLS validation (optional)
#   MQTT_INSECURE       Skip MQTT TLS cert verification (true/false, default false)
#   DM_DEFAULT_DISCOVERY_WAIT  Wait for dynamic discovery checks in some tests (default 10)
#   DM_DEFAULT_STARTUP_WAIT    Wait used by restart/persistence tests (default 60)
#   DM_DEFAULT_HEALTH_RETRIES  Service health retries in harness (default 24)
#   DM_DEFAULT_HEALTH_INTERVAL  Service health retry interval seconds (default 10)
#   DM_STOP_ON_FAIL     Stop suite on first test failure (default: continue)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Run c8y CLI non-interactively: suppress all confirmation prompts and spinners.
export C8Y_SETTINGS_CI=true

# Reserved exit code a test uses to signal "skipped" (prerequisite absent).
# Kept in sync with DM_EXIT_SKIP in test-harness.sh.
DM_SKIP_EXIT_CODE="${DM_EXIT_SKIP:-42}"

# ── ANSI colours ───────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    C_GREEN=$'\033[0;32m'
    C_RED=$'\033[0;31m'
    C_YELLOW=$'\033[1;33m'
    C_CYAN=$'\033[0;36m'
    C_BOLD=$'\033[1m'
    C_DIM=$'\033[2m'
    C_RESET=$'\033[0m'
else
    C_GREEN="" C_RED="" C_YELLOW="" C_CYAN="" C_BOLD="" C_DIM="" C_RESET=""
fi

# ── Test catalogue ─────────────────────────────────────────────────────────────
#  Format: "CATEGORY|script-basename|short description"
declare -a TESTS=(
    # ── Inbound ───────────────────────────────────────────────────────────────
    "inbound|test-inbound-json-default|JSON / DEFAULT → MEASUREMENT"
    "inbound|test-inbound-json-jsonata|JSON / JSONATA → EVENT"
    "inbound|test-inbound-json-smartfunction|JSON / Smart Function → MEASUREMENT"
    "inbound|test-inbound-flatfile|FLAT_FILE / CSV → MEASUREMENT"
    "inbound|test-inbound-hex|HEX → EVENT"
    "inbound|test-inbound-http-connector|HTTP connector → MEASUREMENT"
    "inbound|test-inbound-implicit-device|Implicit device auto-creation"
    "inbound|test-inbound-multi-device|Array payload → multiple devices"
    "inbound|test-inbound-alarm|JSON / DEFAULT → ALARM"
    "inbound|test-inbound-operation|JSON / DEFAULT → OPERATION"
    # ── Inbound (Smart Function patterns) ──────────────────────────────────────
    "smartfunction|test-inbound-smartfunction-02|Pattern 02: Topic-based external ID + sensor filter"
    "smartfunction|test-inbound-smartfunction-04|Pattern 04: Dual payload type + deduplication"
    # ── Inbound (Java Extensions) ─────────────────────────────────────────────
    "extension|test-inbound-extension-custom-measurement|Extension: JSON → Measurement"
    "extension|test-inbound-extension-custom-alarm|Extension: JSON → Alarm"
    "extension|test-inbound-extension-custom-event|Extension: Protobuf → Event"
    "extension|test-inbound-extension-sparkplugb-measurement|Extension: Sparkplug B → Measurement"
    # ── Outbound (payload) ────────────────────────────────────────────────────
    "outbound|test-outbound-measurement|C8Y Measurement → MQTT broker"
    "outbound|test-outbound-event|C8Y Event → MQTT broker"
    "outbound|test-outbound-alarm|C8Y Alarm → MQTT broker"
    "outbound|test-outbound-operation|C8Y Operation → MQTT broker"
    "outbound|test-outbound-filter|filterMapping — selective forwarding"
    "outbound|test-outbound-topic-resolution|Dynamic publish topic resolution"
    "outbound|test-outbound-json-smartfunction|Smart Function: Measurement → MQTT JSON"
    # ── Outbound (subscription management) ────────────────────────────────────
    "outbound|test-outbound-static-subscription|Static subscription management"
    "outbound|test-outbound-type-subscription|Dynamic type subscription"
    "outbound|test-outbound-group-subscription|Dynamic group subscription"
    "outbound|test-outbound-group-subscription-removal|Group subscription removal"
    "outbound|test-outbound-subscription-persistence|Subscription persistence after restart"
    # ── Outbound (Extensions/Protocols) ───────────────────────────────────────
    "extension|test-outbound-extension-alarm-to-sparkplugb|Extension: Alarm → Sparkplug B DCMD"
    # ── Reliability ───────────────────────────────────────────────────────────
    "reliability|test-multi-tenant|Mapping CRUD / tenant isolation"
    "reliability|test-multi-connector|Multiple connector status check"
    "reliability|test-reconnect|Connector disconnect / reconnect cycle"
    "reliability|test-cumulocity-mqtt-service|Cumulocity MQTT Service connector lifecycle"
)

# ── Helpers ───────────────────────────────────────────────────────────────────
_n_tests=${#TESTS[@]}

_cat_of()  { echo "${1%%|*}"; }
_name_of() { local rest="${1#*|}"; echo "${rest%%|*}"; }
_desc_of() { echo "${1##*|}"; }

_print_header() {
    printf "\n${C_BOLD}%s${C_RESET}\n" "Dynamic Mapper — Integration Test Runner"
    printf "${C_DIM}Scripts: %s${C_RESET}\n\n" "$SCRIPT_DIR"
}

_print_menu() {
    _print_header
    local idx=1 cat prev_cat=""
    printf "  ${C_BOLD}%-4s %-14s %s${C_RESET}\n" "#" "Category" "Test"
    printf "  %s\n" "$(printf '─%.0s' {1..60})"
    for entry in "${TESTS[@]}"; do
        cat=$(_cat_of "$entry")
        if [ "$cat" != "$prev_cat" ]; then
            printf "\n  ${C_CYAN}${C_BOLD}%s${C_RESET}\n" "── $(echo "$cat" | tr '[:lower:]' '[:upper:]') ──"
            prev_cat="$cat"
        fi
        printf "  ${C_BOLD}%2d${C_RESET}  %-14s %s\n" \
            "$idx" "[$cat]" "$(_desc_of "$entry")"
        idx=$((idx + 1))
    done
    echo ""
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "a"  "[all]"         "Run all tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "i"  "[inbound]"     "Run all inbound tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "o"  "[outbound]"    "Run all outbound tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "e"  "[extension]"   "Run extension tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "s"  "[smartfunc]"   "Run Smart Function pattern tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "r"  "[reliability]" "Run reliability tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-14s %s\n" "q"  ""              "Quit"
    echo ""
    printf "  ${C_DIM}After selecting tests you'll choose a connector: g=generic MQTT, m=Cumulocity MQTT Service${C_RESET}\n"
    echo ""
}

_print_help() {
        cat <<'EOF'
Dynamic Mapper integration test runner

Usage:
    ./run-tests.sh [SUITE ...] [CONNECTOR]

  SUITE — which tests to run:
    a | all                          Run every test
    i | inbound                      Run all inbound tests
    o | outbound                     Run all outbound tests
    e | extension                    Run all extension tests
    s | smartfunc                    Run all Smart Function pattern tests
    r | reliability                  Run reliability tests
    <script-name>                    Run one script (with or without .sh)
    <n> [n2 ...]                     Run one or more menu indices
    (omit)                           Pick interactively

  CONNECTOR — which broker the MQTT tests drive (default g):
    g                                Generic MQTT (public broker)
    m                                Cumulocity MQTT Service
                                     (CUMULOCITY_MQTT_SERVICE_PULSAR, TLS :9883,
                                     X.509 cert auth → DM_BROKER_MODE=c8y-mqtt-service)

Examples:
    ./run-tests.sh inbound m         All inbound tests against the MQTT Service
    ./run-tests.sh 1 3 5 g           Menu items 1/3/5 against the public broker
    ./run-tests.sh all               Everything, generic MQTT (default)
    ./run-tests.sh m                 Interactive suite pick, MQTT Service connector

Category shortcuts (single letters a/i/o/e/s/r) match the interactive menu;
the connector token (g/m) may appear in any position.

Environment variables:
    DM_SERVICE
        Base Dynamic Mapper API path.
        Default: /service/dynamic-mapper-service

    DM_BROKER_MODE
        Which broker the MQTT helpers drive.
        Values: public | c8y-mqtt-service
        Default: public
        In c8y-mqtt-service mode the helpers target the Cumulocity MQTT Service
        on TLS :9883 with X.509 client-certificate auth (clientId == cert CN,
        tenant id in the username). The 'c' lane sets this automatically.
        Honours DM_C8Y_MQTT_HOST / DM_C8Y_MQTT_PORT / DM_C8Y_MQTT_CONNECTOR_ID.

    MQTT_HOST
        MQTT broker host used by test publish/subscribe helpers.
        Default: broker.hivemq.com (c8y-mqtt-service mode: tenant domain)

    MQTT_PORT
        MQTT broker port used by test publish/subscribe helpers.
        Default: 1883

    MQTT_USER, MQTT_PASS
        Optional MQTT credentials.

    MQTT_TLS
        Enable TLS for MQTT test publish/subscribe (mosquitto_pub/sub).
        Values: true|false
        Default: false

    MQTT_CAFILE
        Optional path to CA certificate used when MQTT_TLS=true.

    MQTT_INSECURE
        Skip TLS certificate verification for MQTT helpers.
        Values: true|false
        Default: false

    DM_DEFAULT_DISCOVERY_WAIT
        Default wait (seconds) for discovery checks in selected tests.
        Default: 10

    DM_DEFAULT_STARTUP_WAIT
        Default wait (seconds) for startup/restart checks.
        Default: 60

    DM_DEFAULT_HEALTH_RETRIES
        Service health retry count in test harness.
        Default: 24

    DM_DEFAULT_HEALTH_INTERVAL
        Service health retry interval (seconds) in test harness.
        Default: 10

    DM_STOP_ON_FAIL
        Stop suite after first failing test.
        Values: 1 to enable
        Default: continue on failures

Notes:
    - Inbound MQTT tests require an enabled Dynamic Mapper MQTT connector that
        matches MQTT_HOST and MQTT_PORT.
    - c8y CLI authentication is required (active session or C8Y_* env vars).
EOF
}

_resolve_script() {   # <basename>  →  full path
    local name="$1"
    # accept with or without .sh
    [[ "$name" != *.sh ]] && name="${name}.sh"
    if [ -f "${SCRIPT_DIR}/${name}" ]; then
        echo "${SCRIPT_DIR}/${name}"
    else
        echo ""
    fi
}

# ── Suite-level service health check ──────────────────────────────────────────
# Runs once before the first test, then sets DM_SKIP_HEALTH_CHECK=1 so that
# every child test script's dm_wait_for_service call becomes a no-op.
_DM_SERVICE="${DM_SERVICE:-/service/dynamic-mapper-service}"
_HEALTH_CHECK_DONE=0

_check_c8y_session() {
    # Fast path: explicit env-var credentials
    [ -n "${C8Y_HOST:-}" ] && return 0
    # Session-file path: parse the host from the active session.
    # 'c8y sessions current' may exit 0 even when no session is loaded, so
    # we check for an actual non-empty host value in the JSON output.
    local _host
    _host=$(c8y sessions current --output json 2>/dev/null \
        | jq -r '.host // empty' 2>/dev/null || true)
    [ -n "${_host:-}" ] && return 0
    printf '%sERROR: No active c8y session.%s\n' "${C_RED}" "${C_RESET}" >&2
    printf '  Activate one:  c8y sessions use <name>\n' >&2
    printf '  Or export:     C8Y_HOST / C8Y_USER / C8Y_PASSWORD\n' >&2
    exit 1
}

_suite_health_check() {
    [ "$_HEALTH_CHECK_DONE" -eq 1 ] && return 0
    _HEALTH_CHECK_DONE=1
    _check_c8y_session

    printf "\n${C_BOLD}── Pre-suite: checking service health ──${C_RESET}\n"
    # Use GET /mapping as the liveness probe: it returns HTTP 200 when the
    # Java process is up (unlike GET /health which aggregates connector state).
    # c8y api exits 0 on success, non-0 on any error — no jq needed.
    local _retries=24 _interval=3 _i
    for _i in $(seq 1 "$_retries"); do
        if c8y api --method GET --url "${_DM_SERVICE}/mapping" \
                --output json >/dev/null 2>&1; then
            printf "%sService is UP — health check will be skipped per test.%s\n" \
                "${C_GREEN}" "${C_RESET}"
            export DM_SKIP_HEALTH_CHECK=1
            return 0
        fi
        printf "  Attempt %d/%d: not ready, retrying in %ds ...\n" \
            "$_i" "$_retries" "$_interval"
        sleep "$_interval"
    done

    printf "${C_RED}ERROR: Dynamic Mapper service did not come UP. Aborting suite.${C_RESET}\n"
    exit 1
}

# ── Per-test execution ─────────────────────────────────────────────────────────
_SUITE_PASS=0
_SUITE_FAIL=0
_SUITE_SKIP=0
declare -a _FAILED_TESTS=()
declare -a _SKIPPED_TESTS=()

_run_one() {   # <entry-from-TESTS>
    local name script exit_code _cleanup_flag
    name=$(_name_of "$1")
    script=$(_resolve_script "$name")

    if [ -z "$script" ]; then
        printf "${C_YELLOW}SKIP${C_RESET}  %s  (script not found)\n" "$name"
        _SUITE_SKIP=$((_SUITE_SKIP + 1))
        return
    fi

    _suite_health_check

    printf "\n${C_BOLD}══ Running: %s ══${C_RESET}\n" "$name"
    _cleanup_flag="--cleanup"
    if [ "$name" = "test-outbound-group-subscription" ]; then
        # Stateful handoff: test-outbound-group-subscription-removal consumes
        # the state emitted by this test. Tests now clean up by default, so we
        # pass --keep here to retain the group/device for the removal test.
        _cleanup_flag="--keep"
    fi
    set +e
    bash "$script" "$_cleanup_flag"
    exit_code=$?
    set -e

    if [ "$exit_code" -eq 0 ]; then
        printf "${C_GREEN}PASS${C_RESET}  %s\n" "$name"
        _SUITE_PASS=$((_SUITE_PASS + 1))
    elif [ "$exit_code" -eq "$DM_SKIP_EXIT_CODE" ]; then
        printf "${C_YELLOW}SKIP${C_RESET}  %s  (prerequisite absent)\n" "$name"
        _SUITE_SKIP=$((_SUITE_SKIP + 1))
        _SKIPPED_TESTS+=("$name")
    else
        printf "${C_RED}FAIL${C_RESET}  %s  (exit %d)\n" "$name" "$exit_code"
        _SUITE_FAIL=$((_SUITE_FAIL + 1))
        _FAILED_TESTS+=("$name")
        if [ "${DM_STOP_ON_FAIL:-0}" = "1" ]; then
            _print_suite_summary
            exit 1
        fi
    fi
}

_run_category() {   # <category>
    for entry in "${TESTS[@]}"; do
        [ "$(_cat_of "$entry")" = "$1" ] && _run_one "$entry"
    done
}

_run_all() {
    for entry in "${TESTS[@]}"; do
        _run_one "$entry"
    done
}

# Map the connector selector ($1: g|m) to DM_BROKER_MODE for the child test
# scripts and announce it. g = generic MQTT (public broker, default);
# m = Cumulocity MQTT Service (CUMULOCITY_MQTT_SERVICE_PULSAR, X.509 cert auth).
_apply_connector_selection() {   # <g|m>
    case "${1:-g}" in
        g|generic|public)
            export DM_BROKER_MODE=public
            printf "${C_DIM}Connector: generic MQTT (public broker)${C_RESET}\n" ;;
        m|mqtt-service|c8y-mqtt-service)
            export DM_BROKER_MODE=c8y-mqtt-service
            printf "${C_CYAN}Connector: Cumulocity MQTT Service — CUMULOCITY_MQTT_SERVICE_PULSAR (TLS :9883, cert auth)${C_RESET}\n" ;;
        *)
            printf "${C_RED}ERROR: unknown connector '%s' — use g (generic MQTT) or m (Cumulocity MQTT Service)${C_RESET}\n" "$1"
            exit 1 ;;
    esac
}

_print_suite_summary() {
    local total=$((_SUITE_PASS + _SUITE_FAIL + _SUITE_SKIP))
    echo ""
    printf "${C_BOLD}%s${C_RESET}\n" "══════════════════════════════════════════════"
    printf "${C_BOLD} Suite results: %d passed" "$_SUITE_PASS"
    [ "$_SUITE_SKIP" -gt 0 ] && printf "${C_YELLOW}, %d skipped${C_RESET}${C_BOLD}" "$_SUITE_SKIP"
    [ "$_SUITE_FAIL" -gt 0 ] && printf "${C_RED}, %d FAILED${C_RESET}${C_BOLD}" "$_SUITE_FAIL"
    printf " (of %d)" "$total"
    [ "$_SUITE_FAIL" -eq 0 ] && printf "${C_GREEN} ✓${C_RESET}"
    printf "${C_BOLD}\n%s${C_RESET}\n" "══════════════════════════════════════════════"
    if [ "${#_SKIPPED_TESTS[@]}" -gt 0 ]; then
        printf "${C_YELLOW}Skipped tests (prerequisite absent):${C_RESET}\n"
        for t in "${_SKIPPED_TESTS[@]}"; do
            printf "  ${C_YELLOW}–${C_RESET}  %s\n" "$t"
        done
        echo ""
    fi
    if [ "${#_FAILED_TESTS[@]}" -gt 0 ]; then
        printf "${C_RED}Failed tests:${C_RESET}\n"
        for t in "${_FAILED_TESTS[@]}"; do
            printf "  ${C_RED}✗${C_RESET}  %s\n" "$t"
        done
        echo ""
    fi
}

# ── Non-interactive dispatch ───────────────────────────────────────────────────
_dispatch_args() {
    local arg="$1"
    case "$arg" in
        -h|--help|help) _print_help ; exit 0 ;;
        a|all)         _run_all ;;
        i|inbound)     _run_category "inbound" ;;
        o|outbound)    _run_category "outbound" ;;
        e|extension)   _run_category "extension" ;;
        s|smartfunc|smartfunction) _run_category "smartfunction" ;;
        r|reliability) _run_category "reliability" ;;
        *)
            # Numeric index(es): already handled by caller.
            # Script name (with or without .sh): run via _run_one so PASS/SKIP/FAIL
            # classification, the group-subscription --keep handoff, and the suite
            # tallies all apply uniformly.
            local script
            script=$(_resolve_script "$arg")
            if [ -n "$script" ]; then
                _run_one "single|${arg%.sh}|"
                return 0
            fi
            printf "${C_RED}Unknown argument: %s${C_RESET}\n" "$arg"
            exit 1
            ;;
    esac
}

# ── Interactive selection ──────────────────────────────────────────────────────
_interactive() {
    _print_menu
    printf "Select tests (e.g. 1 3 5, or a/i/o/e/s/r): "
    read -r REPLY
    echo ""

    # Choose the connector for this run unless one was already preset on the CLI.
    if [ "${_CONN_PRESET:-false}" != "true" ]; then
        printf "Connector  [g] generic MQTT (default)   [m] Cumulocity MQTT Service: "
        read -r _conn_reply
        echo ""
        _apply_connector_selection "${_conn_reply:-g}"
    fi

    case "$REPLY" in
        q|Q) exit 0 ;;
        a|all)         _run_all ;;
        i|inbound)     _run_category "inbound" ;;
        o|outbound)    _run_category "outbound" ;;
        e|extension)   _run_category "extension" ;;
        s|smartfunc|smartfunction) _run_category "smartfunction" ;;
        r|reliability) _run_category "reliability" ;;
        *)
            local -a selections
            # shellcheck disable=SC2206
            selections=($REPLY)
            for sel in "${selections[@]}"; do
                if [[ "$sel" =~ ^[0-9]+$ ]]; then
                    local idx=$(( sel - 1 ))
                    if [ "$idx" -ge 0 ] && [ "$idx" -lt "$_n_tests" ]; then
                        _run_one "${TESTS[$idx]}"
                    else
                        printf "${C_YELLOW}WARN: %s is out of range (1–%d)${C_RESET}\n" \
                            "$sel" "$_n_tests"
                    fi
                else
                    printf "${C_YELLOW}WARN: unrecognised selection '%s'${C_RESET}\n" "$sel"
                fi
            done
            ;;
    esac
}

# ── Entry point ────────────────────────────────────────────────────────────────
# Two parameters:
#   1. the test SUITE  — a category (a/i/o/e/s/r), one or more menu indices, or a
#                        script name. Omit to pick interactively.
#   2. the CONNECTOR   — g (generic MQTT, default) or m (Cumulocity MQTT Service).
# The connector token (g/m) may appear in any position; everything else is the
# suite selection. Example:  ./run-tests.sh inbound m   /   ./run-tests.sh 1 3 m

# Help short-circuits before anything else.
for arg in "$@"; do
    case "$arg" in -h|--help|help) _print_help; exit 0 ;; esac
done

# Split args into the connector selector and the suite selection.
_CONN_PRESET=false
_conn_token="g"
_suite_args=()
for arg in "$@"; do
    case "$arg" in
        g|m|generic|mqtt-service|c8y-mqtt-service|public)
            _conn_token="$arg"; _CONN_PRESET=true ;;
        *)
            _suite_args+=("$arg") ;;
    esac
done

# Apply the connector now (interactive mode skips its own prompt when preset).
[ "$_CONN_PRESET" = "true" ] && _apply_connector_selection "$_conn_token"

if [ "${#_suite_args[@]}" -eq 0 ]; then
    _interactive
else
    # Suite args could be numbers (menu indices) or keywords / script names.
    all_numeric=true
    for arg in "${_suite_args[@]}"; do
        [[ "$arg" =~ ^[0-9]+$ ]] || { all_numeric=false; break; }
    done

    if $all_numeric; then
        for num in "${_suite_args[@]}"; do
            idx=$(( num - 1 ))
            if [ "$idx" -ge 0 ] && [ "$idx" -lt "$_n_tests" ]; then
                _run_one "${TESTS[$idx]}"
            else
                printf "${C_RED}ERROR: index %s out of range (1–%d)${C_RESET}\n" \
                    "$num" "$_n_tests"
                exit 1
            fi
        done
    else
        for arg in "${_suite_args[@]}"; do
            _dispatch_args "$arg"
        done
    fi
fi

_print_suite_summary
[ "$_SUITE_FAIL" -eq 0 ]
