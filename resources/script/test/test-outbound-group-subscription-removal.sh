#!/bin/bash
#
# test-outbound-group-subscription-removal: Remove Device from Group and verify group subscription remains while membership is removed
#
# Steps:
#   1. Load state from test-case-III (group ID, device ID)
#   2. Confirm that a notification subscription exists for the device
#   3. Remove the device from the group "auto-group"
#   4. Wait for the dynamic mapper to react
#   5. Verify the device is no longer assigned to the group and the group subscription still exists
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

TEST_TITLE="34. Group subscription removal"

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

dm_parse_args "$@"
dm_register_cleanup cleanup
dm_validate_only_exit

dm_banner "$TEST_TITLE"

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

# Validate loaded state; if stale, bootstrap fresh state from test-case-III.
GROUP_CHECK=$(c8y inventory get --id "$GROUP_ID" --output json 2>/dev/null || echo '{}')
DEVICE_CHECK=$(c8y inventory get --id "$DEVICE_ID" --output json 2>/dev/null || echo '{}')
GROUP_EXISTS=$(echo "$GROUP_CHECK" | jq -r '.id // empty')
DEVICE_EXISTS=$(echo "$DEVICE_CHECK" | jq -r '.id // empty')

if [ -z "$GROUP_EXISTS" ] || [ -z "$DEVICE_EXISTS" ]; then
    dm_warn "Loaded state is stale (group or device missing) — bootstrapping fresh state"
    "${SCRIPT_DIR}/test-outbound-group-subscription.sh"

    if [ ! -f "$STATE_FILE" ]; then
        dm_fail "State file $STATE_FILE not found after bootstrap."
        exit 1
    fi

    # shellcheck source=/dev/null
    source "$STATE_FILE"
    dm_info "Reloaded state: GROUP_ID=$GROUP_ID, DEVICE_ID=$DEVICE_ID, GROUP_NAME=$GROUP_NAME"
fi

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
if ! c8y inventory children unassign \
    --id "$GROUP_ID" --child "$DEVICE_ID" --childType asset --force; then
    dm_fail "Failed to remove device $DEVICE_ID from group $GROUP_ID"
    exit 1
fi
dm_info "Device removed from group."

# Step 3: Wait for dynamic mapper to react
dm_step 3 "Wait for dynamic mapper to process group change"
dm_wait "$REMOVAL_WAIT" "group-removal propagation"

# Step 4: Verify device membership removed and group subscription remains
dm_step 4 "Verify device membership removed and group subscription remains"
GROUP_ASSETS_JSON=$(dm_api GET "/inventory/managedObjects/${GROUP_ID}/childAssets")
DEVICE_IN_GROUP=$(printf '%s' "$GROUP_ASSETS_JSON" | jq -r --arg did "$DEVICE_ID" '
    [ .. | objects | .id? | select(. != null) | tostring | select(. == $did) ]
    | length
' 2>/dev/null || printf '0')
dm_assert_eq_zero "device removed from group" "${DEVICE_IN_GROUP:-0}"

GROUP_SUB_JSON=$(dm_api GET /subscription/group)
GROUP_MATCH=$(printf '%s' "$GROUP_SUB_JSON" | jq -s -r --arg gid "$GROUP_ID" '
        [ .[]
            | if type == "array" then .[]
                elif type == "object" then .
                else empty
                end
            | select((.id | tostring) == $gid)
        ]
        | length
' 2>/dev/null || printf '0')
    dm_assert_gt "group subscription remains after unassign" "${GROUP_MATCH:-0}" "0"

    if [ "${GROUP_MATCH:-0}" -eq 0 ]; then
        dm_warn "Group subscription missing after removal — showing details:"
        printf '%s' "$GROUP_SUB_JSON" | jq -s '.' 2>/dev/null || true
fi

# Diagnostic: current group subscription state
echo ""
dm_info "--- Group subscription status in dynamic mapper ---"
dm_api GET /subscription/group | jq '.' || true

dm_print_summary
dm_done "$TEST_TITLE"
