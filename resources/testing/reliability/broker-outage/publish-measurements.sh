#!/bin/bash
#
# Creates an increasing measurement for a device every INTERVAL seconds, so a gap in the
# values that arrive on the broker shows a missed outbound message (see watch-pulsar.sh).
#
# Usage:  ./publish-measurements.sh [start]
# Env:    EXT_ID=outage-dev-001   external id (type c8y_Serial); device is created if missing
#         INTERVAL=5              seconds between measurements
# Needs:  c8y CLI with an active session, jq

set -uo pipefail

EXT_ID="${EXT_ID:-outage-dev-001}"
INTERVAL="${INTERVAL:-5}"
N="${1:-1}"

command -v c8y >/dev/null || { echo "c8y CLI not found" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }

DEVICE_ID=$(c8y identity get --name "$EXT_ID" --type c8y_Serial --output json 2>/dev/null | jq -r '.managedObject.id // empty')
if [ -z "$DEVICE_ID" ]; then
    echo "Creating device for external id $EXT_ID ..."
    DEVICE_ID=$(c8y devices create --name "$EXT_ID" --force --output json | jq -r '.id')
    c8y identity create --name "$EXT_ID" --type c8y_Serial --device "$DEVICE_ID" --force >/dev/null
    echo "Remember to subscribe the device for MEASUREMENT in the mapper UI (device subscription)."
fi
echo "Device $DEVICE_ID ($EXT_ID): measurement every ${INTERVAL}s, starting at $N. Ctrl-C to stop."

CHILD=""   # pid of the running c8y call
OUT=$(mktemp)
stop() {
    trap 'exit 130' INT TERM          # a second Ctrl-C leaves immediately
    [ -n "$CHILD" ] && kill "$CHILD" 2>/dev/null
    rm -f "$OUT"
    echo; echo "Stopped. Last value sent: $((N-1))"
    exit 0
}
trap stop INT TERM

# Creates one measurement. The c8y call runs in the background, with stdin closed so it can never
# wait for input, and is given CALL_TIMEOUT seconds (macOS has no `timeout`). Not run in a command
# substitution: a subshell would hide the child from `stop`. Error text is left in $OUT.
create_measurement() {   # <value>
    : >"$OUT"
    c8y measurements create --device "$DEVICE_ID" --type c8y_TemperatureMeasurement --force \
        --data "c8y_TemperatureMeasurement.T.value=$1,c8y_TemperatureMeasurement.T.unit=C" \
        </dev/null >"$OUT" 2>&1 &
    CHILD=$!
    local i
    for i in $(seq 1 "${CALL_TIMEOUT:-20}"); do
        kill -0 "$CHILD" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$CHILD" 2>/dev/null; then
        kill "$CHILD" 2>/dev/null
        wait "$CHILD" 2>/dev/null
        CHILD=""
        printf 'timed out after %ss: %s' "${CALL_TIMEOUT:-20}" "$(head -c 300 "$OUT")" >"$OUT"
        return 1
    fi
    wait "$CHILD"; local rc=$?
    CHILD=""
    return $rc
}

while true; do
    echo "$(date +%H:%M:%S) sending $N ..."
    if create_measurement "$N"; then
        echo "$(date +%H:%M:%S) sent    $N"
    else
        echo "$(date +%H:%M:%S) FAILED  $N: $(head -c 300 "$OUT")"
    fi
    N=$((N+1))
    sleep "$INTERVAL" &
    wait $!                           # a wait returns at once when a trapped signal arrives
done
