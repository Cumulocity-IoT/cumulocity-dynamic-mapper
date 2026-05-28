#!/bin/bash
#
# test-inbound-hex: Inbound HEX binary → C8Y Event
#
# Publishes a hex-encoded MQTT payload. The mapper wraps it as {"message":"0x..."}
# and evaluates JSONata substitutions against that structure.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-hex.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
EXT_ID="dmtest-hex-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --externalId "$EXT_ID" --externalType "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
}

[[ "${1:-}" == "--cleanup" ]] && trap cleanup EXIT

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Inbound HEX Binary Transformation (EVENT)"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker

# Mapping mirrors attic/test-I example "Mapping - 12" (binaryEvent/+)
# source template shows {"message":"<hex bytes>"} — substitution: $substring(message,0,4) for text
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-hex-$$",
  "identifier": "ibh$$",
  "mappingTopic": "dmtest/hex/+",
  "mappingTopicSample": "dmtest/hex/${EXT_ID}",
  "targetAPI": "EVENT",
  "direction": "INBOUND",
  "mappingType": "HEX",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"message\":\"3635 2c20 342e 35\"}",
  "targetTemplate": "{\"text\":\"hex event\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_HexEvent\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[1]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\"Hex: \" & \$substring(message,0,4)","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE",
  "snoopStatus": "NONE",
  "snoopedTemplates": []
}
EOF
)

dm_step "Creating and activating HEX mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "Recording test start time ..."
TEST_START=$(dm_now -10)

# "48657820 74657374" = "Hex test" in ASCII hex bytes
HEX_PAYLOAD="48657820 74657374"
dm_step "Publishing hex payload ..."
dm_mqtt_publish "dmtest/hex/${EXT_ID}" "$HEX_PAYLOAD"

dm_step "Waiting for processing ..."
dm_wait 8

dm_step "Looking up device by external id ..."
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
if [ -z "$DEVICE_ID" ]; then
    dm_fail "Device '$EXT_ID' not found — HEX mapper did not create it"
fi
dm_info "Device id: $DEVICE_ID"

dm_step "Asserting at least 1 event was created ..."
dm_assert_event_count_gt "Event from HEX payload" "$DEVICE_ID" "$TEST_START" 1

dm_done "Inbound HEX Binary Transformation (EVENT)"dm_print_summary