#!/bin/bash
#
# test-outbound-group-subscription-removal: Remove Device from Group → Subscription Deleted — Verify Dynamic Subscription Deleted
#
# Steps:
#   1. Load state from test-case-III (group ID, device ID)
#   2. Confirm that a notification subscription exists for the device
#   3. Remove the device from the group "auto-group"
#   4. Wait for the dynamic mapper to react
#   5. Verify that the dynamic subscription for the device has been deleted
#
# Prerequisites:
#   - test-case-III.sh must have been run first (state file must exist)
#   - c8y CLI configured and authenticated
#   - Dynamic mapper microservice deployed and outbound mapping enabled
#
# Usage:
#   ./test-case-IV.sh
#   ./test-case-IV.sh --cleanup    # Remove created resources afterwards

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

STATE_FILE="/tmp/dm-test-III-state.env"
REMOVAL_WAIT=10
GROUP_ID=""
DEVICE_ID=""
GROUP_NAME=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    dm_delete_device "$DEVICE_ID"
    if [ -n "$GROUP_ID" ]; then
        dm_api DELETE "/subscription/group/${GROUP_ID}" >/dev/null 2>&1 || true
        c8y devicegroups delete --id "$GROUP_ID" --force 2>/dev/null || true
        dm_info "Deleted device group $GROUP_ID"
    fi
    rm -f "$STATE_FILE"
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

dm_banner "Outbound Group Subscription Removal"

# Load state; if missing, auto-bootstrap by running test-case-III first.
if [ ! -f "$STATE_FILE" ]; then
    dm_warn "State file $STATE_FILE not found — bootstrapping via test-outbound-group-subscription.sh"
    "${SCRIPT_DIR}/test-outbound-group-subscription.sh"
fi

if [ ! -f "$STATE_FILE" ]; then
    dm_fail "State file $STATE_FILE not found after bootstrap."
    exit 1
fi

# shellcheck source=/dev/null
source "$STATE_FILE"
dm_info "Loaded state: GROUP_ID=$GROUP_ID, DEVICE_ID=$DEVICE_ID, GROUP_NAME=$GROUP_NAME"

# Step 1: Confirm subscription exists before removal
dm_step 1 "Confirm notification subscription exists for device $DEVICE_ID"
dm_count_subscriptions "$DEVICE_ID" >/dev/null
if [ "$_DM_LAST_SUB_COUNT" -gt 0 ]; then
    dm_info "Found $_DM_LAST_SUB_COUNT subscription(s) before removal:"
    dm_show_subscriptions "$DEVICE_ID"
else
    dm_warn "No notification2 subscriptions found for device $DEVICE_ID before removal."
    dm_api GET /subscription/group | jq '.' || true
fi

# Step 2: Remove device from group
dm_step 2 "Remove device $DEVICE_ID from group $GROUP_ID ('$GROUP_NAME')"
c8y inventory children unassign \
    --id "$GROUP_ID" --child "$DEVICE_ID" --childType asset --force
dm_info "Device removed from group."

# Step 3: Wait for dynamic mapper to react
dm_step 3 "Wait for dynamic mapper to process group change"
dm_wait "$REMOVAL_WAIT" "group-removal propagation"

# Step 4: Verify subscription is deleted
dm_step 4 "Verify dynamic subscription is deleted for device $DEVICE_ID"
if ! dm_wait_for_subscription_absent "$DEVICE_ID" 30 1; then
    dm_warn "Subscription still present after wait window for device $DEVICE_ID"
fi
dm_show_subscriptions "$DEVICE_ID"
dm_assert_no_subscription "subscription removed after group unassign" "$DEVICE_ID"

if [ "$_DM_LAST_SUB_COUNT" -gt 0 ]; then
    dm_warn "Subscriptions still present after group removal — showing details:"
    dm_count_subscriptions "$DEVICE_ID" | jq -s '.' 2>/dev/null || true
fi

# Diagnostic: current group subscription state
echo ""
dm_info "--- Group subscription status in dynamic mapper ---"
dm_api GET /subscription/group | jq '.' || true

dm_print_summary
dm_done "Outbound Group Subscription Removal"
