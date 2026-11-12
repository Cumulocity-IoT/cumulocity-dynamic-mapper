#!/bin/bash
#
# test-outbound-broker-outage: Outbound messages survive a broker outage
#
# When the broker connection of an MQTT connector is down, outbound Notification 2.0
# messages must not be consumed and lost. The mapper closes the connector's device
# WebSocket (without unsubscribing) and Cumulocity keeps the unacknowledged messages.
# When the connector is connected again, the queued messages are delivered.
#
# Steps:
#   1. Start a local mosquitto broker and an MQTT connector that points to it
#   2. Outbound EVENT mapping + static subscription; control event arrives (broker up)
#   3. Stop the broker, wait until the connector is no longer CONNECTED
#   4. Create 3 events while the broker is down
#   5. Start the broker again, let the connector reconnect
#   6. Assert all 3 events are published to the broker after the reconnect
#
# Prerequisites:
#   - mosquitto and mosquitto_sub installed on the test host
#   - the Dynamic Mapper service can reach the local broker. For a service run on this
#     machine the default 127.0.0.1 works; otherwise set
#       DM_OUTAGE_BROKER_HOST=<address of this host as seen from the service>
#   - c8y CLI authenticated, jq installed
#
# Skipped when mosquitto is missing or the service cannot connect to the local broker.
#
# Environment:
#   DM_OUTAGE_BROKER_HOST    address the service uses to reach the broker (default 127.0.0.1)
#   DM_OUTAGE_BROKER_PORT    port the local mosquitto listens on (default 18831)
#   DM_OUTAGE_CONNECTOR_PORT port the connector connects to (default: the listen port;
#                            set it when a TCP tunnel forwards to the local broker)
#
# The test must stop and start the broker, so it cannot use a public broker such as
# broker.hivemq.com: DM_OUTAGE_BROKER_HOST has to lead to the local mosquitto.
#
# Usage:
#   ./test-outbound-broker-outage.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Outbound messages survive a broker outage"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
BROKER_HOST="${DM_OUTAGE_BROKER_HOST:-127.0.0.1}"
BROKER_PORT="${DM_OUTAGE_BROKER_PORT:-18831}"
# Port the connector uses. Differs from the listen port when a TCP tunnel sits in between.
CONNECTOR_PORT="${DM_OUTAGE_CONNECTOR_PORT:-$BROKER_PORT}"
# Alphanumeric only: Cumulocity builds the Notification 2.0 subscriber name from the identifier
# and rejects anything else (HTTP 422 "has to be alphanumeric"). Cleaned up by --cleanup-only.
CONNECTOR_ID="testoutagemqtt"
DEVICE_NAME="dmtest-out-outage-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
TOPIC="dmtest/out/outage"
OUTAGE_EVENTS=3

DEVICE_ID=""
MAPPING_ID=""
CONNECTOR_CREATED=false
BROKER_PID=""
SUB_PID=""
WORK_DIR=""

start_broker() {
    mosquitto -c "$WORK_DIR/mosquitto.conf" >"$WORK_DIR/mosquitto.log" 2>&1 &
    BROKER_PID=$!
    # wait until the port accepts connections
    local _i
    for _i in $(seq 1 20); do
        if (exec 3<>"/dev/tcp/127.0.0.1/$BROKER_PORT") 2>/dev/null; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

stop_broker() {
    if [ -n "$BROKER_PID" ]; then
        kill "$BROKER_PID" 2>/dev/null || true
        wait "$BROKER_PID" 2>/dev/null || true
        BROKER_PID=""
    fi
}

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$SUB_PID" ] && kill "$SUB_PID" 2>/dev/null || true
    stop_broker
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
    if [ "$CONNECTOR_CREATED" = "true" ]; then
        dm_disable_connector "$CONNECTOR_ID" 2>/dev/null || true
        dm_delete_connector "$CONNECTOR_ID" 2>/dev/null || true
    fi
    [ -n "$WORK_DIR" ] && rm -rf "$WORK_DIR" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
command -v mosquitto >/dev/null 2>&1 && command -v mosquitto_sub >/dev/null 2>&1 \
    || dm_skip_exit "mosquitto and mosquitto_sub are required to stop and start a local broker"
dm_validate_only_exit

WORK_DIR=$(mktemp -d)
cat >"$WORK_DIR/mosquitto.conf" <<CONF
listener $BROKER_PORT
allow_anonymous true
persistence false
CONF

dm_step "Starting local broker on port $BROKER_PORT ..."
start_broker || dm_skip_exit "Could not start mosquitto on port $BROKER_PORT (port in use?)"

dm_step "Creating MQTT connector to $BROKER_HOST:$CONNECTOR_PORT ..."
dm_setup_mqtt_test_connector "$CONNECTOR_ID" "Test outage MQTT connector" "$BROKER_HOST" "$CONNECTOR_PORT"
CONNECTOR_CREATED=true
dm_enable_connector "$CONNECTOR_ID"
dm_connect_connector "$CONNECTOR_ID"
if ! dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 40 2 >/dev/null; then
    dm_skip_exit "Connector could not connect to $BROKER_HOST:$CONNECTOR_PORT. Set DM_OUTAGE_BROKER_HOST to the address of this host as seen from the service."
fi
dm_success "Connector is CONNECTED"

dm_step "Creating test device ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"
c8y identity create --name "$EXT_ID" --type "c8y_Serial" --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

dm_step "Creating static subscription for device ..."
dm_create_static_subscription_must "EVENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

dm_step "Creating outbound EVENT mapping ..."
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-outbound-outage-$$",
  "identifier": "out$$",
  "mappingTopic": "$TOPIC",
  "mappingTopicSample": "$TOPIC",
  "publishTopic": "$TOPIC",
  "publishTopicSample": "$TOPIC",
  "targetAPI": "EVENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"text\":\"event text\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_OutageEvent\"}",
  "targetTemplate": "{\"type\":\"c8y_OutageEvent\",\"text\":\"hello\",\"deviceId\":\"source-id\"}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"text","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_connector "$MAPPING_ID" "$CONNECTOR_ID"
dm_activate_mapping "$MAPPING_ID"
dm_wait 3

# ── Control: with the broker up, an event is published ─────────────────────────
dm_step "Control: event while the broker is up ..."
mosquitto_sub -h 127.0.0.1 -p "$BROKER_PORT" -t "$TOPIC" -q 1 -C 1 -W 30 >"$WORK_DIR/control.txt" 2>/dev/null &
SUB_PID=$!
sleep 1
c8y events create --device "$DEVICE_ID" --type "c8y_OutageEvent" --text "outage-0" --output json >/dev/null
wait "$SUB_PID" 2>/dev/null || true
SUB_PID=""
dm_assert_eq "control message published" "1" "$(grep -c 'outage-0' "$WORK_DIR/control.txt" || true)"

# ── Outage ─────────────────────────────────────────────────────────────────────
dm_step "Stopping the broker ..."
stop_broker
CONNECTOR_DOWN=false
_status="UNKNOWN"
for _i in $(seq 1 30); do
    _status=$(dm_get_connector_status "$CONNECTOR_ID" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
    if [ "$_status" != "CONNECTED" ]; then CONNECTOR_DOWN=true; break; fi
    sleep 2
done
[ "$CONNECTOR_DOWN" = "true" ] || dm_error "Connector still CONNECTED 60s after the broker stopped"
dm_success "Connector left CONNECTED (status: $_status)"
# The device WebSocket is paused by a check that runs every 10 seconds
dm_wait 15 "for the mapper to pause the device WebSocket"

dm_step "Creating $OUTAGE_EVENTS events while the broker is down ..."
for _n in $(seq 1 "$OUTAGE_EVENTS"); do
    c8y events create --device "$DEVICE_ID" --type "c8y_OutageEvent" --text "outage-$_n" --output json >/dev/null
done
dm_wait 10 "so any consumed message would have been dropped by now"

# ── Recovery ───────────────────────────────────────────────────────────────────
dm_step "Starting the broker again and listening ..."
start_broker || dm_error "Could not restart mosquitto"
mosquitto_sub -h 127.0.0.1 -p "$BROKER_PORT" -t "$TOPIC" -q 1 -C "$OUTAGE_EVENTS" -W 120 >"$WORK_DIR/received.txt" 2>/dev/null &
SUB_PID=$!

dm_step "Waiting for the connector to reconnect ..."
dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 120 3 >/dev/null \
    || dm_error "Connector did not reconnect within 120s"
dm_success "Connector is CONNECTED again"

dm_step "Waiting for the queued messages ..."
wait "$SUB_PID" 2>/dev/null || true
SUB_PID=""

for _n in $(seq 1 "$OUTAGE_EVENTS"); do
    dm_assert_eq "event outage-$_n published after reconnect" "1" "$(grep -c "outage-$_n" "$WORK_DIR/received.txt" || true)"
done

dm_done "$TEST_TITLE"
dm_print_summary
