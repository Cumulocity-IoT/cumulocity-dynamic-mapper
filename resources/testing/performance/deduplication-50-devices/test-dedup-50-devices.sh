#!/bin/bash
#
# test-dedup-50-devices: message de-duplication across many devices under load
#
# Scenario
#   50 devices (DM_DD_DEVICES) each send 10 distinct messages (DM_DD_MESSAGES). Every message is sent a second
#   time, right after the first, as a SEMANTICALLY EQUAL payload that is written differently: the members are in
#   another order (also inside the nested object) and 21.0 is written 21. The Smart Function calls
#   context.isDuplicate(<the whole payload as an object>, window). The key is an object, not a string, because
#   only objects are normalized: members are sorted by name and numbers get one text form. A key built with
#   JSON.stringify would NOT survive a reordered copy, see README.md.
#
#   After the burst every device must hold exactly its 10 distinct measurements:
#     published              = devices * messages * (1 + copies) + devices (control messages)
#     measurements expected  = devices * messages + devices
#     duplicates expected    = devices * messages * copies
#   Control message: same sequence number as message 1 but a different temperature. It is NOT a repeat and must
#   pass. Without it, a key that ignored the payload and kept only the sequence number would also pass the test.
#
#   Devices are created implicitly by the first message, so the run also races 50 device creations.
#
#   Phase 2 (window expiry, DM_DD_EXPIRY=true): after the window has ended, every device sends message 1 again,
#   in the reordered spelling, then once more in the original spelling.
#     - the first is processed again: the window ended, the old fingerprint is gone
#     - the second is dropped: the new fingerprint was recorded, so the repeat is caught again
#   Expected on top of phase 1: +devices measurements, +devices duplicates, 2*devices more messages received.
#
# Transport
#   MQTT through the active connector, published with mosquitto_pub (one process per device, DM_DD_PARALLEL at
#   a time). In Cumulocity MQTT Service mode only one client per certificate may connect, so DM_DD_PARALLEL is
#   forced to 1 and the devices are sent one after the other.
#
# Prerequisites
#   - Dynamic Mapper service is running, an active MQTT connector, c8y CLI authenticated, jq, mosquitto_pub
#
# Tuning (environment)
#   DM_DD_DEVICES      number of devices                                   (default 50)
#   DM_DD_MESSAGES     distinct messages per device                        (default 10)
#   DM_DD_COPIES       extra equal copies of each message                  (default 1)
#   DM_DD_INTERVAL     seconds between two messages of one device          (default 0.2, i.e. 5 msg/s/device)
#   DM_DD_PARALLEL     devices publishing at the same time                 (default = DM_DD_DEVICES)
#   DM_DD_WINDOW       de-duplication window in seconds, 1..3600           (default 30)
#                      Phase 1 needs it longer than the gap between a message and its copy (milliseconds,
#                      plus processing lag); phase 2 waits window + 3 s after phase 1, so keep it short.
#   DM_DD_EXPIRY       run phase 2, the window expiry check               (default true)
#   DM_DD_SETTLE_SECS  max wait for the burst to be processed              (default 120)
#
# Usage:
#   ./test-dedup-50-devices.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../integration/test-harness.sh
source "${SCRIPT_DIR}/../../integration/test-harness.sh"

TEST_TITLE="De-duplication across ${DM_DD_DEVICES:-50} devices"

# ── Config ─────────────────────────────────────────────────────────────────────
DEVICES="${DM_DD_DEVICES:-50}"
MESSAGES="${DM_DD_MESSAGES:-10}"
COPIES="${DM_DD_COPIES:-1}"
INTERVAL="${DM_DD_INTERVAL:-0.2}"
PARALLEL="${DM_DD_PARALLEL:-$DEVICES}"
WINDOW="${DM_DD_WINDOW:-30}"
EXPIRY="${DM_DD_EXPIRY:-true}"
SETTLE_SECS="${DM_DD_SETTLE_SECS:-120}"

STAMP="$(date +%s)"
EXT_PREFIX="dmtest-dd50-${STAMP}"          # 'dmtest-' devices are swept by run-tests.sh --cleanup-only
MEASUREMENT_TYPE="dmtest_dd50_${STAMP}"
TOPIC_PREFIX="dmtest/dd50-$$"              # mapping topic is <prefix>/+, the device is topic level 2
MAPPING_ID=""
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dm-dd50.XXXXXX")"

EXPECTED_MEASUREMENTS=$((DEVICES * MESSAGES + DEVICES))
EXPECTED_DUPLICATES=$((DEVICES * MESSAGES * COPIES))
PUBLISHED=$((DEVICES * MESSAGES * (1 + COPIES) + DEVICES))
if [ "$EXPIRY" = "true" ]; then
    EXPECTED_MEASUREMENTS=$((EXPECTED_MEASUREMENTS + DEVICES))
    EXPECTED_DUPLICATES=$((EXPECTED_DUPLICATES + DEVICES))
    PUBLISHED=$((PUBLISHED + 2 * DEVICES))
fi
PHASE1_MEASUREMENTS=$((DEVICES * MESSAGES + DEVICES))
PHASE1_DUPLICATES=$((DEVICES * MESSAGES * COPIES))
PHASE1_PUBLISHED=$((DEVICES * MESSAGES * (1 + COPIES) + DEVICES))

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    local _i _ext _id
    for _i in $(seq 1 "$DEVICES"); do
        _ext="${EXT_PREFIX}-$(printf '%03d' "$_i")"
        _id=$(dm_lookup_device_by_ext_id "$_ext" "c8y_Serial") || true
        if [ -n "${_id:-}" ]; then
            c8y identity delete --name "$_ext" --type "c8y_Serial" >/dev/null 2>&1 || true
            c8y inventory delete --id "$_id" >/dev/null 2>&1 || true
        fi
    done
    rm -rf "$WORK_DIR"
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ] && [ "$PARALLEL" -gt 1 ]; then
    dm_warn "Cumulocity MQTT Service allows one client per certificate: DM_DD_PARALLEL forced to 1"
    PARALLEL=1
fi
dm_info "devices=${DEVICES} messages/device=${MESSAGES} copies=${COPIES} interval=${INTERVAL}s parallel=${PARALLEL} window=${WINDOW}s"
dm_info "published=${PUBLISHED} expected measurements=${EXPECTED_MEASUREMENTS} expected duplicates=${EXPECTED_DUPLICATES}"

# ── Payloads ───────────────────────────────────────────────────────────────────
# Two spellings of the same JSON value: other member order at both levels, 21.0 versus 21.
payload_a() {   # <seq> <temperature_int>
    printf '{"seq":%s,"temperature":%s.0,"meta":{"unit":"C","site":"berlin"},"time":"2026-11-15T10:00:%02dZ"}' \
        "$1" "$2" "$(($1 % 60))"
}
payload_b() {   # <seq> <temperature_int>
    printf '{"time":"2026-11-15T10:00:%02dZ","meta":{"site":"berlin","unit":"C"},"temperature":%s,"seq":%s}' \
        "$(($1 % 60))" "$2" "$1"
}

# ── Mapping ────────────────────────────────────────────────────────────────────
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, ctx) {
    const payload = msg.getPayload();
    const config = ctx.getConfig ? ctx.getConfig() : {};
    const levels = typeof config.topic === 'string' ? config.topic.split('/') : [];
    const externalId = levels.length > 0 ? levels[levels.length - 1] : null;
    if (!externalId) {
        return [];
    }

    // The key is the whole payload as an OBJECT: members are sorted and numbers normalized before hashing.
    if (ctx.isDuplicate({ device: externalId, reading: payload }, __WINDOW__)) {
        return [];
    }

    return [{
        cumulocityType: 'measurement',
        action: 'create',
        payload: {
            time: new Date().toISOString(),
            type: '__TYPE__',
            c8y_TemperatureMeasurement: { T: { value: payload['temperature'], unit: 'C' } },
            seq: payload['seq']
        },
        externalSource: [{ type: 'c8y_Serial', externalId: externalId }]
    }];
}
JSCODE
)
SF_CODE=${SF_CODE//__WINDOW__/$WINDOW}
SF_CODE=${SF_CODE//__TYPE__/$MEASUREMENT_TYPE}
SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64 | tr -d '\n')

dm_step 1 "Creating the Smart Function mapping ..."
dm_create_mapping "$(jq -cn \
    --arg name "test-dedup-50-$$" --arg identifier "dd50$$" --arg topic "${TOPIC_PREFIX}/+" \
    --arg sample "${TOPIC_PREFIX}/${EXT_PREFIX}-001" --arg code "$SF_CODE_B64" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: $topic,
      mappingTopicSample: $sample,
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{\"seq\":1,\"temperature\":21.0,\"meta\":{\"unit\":\"C\",\"site\":\"berlin\"},\"time\":\"2026-11-15T10:00:01Z\"}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      updateExistingDevice: false,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      supportsMessageContext: true,
      qos: "AT_LEAST_ONCE"
    }')"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

SINCE=$(dm_now -10)
DUP_BEFORE=$(dm_mapping_stat "$MAPPING_ID" messagesDuplicate)
RECEIVED_BEFORE=$(dm_mapping_received_count "$MAPPING_ID")
RECEIVED_BEFORE=${RECEIVED_BEFORE:-0}

# ── Publish ────────────────────────────────────────────────────────────────────
# One mosquitto_pub per device; it reads one message per line from stdin, so a device keeps one connection.
publish_device() {   # <device_number> [phase=1]
    local _n=$1 _phase=${2:-1} _ext _topic _seq _c _temp
    _ext="${EXT_PREFIX}-$(printf '%03d' "$_n")"
    _topic="${TOPIC_PREFIX}/${_ext}"
    local _args=(-h "${MQTT_HOST:-broker.hivemq.com}" -p "${MQTT_PORT:-1883}" -t "$_topic" -q 1 -l)
    _dm_mqtt_append_auth_args _args "dmtest-dd50-$$-${_n}"
    _dm_mqtt_append_tls_args _args
    if [ "$_phase" = "2" ]; then
        # after the window: message 1 reordered (processed again), then as first sent (dropped again)
        { payload_b 1 21; printf '\n'; sleep "$INTERVAL"; payload_a 1 21; printf '\n'; } | mosquitto_pub "${_args[@]}"
        return
    fi
    {
        for _seq in $(seq 1 "$MESSAGES"); do
            _temp=$((20 + _seq))
            payload_a "$_seq" "$_temp"; printf '\n'
            for _c in $(seq 1 "$COPIES"); do
                payload_b "$_seq" "$_temp"; printf '\n'
            done
            sleep "$INTERVAL"
        done
        # control: sequence number 1 again, other temperature, so it is not a repeat
        payload_a 1 99; printf '\n'
    } | mosquitto_pub "${_args[@]}"
}

publish_all() {   # <phase> <message_count_for_the_rate_line>
    local _phase=$1 _count=$2 _t0 _secs _errors _running=0 _n
    rm -f "${WORK_DIR}"/pub-*.err
    _t0=$(date +%s)
    for _n in $(seq 1 "$DEVICES"); do
        publish_device "$_n" "$_phase" 2>"${WORK_DIR}/pub-${_n}.err" &
        _running=$((_running + 1))
        if [ "$_running" -ge "$PARALLEL" ]; then
            wait
            _running=0
        fi
    done
    wait
    _secs=$(( $(date +%s) - _t0 ))
    [ "$_secs" -lt 1 ] && _secs=1
    _errors=$(cat "${WORK_DIR}"/pub-*.err 2>/dev/null | grep -c . || true)
    dm_assert_eq "No publisher reported an error (phase ${_phase})" "0" "${_errors:-0}"
    dm_info "Published in ${_secs}s (about $((_count / _secs)) msg/s aggregate)"
}

dm_step 2 "Phase 1: publishing ${PHASE1_PUBLISHED} messages for ${DEVICES} devices ..."
publish_all 1 "$PHASE1_PUBLISHED"

# ── Wait for processing ────────────────────────────────────────────────────────
# Measurements of this run, one 'source.id' per line.
measurement_sources() {
    c8y measurements list --type "$MEASUREMENT_TYPE" --dateFrom "$SINCE" --includeAll --pageSize 2000 \
        --select source.id --output json 2>/dev/null \
        | jq -rs '[.[] | if type == "array" then .[] else . end] | .[] | (.source.id // .["source.id"] // empty)'
}

# <expected_measurements> <expected_duplicates>: poll until both are reached, no progress, or the deadline
wait_for_processing() {
_want_m=$1; _want_d=$2
_deadline=$(( $(date +%s) + SETTLE_SECS ))
_last=-1
_stable=0
while [ "$(date +%s)" -lt "$_deadline" ]; do
    measurement_sources > "${WORK_DIR}/sources.txt" || true
    _count=$(grep -c . "${WORK_DIR}/sources.txt" || true)
    _dups=$(( $(dm_mapping_stat "$MAPPING_ID" messagesDuplicate) - DUP_BEFORE ))
    dm_info "measurements=${_count}/${_want_m} duplicates=${_dups}/${_want_d}"
    if [ "$_count" -ge "$_want_m" ] && [ "$_dups" -ge "$_want_d" ]; then
        # expected totals reached: look once more, so a surplus measurement still shows up
        sleep 5
        break
    fi
    if [ "$_count" -eq "$_last" ]; then _stable=$((_stable + 1)); else _stable=0; fi
    [ "$_stable" -ge 6 ] && { dm_warn "No progress for 6 checks, evaluating what arrived"; break; }
    _last=$_count
    sleep 5
done
}

dm_step 3 "Waiting up to ${SETTLE_SECS}s for phase 1 to be processed ..."
wait_for_processing "$PHASE1_MEASUREMENTS" "$PHASE1_DUPLICATES"

if [ "$EXPIRY" = "true" ]; then
    # Phase 1 is fully processed, so every fingerprint was recorded before now and expires within WINDOW.
    dm_step 4 "Phase 2: waiting $((WINDOW + 3))s for the window to end, then sending message 1 again ..."
    dm_wait $((WINDOW + 3)) "window of ${WINDOW}s ending"
    publish_all 2 $((2 * DEVICES))
    wait_for_processing "$EXPECTED_MEASUREMENTS" "$EXPECTED_DUPLICATES"
fi

# ── Assertions ─────────────────────────────────────────────────────────────────
dm_step 5 "Checking the result"
measurement_sources > "${WORK_DIR}/sources.txt" || true
TOTAL=$(grep -c . "${WORK_DIR}/sources.txt" || true)
DUPLICATES=$(( $(dm_mapping_stat "$MAPPING_ID" messagesDuplicate) - DUP_BEFORE ))
RECEIVED=$(( $(dm_mapping_received_count "$MAPPING_ID") - RECEIVED_BEFORE ))

dm_assert_eq "Every published message reached the mapping" "$PUBLISHED" "$RECEIVED"
dm_assert_eq "Measurements created (distinct messages, controls, and re-processed after the window)" "$EXPECTED_MEASUREMENTS" "$TOTAL"
dm_assert_eq "Repeats dropped (counted in messagesDuplicate)" "$EXPECTED_DUPLICATES" "$DUPLICATES"

WANT_PER_DEVICE=$((MESSAGES + 1))
[ "$EXPIRY" = "true" ] && WANT_PER_DEVICE=$((WANT_PER_DEVICE + 1))
# Per device: exactly WANT_PER_DEVICE measurements; shows which devices lost a message or let a repeat through.
DEVICES_WITH_COUNT=$(sort "${WORK_DIR}/sources.txt" | uniq -c | awk -v want="$WANT_PER_DEVICE" '$1 == want' | wc -l | tr -d ' ')
DEVICES_SEEN=$(sort -u "${WORK_DIR}/sources.txt" | wc -l | tr -d ' ')
dm_assert_eq "Devices created" "$DEVICES" "$DEVICES_SEEN"
dm_assert_eq "Devices with exactly ${WANT_PER_DEVICE} measurements" "$DEVICES" "$DEVICES_WITH_COUNT"
if [ "$DEVICES_WITH_COUNT" -ne "$DEVICES" ]; then
    dm_info "Deviating devices (measurements, device id):"
    sort "${WORK_DIR}/sources.txt" | uniq -c | awk -v want="$WANT_PER_DEVICE" '$1 != want' | head -20
fi

dm_done "$TEST_TITLE"
dm_print_summary
