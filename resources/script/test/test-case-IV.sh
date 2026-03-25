#!/bin/bash
#
# Test Case IV: Remove Device from Group — Verify Dynamic Subscription Deleted
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

DM_SERVICE="/service/dynamic-mapper-service"
STATE_FILE="/tmp/dm-test-III-state.env"
REMOVAL_WAIT=10   # seconds to wait for group removal to propagate

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$DEVICE_ID" ]; then
        echo "Deleting device $DEVICE_ID ..."
        c8y devices delete --id "$DEVICE_ID" --force 2>/dev/null || true
    fi
    if [ -n "$GROUP_ID" ]; then
        echo "Deleting group subscription for group $GROUP_ID ..."
        c8y api \
            --method DELETE \
            --url "${DM_SERVICE}/subscription/group/${GROUP_ID}" \
            --force 2>/dev/null || true
        echo "Deleting device group $GROUP_ID ..."
        c8y devicegroups delete --id "$GROUP_ID" --force 2>/dev/null || true
    fi
    rm -f "$STATE_FILE"
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

echo "=============================================="
echo " Test Case IV: Remove Device from Group"
echo "=============================================="

# Load state
if [ ! -f "$STATE_FILE" ]; then
    echo "ERROR: State file $STATE_FILE not found."
    echo "       Please run test-case-III.sh first."
    exit 1
fi

# shellcheck source=/dev/null
source "$STATE_FILE"
echo "Loaded state: GROUP_ID=$GROUP_ID, DEVICE_ID=$DEVICE_ID, GROUP_NAME=$GROUP_NAME"

# Step 1: Confirm subscription exists before removal
echo ""
echo "--- Step 1: Confirm notification subscription exists for device $DEVICE_ID ---"
SUBSCRIPTIONS_BEFORE=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || echo "[]")
SUB_COUNT_BEFORE=$(echo "$SUBSCRIPTIONS_BEFORE" | jq -s 'length' 2>/dev/null || echo "0")

if [ "$SUB_COUNT_BEFORE" -gt 0 ]; then
    echo "OK: Found $SUB_COUNT_BEFORE subscription(s) before removal:"
    echo "$SUBSCRIPTIONS_BEFORE" | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || true
else
    echo "WARN: No notification2 subscriptions found for device $DEVICE_ID before removal."
    echo "      Checking dynamic mapper group subscriptions ..."
    c8y api \
        --method GET \
        --url "${DM_SERVICE}/subscription/group" \
        --output json 2>/dev/null | jq '.' || true
fi

# Step 2: Remove device from group
echo ""
echo "--- Step 2: Remove device $DEVICE_ID from group $GROUP_ID ('$GROUP_NAME') ---"
c8y inventory children unassign \
    --id "$GROUP_ID" \
    --child "$DEVICE_ID" \
    --childType asset \
    --force
echo "Device removed from group."

# Step 3: Wait for dynamic mapper to react
echo ""
echo "--- Step 3: Waiting ${REMOVAL_WAIT}s for dynamic mapper to process group change ---"
sleep "$REMOVAL_WAIT"

# Step 4: Verify subscription is deleted
echo ""
echo "--- Step 4: Verify dynamic subscription is deleted for device $DEVICE_ID ---"
SUBSCRIPTIONS_AFTER=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || echo "[]")
SUB_COUNT_AFTER=$(echo "$SUBSCRIPTIONS_AFTER" | jq -s 'length' 2>/dev/null || echo "0")

if [ "$SUB_COUNT_AFTER" -eq 0 ]; then
    echo "SUCCESS: No notification subscriptions found for device $DEVICE_ID after group removal."
else
    echo "FAIL: Still found $SUB_COUNT_AFTER subscription(s) for device $DEVICE_ID after group removal:"
    echo "$SUBSCRIPTIONS_AFTER" | jq -s '.' 2>/dev/null || true
fi

# Also check via dynamic mapper group subscription API
echo ""
echo "--- Group subscription status in dynamic mapper ---"
c8y api \
    --method GET \
    --url "${DM_SERVICE}/subscription/group" \
    --output json 2>/dev/null | jq '.' || true

echo ""
echo "=============================================="
echo " Test Case IV: DONE"
echo "=============================================="
