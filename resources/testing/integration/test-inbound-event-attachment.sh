#!/bin/bash
#
# test-inbound-event-attachment: _CONTEXT_DATA_ attachment + device naming (inbound EVENT)
#
# A JSONata mapping with eventWithAttachment=true writes:
#   _CONTEXT_DATA_.attachmentName / attachmentType / attachmentData (base64)
#   _CONTEXT_DATA_.deviceName / deviceType (applied to the implicitly created device)
# Asserts the event exists, the binary can be downloaded with identical bytes, and the
# implicit device carries the name and type taken from context data.
#
# Usage:
#   ./test-inbound-event-attachment.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="_CONTEXT_DATA_ → EVENT with binary attachment"

EXT_ID="dmtest-attach-$(date +%s)"
CTX_DEVICE_NAME="dmtest-ctx-name-$(date +%s)"
CTX_DEVICE_TYPE="dmtest_ctx_type"
ATTACH_TEXT="hello-attachment-$$"
MAPPING_ID=""
DEVICE_ID=""
OUT_FILE="$(mktemp)"

cleanup() {
    dm_info "Cleaning up ..."
    rm -f "$OUT_FILE"
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate
dm_validate_only_exit

MAPPING_JSON=$(cat <<EOM
{
  "name": "test-inbound-attach-$$",
  "identifier": "iba$$",
  "mappingTopic": "dmtest/attach/+",
  "mappingTopicSample": "dmtest/attach/${EXT_ID}",
  "targetAPI": "EVENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "JSONATA",
  "sourceTemplate": "{\"data\":\"aGVsbG8=\",\"file\":\"a.txt\",\"name\":\"dev\"}",
  "targetTemplate": "{\"text\":\"event with attachment\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_AttachmentEvent\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"\$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"data","pathTarget":"_CONTEXT_DATA_.attachmentData","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"file","pathTarget":"_CONTEXT_DATA_.attachmentName","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"'text/plain'","pathTarget":"_CONTEXT_DATA_.attachmentType","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"name","pathTarget":"_CONTEXT_DATA_.deviceName","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"'${CTX_DEVICE_TYPE}'","pathTarget":"_CONTEXT_DATA_.deviceType","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "eventWithAttachment": true,
  "createNonExistingDevice": true,
  "updateExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOM
)

dm_step "Creating and activating mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

PAYLOAD_B64=$(printf '%s' "$ATTACH_TEXT" | base64 | tr -d '\n')

dm_step "Publishing payload with base64 attachment ..."
dm_mqtt_publish "dmtest/attach/${EXT_ID}" "{\"data\":\"${PAYLOAD_B64}\",\"file\":\"a.txt\",\"name\":\"${CTX_DEVICE_NAME}\"}"

dm_assert_event_present "Event created" "$EXT_ID" "c8y_Serial" 1 20
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")

dm_step "Verifying device name/type from _CONTEXT_DATA_ ..."
dm_assert_mo_field_eventually "Device name from context data" "$DEVICE_ID" '.name' "$CTX_DEVICE_NAME" 20
dm_assert_mo_field_eq "Device type from context data" "$DEVICE_ID" '.type' "$CTX_DEVICE_TYPE"

dm_step "Downloading the event binary ..."
EVENT_ID=$(c8y events list --device "$DEVICE_ID" --type c8y_AttachmentEvent --pageSize 1 --output json 2>/dev/null | jq -rs 'flatten | .[0].id // empty')
dm_assert_ne "Event id resolved" "" "${EVENT_ID:-}"
c8y events downloadBinary --id "$EVENT_ID" --outputFileRaw "$OUT_FILE" >/dev/null 2>&1 || true
dm_assert_eq "Attachment bytes round-trip" "$ATTACH_TEXT" "$(cat "$OUT_FILE" 2>/dev/null)"

dm_done "$TEST_TITLE"
dm_print_summary
