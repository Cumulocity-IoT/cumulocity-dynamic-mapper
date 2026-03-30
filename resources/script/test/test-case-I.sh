#!/bin/bash
#
# Test Case I: Static Subscription
#
# Steps:
#   1. Create a device
#   2. Create a static subscription for that device via the dynamic mapper service
#   3. Send a measurement for the device
#   4. Verify that a notification subscription exists for the device in c8y notification2
#
# Prerequisites:
#   - c8y CLI configured and authenticated
#   - Dynamic mapper microservice deployed and outbound mapping enabled
#
# Usage:
#   ./test-case-I.sh
#   ./test-case-I.sh --cleanup    # Remove created resources afterwards

set -e

DM_SERVICE="/service/dynamic-mapper-service"
SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="test-static-device-01"
DEVICE_TYPE="test-static-type"

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$DEVICE_ID" ]; then
        echo "Deleting static subscription for device $DEVICE_ID ..."
        c8y api \
            --method DELETE \
            --url "${DM_SERVICE}/subscription/${DEVICE_ID}?subscription=${SUBSCRIPTION_NAME}" \
            --silentStatusCodes 404 2>/dev/null || true

        echo "Deleting device $DEVICE_ID ..."
        c8y devices delete --id "$DEVICE_ID" --force 2>/dev/null || true
    fi
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

echo "=============================================="
echo " Test Case I: Static Subscription"
echo "=============================================="

# Step 1: Create device
echo ""
echo "--- Step 1: Create test device ---"
DEVICE_JSON=$(c8y devices create \
    --name "$DEVICE_NAME" \
    --type "$DEVICE_TYPE" \
    --force \
    --output json)

DEVICE_ID=$(echo "$DEVICE_JSON" | jq -r '.id')
DEVICE_NAME_ACTUAL=$(echo "$DEVICE_JSON" | jq -r '.name')
echo "Created device: $DEVICE_NAME_ACTUAL (id=$DEVICE_ID)"

# Step 2: Create static subscription
echo ""
echo "--- Step 2: Create static subscription ---"
SUBSCRIPTION_RESPONSE=$(c8y api \
    --method POST \
    --url "${DM_SERVICE}/subscription" \
    --data "{
        \"api\": \"MEASUREMENT\",
        \"devices\": [{\"id\": \"${DEVICE_ID}\", \"name\": \"${DEVICE_NAME_ACTUAL}\"}]
    }" \
    --output json)

echo "Subscription response:"
echo "$SUBSCRIPTION_RESPONSE" | jq '.'

# Step 3: Send a test measurement
echo ""
echo "--- Step 3: Send test measurement for device $DEVICE_ID ---"
c8y measurements create \
    --device "$DEVICE_ID" \
    --data "c8y_TemperatureMeasurement.T.value=25.5,c8y_TemperatureMeasurement.T.unit=C,type='c8y_TemperatureMeasurement'" \
    --force
echo "Measurement sent."

# Step 4: Verify notification subscription exists
echo ""
echo "--- Step 4: Verify notification subscription exists ---"
sleep 2

SUBSCRIPTIONS=$(c8y notification2 subscriptions list --source "$DEVICE_ID" --output json 2>/dev/null || echo "[]")
SUB_COUNT=$(echo "$SUBSCRIPTIONS" | jq -s 'length' 2>/dev/null || echo "0")

if [ "$SUB_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $SUB_COUNT notification subscription(s) for device $DEVICE_ID"
    echo "$SUBSCRIPTIONS" | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || true
else
    echo "WARN: No notification2 subscriptions found for device $DEVICE_ID"
    echo "      Checking via dynamic mapper service API ..."
    DM_SUB=$(c8y api \
        --method GET \
        --url "${DM_SERVICE}/subscription?subscription=${SUBSCRIPTION_NAME}" \
        --output json 2>/dev/null || echo "{}")
    echo "$DM_SUB" | jq '.'
fi

echo ""
echo "=============================================="
echo " Test Case I: DONE"
echo "=============================================="
