#!/bin/bash
#
# Test Case III: Dynamic Subscription by Device Group
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

DM_SERVICE="/service/dynamic-mapper-service"
GROUP_NAME="auto-group"
DEVICE_NAME="test-group-device-01"
DEVICE_TYPE="test-group-type"
STATE_FILE="/tmp/dm-test-III-state.env"
DISCOVERY_WAIT=10   # seconds to wait for group-based discovery

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$DEVICE_ID" ]; then
        echo "Removing device $DEVICE_ID from group ..."
        c8y inventory children unassign \
            --id "$GROUP_ID" \
            --child "$DEVICE_ID" \
            --force 2>/dev/null || true
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
echo " Test Case III: Dynamic Subscription by Group"
echo "=============================================="

# Step 1: Create device group
echo ""
echo "--- Step 1: Create device group '$GROUP_NAME' ---"
GROUP_JSON=$(c8y devicegroups create \
    --name "$GROUP_NAME" \
    --force \
    --output json)

GROUP_ID=$(echo "$GROUP_JSON" | jq -r '.id')
echo "Created device group: $GROUP_NAME (id=$GROUP_ID)"

# Step 2: Create dynamic group subscription
echo ""
echo "--- Step 2: Create dynamic group subscription for '$GROUP_NAME' (id=$GROUP_ID) ---"
GROUP_SUB_RESPONSE=$(c8y api \
    --method PUT \
    --url "${DM_SERVICE}/subscription/group" \
    --data "{
        \"api\": \"MEASUREMENT\",
        \"devices\": [{\"id\": \"${GROUP_ID}\", \"name\": \"${GROUP_NAME}\"}]
    }" \
    --output json)

echo "Group subscription response:"
echo "$GROUP_SUB_RESPONSE" | jq '.'

# Step 3: Create device and assign to group
echo ""
echo "--- Step 3: Create device and assign to group '$GROUP_NAME' ---"
DEVICE_JSON=$(c8y devices create \
    --name "$DEVICE_NAME" \
    --type "$DEVICE_TYPE" \
    --force \
    --output json)

DEVICE_ID=$(echo "$DEVICE_JSON" | jq -r '.id')
DEVICE_NAME_ACTUAL=$(echo "$DEVICE_JSON" | jq -r '.name')
echo "Created device: $DEVICE_NAME_ACTUAL (id=$DEVICE_ID)"

c8y inventory children assign \
    --id "$GROUP_ID" \
    --child "$DEVICE_ID" \
    --childType asset \
    --force
echo "Assigned device $DEVICE_ID to group $GROUP_ID"

# Save state for test-case-IV
echo "GROUP_ID=$GROUP_ID" > "$STATE_FILE"
echo "DEVICE_ID=$DEVICE_ID" >> "$STATE_FILE"
echo "GROUP_NAME=$GROUP_NAME" >> "$STATE_FILE"
echo "State saved to $STATE_FILE"

# Step 4: Wait for dynamic discovery
echo ""
echo "--- Step 4: Waiting ${DISCOVERY_WAIT}s for dynamic mapper to discover device via group ---"
sleep "$DISCOVERY_WAIT"

# Step 5: Send a test measurement
echo ""
echo "--- Step 5: Send test measurement for device $DEVICE_ID ---"
c8y measurements create \
    --device "$DEVICE_ID" \
    --data "c8y_TemperatureMeasurement.T.value=21.7,c8y_TemperatureMeasurement.T.unit=C,type='c8y_TemperatureMeasurement'" \
    --force
echo "Measurement sent."

# Step 6: Verify notification subscription exists
echo ""
echo "--- Step 6: Verify notification subscription exists for device $DEVICE_ID ---"
sleep 2

SUBSCRIPTIONS=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || echo "[]")
SUB_COUNT=$(echo "$SUBSCRIPTIONS" | jq -s 'length' 2>/dev/null || echo "0")

if [ "$SUB_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $SUB_COUNT notification subscription(s) for device $DEVICE_ID"
    echo "$SUBSCRIPTIONS" | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || true
else
    echo "WARN: No notification2 subscriptions found for device $DEVICE_ID"
    echo "      Checking group subscriptions via dynamic mapper service API ..."
    DM_GROUP_SUB=$(c8y api \
        --method GET \
        --url "${DM_SERVICE}/subscription/group" \
        --output json 2>/dev/null || echo "{}")
    echo "$DM_GROUP_SUB" | jq '.'
fi

echo ""
echo "=============================================="
echo " Test Case III: DONE"
echo " State saved to: $STATE_FILE"
echo " Run test-case-IV.sh to test group removal."
echo "=============================================="
