#!/bin/bash
#
# test-inbound-json-jsonata: Inbound JSON → C8Y Event (JSONATA transformation)
#
# Publishes a JSON MQTT message using a JSONata-based substitution and verifies
# that a C8Y event is created.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-json-jsonata.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Config ─────────────────────────────────────────────────────────────────────
EXT_ID="dmtest-jsonata-$(date +%s)"
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner " 2. JSON / JSONATA → EVENT"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-json-jsonata-$$",
  "identifier": "ibj$$",
  "mappingTopic": "dmtest/event/+",
  "mappingTopicSample": "dmtest/event/${EXT_ID}",
  "targetAPI": "EVENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"msg_type\":\"c8y_TestEvent\",\"txt\":\"hello world\",\"td\":\"2022-09-08T16:21:53.389+02:00\"}",
  "targetTemplate": "{\"text\":\"event text\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"txt","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"msg_type","pathTarget":"type","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOF
)

dm_step "Creating and activating inbound mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Publishing MQTT message ..."
dm_mqtt_publish "dmtest/event/${EXT_ID}" '{"msg_type":"c8y_TestEvent","txt":"hello JSONata","td":"2025-01-01T00:00:00Z"}'

dm_step "Asserting at least 1 event was created ..."
dm_assert_event_present "Event created" "$EXT_ID" "c8y_Serial" 1 20

dm_done " 2. JSON / JSONATA → EVENT"
dm_print_summary
