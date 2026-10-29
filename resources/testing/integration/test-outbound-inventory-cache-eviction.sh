#!/bin/bash
#
# test-outbound-inventory-cache-eviction: a deleted device is evicted from the inventory cache
#
# The inventory cache gets an entry when a filterInventory check (or a Smart Function
# managed-object lookup) reads a device, and the mapper then subscribes to that managed object
# through Notification 2.0. A DELETE notification on that subscription evicts the entry
# (CacheInventoryUpdateClient -> C8YAgent.evictDeletedManagedObjectFromCaches).
#
# Flow:
#   1. Record the inventory cache size
#   2. Change dmtest_Trigger on the device → filterInventory reads it → entry is cached
#   3. Delete the device                    → DELETE notification → entry is evicted
#
# Step 2 proves the cache is populated, so an eviction that never has anything to evict
# cannot pass. Sizes are compared against the baseline, not absolute values, so entries from
# other devices do not matter.
#
# Prerequisites:
#   - Dynamic mapper service running with outbound mapping capability
#   - An active MQTT connector; c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-outbound-inventory-cache-eviction.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Inventory cache — eviction on device delete"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
CACHE_ID="INVENTORY_CACHE"
EVICTION_TIMEOUT=60
STAMP="$(date +%s)"
DEVICE_NAME="dmtest-ice-${STAMP}"
DEVICE_ID=""
MAPPING_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" || true
    if [ -n "$DEVICE_ID" ]; then
        dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
        dm_delete_device "$DEVICE_ID" || true
    fi
}

# Poll the cache until its size satisfies <op> <value> (-gt | -le) or the timeout expires.
wait_for_cache_size() {   # <op> <value> [timeout_secs]
    local _op=$1 _value=$2 _timeout=${3:-30} _elapsed=0
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _CACHE_SIZE=$(dm_cache_size "$CACHE_ID")
        if [ "$_CACHE_SIZE" -ge 0 ] && [ "$_CACHE_SIZE" "$_op" "$_value" ]; then
            return 0
        fi
        sleep 2
        _elapsed=$((_elapsed + 2))
    done
    _CACHE_SIZE=$(dm_cache_size "$CACHE_ID")
    return 1
}

dm_parse_args "$@"
dm_register_cleanup cleanup

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_require_mqtt_broker
dm_verify_mqtt_connector_ready
dm_validate_only_exit

dm_step "Reading the inventory cache size ..."
BASELINE=$(dm_cache_size "$CACHE_ID")
dm_info "Baseline $CACHE_ID entries=$BASELINE"
if [ "$BASELINE" -lt 0 ]; then
    dm_error "Cannot read GET /cache?cacheId=$CACHE_ID"
fi

dm_step "Creating device and binding its c8y_Serial external id ..."
dm_create_device "$DEVICE_NAME" "dmtest-ice-match"
DEVICE_ID=$_DM_LAST_DEVICE_ID
c8y identity create --name "$DEVICE_NAME" --type "c8y_Serial" --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "External id may already exist: $DEVICE_NAME"

dm_step "Creating static INVENTORY subscription ..."
dm_create_static_subscription_must "INVENTORY" "$DEVICE_ID" "$DEVICE_NAME"
dm_wait 5

dm_step "Creating outbound INVENTORY mapping with filterInventory (reads the device into the cache) ..."
MAPPING_JSON=$(cat <<EOF2
{
  "name": "test-outbound-ice-$$",
  "identifier": "ice$$",
  "mappingTopic": "dmtest/out/cacheevict",
  "mappingTopicSample": "dmtest/out/cacheevict",
  "publishTopic": "dmtest/out/cacheevict",
  "publishTopicSample": "dmtest/out/cacheevict",
  "targetAPI": "INVENTORY",
  "direction": "OUTBOUND",
  "mappingType": "JSON",
  "transformationType": "JSONATA",
  "filterMapping": "\$exists(dmtest_Trigger)",
  "filterInventory": "type = 'dmtest-ice-match'",
  "sourceTemplate": "{\"type\":\"dmtest-ice-match\",\"name\":\"device name\",\"dmtest_Trigger\":{\"rev\":1}}",
  "targetTemplate": "{\"deviceId\":\"source-id\",\"rev\":0}",
  "substitutions": [
    {"pathSource":"_IDENTITY_.externalId","pathTarget":"deviceId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"dmtest_Trigger.rev","pathTarget":"rev","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE"
}
EOF2
)
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"

dm_step "1. Changing dmtest_Trigger on the device (filterInventory caches it) ..."
c8y inventory update --id "$DEVICE_ID" --data '{"dmtest_Trigger":{"rev":1}}' --output json >/dev/null
if wait_for_cache_size -gt "$BASELINE" 30; then
    dm_assert_gt "Device is in the inventory cache" "$_CACHE_SIZE" "$BASELINE"
else
    dm_assert_gt "Device is in the inventory cache" "$_CACHE_SIZE" "$BASELINE"
    dm_warn "Nothing was cached, so eviction cannot be tested. Check that filterInventory ran."
    dm_done "$TEST_TITLE"
    dm_print_summary
    exit $?
fi
CACHED=$_CACHE_SIZE

dm_step "2. Deleting the device (waiting up to ${EVICTION_TIMEOUT}s for the DELETE notification) ..."
dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME" 2>/dev/null || true
dm_delete_device "$DEVICE_ID"
DELETED_ID="$DEVICE_ID"
DEVICE_ID=""
dm_info "Deleted device $DELETED_ID; inventory cache held $CACHED entries"
wait_for_cache_size -le "$BASELINE" "$EVICTION_TIMEOUT" || true
dm_assert_eq "Deleted device is evicted from the inventory cache" "$BASELINE" "$_CACHE_SIZE"

dm_done "$TEST_TITLE"
dm_print_summary
