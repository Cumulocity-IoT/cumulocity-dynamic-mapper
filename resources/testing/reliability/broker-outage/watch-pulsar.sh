#!/bin/bash
#
# Follows the outbound topic of the Pulsar test mapping and reports every received value,
# flagging gaps (missed messages) and repeats (redeliveries). Survives Pulsar restarts:
# it reconnects, and the durable subscription continues where it stopped.
#
# Usage:  ./watch-pulsar.sh
# Env:    EXT_ID=outage-dev-001   must match publish-measurements.sh
#         SUB=outage-watch        durable subscription; change it to start from scratch
#         TENANT=example-tenant   Pulsar tenant of the connector
#         CONTAINER=pulsar-standalone
# Needs:  docker, jq, python3  (Pulsar from environments/pulsar)

set -uo pipefail

EXT_ID="${EXT_ID:-outage-dev-001}"
SUB="${SUB:-outage-watch}"
TENANT="${TENANT:-example-tenant}"
CONTAINER="${CONTAINER:-pulsar-standalone}"
TOPIC="persistent://$TENANT/mqtt/pulsar-test-outbound-$EXT_ID"

command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }

# Create topic and subscription up front: a new subscription starts at "latest", so it must
# exist before the first message to be counted.
docker exec "$CONTAINER" bin/pulsar-admin topics create "$TOPIC" >/dev/null 2>&1 || true
docker exec "$CONTAINER" bin/pulsar-admin topics create-subscription -s "$SUB" "$TOPIC" >/dev/null 2>&1 || true
echo "Watching $TOPIC (subscription $SUB). Ctrl-C to stop."

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Killing `docker exec` does not stop the consumer inside the container, and a lingering consumer
# keeps the subscription "in use". Stop it on exit.
cleanup() { docker exec "$CONTAINER" pkill -f "pulsar-client consume.* -s $SUB " >/dev/null 2>&1 || true; }
trap cleanup EXIT

# One long-lived pipeline, so the sequence state survives Pulsar restarts: the consumer is retried
# inside the first stage, the checker (sequence-check.py) sees a single continuous stream.
{
    trap 'exit 0' INT TERM   # Ctrl-C: stop quietly, the checker prints its summary
    while true; do
        docker exec "$CONTAINER" bin/pulsar-client consume "$TOPIC" -s "$SUB" -n 0 -p Earliest 2>/dev/null
        echo "$(date +%H:%M:%S) consumer ended (Pulsar down?), retrying in 5s ..." >&2
        sleep 5
    done
} | jq -R -r --unbuffered 'capture("content:(?<j>\\{.*\\})$") | .j | fromjson | .temperature' 2>/dev/null \
  | python3 -u "$SCRIPT_DIR/sequence-check.py"
