#!/bin/bash
#
# run-live-set-load.sh — drive a steady inbound load through an MQTT broker so the live set of a
# (locally running) dynamic mapper can be measured with measure-live-set.sh.
#
# Default broker: the public HiveMQ broker (broker.hivemq.com). A locally running mapper cannot reach the
# Cumulocity MQTT Service: its connector talks Pulsar to localhost:6650 (C8Y_BASEURL_PULSAR in
# application-dev.properties), which only exists inside the cloud. DM_BROKER_MODE=c8y-mqtt-service still
# works if you run against a deployed instance or have that endpoint reachable.
#
# It uses three Smart Function mappings taken from the integration suite's "smartfunction" group
# (the code is extracted from the test scripts, so it cannot drift from them):
#   02  test-inbound-smartfunction-02.sh  testSmartInbound/<id>       getManagedObjectByExternalId + filter
#   03  test-inbound-smartfunction-03.sh  testSmartInbound/sf03/<id>  getManagedObjectByExternalId -> EVENT
#   04  test-inbound-smartfunction-04.sh  flowState/<id>              per-device state (setState/getState),
#                                                                     implicit device creation
# 02/03 exercise the inventory and external-id caches; 04 the per-device flow state. Test 05 is not
# used: it needs one client certificate per device, which only the MQTT Service mode provides.
#
# Usage:
#   ./run-live-set-load.sh [--devices N] [--duration SEC] [--publishers P] [--local [HOST:PORT]] [--keep]
#
#   --devices N       devices per mapping (default 30; 02/03 pre-create them, 04 creates them implicitly)
#   --duration SEC    how long to publish (default 900)
#   --publishers P    parallel publisher processes (default 4 on the public broker). In MQTT Service mode
#                     the clientId is the certificate CN, so more than 1 would kick each other off:
#                     forced back to 1.
#   --local [H:P]     send the REST setup calls straight to a locally running mapper (default
#                     localhost:8080) instead of the tenant's /service/... route, which always reaches
#                     the DEPLOYED instance. Needs a c8y CLI session for the same tenant.
#   --keep            keep mappings/devices afterwards (otherwise everything is removed)
#   --validate-only   check the environment and exit
#
# Environment: DM_BROKER_MODE defaults to public here (c8y-mqtt-service is the alternative). MQTT_HOST /
# MQTT_PORT select another public broker; without them the host, port and TLS setting of the first enabled
# MQTT connector in the mapper are used. Everything else is the harness's (see run-tests.sh).
#
# Public broker caveat: the topics are the integration suite's fixed ones (testSmartInbound/..., flowState/...),
# so strangers publishing to the same topics would reach your mappings. Device ids are unique per run, so
# such messages are dropped or create nothing useful, but they would add noise to the load.
#
# Only ONE mapper instance should serve the tenant while this runs: with a deployed instance running too,
# either instance may process a given message, which skews the measurement.

set -euo pipefail

# Everything below sits in one brace group so bash parses the WHOLE script before running any of it.
# Otherwise bash reads the file incrementally while it runs: an edit or editor auto-format of this
# file during the multi-minute device setup shifts the read position and shows up as a bogus
# "syntax error near unexpected token" at a random line.
{

HERE="$(cd "$(dirname "$0")" && pwd)"
INT="$(cd "$HERE/../../integration" && pwd)"

DEVICES=30
DURATION=900
PUBLISHERS=0   # 0 = decide by broker mode below
KEEP_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --devices)    DEVICES=$2; shift 2 ;;
        --duration)   DURATION=$2; shift 2 ;;
        --publishers) PUBLISHERS=$2; shift 2 ;;
        --local)
            if [ $# -gt 1 ] && [[ "$2" != --* ]]; then export DM_SERVICE_HOST=$2; shift 2
            else export DM_SERVICE_HOST=localhost:8080; shift; fi ;;
        --keep|--validate-only) KEEP_ARGS+=("$1"); shift ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

export DM_BROKER_MODE="${DM_BROKER_MODE:-public}"
BROKER_CHOSEN_BY_USER=false
[ -n "${MQTT_HOST:-}${MQTT_PORT:-}" ] && BROKER_CHOSEN_BY_USER=true
# shellcheck disable=SC1091
source "$INT/test-harness.sh"

RUN="$(date +%s)"
declare -a IDS_02 IDS_03 IDS_04 DEVICE_IDS_CREATED
MAPPINGS=()
INVENTORY_FRAGMENTS_CHANGED=false
ORIGINAL_INVENTORY_FRAGMENTS=""
SENT=0

dm_parse_args ${KEEP_ARGS[@]+"${KEEP_ARGS[@]}"}

# ── helpers ───────────────────────────────────────────────────────────────────

# Smart Function body of a test script: the text between "<<'JSCODE'" and the closing "JSCODE".
extract_code() {   # <test script>
    awk "/<<'JSCODE'/ {on=1; next} /^JSCODE\$/ {on=0} on" "$INT/$1"
}

create_mapping() {   # <name> <identifier> <topic> <targetApi> <createNonExistingDevice> <code>
    local _code_b64 _json
    _code_b64=$(printf '%s\n' "$(dm_wrap_onmessage_code "$6")" | base64 | tr -d '\n')
    _json=$(jq -cn --arg name "$1" --arg id "$2" --arg topic "$3" --arg api "$4" \
        --argjson create "$5" --arg code "$_code_b64" '{
          name: $name, identifier: $id,
          mappingTopic: ($topic + "/+"), mappingTopicSample: ($topic + "/sample"),
          targetAPI: $api, direction: "INBOUND", mappingType: "JSON",
          transformationType: "SMART_FUNCTION", sourceTemplate: "{}", targetTemplate: "{}",
          substitutions: [], code: $code, active: false, debug: false,
          createNonExistingDevice: $create, useExternalId: true, externalIdType: "c8y_Serial",
          genericDeviceIdentifier: "_IDENTITY_.externalId", qos: "AT_LEAST_ONCE" }')
    dm_create_mapping "$_json"
    MAPPINGS+=("$_DM_LAST_MAPPING_ID")
    dm_deploy_mapping_to_mqtt_connector "$_DM_LAST_MAPPING_ID"
    dm_activate_mapping "$_DM_LAST_MAPPING_ID"
}

create_device() {   # <name> <ext id> <sensor-voltage true|false|none>
    local _data=(--data "c8y_IsDevice={}") _json _id
    [ "$3" != none ] && _data+=(--data "c8y_Sensor={\"type\":{\"voltage\":$3}}")
    _json=$(c8y inventory create --name "$1" --type "c8y_Sensor" "${_data[@]}" 2>/dev/null </dev/null || echo "{}")
    _id=$(printf '%s' "$_json" | jq -r '.id // empty')
    [ -n "$_id" ] || dm_error "Failed to create device $1"
    c8y identity create --name "$2" --type c8y_Serial --device "$_id" --output json >/dev/null 2>&1 </dev/null \
        || dm_warn "External ID may already exist: $2"
    DEVICE_IDS_CREATED+=("$_id")
}

restore_inventory_fragments() {
    [ "$INVENTORY_FRAGMENTS_CHANGED" = "true" ] || return 0
    dm_api PUT /configuration/service "$(jq -cn --argjson f "$ORIGINAL_INVENTORY_FRAGMENTS" '{inventoryFragmentsToCache: $f}')" >/dev/null
    INVENTORY_FRAGMENTS_CHANGED=false
    dm_info "Restored inventory cache fragments"
}

cleanup() {
    dm_info "Cleaning up ..."
    local _m _i _id
    for _m in ${MAPPINGS[@]+"${MAPPINGS[@]}"}; do dm_delete_mapping "$_m" 2>/dev/null || true; done
    for _id in ${IDS_02[@]+"${IDS_02[@]}"} ${IDS_03[@]+"${IDS_03[@]}"} ${IDS_04[@]+"${IDS_04[@]}"}; do
        _i=$(dm_lookup_device_by_ext_id "$_id" c8y_Serial 2>/dev/null || true)
        c8y identity delete --name "$_id" --type c8y_Serial >/dev/null 2>&1 </dev/null || true
        [ -n "$_i" ] && c8y inventory delete --id "$_i" >/dev/null 2>&1 </dev/null || true
    done
    restore_inventory_fragments
    [ -n "${COUNT_DIR:-}" ] && rm -rf "$COUNT_DIR"
    dm_info "Cleanup complete"
}
dm_register_cleanup cleanup


# The harness only accepts an enabled MQTT connector whose host:port equals MQTT_HOST:MQTT_PORT, and its
# defaults (broker.hivemq.com:1883, no TLS) rarely match the tenant. Unless MQTT_HOST/MQTT_PORT were set,
# adopt the first enabled MQTT connector's host, port and TLS setting from the mapper.
detect_public_broker() {
    local _conn _host _port _proto
    _conn=$(dm_api_json_array GET /configuration/connector/instance \
        | jq -s 'map(if type=="array" then .[] else . end) | map(select(type=="object"))
                 | map(select(.connectorType=="MQTT" and .enabled==true)) | .[0] // empty' 2>/dev/null || true)
    if [ -z "$_conn" ]; then
        dm_warn "No enabled MQTT connector found in the mapper; the harness will skip. Enable one, or set MQTT_HOST/MQTT_PORT."
        return 0
    fi
    _host=$(printf '%s' "$_conn" | jq -r '.properties.mqttHost // empty')
    _port=$(printf '%s' "$_conn" | jq -r '.properties.mqttPort // empty')
    _proto=$(printf '%s' "$_conn" | jq -r '.properties.protocol // "mqtt://"')
    [ -n "$_host" ] && [ -n "$_port" ] || return 0
    case "$_proto" in
        ws://|wss://) dm_warn "Connector '$(printf '%s' "$_conn" | jq -r .name)' uses WebSocket ($_proto); mosquitto_pub cannot publish to it." ;;
    esac
    export MQTT_HOST="$_host" MQTT_PORT="$_port"
    case "$_proto" in
        mqtts://|ssl://|tls://|wss://)
            export MQTT_TLS=true
            if [ -z "${MQTT_CAFILE:-}" ]; then
                for _ca in /etc/ssl/cert.pem /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt; do
                    [ -r "$_ca" ] && { export MQTT_CAFILE="$_ca"; break; }
                done
            fi ;;
        *) export MQTT_TLS=false ;;
    esac
    dm_info "Using mapper connector '$(printf '%s' "$_conn" | jq -r .name)': $MQTT_HOST:$MQTT_PORT (tls=$MQTT_TLS${MQTT_CAFILE:+, ca=$MQTT_CAFILE})"
}

# ── 1. environment ────────────────────────────────────────────────────────────
dm_banner "Live-set load: 3 Smart Function mappings via MQTT ($DM_BROKER_MODE)"
dm_step 1 "Validating environment"
if [ "$DM_BROKER_MODE" = "public" ] && [ "$BROKER_CHOSEN_BY_USER" = "false" ]; then
    dm_wait_for_service
    detect_public_broker
fi
dm_test_setup_and_validate
dm_validate_only_exit

if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
    if [ "$PUBLISHERS" -gt 1 ]; then
        dm_warn "MQTT Service: clientId == certificate CN, parallel publishers would disconnect each other — using 1."
    fi
    PUBLISHERS=1
elif [ "$PUBLISHERS" -le 0 ]; then
    PUBLISHERS=4
fi
dm_info "REST target: ${DM_SERVICE_HOST:-tenant /service route (the DEPLOYED instance)}"

# ── 2. inventory cache must hold c8y_Sensor for mapping 02's filter ───────────
dm_step 2 "Including c8y_Sensor in the inventory cache"
ORIGINAL_INVENTORY_FRAGMENTS=$(dm_api GET /configuration/service \
    | jq -ce '.inventoryFragmentsToCache | select(type == "array")' 2>/dev/null || printf '')
[ -n "$ORIGINAL_INVENTORY_FRAGMENTS" ] || dm_error "Could not read inventoryFragmentsToCache"
if ! printf '%s' "$ORIGINAL_INVENTORY_FRAGMENTS" | jq -e 'index("c8y_Sensor") != null' >/dev/null; then
    INVENTORY_FRAGMENTS_CHANGED=true
    dm_api PUT /configuration/service \
        "$(jq -cn --argjson f "$ORIGINAL_INVENTORY_FRAGMENTS" '{inventoryFragmentsToCache: ($f + ["c8y_Sensor"])}')" >/dev/null
fi

# ── 3. mappings ───────────────────────────────────────────────────────────────
dm_step 3 "Creating, deploying and activating the three mappings"
create_mapping "lsm-sf-02-$RUN" "lsm-sf-02-$RUN" "testSmartInbound"      MEASUREMENT false "$(extract_code test-inbound-smartfunction-02.sh)"
create_mapping "lsm-sf-03-$RUN" "lsm-sf-03-$RUN" "testSmartInbound/sf03"  EVENT       false "$(extract_code test-inbound-smartfunction-03.sh)"
create_mapping "lsm-sf-04-$RUN" "lsm-sf-04-$RUN" "flowState"              MEASUREMENT true  "$(extract_code test-inbound-smartfunction-04.sh)"
dm_success "Mappings active: ${MAPPINGS[*]}"

# ── 4. devices ────────────────────────────────────────────────────────────────
dm_step 4 "Creating $DEVICES devices each for mappings 02 and 03 (04 creates its devices implicitly)"
i=1
while [ "$i" -le "$DEVICES" ]; do
    IDS_02+=("lsm02-$RUN-$i"); create_device "lsm voltage sensor $i" "lsm02-$RUN-$i" true
    IDS_03+=("lsm03-$RUN-$i"); create_device "lsm sensor $i"         "lsm03-$RUN-$i" none
    IDS_04+=("lsm04-$RUN-$i")
    i=$((i + 1))
done
dm_success "$((DEVICES * 2)) devices created"

# ── 5. load ───────────────────────────────────────────────────────────────────
dm_step 5 "Publishing for ${DURATION}s to ${MQTT_HOST}:${MQTT_PORT} with $PUBLISHERS publisher(s) — sample the live set now"
echo "    Start in another terminal:  $HERE/measure-live-set.sh --interval 60"

MQTT_ARGS=(-h "$MQTT_HOST" -p "$MQTT_PORT" -q 1)
_dm_mqtt_append_auth_args MQTT_ARGS ""
_dm_mqtt_append_tls_args MQTT_ARGS

COUNT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/lsm-count.XXXXXX")
PUB_WORKER=0   # set per worker in publish_loop
PUB_SEQ=0
pub() {   # <topic> <payload>
    # In public mode every publish gets its own client id: parallel workers must not take each
    # other's session over (a shared id makes the broker disconnect the older one). Worker number
    # plus a counter, not $RANDOM: forked workers inherit identical $RANDOM sequences.
    if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
        mosquitto_pub "${MQTT_ARGS[@]}" -t "$1" -m "$2" 2>/dev/null
    else
        PUB_SEQ=$((PUB_SEQ + 1))
        mosquitto_pub "${MQTT_ARGS[@]}" -i "lsm-$RUN-w$PUB_WORKER-$PUB_SEQ" -t "$1" -m "$2" 2>/dev/null
    fi
}

# One initial error per mapping-04 device so its per-device flow state is populated as in real use.
for id in "${IDS_04[@]}"; do
    pub "flowState/$id" "{\"messageId\":\"m-init\",\"externalId\":\"$id\",\"payloadType\":\"error\",\"logMessage\":\"initial error\",\"sensorData\":{}}" || true
done

publish_loop() {   # <worker> <end epoch>
    local _w=$1 _end=$2 _n=0 _sent=0 _failed=0 _d _t
    PUB_WORKER=$_w
    while [ "$(date +%s)" -lt "$_end" ]; do
        _d=$(( (_n + _w) % DEVICES + 1 ))
        case $(( (_n + _w) % 3 )) in
            0) pub "testSmartInbound/lsm02-$RUN-$_d" "{\"messageId\":\"m-$_n\",\"deviceId\":\"$_d\",\"sensorData\":{\"val\":$((_n % 400))}}" ;;
            1) pub "testSmartInbound/sf03/lsm03-$RUN-$_d" "{\"trigger\":\"load\",\"n\":$_n}" ;;
            2) pub "flowState/lsm04-$RUN-$_d" "{\"messageId\":\"m-$_n\",\"externalId\":\"lsm04-$RUN-$_d\",\"payloadType\":\"telemetry\",\"sensorData\":{\"temp_val\":$((_n % 60))}}" ;;
        esac && _sent=$((_sent + 1)) || _failed=$((_failed + 1))
        _n=$((_n + 1))
        if [ $((_n % 50)) -eq 0 ]; then
            _t=$(( _end - $(date +%s) ))
            echo "    worker $_w: $_sent sent, $_failed failed, ${_t}s left"
        fi
    done
    echo "    worker $_w done: $_sent sent, $_failed failed"
    echo "$_sent" > "$COUNT_DIR/sent.$_w"
}

END=$(( $(date +%s) + DURATION ))
w=0
while [ "$w" -lt "$PUBLISHERS" ]; do
    if [ "$PUBLISHERS" -gt 1 ]; then publish_loop "$w" "$END" & else publish_loop "$w" "$END"; fi
    w=$((w + 1))
done
wait

# "sent" only means mosquitto_pub succeeded. Ask the mapper what it actually received and processed per
# mapping — before cleanup deletes the mappings and their counters.
verify_processing() {
    local _f _total_sent=0 _stats _rows _received
    for _f in "$COUNT_DIR"/sent.*; do
        [ -f "$_f" ] && _total_sent=$((_total_sent + $(cat "$_f")))
    done
    sleep 5   # let the mapper flush its status counters
    _stats=$(dm_api GET /monitoring/status/mapping/statistic)
    _rows=$(printf '%s' "$_stats" | jq -s -r --arg run "$RUN" '
        map(if type=="array" then .[] else . end) | map(select(type=="object"))
        | map(select((.identifier // "") | test("^lsm-sf-0[234]-" + $run + "$")))
        | .[] | "\(.identifier)\t\(.messagesReceived // 0)\t\(.messagesProcessed // "n/a")\t\(.errors // 0)"' 2>/dev/null || true)
    echo "    published by this run : $_total_sent (plus $DEVICES initial error messages to mapping 04)"
    if [ -z "$_rows" ]; then
        dm_warn "No statistics for the three mappings came back — cannot confirm the mapper processed anything."
        return 0
    fi
    _received=0
    while IFS=$'\t' read -r _id _rcv _proc _err; do
        echo "    $_id: received $_rcv, processed $_proc, errors $_err"
        _received=$((_received + _rcv))
    done <<EOF_ROWS
$_rows
EOF_ROWS
    if [ "$_received" -eq 0 ]; then
        dm_warn "The mappings received 0 messages: the load was NOT processed — the live-set numbers describe an idle service."
    elif [ $((_received * 10)) -lt $(( (_total_sent + DEVICES) * 9 )) ]; then
        dm_warn "Mappings received only $_received of ~$((_total_sent + DEVICES)) published messages (< 90%): the measured load was lighter than published — check connector status and mapper errors."
    else
        dm_success "Mappings received $_received of ~$((_total_sent + DEVICES)) published messages"
    fi
}

dm_step 5b "Verifying the mapper processed the load"
verify_processing

dm_step 6 "Done"
dm_done "Load finished. Check the mapper log for errors; the live-set CSV is in the measuring terminal."
dm_print_summary

}
exit 0
