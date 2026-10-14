#!/bin/bash
#
# test-inbound-alarm-dedup-upsert: repeated inbound alarm for the same source+type
#
# Two messages for the same device and alarm type must yield ONE alarm, not two.
#   - Always asserted (holds for both the legacy create path, which bumps the alarm's
#     `count`, and for POST /alarm/alarms/upsert, issue #522): exactly 1 alarm.
#   - Upsert-only (DM_EXPECT_ALARM_UPSERT=true): the existing alarm is fully
#     refreshed, i.e. the text of the SECOND message wins.
#
# Prerequisites: MQTT connector, mosquitto_pub, c8y CLI.
#
# Usage: [DM_EXPECT_ALARM_UPSERT=true] ./test-inbound-alarm-dedup-upsert.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Inbound ALARM dedup / upsert"

EXT_ID="dmtest-alarm-upsert-$(date +%s)"
ALARM_TYPE="dmtest_UpsertAlarm_$$"
MAPPING_ID=""
DEVICE_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" --force </dev/null 2>/dev/null || true
        dm_delete_device "$DEVICE_ID"
    fi
}

alarms_json() {
    c8y alarms list --device "$DEVICE_ID" --type "$ALARM_TYPE" --pageSize 50 --output json </dev/null 2>/dev/null \
        | jq -sc '[ .[] | if type == "array" then .[] else . end ]' 2>/dev/null || printf '[]'
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating, deploying and activating the alarm mapping"
MAPPING_JSON=$(jq -cn --arg name "test-alarm-upsert-$$" --arg id "alarmupsert$$" --arg ext "$EXT_ID" '{
  name: $name,
  identifier: $id,
  mappingTopic: "dmtest/alarmupsert/+",
  mappingTopicSample: ("dmtest/alarmupsert/" + $ext),
  targetAPI: "ALARM",
  direction: "INBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  sourceTemplate: "{\"alarmType\":\"\",\"severity\":\"\",\"text\":\"\",\"time\":\"\"}",
  targetTemplate: "{\"type\":\"c8y_SystemAlarm\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"text\":\"Device alarm triggered\",\"time\":\"1970-01-01T00:00:00Z\"}",
  substitutions: [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"alarmType","pathTarget":"type","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"severity","pathTarget":"severity","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"text","pathTarget":"text","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"time","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
  active: false,
  debug: false,
  createNonExistingDevice: true,
  useExternalId: true,
  externalIdType: "c8y_Serial",
  genericDeviceIdentifier: "_IDENTITY_.externalId",
  qos: "AT_LEAST_ONCE"
}')
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

publish_alarm() {   # <severity> <text>
    dm_mqtt_publish "dmtest/alarmupsert/${EXT_ID}" \
        "$(jq -cn --arg t "$ALARM_TYPE" --arg s "$1" --arg x "$2" '{alarmType:$t, severity:$s, text:$x, time:(now|todate)}')" 1
}

dm_step 3 "First message creates the alarm"
publish_alarm MINOR "first occurrence"
dm_wait_for_alarm_count "$EXT_ID" "c8y_Serial" 1 30 2 || dm_error "No alarm created from the first message"
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
dm_assert_eq "Alarm text after first message" "first occurrence" "$(alarms_json | jq -r '.[0].text // empty')"

dm_step 4 "Second message (same source+type, new text/severity)"
publish_alarm MAJOR "second occurrence"
dm_wait 12 "second message processing"

ALARMS=$(alarms_json)
dm_assert_eq "Exactly one alarm for source+type" "1" "$(printf '%s' "$ALARMS" | jq 'length')"
dm_info "Alarm: $(printf '%s' "$ALARMS" | jq -c '.[0] | {id, text, severity, count}')"

if [ "${DM_EXPECT_ALARM_UPSERT:-false}" = "true" ]; then
    dm_step 5 "Upsert semantics: fields of the second message win"
    dm_assert_eq "Alarm text refreshed" "second occurrence" "$(printf '%s' "$ALARMS" | jq -r '.[0].text // empty')"
    dm_assert_eq "Alarm severity refreshed" "MAJOR" "$(printf '%s' "$ALARMS" | jq -r '.[0].severity // empty')"
else
    dm_info "Skipping upsert-only assertions (set DM_EXPECT_ALARM_UPSERT=true once #522 is implemented)"
fi

dm_done "$TEST_TITLE"
dm_print_summary
