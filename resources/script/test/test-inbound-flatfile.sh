#!/bin/bash
#
# test-inbound-flatfile: Inbound FLAT_FILE (CSV) → C8Y Measurement
#
# Publishes a raw CSV string over MQTT. The mapper wraps the payload as
# {"payload":"<csv>"} before evaluating JSONata substitutions.
#
# Prerequisites:
#   - Dynamic mapper service is running
#   - Active MQTT connector
#   - c8y CLI authenticated, mosquitto_pub installed
#
# Usage:
#   ./test-inbound-flatfile.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE=" 4. FLAT_FILE / CSV → MEASUREMENT"

# ── Config ─────────────────────────────────────────────────────────────────────
EXT_ID="dmtest-flat-$(date +%s)"
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
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

# CSV format: <value>, <temperature>, <timestamp>, <type>
# The DM wraps it as {"payload":"<raw string>"} — substitutions reference 'payload'.
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-flatfile-$$",
  "identifier": "ibf$$",
  "mappingTopic": "dmtest/flat/+",
  "mappingTopicSample": "dmtest/flat/${EXT_ID}",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "FLAT_FILE",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"payload\":\"165, 14.5, 2022-08-06T00:14:50.000+02:00, c8y_FuelMeasurement\"}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$number(\$trim(\$split(payload,\",\")[1]))","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
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

dm_step "Creating and activating FLAT_FILE mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

# Publish the raw CSV string (not JSON)
CSV_PAYLOAD='165, 14.5, "2022-08-06T00:14:50.000+02:00","c8y_FuelMeasurement"'
dm_step "Publishing flat CSV payload ..."
dm_mqtt_publish "dmtest/flat/${EXT_ID}" "$CSV_PAYLOAD"

dm_step "Asserting at least 1 measurement was created ..."
dm_assert_measurement_present "Measurement from CSV" "$EXT_ID" "c8y_Serial" 1 20

dm_done "$TEST_TITLE"
dm_print_summary