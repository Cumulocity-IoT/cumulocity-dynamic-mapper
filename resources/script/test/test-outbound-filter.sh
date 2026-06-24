#!/bin/bash
#
# test-outbound-filter: Outbound filterMapping expression blocks non-matching objects
#
# Creates two outbound mappings with different filterMapping expressions.
# Verifies that only the matching mapping processes the notification.
#
# Flow:
#   1. Mapping A: filterMapping = "type = \"c8y_BusEvent\"" (only bus events)
#   2. Mapping B: filterMapping = "true" (all events)
#   3. Create a generic event → only mapping B should process it
#   4. Create a bus event → both mappings should process it
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-filter.sh [--cleanup]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="dmtest-out-filter-$(date +%s)"
DEVICE_TYPE="dmtest-out-type"
EXT_ID="$DEVICE_NAME"
DEVICE_ID=""
MAPPING_A_ID=""
MAPPING_B_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_A_ID" ] && dm_delete_mapping "$MAPPING_A_ID" || true
    [ -n "$MAPPING_B_ID" ] && dm_delete_mapping "$MAPPING_B_ID" || true
    [ -n "$DEVICE_ID" ] && dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "25. filterMapping — selective forwarding"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Creating test device ..."
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_info "Device id: $DEVICE_ID"

dm_step "Binding c8y_Serial external id ..."
c8y identity create \
  --name "$EXT_ID" \
  --type "c8y_Serial" \
  --device "$DEVICE_ID" \
  --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $EXT_ID"

dm_step "Creating static subscription for device ..."
dm_create_static_subscription_must "EVENT" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 3

# Mapping A: only bus events
dm_step "Creating filtered mapping A (bus events only) ..."
MAPPING_A_JSON=$(cat <<EOF
{
  "name": "test-outbound-filter-A-$$",
  "identifier": "fltA$$",
  "mappingTopic": "dmtest/out/filter/bus",
  "mappingTopicSample": "dmtest/out/filter/bus",
  "publishTopic": "dmtest/out/filter/bus",
  "publishTopicSample": "dmtest/out/filter/bus",
  "targetAPI": "EVENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "type = \"c8y_BusEvent\"",
  "sourceTemplate": "{\"text\":\"bus event\",\"type\":\"c8y_BusEvent\"}",
  "targetTemplate": "{\"type\":\"c8y_BusEvent\"}",
  "substitutions": [],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF
)
dm_create_mapping "$MAPPING_A_JSON"
MAPPING_A_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_A_ID"
dm_activate_mapping "$MAPPING_A_ID"

# Mapping B: all events
dm_step "Creating catch-all mapping B ..."
MAPPING_B_JSON=$(cat <<EOF
{
  "name": "test-outbound-filter-B-$$",
  "identifier": "fltB$$",
  "mappingTopic": "dmtest/out/filter/all",
  "mappingTopicSample": "dmtest/out/filter/all",
  "publishTopic": "dmtest/out/filter/all",
  "publishTopicSample": "dmtest/out/filter/all",
  "targetAPI": "EVENT",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "filterMapping": "true",
  "sourceTemplate": "{\"text\":\"any event\",\"type\":\"c8y_TestEvent\"}",
  "targetTemplate": "{\"type\":\"c8y_TestEvent\"}",
  "substitutions": [],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF
)
dm_create_mapping "$MAPPING_B_JSON"
MAPPING_B_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_B_ID"
dm_activate_mapping "$MAPPING_B_ID"


dm_step "Recording baselines ..."
BASELINE_A=$(dm_mapping_received_count "$MAPPING_A_ID")
BASELINE_B=$(dm_mapping_received_count "$MAPPING_B_ID")
dm_info "Baseline A=$BASELINE_A B=$BASELINE_B"

dm_step "Creating a generic event (should match only B) ..."
c8y events create \
    --device "$DEVICE_ID" \
    --type "c8y_GenericEvent" \
    --text "generic event" \
    --output json >/dev/null
dm_wait 8

AFTER_GENERIC_A=$(dm_mapping_received_count "$MAPPING_A_ID")
AFTER_GENERIC_B=$(dm_mapping_received_count "$MAPPING_B_ID")
dm_info "After generic event: A=$AFTER_GENERIC_A B=$AFTER_GENERIC_B"
dm_assert_eq "Mapping A should NOT process generic event" "$BASELINE_A" "$AFTER_GENERIC_A"
dm_assert_gt  "Mapping B should process generic event" "$AFTER_GENERIC_B" "$BASELINE_B"

dm_step "Creating a bus event (should match both A and B) ..."
BASELINE_A2=$(dm_mapping_received_count "$MAPPING_A_ID")
BASELINE_B2=$(dm_mapping_received_count "$MAPPING_B_ID")
c8y events create \
    --device "$DEVICE_ID" \
    --type "c8y_BusEvent" \
    --text "bus event" \
    --output json >/dev/null
dm_wait 8

dm_assert_mapping_received_gt "Mapping A should process bus event" "$MAPPING_A_ID" "$BASELINE_A2"
dm_assert_mapping_received_gt "Mapping B should process bus event" "$MAPPING_B_ID" "$BASELINE_B2"

dm_done "25. filterMapping — selective forwarding"
dm_print_summary
