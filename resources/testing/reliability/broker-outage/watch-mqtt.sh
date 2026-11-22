#!/bin/bash
#
# Follows the outbound topic of the MQTT test mapping with a persistent session (QoS 1) and reports
# the values with sequence-check.py (gaps, late values, duplicates). The broker queues messages for
# the session while this subscriber is disconnected, and the subscriber reconnects by itself.
#
# Usage:  ./watch-mqtt.sh
# Env:    EXT_ID=outage-dev-001       must match publish-measurements.sh
#         MQTT_HOST=localhost       HiveMQ from environments/hivemq
#         MQTT_PORT=1883            (HIVEMQ_MQTT_PORT of the compose file)
#         CLIENT_ID=outage-watch    persistent session id; change it to start from scratch
# Needs:  mosquitto_sub, jq, python3

set -uo pipefail

EXT_ID="${EXT_ID:-outage-dev-001}"
MQTT_HOST="${MQTT_HOST:-localhost}"
MQTT_PORT="${MQTT_PORT:-1883}"
CLIENT_ID="${CLIENT_ID:-outage-watch}"
TOPIC="mqtt/test/outbound/$EXT_ID"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

command -v mosquitto_sub >/dev/null || { echo "mosquitto_sub not found" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }
echo "Watching $TOPIC on $MQTT_HOST:$MQTT_PORT (session $CLIENT_ID). Ctrl-C to stop."

{
    trap 'exit 0' INT TERM   # Ctrl-C: stop quietly, the checker prints its summary
    while true; do
        # -c with a fixed -i: persistent session, so the broker keeps QoS 1 messages while we are away
        mosquitto_sub -h "$MQTT_HOST" -p "$MQTT_PORT" -t "$TOPIC" -q 1 -c -i "$CLIENT_ID" 2>/dev/null
        echo "$(date +%H:%M:%S) subscriber ended (broker down?), retrying in 5s ..." >&2
        sleep 5
    done
} | jq -R -r --unbuffered 'try (fromjson | .temperature) catch empty' 2>/dev/null \
  | python3 -u "$SCRIPT_DIR/sequence-check.py"
