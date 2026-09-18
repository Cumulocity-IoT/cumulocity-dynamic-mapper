#!/bin/bash
#
# test-outbound-type-subscription: Outbound Dynamic Subscription by Device Type
#
# Steps:
#   1. Add a dynamic type subscription for type "auto-type"
#   2. Create a device with type "auto-type"
#   3. Wait for the dynamic mapper to discover the new device
#   4. Send a measurement for the device
#   5. Verify that a notification subscription exists for the device
#
# Prerequisites:
#   - c8y CLI configured and authenticated
#   - Dynamic mapper microservice deployed and outbound mapping enabled
#
# Usage:
#   ./test-case-II.sh
#   ./test-case-II.sh --cleanup    # Remove created resources afterwards

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="33. Dynamic type subscription"

DEVICE_TYPE="auto-type"
DEVICE_NAME="test-dynamic-type-device-01"
DISCOVERY_WAIT=${DM_DEFAULT_DISCOVERY_WAIT}
DEVICE_ID=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    dm_delete_device "$DEVICE_ID"
    # Remove only this test's type, preserving any other subscribed types
    # (dm_set_type_subscriptions overwrites the whole list, so it's read-modify-write here).
    local _remaining
    _remaining=$(dm_api GET /subscription/type | jq -c --arg t "$DEVICE_TYPE" '
        (if type == "array" then (.[0] // {} | .types // [])
         elif type == "object" then (.types // [])
         else [] end)
        | map(select(. != $t))
    ' 2>/dev/null || echo '[]')
    # Never abort inside the trap: a clear that does not read back only warns here.
    _DM_TYPE_SUB_VERIFY=false dm_set_type_subscriptions MEASUREMENT "${_remaining:-[]}"
    echo "Cleanup done."
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_validate_tools
dm_wait_for_service
dm_validate_only_exit

dm_banner "$TEST_TITLE"

# Step 1: Add dynamic type subscription
dm_step 1 "Add dynamic type subscription for '$DEVICE_TYPE'"
# Verified read-after-write: aborts here if the type filter never lands, rather than
# letting the run continue and fail at step 5 as if discovery were broken.
dm_set_type_subscriptions MEASUREMENT "[\"${DEVICE_TYPE}\"]"

# Step 2: Create device with type
dm_step 2 "Create device with type '$DEVICE_TYPE'"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID

# Step 3: Wait for dynamic discovery
dm_step 3 "Wait for dynamic mapper to discover device"
dm_wait "$DISCOVERY_WAIT" "type-based device discovery"

# Step 4: Send a test measurement
dm_step 4 "Send test measurement for device $DEVICE_ID"
dm_send_measurement "$DEVICE_ID" "18.3"

# Step 5: Verify notification subscription exists
dm_step 5 "Verify notification subscription exists for device $DEVICE_ID"
# Polls rather than sampling once: the previous single read 2s after the measurement
# turned a filter that had not propagated yet into a bare "0 is not > 0". On failure the
# assertion dumps both the mapper's answer and the raw C8Y management subscription.
dm_assert_type_subscription_present "type-based subscription exists" "$DEVICE_TYPE" 20

dm_print_summary
dm_done "$TEST_TITLE"
