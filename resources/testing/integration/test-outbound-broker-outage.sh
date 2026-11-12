#!/bin/bash
#
# test-outbound-broker-outage: Outbound messages survive a broker outage
#
# When the broker connection of an MQTT connector is down, outbound Notification 2.0
# messages must not be consumed and lost. The mapper closes the connector's device
# WebSocket (without unsubscribing) and Cumulocity keeps the unacknowledged messages.
# When the connector is connected again, the queued messages are delivered.
#
# The outage is simulated by pointing the connector at an unreachable endpoint
# (127.0.0.1:1, as seen from the service) and restoring the real broker afterwards, so
# the test works against a deployed service and a public broker. No local broker needed.
#
# Steps:
#   1. MQTT connector to the test broker; outbound EVENT mapping + static subscription;
#      a control event arrives (broker reachable)
#   2. Point the connector at an unreachable endpoint, wait until it is not CONNECTED
#   3. Create 3 events while the connector is down
#   4. Restore the broker, let the connector reconnect
#   5. Assert all 3 events are published to the broker after the reconnect
#
# Prerequisites:
#   - mosquitto_sub installed on the test host; the broker reachable from the test host
#     and from the service
#   - c8y CLI authenticated, jq installed
#
# Environment (harness defaults):
#   MQTT_HOST / MQTT_PORT   broker for the connector and the test subscriber
#                           (default broker.hivemq.com:1883)
#
# Usage:
#   ./test-outbound-broker-outage.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Outbound messages survive a broker outage"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
BROKER_HOST="${MQTT_HOST:-broker.hivemq.com}"
BROKER_PORT="${MQTT_PORT:-1883}"
# Unreachable from the service (connection refused): simulates the broker being down
DOWN_HOST="127.0.0.1"
DOWN_PORT=1
CONNECTOR_ID="testoutagemqtt"
DEVICE_NAME="dmtest-out-outage-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
TOPIC="dmtest/out/outage/$$-$RANDOM"
OUTAGE_EVENTS=3

DEVICE_ID=""
MAPPING_ID=""
CONNECTOR_CREATED=false
SUB_PID=""
WORK_DIR=""

set_connector_endpoint() {   # <host> <port>
    local _cfg _body
    _cfg=$(dm_api GET "/configuration/connector/instance/$CONNECTOR_ID")
    _body=$(printf '%s' "$_cfg" | jq -c --arg h "$1" --argjson p "$2" '.properties.mqttHost = $h | .properties.mqttPort = $p')
    dm_api PUT "/configuration/connector/instance/$CONNECTOR_ID" "$_body" >/dev/null
    dm_info "Connector endpoint set to $1:$2"
    # A saved configuration is only applied on the next connect: CONNECT reconnects the running client
    # with the stored configuration. It can answer with an error while the new endpoint is unreachable.
    dm_connect_connector "$CONNECTOR_ID" || true
}

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$SUB_PID" ] && kill "$SUB_PID" 2>/dev/null || true
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
command -v mosquitto_sub >/dev/null 2>&1 || dm_skip_exit "mosquitto_sub is required"
dm_validate_only_exit

WORK_DIR=$(mktemp -d)

dm_step "Creating MQTT connector to $BROKER_HOST:$BROKER_PORT ..."
dm_setup_mqtt_test_connector "$CONNECTOR_ID" "Test outage MQTT connector" "$BROKER_HOST" "$BROKER_PORT"
CONNECTOR_CREATED=true
dm_enable_connector "$CONNECTOR_ID"
dm_connect_connector "$CONNECTOR_ID"
if ! dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 40 2 >/dev/null; then
    dm_skip_exit "Connector could not connect to $BROKER_HOST:$BROKER_PORT from the service"
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
mosquitto_sub -h "$BROKER_HOST" -p "$BROKER_PORT" -t "$TOPIC" -q 1 -C 1 -W 30 >"$WORK_DIR/control.txt" 2>/dev/null &
SUB_PID=$!
sleep 1
c8y events create --device "$DEVICE_ID" --type "c8y_OutageEvent" --text "outage-0" --output json >/dev/null
wait "$SUB_PID" 2>/dev/null || true
SUB_PID=""
dm_assert_eq "control message published" "1" "$(grep -c '"outage-0"' "$WORK_DIR/control.txt" || true)"

# ── Outage ─────────────────────────────────────────────────────────────────────
dm_step "Simulating the outage: connector endpoint -> $DOWN_HOST:$DOWN_PORT ..."
set_connector_endpoint "$DOWN_HOST" "$DOWN_PORT"
CONNECTOR_DOWN=false
_status="UNKNOWN"
for _i in $(seq 1 30); do
    _status=$(dm_get_connector_status "$CONNECTOR_ID" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
    if [ "$_status" != "CONNECTED" ]; then CONNECTOR_DOWN=true; break; fi
    sleep 2
done
[ "$CONNECTOR_DOWN" = "true" ] || dm_error "Connector still CONNECTED 60s after its endpoint became unreachable"
dm_success "Connector left CONNECTED (status: $_status)"
# The device WebSocket is paused by a check that runs every 10 seconds
dm_wait 15 "for the mapper to pause the device WebSocket"

dm_step "Creating $OUTAGE_EVENTS events while the broker is down ..."
for _n in $(seq 1 "$OUTAGE_EVENTS"); do
    c8y events create --device "$DEVICE_ID" --type "c8y_OutageEvent" --text "outage-$_n" --output json >/dev/null
done
dm_wait 10 "so any consumed message would have been dropped by now"

# ── Recovery ───────────────────────────────────────────────────────────────────
dm_step "Listening on the broker, then restoring the connector endpoint ..."
mosquitto_sub -h "$BROKER_HOST" -p "$BROKER_PORT" -t "$TOPIC" -q 1 -C "$OUTAGE_EVENTS" -W 150 >"$WORK_DIR/received.txt" 2>/dev/null &
SUB_PID=$!
sleep 2
set_connector_endpoint "$BROKER_HOST" "$BROKER_PORT"

dm_step "Waiting for the connector to reconnect ..."
dm_wait_for_connector_status "$CONNECTOR_ID" CONNECTED 150 3 >/dev/null \
    || dm_error "Connector did not reconnect within 150s"
dm_success "Connector is CONNECTED again"

dm_step "Waiting for the queued messages ..."
wait "$SUB_PID" 2>/dev/null || true
SUB_PID=""

# Match the quoted text value: the device name (deviceId in the payload) also contains "outage-<timestamp>"
for _n in $(seq 1 "$OUTAGE_EVENTS"); do
    dm_assert_eq "event outage-$_n published after reconnect" "1" "$(grep -c "\"outage-$_n\"" "$WORK_DIR/received.txt" || true)"
done

dm_done "$TEST_TITLE"
dm_print_summary
