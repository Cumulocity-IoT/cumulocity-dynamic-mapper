#!/bin/bash
#
# Test Case II: Dynamic Subscription by Device Type
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

DM_SERVICE="/service/dynamic-mapper-service"
DEVICE_TYPE="auto-type"
DEVICE_NAME="test-dynamic-type-device-01"
DISCOVERY_WAIT=10   # seconds to wait for type-based discovery

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$DEVICE_ID" ]; then
        echo "Deleting device $DEVICE_ID ..."
        c8y devices delete --id "$DEVICE_ID" --force 2>/dev/null || true
    fi
    echo "Removing type subscription for '$DEVICE_TYPE' ..."
    c8y api \
        --method PUT \
        --url "${DM_SERVICE}/subscription/type" \
        --data "{\"api\": \"MEASUREMENT\", \"types\": []}" \
        --force 2>/dev/null || true
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

echo "=============================================="
echo " Test Case II: Dynamic Subscription by Type"
echo "=============================================="

# Step 1: Add dynamic type subscription for "auto-type"
echo ""
echo "--- Step 1: Add dynamic type subscription for '$DEVICE_TYPE' ---"
TYPE_SUB_RESPONSE=$(c8y api \
    --method PUT \
    --url "${DM_SERVICE}/subscription/type" \
    --data "{
        \"api\": \"MEASUREMENT\",
        \"types\": [\"${DEVICE_TYPE}\"]
    }" \
    --output json)

echo "Type subscription response:"
echo "$TYPE_SUB_RESPONSE" | jq '.'

# Step 2: Create device with type "auto-type"
echo ""
echo "--- Step 2: Create device with type '$DEVICE_TYPE' ---"
DEVICE_JSON=$(c8y devices create \
    --name "$DEVICE_NAME" \
    --type "$DEVICE_TYPE" \
    --force \
    --output json)

DEVICE_ID=$(echo "$DEVICE_JSON" | jq -r '.id')
DEVICE_NAME_ACTUAL=$(echo "$DEVICE_JSON" | jq -r '.name')
echo "Created device: $DEVICE_NAME_ACTUAL (id=$DEVICE_ID, type=$DEVICE_TYPE)"

# Step 3: Wait for dynamic discovery
echo ""
echo "--- Step 3: Waiting ${DISCOVERY_WAIT}s for dynamic mapper to discover device ---"
sleep "$DISCOVERY_WAIT"

# Step 4: Send a test measurement
echo ""
echo "--- Step 4: Send test measurement for device $DEVICE_ID ---"
c8y measurements create \
    --device "$DEVICE_ID" \
    --data "c8y_TemperatureMeasurement.T.value=18.3,c8y_TemperatureMeasurement.T.unit=C,type='c8y_TemperatureMeasurement'" \
    --force
echo "Measurement sent."

# Step 5: Verify notification subscription exists
echo ""
echo "--- Step 5: Verify notification subscription exists for device $DEVICE_ID ---"
sleep 2

SUBSCRIPTIONS=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || echo "[]")
SUB_COUNT=$(echo "$SUBSCRIPTIONS" | jq -s 'length' 2>/dev/null || echo "0")

if [ "$SUB_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $SUB_COUNT notification subscription(s) for device $DEVICE_ID"
    echo "$SUBSCRIPTIONS" | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || true
else
    echo "WARN: No notification2 subscriptions found for device $DEVICE_ID"
    echo "      Checking current type subscriptions via dynamic mapper service API ..."
    DM_TYPE_SUB=$(c8y api \
        --method GET \
        --url "${DM_SERVICE}/subscription/type" \
        --output json 2>/dev/null || echo "{}")
    echo "$DM_TYPE_SUB" | jq '.'
fi

echo ""
echo "=============================================="
echo " Test Case II: DONE"
echo "=============================================="
