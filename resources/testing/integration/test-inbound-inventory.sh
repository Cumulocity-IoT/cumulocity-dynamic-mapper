#!/bin/bash
#
# test-inbound-inventory: Inbound JSON → C8Y INVENTORY (device metadata update)
#
# Exercises the INVENTORY targetAPI — i.e. a mapping that MODIFIES the device's
# managed object (its metadata: type and custom fragments) rather than creating
# a measurement/event/alarm. This is the only inbound path that writes to
# /inventory/managedObjects and the only test covering updateExistingDevice=true.
#
# Flow:
#   1. Pre-create a device with a known type and bind a c8y_Serial external id.
#   2. Deploy an inbound INVENTORY mapping with updateExistingDevice=true that
#      maps the payload onto the device's `type` and a custom `dmtest_Meta`
#      fragment, resolving the target device via _IDENTITY_.externalId.
#   3. Publish an MQTT message carrying the new metadata.
#   4. Assert the existing managed object was updated in place (type changed and
#      the custom fragment now present) — and that NO second device was created.
#
# Prerequisites:
#   - Dynamic mapper service running (DM_SERVICE env var or default)
#   - An active MQTT connector (MQTT_HOST, MQTT_PORT, ...)
#   - c8y CLI authenticated, jq + mosquitto_pub installed
#
# Usage:
#   ./test-inbound-inventory.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="12. JSON / DEFAULT → INVENTORY (device metadata update)"

# ── Config ─────────────────────────────────────────────────────────────────────
DEVICE_NAME="dmtest-inv-$(date +%s)"
EXT_ID="$DEVICE_NAME"
ORIGINAL_TYPE="dmtest_OriginalType"
UPDATED_TYPE="dmtest_UpdatedType"
DEVICE_ID=""
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    [ -n "${DEVICE_ID:-}" ] && c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_validate_only_exit

dm_step "Pre-creating device with type '$ORIGINAL_TYPE' ..."
dm_create_device "$DEVICE_NAME" "$ORIGINAL_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

dm_step "Binding c8y_Serial external id ..."
c8y identity create \
    --name "$EXT_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

dm_step "Asserting baseline metadata (type='$ORIGINAL_TYPE', no custom fragment) ..."
dm_assert_mo_field_eq "Baseline type" "$DEVICE_ID" ".type" "$ORIGINAL_TYPE"
dm_assert_eq "Baseline has no dmtest_Meta" "" "$(dm_get_mo_field "$DEVICE_ID" '.dmtest_Meta.site')"

# updateExistingDevice=true → the mapping updates the managed object resolved via
# _IDENTITY_.externalId instead of creating a new one. createNonExistingDevice is
# false: this test is strictly about modifying *existing* device metadata.
MAPPING_JSON=$(cat <<EOF
{
  "name": "test-inbound-inventory-$$",
  "identifier": "ibinv$$",
  "mappingTopic": "dmtest/inventory/+",
  "mappingTopicSample": "dmtest/inventory/${EXT_ID}",
  "targetAPI": "INVENTORY",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"newType\":\"dmtest_UpdatedType\",\"meta\":{\"site\":\"berlin\",\"rev\":7}}",
  "targetTemplate": "{\"type\":\"placeholder\",\"dmtest_Meta\":{\"site\":\"\",\"rev\":0}}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"newType","pathTarget":"type","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"meta","pathTarget":"dmtest_Meta","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": false,
  "updateExistingDevice": true,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "genericDeviceIdentifier": "_IDENTITY_.externalId",
  "qos": "AT_LEAST_ONCE"
}
EOF
)

dm_step "Creating and activating inbound INVENTORY mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active

dm_step "Publishing MQTT metadata message to dmtest/inventory/${EXT_ID} ..."
dm_mqtt_publish "dmtest/inventory/${EXT_ID}" \
    '{"newType":"dmtest_UpdatedType","meta":{"site":"berlin","rev":7}}'

dm_step "Waiting for inventory update to propagate ..."
dm_assert_mo_field_eventually "Device type updated" "$DEVICE_ID" ".type" "$UPDATED_TYPE" 30

dm_step "Asserting custom fragment was written onto the managed object ..."
dm_assert_mo_field_eq "Custom fragment dmtest_Meta.site" "$DEVICE_ID" ".dmtest_Meta.site" "berlin"
dm_assert_mo_field_eq "Custom fragment dmtest_Meta.rev"  "$DEVICE_ID" ".dmtest_Meta.rev"  "7"

dm_step "Asserting the update was in place (no duplicate device created) ..."
RESOLVED_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
dm_assert_eq "Same managed object updated in place" "$DEVICE_ID" "$RESOLVED_ID"

dm_done "$TEST_TITLE"
dm_print_summary
