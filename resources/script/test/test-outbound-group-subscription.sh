#!/bin/bash
#
# test-outbound-group-subscription: Outbound Dynamic Subscription by Device Group
#
# Steps:
#   1. Create a device group "auto-group"
#   2. Create a dynamic group subscription for "auto-group"
#   3. Create a device and assign it to the group
#   4. Wait for the dynamic mapper to discover the new device via the group
#   5. Send a measurement for the device
#   6. Verify that a notification subscription exists for the device
#
# Prerequisites:
#   - c8y CLI configured and authenticated
#   - Dynamic mapper microservice deployed and outbound mapping enabled
#
# Usage:
#   ./test-case-III.sh
#   ./test-case-III.sh --cleanup    # Remove created resources afterwards
#
# Note: The group ID and device ID are written to /tmp/dm-test-III-state.env
#       so test-case-IV.sh can pick them up for the removal test.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

GROUP_NAME="auto-group"
DEVICE_NAME="test-group-device-01"
DEVICE_TYPE="test-group-type"
STATE_FILE="/tmp/dm-test-III-state.env"
DISCOVERY_WAIT=${DM_DEFAULT_DISCOVERY_WAIT}
GROUP_ID=""
DEVICE_ID=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$DEVICE_ID" ] && [ -n "$GROUP_ID" ]; then
        c8y inventory children unassign \
            --id "$GROUP_ID" --child "$DEVICE_ID" --force 2>/dev/null || true
    fi
    dm_delete_device "$DEVICE_ID"
    if [ -n "$GROUP_ID" ]; then
        dm_api DELETE "/subscription/group/${GROUP_ID}" >/dev/null 2>&1 || true
        c8y devicegroups delete --id "$GROUP_ID" --force 2>/dev/null || true
        dm_info "Deleted device group $GROUP_ID"
    fi
    # State file is intentionally kept so test-outbound-group-subscription-removal
    # can read it. The removal test is responsible for deleting it.
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

dm_banner "Outbound Dynamic Subscription by Device Group"

# Step 1: Create device group
dm_step 1 "Create device group '$GROUP_NAME'"
GROUP_JSON=$(c8y devicegroups create --name "$GROUP_NAME" --force --output json)
GROUP_ID=$(printf '%s' "$GROUP_JSON" | jq -r '.id')
dm_info "Created device group: $GROUP_NAME (id=$GROUP_ID)"

# Step 2: Create dynamic group subscription
dm_step 2 "Create dynamic group subscription for '$GROUP_NAME' (id=$GROUP_ID)"
dm_api PUT /subscription/group \
    "{\"api\": \"MEASUREMENT\", \"devices\": [{\"id\": \"${GROUP_ID}\", \"name\": \"${GROUP_NAME}\"}]}" \
    | jq '.'

# Step 3: Create device and assign to group
dm_step 3 "Create device and assign to group '$GROUP_NAME'"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID

c8y inventory children assign \
    --id "$GROUP_ID" --child "$DEVICE_ID" --childType asset --force
dm_info "Assigned device $DEVICE_ID to group $GROUP_ID"

# Save state for test-case-IV
printf 'GROUP_ID=%s\nDEVICE_ID=%s\nGROUP_NAME=%s\n' \
    "$GROUP_ID" "$DEVICE_ID" "$GROUP_NAME" > "$STATE_FILE"
dm_info "State saved to $STATE_FILE"

# Step 4: Wait for dynamic discovery
dm_step 4 "Wait for dynamic mapper to discover device via group"
dm_wait "$DISCOVERY_WAIT" "group-based device discovery"

# Step 5: Send a test measurement
dm_step 5 "Send test measurement for device $DEVICE_ID"
dm_send_measurement "$DEVICE_ID" "21.7"

# Step 6: Verify notification subscription exists
dm_step 6 "Verify notification subscription exists for device $DEVICE_ID"
dm_wait 2 "allowing subscription propagation"
dm_show_subscriptions "$DEVICE_ID"
dm_assert_has_subscription "group-based subscription exists" "$DEVICE_ID"

if [ "$_DM_LAST_SUB_COUNT" -eq 0 ]; then
    dm_warn "Checking group subscriptions via dynamic mapper service API ..."
    dm_api GET /subscription/group | jq '.'
fi

dm_print_summary
dm_done "Outbound Dynamic Subscription by Device Group"
echo " State saved to: $STATE_FILE"
echo " Run test-case-IV.sh to test group removal."
