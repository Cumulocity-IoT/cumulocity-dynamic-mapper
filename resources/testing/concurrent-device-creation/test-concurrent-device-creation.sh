#!/bin/bash
#
# test-concurrent-device-creation: a burst of messages for an unknown device creates exactly ONE device
#
# Scenario
#   An inbound mapping has "create non-existing device" switched on. Many messages for the SAME,
#   not yet existing external ID arrive at the same moment, from several MQTT clients. Every one of
#   them finds no device, so the Dynamic Mapper must serialize the creation (striped lock plus a second
#   lookup inside the lock). A broken lock shows up as more than one device for that external ID.
#
# Mapping
#   mapping-IN-08-alarm.json (a Smart Function that creates an ALARM with externalSource taken from
#   topic level 1). The test copies it, switches createNonExistingDevice on, and moves the topic to
#   '<prefix>/+' so it does not collide with a mapping you already run on 'alarm/+'. The Smart Function
#   reads the device from topic level 1, so the prefix is level 0 and the external ID is level 1.
#
# Transport (DM_BURST_TRANSPORT)
#   mqtt (default)  Publishes to the public HiveMQ broker (broker.hivemq.com:1883), the default of the
#                   harness; override with MQTT_HOST / MQTT_PORT. The Cumulocity MQTT Service mode is not
#                   supported: it allows one MQTT client per certificate, and this test needs several.
#                   The MQTT connector hands the messages over in order, so the first message of a burst
#                   can finish creating the device before the rest are delivered. Use this transport for
#                   the end-to-end path, and see the race check below.
#   http            POSTs to the HTTP connector (<service>/httpConnector/<topic>). Every request is handled
#                   on its own thread, so the requests really do race for the missing device. Use this
#                   transport to test the creation lock.
#
# Did the race happen?
#   A pass proves nothing if only one message ever saw the device missing. The test reads the mapping's
#   service log (/monitoring/service-logs) and counts the messages that looked the device up and did not
#   find it. It asserts that exactly one of them created the device, and warns when fewer than two messages
#   hit the race. Set DM_BURST_REQUIRE_RACE=true to make that warning a failure.
#
# Prerequisites
#   - Dynamic Mapper service is running; for mqtt an active MQTT connector connected to the same broker,
#     for http the built-in HTTP connector
#   - c8y CLI authenticated (ROLE_DYNAMIC_MAPPER_ADMIN to read the service log), jq installed,
#     mosquitto_pub for mqtt
#
# Tuning (environment)
#   DM_BURST_TRANSPORT      mqtt | http                            (default mqtt)
#   DM_BURST_MESSAGES       total messages in the burst            (default 60)
#   DM_BURST_CLIENTS        parallel publisher processes           (default 12)
#   DM_BURST_SETTLE_SECS    max wait for the burst to be processed (default 40)
#   DM_BURST_REQUIRE_RACE   fail if fewer than two messages raced  (default false)
#   DM_BURST_HTTP_MODE      workers | processes (http transport)   (default workers)
#                           workers: ONE c8y process with DM_BURST_CLIENTS parallel workers, so the requests
#                           leave together. processes: one c8y process per client; the start-up of the
#                           processes spreads the requests over tens of milliseconds, which can let the
#                           first request finish creating the device before the others arrive.
#
# Usage:
#   ./test-concurrent-device-creation.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# The shared harness of the integration tests
# shellcheck source=../integration/test-harness.sh
source "${SCRIPT_DIR}/../integration/test-harness.sh"

TEST_TITLE="Concurrent device creation (one device for a burst)"

# ── Config ─────────────────────────────────────────────────────────────────────
TRANSPORT="${DM_BURST_TRANSPORT:-mqtt}"
REQUIRE_RACE="${DM_BURST_REQUIRE_RACE:-false}"
HTTP_MODE="${DM_BURST_HTTP_MODE:-workers}"
BURST_MESSAGES="${DM_BURST_MESSAGES:-60}"
BURST_CLIENTS="${DM_BURST_CLIENTS:-12}"
SETTLE_SECS="${DM_BURST_SETTLE_SECS:-40}"

TS="$(date +%s)"
# 'dmtest-' devices are swept by run-tests.sh --cleanup-only
EXT_ID="dmtest-burst-${TS}"
TOPIC_PREFIX="dmtest-burst-$$"
MAPPING_FIXTURE="${SCRIPT_DIR}/mapping-IN-08-alarm.json"
MAPPING_ID=""
MAPPING_IDENTIFIER="ccd$$"
START_TIME=""
# The mapper names an implicitly created device like this (not like the external id)
DEVICE_NAME="device_c8y_Serial_${EXT_ID}"

# ── Helpers ────────────────────────────────────────────────────────────────────

# Number of devices with the given name. Emits 0 on any error.
count_devices_named() {   # <name>
    c8y devices list --name "$1" --includeAll --output json </dev/null 2>/dev/null \
        | jq -s 'flatten | map(select(type == "object" and .id != null)) | length' 2>/dev/null \
        || printf '0'
}

# Devices created after <iso8601, UTC with trailing Z>. Fallback when the device name is not the expected one.
count_devices_created_since() {   # <iso8601>
    local _since=${1%Z}
    c8y devices list --query "creationTime.date gt '${_since}'" --includeAll --output json </dev/null 2>/dev/null \
        | jq -s 'flatten | map(select(type == "object" and .id != null)) | length' 2>/dev/null \
        || printf '0'
}

# Ids of the devices with the given name (for cleanup).
device_ids_named() {   # <name>
    c8y devices list --name "$1" --includeAll --output json </dev/null 2>/dev/null \
        | jq -rs 'flatten | map(select(type == "object") | .id) | .[]' 2>/dev/null || true
}

# One MQTT publisher process: sends <count> messages over ONE connection to the burst topic.
publish_burst_client_mqtt() {   # <client_index> <count>
    local _idx=$1 _count=$2 _i _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "${TOPIC_PREFIX}/${EXT_ID}" -q 1 -l)
    _dm_mqtt_append_auth_args _args "dmtest-burst-$$-${_idx}"
    _dm_mqtt_append_tls_args _args
    _i=0
    while [ "$_i" -lt "$_count" ]; do
        printf '{"code":"Overheat","level":3,"message":"burst client %s message %s"}\n' "$_idx" "$_i"
        _i=$((_i + 1))
    done | mosquitto_pub "${_args[@]}"
}

# One HTTP client process: POSTs <count> messages, one after the other, to the HTTP connector.
publish_burst_client_http() {   # <client_index> <count>
    local _idx=$1 _count=$2 _i=0 _rc=0
    while [ "$_i" -lt "$_count" ]; do
        c8y api --method POST \
            ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} \
            --url "${DM_SERVICE}/httpConnector/${TOPIC_PREFIX}/${EXT_ID}" \
            --header 'Content-Type: application/json' \
            --data "{\"code\":\"Overheat\",\"level\":3,\"message\":\"burst client ${_idx} message ${_i}\"}" \
            --force --output json </dev/null >/dev/null 2>&1 || _rc=1
        _i=$((_i + 1))
    done
    return "$_rc"
}

# The whole HTTP burst from ONE c8y process: <messages> lines go in, DM_BURST_CLIENTS workers send them in
# parallel without a process start-up in between.
publish_burst_http_workers() {   # <messages> <workers>
    seq 1 "$1" | c8y api --method POST \
        ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} \
        --url "${DM_SERVICE}/httpConnector/${TOPIC_PREFIX}/${EXT_ID}" \
        --header 'Content-Type: application/json' \
        --template "{code: 'Overheat', level: 3, message: 'burst message ' + input.value}" \
        --workers "$2" --maxJobs 0 --abortOnErrors 100000 --delay 0ms --delayBefore 0ms \
        --force --output json >/dev/null
}

# The mapping's log lines since the start of the burst, read through the service-log API. Prints nothing if
# the log is not available (no admin role, or the lines are not collected yet).
fetch_mapping_log() {   # <identifier> <from_iso8601>
    local _identifier=$1 _from=$2 _page _pages=0 _more=true
    dm_api POST /monitoring/service-logs/flush >/dev/null 2>&1 || true
    sleep 2
    while [ "$_more" = "true" ] && [ "$_pages" -lt 10 ]; do
        _page=$(dm_api GET "/monitoring/service-logs?mapping=${_identifier}&dateFrom=${_from}")
        # To stderr, so it is not mixed into the log text this function prints
        printf '%s\n' "service-log page ${_pages}: $(printf '%s' "$_page" | jq -c '{chunks, lines, omitted, more, empty}' 2>/dev/null)" >&2
        printf '%s' "$_page" | jq -r '.logs // empty' 2>/dev/null || true
        _more=$(printf '%s' "$_page" | jq -r '.more // false' 2>/dev/null || printf 'false')
        _from=$(printf '%s' "$_page" | jq -r '.dateTo // empty' 2>/dev/null || printf '')
        [ -z "$_from" ] && break
        _pages=$((_pages + 1))
    done
}

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    local _id
    for _id in $(device_ids_named "$DEVICE_NAME") $(device_ids_named "$EXT_ID"); do
        c8y inventory delete --id "$_id" 2>/dev/null || true
    done
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${_id:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$_id" 2>/dev/null || true
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE (transport: ${TRANSPORT})"

case "$TRANSPORT" in
    mqtt|http) ;;
    *) dm_error "DM_BURST_TRANSPORT must be 'mqtt' or 'http' (got '${TRANSPORT}')" ;;
esac
if [ "$TRANSPORT" = "mqtt" ] && [ "${DM_BROKER_MODE:-public}" != "public" ]; then
    dm_skip_exit "Needs several parallel MQTT clients; the Cumulocity MQTT Service allows one per certificate." \
                 "Run it against the public broker: unset DM_BROKER_MODE (default) or set DM_BROKER_MODE=public," \
                 "or use DM_BURST_TRANSPORT=http."
fi
[ -f "$MAPPING_FIXTURE" ] || dm_error "Mapping fixture not found: $MAPPING_FIXTURE"

dm_step "Waiting for Dynamic Mapper service ..."
if [ "$TRANSPORT" = "mqtt" ]; then
    dm_test_setup_and_validate
else
    dm_wait_for_service
fi
dm_validate_only_exit

if [ "$TRANSPORT" = "mqtt" ]; then
    dm_info "Broker: ${MQTT_HOST:-broker.hivemq.com}:${MQTT_PORT:-1883}"
else
    dm_info "HTTP connector endpoint: ${DM_SERVICE}/httpConnector/${TOPIC_PREFIX}/${EXT_ID}"
fi
dm_info "Burst: ${BURST_MESSAGES} messages from ${BURST_CLIENTS} parallel clients, external id ${EXT_ID}"

# The export is an array with one mapping. Make it a fresh, inactive test mapping with the option on.
MAPPING_JSON=$(jq -c \
    --arg name "test-concurrent-device-creation-$$" \
    --arg identifier "$MAPPING_IDENTIFIER" \
    --arg topic "${TOPIC_PREFIX}/+" \
    --arg sample "${TOPIC_PREFIX}/${EXT_ID}" \
    '(if type == "array" then .[0] else . end)
     | del(.id, .lastUpdate, .draftDirty, .versionNote)
     | .name = $name
     | .identifier = $identifier
     | .mappingTopic = $topic
     | .mappingTopicSample = $sample
     | .createNonExistingDevice = true
     | .updateExistingDevice = false
     | .active = false' "$MAPPING_FIXTURE")

dm_step "Verifying device does not exist yet ..."
PRE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
dm_assert_eq "No device for ${EXT_ID} before the burst" "" "${PRE_ID:-}"

dm_step "Creating and activating the mapping with createNonExistingDevice=true ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
if [ "$TRANSPORT" = "mqtt" ]; then
    dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
    dm_activate_mapping "$MAPPING_ID"
    dm_assert_mqtt_topics_active "$MAPPING_ID"
else
    # Deploy before activating, and connect the HTTP connector so it rebuilds its inbound mappings
    dm_deploy_mapping_to_connector "$MAPPING_ID" "HTTP_CONNECTOR_IDENTIFIER"
    dm_activate_mapping "$MAPPING_ID"
    dm_connect_connector "HTTP_CONNECTOR_IDENTIFIER"
    dm_wait 3 "letting the HTTP connector pick up the mapping"
fi

BASELINE_RECEIVED=$(dm_mapping_received_count "$MAPPING_ID")
BASELINE_RECEIVED=${BASELINE_RECEIVED:-0}

# Checked here, not inside the publishers: dm_mqtt_publish does it per message, which would slow the burst.
[ "$TRANSPORT" = "mqtt" ] && _dm_mqtt_require_connector_connected

dm_step "Sending the burst (${BURST_MESSAGES} messages, ${BURST_CLIENTS} ${TRANSPORT} clients in parallel) ..."
START_TIME="$(dm_now -5)"
PUBLISH_FAILED=0
if [ "$TRANSPORT" = "http" ] && [ "$HTTP_MODE" = "workers" ]; then
    # c8y caps the workers of one process at 50 and warns when it is asked for more
    WORKERS=$BURST_CLIENTS
    if [ "$WORKERS" -gt 50 ]; then
        dm_warn "c8y allows at most 50 workers per process; using 50 instead of ${BURST_CLIENTS}."
        WORKERS=50
    fi
    dm_info "One c8y process with ${WORKERS} workers (DM_BURST_HTTP_MODE=workers)"
    publish_burst_http_workers "$BURST_MESSAGES" "$WORKERS" || PUBLISH_FAILED=1
    SENT=$BURST_MESSAGES
    CLIENT=$WORKERS
else
    PER_CLIENT=$(( (BURST_MESSAGES + BURST_CLIENTS - 1) / BURST_CLIENTS ))
    SENT=0
    PIDS=""
    CLIENT=0
    while [ "$CLIENT" -lt "$BURST_CLIENTS" ] && [ "$SENT" -lt "$BURST_MESSAGES" ]; do
        COUNT=$PER_CLIENT
        [ $((SENT + COUNT)) -gt "$BURST_MESSAGES" ] && COUNT=$((BURST_MESSAGES - SENT))
        "publish_burst_client_${TRANSPORT}" "$CLIENT" "$COUNT" &
        PIDS="$PIDS $!"
        SENT=$((SENT + COUNT))
        CLIENT=$((CLIENT + 1))
    done
    for PID in $PIDS; do
        wait "$PID" || PUBLISH_FAILED=$((PUBLISH_FAILED + 1))
    done
fi
if [ "$PUBLISH_FAILED" -ne 0 ] && [ "$TRANSPORT" = "http" ] && [ "$HTTP_MODE" = "workers" ]; then
    dm_warn "The workers run failed. If your c8y version does not support 'c8y api --workers' with piped input," \
            "run with DM_BURST_HTTP_MODE=processes."
fi
if [ "$PUBLISH_FAILED" -ne 0 ] && [ "$TRANSPORT" = "http" ] && [ "$HTTP_MODE" = "workers" ]; then
    # A few requests of a 300-request burst often get "connection reset by peer" from the gateway in front of the
    # service, though the service still handles them. What counts is below: the mapping must receive every message.
    dm_warn "Some HTTP requests failed on the client side (see the c8y errors above); the check that the mapping" \
            "received every message decides whether any message was lost."
else
    dm_assert_eq_zero "All ${CLIENT} ${TRANSPORT} clients finished without error" "$PUBLISH_FAILED"
fi
dm_info "Sent ${SENT} messages for ${EXT_ID}"

dm_step "Waiting until the mapping has processed the burst (max ${SETTLE_SECS}s) ..."
ELAPSED=0
RECEIVED=0
while [ "$ELAPSED" -lt "$SETTLE_SECS" ]; do
    RECEIVED=$(( $(dm_mapping_received_count "$MAPPING_ID") - BASELINE_RECEIVED ))
    [ "$RECEIVED" -ge "$SENT" ] && break
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done
dm_info "Mapping received ${RECEIVED} of ${SENT} messages"
dm_assert_eq "The mapping received every message of the burst" "$SENT" "$RECEIVED"

dm_step "Asserting the device was created ..."
if dm_wait_for_device_by_ext_id "$EXT_ID" "c8y_Serial" 30 2; then
    DEVICE_ID="$_DM_LAST_DEVICE_ID"
else
    DEVICE_ID=""
fi
dm_assert_gt "Device auto-created (id=${DEVICE_ID:-none})" "${#DEVICE_ID}" 0

# Let late creations show up: a race that lost the lock check would create its duplicate a moment later.
dm_wait 5 "letting any duplicate creation show up"

dm_step "Asserting exactly ONE device exists for the external id ..."
NAMED=$(count_devices_named "$DEVICE_NAME")
NEW_DEVICES=$(count_devices_created_since "$START_TIME")
dm_info "Devices named '${DEVICE_NAME}': ${NAMED}; devices created since ${START_TIME} (any name): ${NEW_DEVICES}"
if [ "${NAMED:-0}" -gt 0 ]; then
    dm_assert_eq "Exactly one device named ${DEVICE_NAME}" "1" "$NAMED"
    if [ "${NEW_DEVICES:-0}" -ne 1 ]; then
        dm_warn "${NEW_DEVICES} devices were created in this window. Other activity on the tenant, or a duplicate with another name?"
    fi
else
    # The mapper named the device differently: fall back to counting what was created in the window.
    dm_warn "No device is named '${DEVICE_NAME}'; counting the devices created since the start instead (the tenant should be idle)."
    dm_assert_eq "Exactly one device created during the burst" "1" "$NEW_DEVICES"
fi

dm_step "Checking in the service log that the messages really raced for the device ..."
# From a minute before the burst: the log is stored in chunks, and the first lines of the burst can sit in a chunk
# that began earlier than the burst did.
MAPPING_LOG=$(fetch_mapping_log "$MAPPING_IDENTIFIER" "$(dm_now -50)")
dm_info "Read $(printf '%s\n' "$MAPPING_LOG" | grep -c . || true) log line(s) of mapping ${MAPPING_IDENTIFIER}"
# Not "printf | grep -q": grep exits at the first match, printf then dies of SIGPIPE, and with pipefail
# the pipeline reports failure although the id is there.
if [[ "$MAPPING_LOG" != *"$EXT_ID"* ]]; then
    dm_warn "No log lines of mapping ${MAPPING_IDENTIFIER} for ${EXT_ID}: the service log is not readable (needs" \
            "ROLE_DYNAMIC_MAPPER_ADMIN), not collected yet, or this instance did not handle the burst."
    dm_warn "The result above cannot show whether the messages raced."
else
    # A message that looked the device up and did not find it. Every such message logs it, from its own thread.
    MISSED=$(printf '%s\n' "$MAPPING_LOG" | grep "External ID ${EXT_ID} not found" \
        | sed -E 's/^[^[]*\[ *([^]]*)\].*/\1/' | sort -u | grep -c . || true)
    CREATIONS=$(printf '%s\n' "$MAPPING_LOG" | grep -c "Creating new implicit device for c8y_Serial/${EXT_ID}" || true)
    dm_info "Messages that found no device: ${MISSED}; device creations: ${CREATIONS}"
    if [ "${CREATIONS:-0}" -eq 0 ]; then
        # The lines of the creation are missing, so nothing can be said about it from the log. Show what the log
        # holds for the external id, to tell a log that lacks the lines from a creation that was not logged.
        dm_warn "The log has no 'Creating new implicit device' line for ${EXT_ID}; the creation count is unknown."
        dm_info "Log lines that mention ${EXT_ID}: $(printf '%s\n' "$MAPPING_LOG" | grep -c "$EXT_ID" || true)"
        printf '%s\n' "$MAPPING_LOG" | grep -i "implicit\|not found" | head -5 || true
        dm_info "First lines of the log that mention ${EXT_ID}:"
        printf '%s\n' "$MAPPING_LOG" | grep "$EXT_ID" | head -3 | cut -c1-200 || true
        # Strict mode must not pass on a log that cannot confirm the race
        [ "$REQUIRE_RACE" = "true" ] && dm_assert_gt "Device creations found in the service log" "0" "0"
    else
        dm_assert_eq "The device was created exactly once" "1" "$CREATIONS"
    fi
    if [ "${CREATIONS:-0}" -eq 0 ]; then
        :
    elif [ "${MISSED:-0}" -ge 2 ]; then
        dm_success "The race happened: ${MISSED} messages found no device at the same time, one of them created it"
    elif [ "$REQUIRE_RACE" = "true" ]; then
        dm_assert_gt "Messages that raced for the missing device" "${MISSED:-0}" 1
    else
        dm_warn "Only ${MISSED} message(s) found no device: the first message created it before the others were" \
                "processed, so the creation lock was not exercised."
        if [ "$TRANSPORT" = "mqtt" ]; then
            dm_warn "The MQTT connector hands messages over in order. Run with DM_BURST_TRANSPORT=http to test the lock."
        else
            dm_warn "Raise DM_BURST_MESSAGES / DM_BURST_CLIENTS to make the race more likely."
        fi
    fi
fi

dm_step "Asserting the alarm of the burst landed on that device ..."
ALARMS=$(dm_count_alarms_since "$DEVICE_ID" "$START_TIME")
dm_assert_gt "At least one alarm on device ${DEVICE_ID}" "${ALARMS:-0}" 0

dm_done "$TEST_TITLE"
dm_print_summary
