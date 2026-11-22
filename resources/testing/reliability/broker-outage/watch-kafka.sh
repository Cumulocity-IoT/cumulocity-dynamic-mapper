#!/bin/bash
#
# Follows the outbound topic of the Kafka test mapping through a consumer group and reports the
# values with sequence-check.py (gaps, late values, duplicates). A consumer group keeps its
# committed offsets, so a restart of the consumer or the broker continues where it stopped.
#
# Usage:  ./watch-kafka.sh
# Env:    TOPIC=kafka-test-outbound   topic the outbound Smart Function writes to
#         GROUP=outage-watch          consumer group; change it to start from scratch
#         CONTAINER=broker            Kafka container from environments/kafka
# Needs:  docker, jq, python3

set -uo pipefail

TOPIC="${TOPIC:-kafka-test-outbound}"
GROUP="${GROUP:-outage-watch}"
CONTAINER="${CONTAINER:-broker}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KAFKA_BIN=/opt/kafka/bin

command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }

# One partition keeps the order. Without it the mapper creates the topic with the broker default
# (3 partitions) and values from different partitions can interleave; the checker tolerates that.
docker exec "$CONTAINER" "$KAFKA_BIN/kafka-topics.sh" --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "$TOPIC" --partitions 1 >/dev/null 2>&1 || true
echo "Watching topic $TOPIC (group $GROUP). Ctrl-C to stop."

# Killing `docker exec` does not stop the consumer inside the container: stop it on exit.
cleanup() { docker exec "$CONTAINER" pkill -f "kafka-console-consumer.*--group $GROUP" >/dev/null 2>&1 || true; }
trap cleanup EXIT

{
    trap 'exit 0' INT TERM   # Ctrl-C: stop quietly, the checker prints its summary
    while true; do
        docker exec "$CONTAINER" "$KAFKA_BIN/kafka-console-consumer.sh" --bootstrap-server localhost:9092 \
            --topic "$TOPIC" --group "$GROUP" --from-beginning 2>/dev/null
        echo "$(date +%H:%M:%S) consumer ended (broker down?), retrying in 5s ..." >&2
        sleep 5
    done
} | jq -R -r --unbuffered 'try (fromjson | .temperature) catch empty' 2>/dev/null \
  | python3 -u "$SCRIPT_DIR/sequence-check.py"
