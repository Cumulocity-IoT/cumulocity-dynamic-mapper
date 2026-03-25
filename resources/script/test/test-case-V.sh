#!/bin/bash
#
# Test Case V: Microservice Restart — Verify Static and Dynamic Subscriptions Survive
#
# Steps:
#   1. Create a static subscription for a test device (if not already present)
#   2. Create a dynamic type subscription for type "auto-restart-type" (if not already present)
#   3. Record the current subscriptions (static + dynamic)
#   4. Trigger a microservice restart by unsubscribing and re-subscribing the microservice
#   5. Wait for the service to come back up
#   6. Send test messages for both devices
#   7. Verify that both static and dynamic subscriptions are still active
#
# Prerequisites:
#   - c8y CLI configured and authenticated
#   - Dynamic mapper microservice deployed and outbound mapping enabled
#   - The c8y user must have microservice admin permissions
#
# Usage:
#   ./test-case-V.sh
#   ./test-case-V.sh --cleanup    # Remove created resources afterwards

set -e

DM_SERVICE="/service/dynamic-mapper-service"
DM_MICROSERVICE_NAME="dynamic-mapper-service"
STATIC_DEVICE_NAME="test-restart-static-device"
STATIC_DEVICE_TYPE="test-restart-static"
DYNAMIC_DEVICE_NAME="test-restart-dynamic-device"
DYNAMIC_DEVICE_TYPE="auto-restart-type"
STATIC_SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
STARTUP_WAIT=30   # seconds to wait for microservice to restart
DISCOVERY_WAIT=10 # seconds to wait for dynamic discovery after restart

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    if [ -n "$STATIC_DEVICE_ID" ]; then
        echo "Deleting static subscription for device $STATIC_DEVICE_ID ..."
        c8y api \
            --method DELETE \
            --url "${DM_SERVICE}/subscription/${STATIC_DEVICE_ID}?subscription=${STATIC_SUBSCRIPTION_NAME}" \
            --silentStatusCodes 404 2>/dev/null || true
        echo "Deleting static device $STATIC_DEVICE_ID ..."
        c8y devices delete --id "$STATIC_DEVICE_ID" --force 2>/dev/null || true
    fi
    if [ -n "$DYNAMIC_DEVICE_ID" ]; then
        echo "Deleting dynamic device $DYNAMIC_DEVICE_ID ..."
        c8y devices delete --id "$DYNAMIC_DEVICE_ID" --force 2>/dev/null || true
    fi
    echo "Removing type subscription for '$DYNAMIC_DEVICE_TYPE' ..."
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
echo " Test Case V: Subscriptions Survive Restart"
echo "=============================================="

# Step 1: Create static device and subscription
echo ""
echo "--- Step 1: Set up static subscription ---"
STATIC_DEVICE_JSON=$(c8y devices create \
    --name "$STATIC_DEVICE_NAME" \
    --type "$STATIC_DEVICE_TYPE" \
    --force \
    --output json)

STATIC_DEVICE_ID=$(echo "$STATIC_DEVICE_JSON" | jq -r '.id')
echo "Created static device: $STATIC_DEVICE_NAME (id=$STATIC_DEVICE_ID)"

c8y api \
    --method POST \
    --url "${DM_SERVICE}/subscription" \
    --data "{
        \"api\": \"MEASUREMENT\",
        \"devices\": [{\"id\": \"${STATIC_DEVICE_ID}\", \"name\": \"${STATIC_DEVICE_NAME}\"}]
    }" \
    --output json | jq '.status // "submitted"'
echo "Static subscription created."

# Step 2: Create dynamic type subscription
echo ""
echo "--- Step 2: Set up dynamic type subscription for '$DYNAMIC_DEVICE_TYPE' ---"
c8y api \
    --method PUT \
    --url "${DM_SERVICE}/subscription/type" \
    --data "{
        \"api\": \"MEASUREMENT\",
        \"types\": [\"${DYNAMIC_DEVICE_TYPE}\"]
    }" \
    --output json | jq '.types // "submitted"'
echo "Type subscription created."

DYNAMIC_DEVICE_JSON=$(c8y devices create \
    --name "$DYNAMIC_DEVICE_NAME" \
    --type "$DYNAMIC_DEVICE_TYPE" \
    --force \
    --output json)

DYNAMIC_DEVICE_ID=$(echo "$DYNAMIC_DEVICE_JSON" | jq -r '.id')
echo "Created dynamic device: $DYNAMIC_DEVICE_NAME (id=$DYNAMIC_DEVICE_ID)"

echo "Waiting ${DISCOVERY_WAIT}s for dynamic discovery ..."
sleep "$DISCOVERY_WAIT"

# Step 3: Record subscriptions before restart
echo ""
echo "--- Step 3: Record subscriptions before restart ---"
echo "Static subscription:"
c8y notification2 subscriptions list --source "$STATIC_DEVICE_ID" --output json 2>/dev/null \
    | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || echo "(none found)"

echo "Dynamic subscription:"
c8y notification2 subscriptions list --source "$DYNAMIC_DEVICE_ID" --output json 2>/dev/null \
    | jq -s '.[].subscription // .[].subscriptionName // .[].id' 2>/dev/null || echo "(none found)"

echo "Type subscriptions in dynamic mapper:"
c8y api --method GET --url "${DM_SERVICE}/subscription/type" --output json 2>/dev/null | jq '.types // []' || true

# Step 4: Restart the microservice (unsubscribe + subscribe)
echo ""
echo "--- Step 4: Restart microservice by unsubscribing and re-subscribing ---"
echo "Unsubscribing microservice '$DM_MICROSERVICE_NAME' ..."
c8y microservices unsubscribe --id "$DM_MICROSERVICE_NAME" --force
echo "Waiting 5s ..."
sleep 5

echo "Re-subscribing microservice '$DM_MICROSERVICE_NAME' ..."
c8y microservices subscribe --id "$DM_MICROSERVICE_NAME" --force

# Step 5: Wait for the service to come back up
echo ""
echo "--- Step 5: Waiting ${STARTUP_WAIT}s for microservice to restart and initialize ---"
sleep "$STARTUP_WAIT"

# Poll until the service responds
MAX_RETRIES=12
RETRY_INTERVAL=5
for i in $(seq 1 $MAX_RETRIES); do
    STATUS_CODE=$(c8y api --method GET --url "${DM_SERVICE}/health" --output json 2>/dev/null \
        | jq -r '.status // "DOWN"' 2>/dev/null || echo "DOWN")
    if [ "$STATUS_CODE" = "UP" ]; then
        echo "Microservice is UP."
        break
    fi
    echo "  Attempt $i/$MAX_RETRIES: service not ready yet (status=$STATUS_CODE), retrying in ${RETRY_INTERVAL}s ..."
    sleep "$RETRY_INTERVAL"
done

# Step 6: Send test messages for both devices
echo ""
echo "--- Step 6: Send test measurements after restart ---"
c8y measurements create \
    --device "$STATIC_DEVICE_ID" \
    --data "c8y_TemperatureMeasurement.T.value=30.0,c8y_TemperatureMeasurement.T.unit=C,type='c8y_TemperatureMeasurement'" \
    --force
echo "Measurement sent for static device $STATIC_DEVICE_ID."

c8y measurements create \
    --device "$DYNAMIC_DEVICE_ID" \
    --data "c8y_TemperatureMeasurement.T.value=31.0,c8y_TemperatureMeasurement.T.unit=C,type='c8y_TemperatureMeasurement'" \
    --force
echo "Measurement sent for dynamic device $DYNAMIC_DEVICE_ID."

# Step 7: Verify subscriptions are still active
echo ""
echo "--- Step 7: Verify subscriptions still active after restart ---"
sleep 2

# Check static subscription
STATIC_SUBS_AFTER=$(c8y notification2 subscriptions list --source "$STATIC_DEVICE_ID" --output json 2>/dev/null || echo "[]")
STATIC_COUNT_AFTER=$(echo "$STATIC_SUBS_AFTER" | jq -s 'length' 2>/dev/null || echo "0")
if [ "$STATIC_COUNT_AFTER" -gt 0 ]; then
    echo "SUCCESS: Static subscription still active for device $STATIC_DEVICE_ID ($STATIC_COUNT_AFTER subscription(s))"
else
    echo "FAIL: Static subscription missing for device $STATIC_DEVICE_ID after restart!"
    echo "      Checking static subscriptions via dynamic mapper API ..."
    c8y api --method GET \
        --url "${DM_SERVICE}/subscription?subscription=${STATIC_SUBSCRIPTION_NAME}" \
        --output json 2>/dev/null | jq '.' || true
fi

# Check dynamic subscription
DYNAMIC_SUBS_AFTER=$(c8y notification2 subscriptions list --source "$DYNAMIC_DEVICE_ID" --output json 2>/dev/null || echo "[]")
DYNAMIC_COUNT_AFTER=$(echo "$DYNAMIC_SUBS_AFTER" | jq -s 'length' 2>/dev/null || echo "0")
if [ "$DYNAMIC_COUNT_AFTER" -gt 0 ]; then
    echo "SUCCESS: Dynamic subscription still active for device $DYNAMIC_DEVICE_ID ($DYNAMIC_COUNT_AFTER subscription(s))"
else
    echo "FAIL: Dynamic subscription missing for device $DYNAMIC_DEVICE_ID after restart!"
    echo "      Checking type subscriptions via dynamic mapper API ..."
    c8y api --method GET --url "${DM_SERVICE}/subscription/type" --output json 2>/dev/null | jq '.' || true
fi

echo ""
echo "=============================================="
echo " Test Case V: DONE"
echo "=============================================="
