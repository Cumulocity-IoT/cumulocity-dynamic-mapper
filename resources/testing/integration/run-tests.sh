#!/bin/bash
#
# run-tests.sh — Dynamic Mapper integration test runner
#
# Usage:  ./run-tests.sh [TESTS ...] [CONNECTOR] [--connector g|m] [--keep]
#         ./run-tests.sh --list
#   --connector, -c <g|m>  Same as the positional CONNECTOR token.
#   --keep, -k             Keep all created test data (same as DM_KEEP=true).
#   --cleanup-only [-y]    Sweep data kept by --keep (mappings, devices, certs, test connectors) and exit.
#   --list, -l             Print the catalogue (number, category, scope, path); flags missing,
#                          duplicate or unlisted scripts.
#   TESTS      a/all i/inbound o/outbound e/extension s/smartfunc r/reliability
#             x/independent (only tests that ignore g/m), a <script-name>, menu number(s),
#             or omit for the interactive menu.
#   CONNECTOR  g = generic MQTT (public broker, default)
#             m = Cumulocity MQTT Service (CUMULOCITY_MQTT_SERVICE_PULSAR, cert auth)
#   The g/m token may appear in any position. Examples:
#     ./run-tests.sh                 # interactive (prompts for tests + connector)
#     ./run-tests.sh inbound m       # all inbound tests against the MQTT Service
#     ./run-tests.sh 1 3 5 g         # menu items 1/3/5 against the public broker
#     ./run-tests.sh all             # every test, generic MQTT
#     ./run-tests.sh inbound -c m -k # same as "inbound m", retaining test data
#     ./run-tests.sh x               # only the connector-independent tests
#
# Layout:  tests/mqtt/<group>/         scope mqtt: drive the broker, run against g or m
#          tests/independent/<group>/  scope own / none: ignore g/m, each runs once per run
#          lib/ + test-harness.sh      the harness;  tools/  template, cert helper, diagnostic
#          Each catalogue entry (TESTS below) is "category|scope|name|description".
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
#   DM_STOP_ON_FAIL     Stop the run on first test failure (default: continue)

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
#  Format: "CATEGORY|SCOPE|script-basename|short description"
#  One entry per line (the harness derives each test's banner number from the line position).
#  The script lives somewhere under tests/ — its basename must be unique.
#
#  SCOPE says how a test relates to the g / m connector choice:
#    mqtt  drives the MQTT broker; runs against the selected connector (g or m)
#    own   creates and drives its own connector (HTTP, REST_POLLING, WEB_HOOK, MQTT Service lifecycle,
#          its own broker endpoint); g/m is ignored
#    none  needs no broker (REST API / platform checks); g/m is ignored
#  own and none tests are "independent": a run executes each of them once, whatever connector is
#  selected, and does not provision the run-wide MQTT Service certificate for them.
declare -a TESTS=(
    # ── Inbound (broker → Cumulocity) ───────────────────────────────
    "inbound|mqtt|test-inbound-json-default|JSON / DEFAULT → MEASUREMENT"
    "inbound|mqtt|test-inbound-json-jsonata|JSON / JSONATA → EVENT"
    "inbound|mqtt|test-inbound-json-smartfunction|JSON / Smart Function → MEASUREMENT"
    "inbound|mqtt|test-inbound-flatfile|FLAT_FILE / CSV → MEASUREMENT"
    "inbound|mqtt|test-inbound-hex|HEX → EVENT"
    "inbound|own|test-inbound-http-connector|HTTP connector → MEASUREMENT"
    "inbound|mqtt|test-inbound-implicit-device|Implicit device auto-creation"
    "inbound|mqtt|test-inbound-implicit-device-context-data|Implicit device: contextData applied to created device (#700)"
    "inbound|mqtt|test-inbound-implicit-device-recreate-after-delete|Implicit device recreation after delete (inconsistent-cache regression)"
    "inbound|mqtt|test-inbound-multilevel-wildcard|Multi-level wildcard # matches parent and deeper topics"
    "inbound|mqtt|test-inbound-mapping-topic-change|Mapping topic change moves to the new topic"
    "inbound|mqtt|test-inbound-multi-device|Array payload → multiple devices"
    "inbound|mqtt|test-inbound-alarm|JSON / DEFAULT → ALARM"
    "inbound|mqtt|test-inbound-operation|JSON / DEFAULT → OPERATION"
    "inbound|mqtt|test-inbound-inventory|JSON / DEFAULT → INVENTORY (device metadata update)"
    "inbound|mqtt|test-inbound-alarm-dedup-upsert|Repeated ALARM (same source+type) → one alarm; upsert semantics via DM_EXPECT_ALARM_UPSERT=true"
    "inbound|mqtt|test-inbound-deployment-reconcile|Deployment change applies live (un-deploy / re-deploy, no reconnect)"
    "inbound|mqtt|test-inbound-deployment-reset|RESET_DEPLOYMENT_MAP applies live (destructive: needs DM_ALLOW_DEPLOYMENT_RESET=true)"
    "inbound|mqtt|test-inbound-mapping-versioning|Mapping versioning (draft, publish, activate, rollback)"
    "inbound|own|test-inbound-rest-polling|REST_POLLING connector → MEASUREMENT (needs http-polling-mock)"
    "inbound|mqtt|test-inbound-filter|filterMapping / filterInventory — inbound"
    "inbound|mqtt|test-inbound-dedup|Repeated message dropped (\$isDuplicate filter and context.isDuplicate); window, edit, counter"
    "inbound|mqtt|test-inbound-malformed-payload|Malformed / wrong-shape payload dropped; mapping keeps processing"
    "inbound|mqtt|test-inbound-source-c8y-id|useExternalId=false: device addressed by internal id (_IDENTITY_.c8ySourceId)"
    "inbound|mqtt|test-inbound-event-attachment|_CONTEXT_DATA_ attachment + deviceName/deviceType on inbound EVENT"

    # ── Inbound (Smart Function patterns) ───────────────────────────
    "smartfunction|mqtt|test-inbound-smartfunction-02|Inbound: Pattern 02: Topic-based external ID + sensor filter"
    "smartfunction|mqtt|test-inbound-smartfunction-03|Inbound: Pattern 03: getManagedObjectByExternalId — MO enrichment"
    "smartfunction|mqtt|test-inbound-smartfunction-04|Inbound: Pattern 04: Dual payload type + deduplication"
    "smartfunction|mqtt|test-inbound-smartfunction-05|Inbound: Pattern 11: Per-device running statistics (device ID from context)"
    "smartfunction|mqtt|test-inbound-smartfunction-custom-route|Inbound: cumulocityType custom → /service/ route (API.CUSTOM)"
    "smartfunction|mqtt|test-inbound-smartfunction-uns-group|Inbound: UNS N:1 — many single-value messages into one measurement (context.collect)"
    "smartfunction|mqtt|test-inbound-smartfunction-uns-flush|Inbound: UNS N:1 — a newer timestamp flushes the previous group (context.take)"

    # ── Extensions (Java processor extensions, inbound + outbound) ───
    "extension|mqtt|test-inbound-extension-custom-measurement|Extension: JSON → Measurement"
    "extension|mqtt|test-inbound-extension-custom-alarm|Extension: JSON → Alarm"
    "extension|mqtt|test-inbound-extension-custom-event|Extension: Protobuf → Event"
    "extension|mqtt|test-inbound-extension-sparkplugb-measurement|Extension: Sparkplug B → Measurement"
    "extension|mqtt|test-outbound-extension-alarm-to-sparkplugb|Extension: Alarm → Sparkplug B DCMD"
    "extension|mqtt|test-outbound-extension-anypayload|Any Payload outbound Java extension: Operation → raw text command on the broker (exact bytes)"

    # ── Outbound (Cumulocity → broker) ──────────────────────────────
    "outbound|mqtt|test-outbound-measurement|C8Y Measurement → MQTT broker"
    "outbound|mqtt|test-outbound-event|C8Y Event → MQTT broker"
    "outbound|mqtt|test-outbound-alarm|C8Y Alarm → MQTT broker"
    "outbound|mqtt|test-outbound-operation|C8Y Operation → MQTT broker"
    "outbound|mqtt|test-outbound-inventory|C8Y managed-object change → MQTT broker (metadata)"
    "outbound|mqtt|test-outbound-filter|filterMapping — selective forwarding"
    "outbound|mqtt|test-outbound-filter-inventory|filterInventory / filterMapping — outbound inventory"
    "outbound|mqtt|test-outbound-inventory-cache-eviction|Inventory cache — eviction on device delete"
    "outbound|mqtt|test-outbound-topic-resolution|Dynamic publish topic resolution"
    "outbound|mqtt|test-outbound-json-smartfunction|Smart Function: Measurement → MQTT JSON"
    "outbound|mqtt|test-outbound-anypayload-smartfunction|Any Payload outbound Smart Function: Operation → raw text command and Base64 binary on the broker (exact bytes)"
    "outbound|mqtt|test-outbound-smartfunction-externalsource|Smart Function externalSource → _externalId_ topic (broker round-trip)"
    "outbound|mqtt|test-outbound-smartfunction-molookup|Smart Function outbound: Pattern 03 — getManagedObjectByExternalId — MO enrichment"
    "outbound|own|test-outbound-webhook|C8Y Measurement → WEB_HOOK connector (needs http-polling-mock)"
    "outbound|mqtt|test-outbound-static-subscription|Static subscription management"
    "outbound|mqtt|test-outbound-subscription-api-all|Static subscription api=ALL delivers measurement, event and alarm mappings"
    "outbound|own|test-outbound-broker-outage|Outbound messages survive a broker outage (connector endpoint made unreachable, then restored)"
    "outbound|mqtt|test-outbound-with-children|EVENT_WITH_CHILDREN / ALARM_WITH_CHILDREN subscriptions (parent + child)"
    "outbound|mqtt|test-outbound-type-subscription|Dynamic type subscription"
    "outbound|mqtt|test-outbound-type-subscription-resync|Type subscription resync (backfill of pre-existing devices)"
    "outbound|mqtt|test-outbound-group-subscription|Dynamic group subscription"
    "outbound|mqtt|test-outbound-group-subscription-removal|Group subscription removal"
    "outbound|mqtt|test-outbound-group-subscription-desired-state|Group subscription desired-state update (removal keeps still-covered devices)"
    "outbound|mqtt|test-outbound-type-subscription-removal|Type subscription removal unsubscribes devices"
    "outbound|mqtt|test-outbound-subscription-persistence|Subscription persistence after restart"

    # ── Reliability and API ─────────────────────────────────────────
    "reliability|none|test-api-mapping-dry-run|POST /test/mapping dry-run + REST validation errors (no broker needed)"
    "reliability|none|test-api-code-templates|/configuration/code CRUD (no broker needed)"
    "reliability|none|test-multi-tenant|Mapping CRUD / tenant isolation"
    "reliability|none|test-multi-connector|Multiple connector status check"
    "reliability|none|test-reconnect|Connector disconnect / reconnect cycle"
    "reliability|own|test-cumulocity-mqtt-service|Cumulocity MQTT Service connector lifecycle"
)

# ── Helpers ───────────────────────────────────────────────────────────────────
_n_tests=${#TESTS[@]}

# Entry fields: category|scope|name|description (the description may not contain '|').
_cat_of()   { echo "${1%%|*}"; }
_scope_of() { local rest="${1#*|}"; echo "${rest%%|*}"; }
_name_of()  { local rest="${1#*|}"; rest="${rest#*|}"; echo "${rest%%|*}"; }
_desc_of()  { echo "${1##*|}"; }

# Scope of a test given by name (mqtt when it is not in the catalogue).
_scope_for_name() {   # <name>
    local entry
    for entry in "${TESTS[@]}"; do
        if [ "$(_name_of "$entry")" = "$1" ]; then _scope_of "$entry"; return; fi
    done
    echo mqtt
}

# Independent = does not depend on the g/m choice (scope own or none).
_is_independent() { [ "$(_scope_of "$1")" != "mqtt" ]; }

# Returns 0 if the token is a bare number or an N-M range.
_is_index_like() { [[ "$1" =~ ^[0-9]+$ ]] || [[ "$1" =~ ^[0-9]+-[0-9]+$ ]]; }

# Prints the expanded list of menu indices for a number or N-M range token.
_expand_index_token() {
    local token="$1"
    if [[ "$token" =~ ^[0-9]+$ ]]; then
        echo "$token"
    elif [[ "$token" =~ ^([0-9]+)-([0-9]+)$ ]]; then
        local lo="${BASH_REMATCH[1]}" hi="${BASH_REMATCH[2]}"
        if [ "$lo" -gt "$hi" ]; then
            printf '%sERROR: invalid range %s (start > end)%s\n' \
                "${C_RED}" "$token" "${C_RESET}" >&2
            return 1
        fi
        seq "$lo" "$hi"
    fi
}

# Run all tests identified by one index-like token (number or N-M range).
_run_index_token() {
    local num idx expanded
    if ! expanded=$(_expand_index_token "$1"); then
        exit 1
    fi
    while IFS= read -r num; do
        idx=$(( num - 1 ))
        if [ "$idx" -ge 0 ] && [ "$idx" -lt "$_n_tests" ]; then
            _run_one "${TESTS[$idx]}"
        else
            printf "${C_RED}ERROR: index %s out of range (1–%d)${C_RESET}\n" \
                "$num" "$_n_tests"
            exit 1
        fi
    done <<< "$expanded"
}

_print_header() {
    printf "\n${C_BOLD}%s${C_RESET}\n" "Dynamic Mapper — Integration Test Runner"
    printf "${C_DIM}Scripts: %s${C_RESET}\n\n" "$SCRIPT_DIR"
}

_print_menu() {
    _print_header
    local idx=1 cat prev_cat=""
    printf "  ${C_BOLD}%-4s %-16s %-6s %s${C_RESET}\n" "#" "Category" "Scope" "Test"
    printf "  %s\n" "$(printf '─%.0s' {1..60})"
    for entry in "${TESTS[@]}"; do
        cat=$(_cat_of "$entry")
        if [ "$cat" != "$prev_cat" ]; then
            printf "\n  ${C_CYAN}${C_BOLD}%s${C_RESET}\n" "── $(echo "$cat" | tr '[:lower:]' '[:upper:]') ──"
            prev_cat="$cat"
        fi
        printf "  ${C_BOLD}%2d${C_RESET}  %-16s %-6s %s\n" \
            "$idx" "[$cat]" "$(_scope_of "$entry")" "$(_desc_of "$entry")"
        idx=$((idx + 1))
    done
    echo ""
    printf "  ${C_DIM}Scope: mqtt = runs against the g/m connector;  own/none = independent of g/m (run once)${C_RESET}\n"
    echo ""
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "a"  "[all]"           "Run all tests (independent ones once)"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "i"  "[inbound]"       "Run all inbound tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "o"  "[outbound]"      "Run all outbound tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "e"  "[extension]"     "Run extension tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "s"  "[smartfunc]"     "Run Smart Function pattern tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "r"  "[reliability]"   "Run reliability tests"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "x"  "[independent]"   "Run only the tests independent of g/m (scope own / none)"
    printf "  ${C_BOLD}%2s${C_RESET}  %-16s %s\n" "q"  "[quit]"          "Quit"
    echo ""
    printf "  ${C_DIM}After selecting tests you'll choose a connector (needed by scope mqtt only): g=generic MQTT, m=Cumulocity MQTT Service${C_RESET}\n"
    echo ""
}

_print_help() {
        cat <<'EOF'
Dynamic Mapper integration test runner

Usage:
    ./run-tests.sh [TESTS ...] [CONNECTOR] [--connector g|m] [--keep]
    ./run-tests.sh --list

  OPTIONS:
    -l, --list                       Print the catalogue (number, category, scope, script
                                     path) and verify every entry resolves to a script
    -c, --connector <g|m>            Choose the connector (same as the g/m token)
    -k, --keep                       Keep all created test data (= DM_KEEP=true)
    --cleanup-only [-y]              Delete kept test data (mappings test-*, devices
                                     test-*/dmtest-*, certs dmtest*, test connectors
                                     test-*/testc8ymqttservice*/testrestpolling*/
                                     testwebhook*/testoutage*) and exit; -y skips the confirmation.
                                     Device groups: remove by hand.

  TESTS — which tests to run (a category, menu numbers, or a script name):
    a | all                          Run every test
    i | inbound                      Run all inbound tests
    o | outbound                     Run all outbound tests
    e | extension                    Run all extension tests
    s | smartfunc                    Run all Smart Function pattern tests
    r | reliability                  Run reliability tests
    x | independent                  Run only the tests independent of g/m (scope own / none)
    <script-name>                    Run one script (with or without .sh)
    <n> [n2 ...]                     Run one or more menu indices
    <n>-<m>                          Run a range of menu indices (e.g. 3-7)
    (omit)                           Pick interactively

  CONNECTOR — which broker the scope-mqtt tests drive (default g):
    g                                Generic MQTT (public broker)
    m                                Cumulocity MQTT Service
                                     (CUMULOCITY_MQTT_SERVICE_PULSAR, TLS :9883,
                                     X.509 cert auth → DM_BROKER_MODE=c8y-mqtt-service)

Examples:
    ./run-tests.sh inbound m         All inbound tests against the MQTT Service
    ./run-tests.sh 1 3 5 g           Menu items 1/3/5 against the public broker
    ./run-tests.sh all               Everything, generic MQTT (default)
    ./run-tests.sh m                 Interactive test pick, MQTT Service connector

Category shortcuts (single letters a/i/o/e/s/r/x) match the interactive menu;
the connector token (g/m) may appear in any position.

Test scopes (column "Scope" in the menu and --list):
    mqtt   drives the MQTT broker, so it runs against the selected connector (g or m)
    own    creates and drives its own connector (HTTP, REST_POLLING, WEB_HOOK, the
           Cumulocity MQTT Service lifecycle, its own broker endpoint)
    none   needs no broker (REST API and platform checks)
  own and none tests are independent of g/m. A run executes each of them once, with
  whichever connector is selected (the selection is ignored), and the run's shared
  MQTT Service certificate is not provisioned for them. "all m" therefore runs every
  mqtt test against the MQTT Service and every independent test once. To run only the
  independent tests use "x" (no connector needed).

Environment variables:
    DM_KEEP
        true → pass --keep to every test and keep the run-wide MQTT certificate,
        retaining all created mappings/devices/subscriptions for inspection.
        Default: false (tests clean up after themselves)

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
        Stop the run after first failing test.
        Values: 1 to enable
        Default: continue on failures

    DM_REUSE_MQTT_CERT
        Reuse a single X.509 client certificate across all tests in a run
        when DM_BROKER_MODE=c8y-mqtt-service. The cert is provisioned once
        before the first test and deleted after the last. This avoids the
        5 s per-test registration wait and reduces API churn on the tenant.
        Values: true (default) | false
        Set false to provision a fresh cert for each individual test.

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
    # Tests live in tests/<mqtt|independent>/<group>/ — the basename is unique across tests/.
    { find "${SCRIPT_DIR}/tests" -type f -name "$name" 2>/dev/null || true; } | head -n 1
}

# ── Run-level service health check ──────────────────────────────────────────
# Runs once before the first test, then sets DM_SKIP_HEALTH_CHECK=1 so that
# every child test script's dm_wait_for_service call becomes a no-op.
_DM_SERVICE="${DM_SERVICE:-/service/dynamic-mapper-service}"
_HEALTH_CHECK_DONE=0

_check_c8y_session() {
    # Fast path: explicit env-var credentials (password or token-based auth)
    if [ -n "${C8Y_HOST:-}" ] && [ -n "${C8Y_USER:-}" ] \
            && { [ -n "${C8Y_PASSWORD:-}" ] || [ -n "${C8Y_TOKEN:-}" ]; }; then
        return 0
    fi
    # Session-file path: parse the host from the active session.
    # 'c8y sessions current' may exit 0 even when no session is loaded, so
    # we check for an actual non-empty host value in the JSON output.
    local _host
    _host=$(c8y sessions current --output json </dev/null 2>/dev/null \
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

    printf "\n${C_BOLD}── Pre-run: checking service health ──${C_RESET}\n"
    # Use GET /mapping as the liveness probe: it returns HTTP 200 when the
    # Java process is up (unlike GET /health which aggregates connector state).
    # c8y api exits 0 on success, non-0 on any error — no jq needed.
    local _retries=24 _interval=3 _i
    for _i in $(seq 1 "$_retries"); do
        if c8y api --method GET --url "${_DM_SERVICE}/mapping" \
                --output json </dev/null >/dev/null 2>&1; then
            printf "%sService is UP — health check will be skipped per test.%s\n" \
                "${C_GREEN}" "${C_RESET}"
            export DM_SKIP_HEALTH_CHECK=1
            return 0
        fi
        printf "  Attempt %d/%d: not ready, retrying in %ds ...\n" \
            "$_i" "$_retries" "$_interval"
        sleep "$_interval"
    done

    printf "${C_RED}ERROR: Dynamic Mapper service did not come UP. Aborting run.${C_RESET}\n"
    exit 1
}

# ── Run-level MQTT Service cert (shared across tests) ───────────────────────
# When DM_BROKER_MODE=c8y-mqtt-service and DM_REUSE_MQTT_CERT != false, one
# X.509 client cert is provisioned before the first test and reused by all
# subsequent tests. Test scripts detect the exported DM_MQTT_SVC_* variables
# and skip their own per-test provisioning and 5 s registration wait.
_SUITE_CERT_PROVISIONED=0

_suite_provision_mqtt_service_cert() {
    command -v openssl >/dev/null 2>&1 \
        || { printf '%sERROR: openssl is required to provision an MQTT Service cert%s\n' "${C_RED}" "${C_RESET}" >&2; exit 1; }

    printf "\n%s── Pre-run: provisioning shared MQTT Service cert ──%s\n" "${C_BOLD}" "${C_RESET}"

    local _client_id="dmtest$$"
    local _cert_dir; _cert_dir=$(mktemp -d)
    local _key="${_cert_dir}/${_client_id}.key"
    local _cert="${_cert_dir}/${_client_id}.pem"

    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$_key" -out "$_cert" \
        -days 2 -subj "/CN=${_client_id}" >/dev/null 2>&1 \
        || { printf '%sERROR: Failed to generate run MQTT Service cert%s\n' "${C_RED}" "${C_RESET}" >&2; exit 1; }

    if ! c8y devicemanagement certificates create \
            --name "$_client_id" --file "$_cert" \
            --autoRegistrationEnabled \
            --force --output json </dev/null >/dev/null 2>&1; then
        printf '%sERROR: Failed to upload run trusted certificate — check user has Mqtt service permission%s\n' "${C_RED}" "${C_RESET}" >&2
        exit 1
    fi

    export DM_MQTT_SVC_CLIENT_ID="$_client_id"
    export DM_MQTT_SVC_CERT="$_cert"
    export DM_MQTT_SVC_KEY="$_key"
    export DM_MQTT_SVC_CERT_NAME="$_client_id"
    export DM_MQTT_SVC_CERT_DIR="$_cert_dir"
    _SUITE_CERT_PROVISIONED=1

    printf "%sRun MQTT cert provisioned: CN=%s — waiting 5s for registration ...%s\n" \
        "${C_CYAN}" "$_client_id" "${C_RESET}"
    sleep 5
}

_suite_cleanup_mqtt_service_cert() {
    [ "${_SUITE_CERT_PROVISIONED:-0}" -eq 0 ] && return 0
    if [ "${DM_KEEP:-false}" = "true" ]; then
        printf "DM_KEEP=true: keeping run MQTT trusted certificate: %s\n" "${DM_MQTT_SVC_CERT_NAME:-}"
        return 0
    fi
    if [ -n "${DM_MQTT_SVC_CERT_NAME:-}" ]; then
        c8y devicemanagement certificates delete --id "$DM_MQTT_SVC_CERT_NAME" \
            --force </dev/null >/dev/null 2>&1 || true
        printf "Deleted run MQTT trusted certificate: %s\n" "$DM_MQTT_SVC_CERT_NAME"
    fi
    [ -n "${DM_MQTT_SVC_CERT_DIR:-}" ] && rm -rf "$DM_MQTT_SVC_CERT_DIR" 2>/dev/null || true
}

_ensure_suite_mqtt_cert() {
    [ "${_SUITE_CERT_PROVISIONED:-0}" -eq 1 ] && return 0
    [ "${DM_BROKER_MODE:-public}" = "c8y-mqtt-service" ] || return 0
    [ "${DM_REUSE_MQTT_CERT:-true}" = "false" ] && return 0
    _suite_provision_mqtt_service_cert
}

_dm_suite_on_exit() {
    _suite_cleanup_mqtt_service_cert || true
}
trap _dm_suite_on_exit EXIT

# ── Per-test execution ─────────────────────────────────────────────────────────
_SUITE_PASS=0
_SUITE_FAIL=0
_SUITE_SKIP=0
declare -a _FAILED_TESTS=()
declare -a _SKIPPED_TESTS=()

_run_one() {   # <entry-from-TESTS>
    local name script exit_code _cleanup_flag scope label
    name=$(_name_of "$1")
    scope=$(_scope_of "$1")
    # An entry built for a script given by name ("single||<name>|") has no scope yet.
    [ -z "$scope" ] || [ "$scope" = "single" ] && scope=$(_scope_for_name "$name")
    script=$(_resolve_script "$name")

    if [ -z "$script" ]; then
        printf "${C_YELLOW}SKIP${C_RESET}  %s  (script not found)\n" "$name"
        _SUITE_SKIP=$((_SUITE_SKIP + 1))
        return
    fi

    _suite_health_check
    # Independent tests (scope own / none) ignore g/m: no run-wide MQTT Service certificate for them.
    if [ "$scope" = "mqtt" ]; then
        _ensure_suite_mqtt_cert
        label="${DM_BROKER_MODE:-public}"
    else
        label="independent: $scope"
    fi

    printf "\n${C_BOLD}══ Running: %s ${C_DIM}[%s]${C_RESET}${C_BOLD} ══${C_RESET}\n" "$name" "$label"
    _cleanup_flag="--cleanup"
    [ "${DM_KEEP:-false}" = "true" ] && _cleanup_flag="--keep"
    if [ "$name" = "test-outbound-group-subscription" ]; then
        # Stateful handoff: test-outbound-group-subscription-removal consumes
        # the state emitted by this test. Tests now clean up by default, so we
        # pass --keep here to retain the group/device for the removal test.
        _cleanup_flag="--keep"
    fi
    set +e
    # </dev/null: when _run_one is called from _run_index_token's `while read`
    # loop, stdin is a process-substitution pipe. go-c8y-cli detects non-terminal
    # stdin and enters "pipeline mode", causing c8y commands with no explicit
    # --data to silently produce no output. /dev/null is not a FIFO so go-c8y-cli
    # stays out of pipeline mode — all test-script c8y calls behave correctly.
    if [ "$scope" = "mqtt" ]; then
        bash "$script" "$_cleanup_flag" </dev/null
    else
        # Independent test: neutral broker mode, so a stray g/m selection cannot leak in.
        DM_BROKER_MODE=public bash "$script" "$_cleanup_flag" </dev/null
    fi
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

# Independent tests only (scope own / none): the g/m choice does not apply to them.
_run_independent() {
    for entry in "${TESTS[@]}"; do
        _is_independent "$entry" && _run_one "$entry"
    done
    return 0
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
    printf "${C_BOLD} Run results: %d passed" "$_SUITE_PASS"
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
        x|independent) _run_independent ;;
        *)
            # Numeric index(es): already handled by caller.
            # Script name (with or without .sh): run via _run_one so PASS/SKIP/FAIL
            # classification, the group-subscription --keep handoff, and the run
            # tallies all apply uniformly.
            local script
            script=$(_resolve_script "$arg")
            if [ -n "$script" ]; then
                _run_one "single|single|${arg%.sh}|"
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
    printf "Select tests (e.g. 1 3 5, 3-7, or a/i/o/e/s/r/x): "
    read -r REPLY
    echo ""

    # Choose the connector for this run unless one was already preset on the CLI.
    # (The independent tests ignore the connector, so "x" skips the prompt.)
    case "$REPLY" in x|independent|q|Q) _CONN_PRESET=true ;; esac
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
        x|independent) _run_independent ;;
        *)
            local -a selections
            # shellcheck disable=SC2206
            selections=($REPLY)
            for sel in "${selections[@]}"; do
                if _is_index_like "$sel"; then
                    local num idx expanded
                    if ! expanded=$(_expand_index_token "$sel"); then
                        continue
                    fi
                    while IFS= read -r num; do
                        idx=$(( num - 1 ))
                        if [ "$idx" -ge 0 ] && [ "$idx" -lt "$_n_tests" ]; then
                            _run_one "${TESTS[$idx]}"
                        else
                            printf "${C_YELLOW}WARN: %s is out of range (1–%d)${C_RESET}\n" \
                                "$num" "$_n_tests"
                        fi
                    done <<< "$expanded"
                else
                    printf "${C_YELLOW}WARN: unrecognised selection '%s'${C_RESET}\n" "$sel"
                fi
            done
            ;;
    esac
}

# ── Sweep of kept test data (--cleanup-only) ───────────────────────────────────
# Removes what tests leave behind under --keep / DM_KEEP=true, matched by name:
#   mappings  test-*            devices  test-* | dmtest-*
#   trusted certificates dmtest*
#   connectors (by identifier) test-*, testc8ymqttservice*, testrestpolling*, testwebhook*/testoutage*
#     — exactly the identifiers the tests create; a connector you configured yourself
#       is never matched unless its identifier starts with one of these.
# Device groups are NOT swept (names too generic) — remove by hand.
_cleanup_only() {   # [assume_yes]
    local _yes="${1:-false}" _svc="${DM_SERVICE:-/service/dynamic-mapper-service}"
    local _maps _devs _certs _conns _reply id name

    _check_c8y_session || exit 1

    _maps=$(c8y api --method GET --url "${_svc}/mapping" --output json </dev/null 2>/dev/null \
        | jq -r '(if type == "array" then .[] else . end) | objects | select((.name // "")|startswith("test-")) | "\(.id)\t\(.name)"')
    _devs=$({ c8y devices list --name "test-*" --includeAll --output json </dev/null 2>/dev/null
              c8y devices list --name "dmtest-*" --includeAll --output json </dev/null 2>/dev/null; } \
        | jq -r 'select(.id) | "\(.id)\t\(.name)"' | sort -u)
    _certs=$(c8y devicemanagement certificates list --includeAll --output json </dev/null 2>/dev/null \
        | jq -r 'select((.name // "")|startswith("dmtest")) | .name')

    _conns=$(c8y api --method GET --url "${_svc}/configuration/connector/instance" --output json </dev/null 2>/dev/null \
        | jq -rs '[ .[] | if type == "array" then .[] else . end ] | map(select(type == "object")) | .[]
                  | (.identifier // "") as $i
                  | select($i | test("^(test-|testc8ymqttservice|testrestpolling|testwebhook|testoutage)"))
                  | "\($i)\t\(.name // "")"' 2>/dev/null)

    printf "${C_BOLD}Mappings (test-*):${C_RESET}\n%s\n" "${_maps:-  (none)}"
    printf "${C_BOLD}Devices (test-*, dmtest-*):${C_RESET}\n%s\n" "${_devs:-  (none)}"
    printf "${C_BOLD}Trusted certificates (dmtest*):${C_RESET}\n%s\n" "${_certs:-  (none)}"
    printf "${C_BOLD}Connectors (test-*, testc8ymqttservice*, testrestpolling*, testwebhook*/testoutage*):${C_RESET}\n%s\n" "${_conns:-  (none)}"
    if [ -z "$_maps$_devs$_certs$_conns" ]; then echo "Nothing to clean up."; return 0; fi

    if [ "$_yes" != "true" ]; then
        printf "\nDelete all of the above? [y/N] "
        read -r _reply
        case "$_reply" in y|Y|yes) ;; *) echo "Aborted."; return 1 ;; esac
    fi

    while IFS=$'\t' read -r id name; do
        [ -z "$id" ] && continue
        c8y api --method POST --url "${_svc}/operation" \
            --data "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"$id\",\"active\":\"false\"}}" \
            --force </dev/null >/dev/null 2>&1 || true
        c8y api --method DELETE --url "${_svc}/mapping/$id" --force </dev/null >/dev/null 2>&1 || true
        printf "Deleted mapping: %s (%s)\n" "$id" "$name"
    done <<< "$_maps"
    # Connectors after the mappings: mappings deployed to them are already gone.
    while IFS=$'\t' read -r id name; do
        [ -z "$id" ] && continue
        c8y api --method DELETE --url "${_svc}/configuration/connector/instance/$id" --force </dev/null >/dev/null 2>&1 || true
        printf "Deleted connector: %s (%s)\n" "$id" "$name"
    done <<< "$_conns"
    while IFS=$'\t' read -r id name; do
        [ -z "$id" ] && continue
        c8y devices delete --id "$id" --force </dev/null >/dev/null 2>&1 || true
        printf "Deleted device: %s (%s)\n" "$id" "$name"
    done <<< "$_devs"
    while IFS= read -r name; do
        [ -z "$name" ] && continue
        c8y devicemanagement certificates delete --id "$name" --force </dev/null >/dev/null 2>&1 || true
        printf "Deleted certificate: %s\n" "$name"
    done <<< "$_certs"
}

# ── Entry point ────────────────────────────────────────────────────────────────
# Two parameters:
#   1. the TESTS       — a category (a/i/o/e/s/r), one or more menu indices, or a
#                        script name. Omit to pick interactively.
#   2. the CONNECTOR   — g (generic MQTT, default) or m (Cumulocity MQTT Service).
# The connector token (g/m) may appear in any position; everything else is the
# tests selection. Example:  ./run-tests.sh inbound m   /   ./run-tests.sh 1 3 m

# Help short-circuits before anything else.
for arg in "$@"; do
    case "$arg" in -h|--help|help) _print_help; exit 0 ;; esac
done

# --list: print the catalogue and check that every entry resolves to exactly one script.
_list_catalogue() {
    local idx=1 entry name script rc=0 count
    printf "%-4s %-13s %-6s %s\n" "#" "Category" "Scope" "Script"
    for entry in "${TESTS[@]}"; do
        name=$(_name_of "$entry")
        script=$(_resolve_script "$name")
        count=$(find "${SCRIPT_DIR}/tests" -type f -name "${name}.sh" 2>/dev/null | wc -l | tr -d ' ')
        if [ -z "$script" ]; then
            printf "%-4s %-13s %-6s ${C_RED}MISSING${C_RESET} %s\n" "$idx" "$(_cat_of "$entry")" "$(_scope_of "$entry")" "$name"; rc=1
        elif [ "$count" -gt 1 ]; then
            printf "%-4s %-13s %-6s ${C_RED}DUPLICATE (%s copies)${C_RESET} %s\n" "$idx" "$(_cat_of "$entry")" "$(_scope_of "$entry")" "$count" "$name"; rc=1
        else
            printf "%-4s %-13s %-6s %s\n" "$idx" "$(_cat_of "$entry")" "$(_scope_of "$entry")" "${script#"${SCRIPT_DIR}"/}"
        fi
        idx=$((idx + 1))
    done
    # Scripts under tests/ that the catalogue does not list.
    while IFS= read -r script; do
        name=$(basename "$script" .sh)
        local found=false
        for entry in "${TESTS[@]}"; do [ "$(_name_of "$entry")" = "$name" ] && { found=true; break; }; done
        if ! $found; then printf "${C_YELLOW}NOT IN CATALOGUE${C_RESET} %s\n" "${script#"${SCRIPT_DIR}"/}"; rc=1; fi
    done < <(find "${SCRIPT_DIR}/tests" -type f -name 'test-*.sh' | sort)
    return $rc
}
for arg in "$@"; do
    case "$arg" in -l|--list) _list_catalogue; exit $? ;; esac
done

# Split args into the connector selector and the tests selection.
_CONN_PRESET=false
_conn_token="g"
_suite_args=()
_CLEANUP_ONLY=false
_ASSUME_YES=false
_args=("$@")
_i=0
while [ "$_i" -lt "${#_args[@]}" ]; do
    arg="${_args[$_i]}"; _i=$((_i + 1))
    case "$arg" in
        -k|--keep) DM_KEEP=true; continue ;;
        --cleanup-only) _CLEANUP_ONLY=true; continue ;;
        -y|--yes) _ASSUME_YES=true; continue ;;
        -c|--connector)
            if [ "$_i" -ge "${#_args[@]}" ]; then
                printf "${C_RED}%s requires a value (g|m)${C_RESET}\n" "$arg"; exit 1
            fi
            _conn_token="${_args[$_i]}"; _i=$((_i + 1)); _CONN_PRESET=true; continue ;;
        --connector=*) _conn_token="${arg#--connector=}"; _CONN_PRESET=true; continue ;;
    esac
    case "$arg" in
        g|m|generic|mqtt-service|c8y-mqtt-service|public)
            _conn_token="$arg"; _CONN_PRESET=true ;;
        *)
            _suite_args+=("$arg") ;;
    esac
done

if [ "$_CLEANUP_ONLY" = "true" ]; then
    _cleanup_only "$_ASSUME_YES"
    exit $?
fi

# Apply the connector now (interactive mode skips its own prompt when preset).
[ "$_CONN_PRESET" = "true" ] && _apply_connector_selection "$_conn_token"

if [ "${#_suite_args[@]}" -eq 0 ]; then
    _interactive
else
    # Test args could be numbers/ranges (menu indices) or keywords / script names.
    all_numeric=true
    for arg in "${_suite_args[@]}"; do
        _is_index_like "$arg" || { all_numeric=false; break; }
    done

    if $all_numeric; then
        for tok in "${_suite_args[@]}"; do
            _run_index_token "$tok"
        done
    else
        for arg in "${_suite_args[@]}"; do
            _dispatch_args "$arg"
        done
    fi
fi

_print_suite_summary
[ "$_SUITE_FAIL" -eq 0 ]
