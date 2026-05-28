#!/bin/bash
#
# test-outbound-static-subscription: Outbound Static Device Subscription
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
DEVICE_NAME="test-static-device-01"
DEVICE_TYPE="test-static-type"
DEVICE_ID=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    dm_delete_static_subscription "$DEVICE_ID" "$SUBSCRIPTION_NAME"
    dm_delete_device "$DEVICE_ID"
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

dm_banner "Outbound Static Device Subscription"

# Step 1: Create device
dm_step 1 "Create test device"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
DEVICE_NAME_ACTUAL=$_DM_LAST_DEVICE_NAME

# Step 2: Create static subscription
dm_step 2 "Create static subscription"
dm_api POST /subscription \
    "{\"api\": \"MEASUREMENT\", \"devices\": [{\"id\": \"${DEVICE_ID}\", \"name\": \"${DEVICE_NAME_ACTUAL}\"}]}" \
    | jq '.'

# Step 3: Send a test measurement
dm_step 3 "Send test measurement for device $DEVICE_ID"
dm_send_measurement "$DEVICE_ID" "25.5"

# Step 4: Verify notification subscription exists
dm_step 4 "Verify notification subscription exists"
dm_wait 2 "allowing subscription propagation"
dm_show_subscriptions "$DEVICE_ID"
dm_assert_has_subscription "static subscription exists" "$DEVICE_ID"

if [ "$_DM_LAST_SUB_COUNT" -eq 0 ]; then
    dm_warn "Checking via dynamic mapper service API ..."
    dm_api GET "/subscription?subscription=${SUBSCRIPTION_NAME}" | jq '.'
fi

dm_print_summary
dm_done "Outbound Static Device Subscription"
