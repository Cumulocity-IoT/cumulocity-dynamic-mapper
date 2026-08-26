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

TEST_TITLE="32. Static subscription management"

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

dm_parse_args "$@"
dm_register_cleanup cleanup
dm_validate_only_exit

dm_banner "$TEST_TITLE"

# Step 1: Create device
dm_step 1 "Create test device"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
DEVICE_NAME_ACTUAL=$_DM_LAST_DEVICE_NAME

# Step 2: Create static subscription
dm_step 2 "Create static subscription"
dm_create_static_subscription_must "MEASUREMENT" "$DEVICE_ID" "$DEVICE_NAME_ACTUAL"

# Step 3: Send a test measurement
dm_step 3 "Send test measurement for device $DEVICE_ID"
dm_send_measurement "$DEVICE_ID" "25.5"

# Step 4: Verify notification subscription exists
dm_step 4 "Verify notification subscription exists"
dm_wait 2 "allowing subscription propagation"
STATIC_SUB_JSON=$(dm_api GET "/subscription?subscription=${SUBSCRIPTION_NAME}")
STATIC_MATCH_COUNT=$(printf '%s' "$STATIC_SUB_JSON" | jq -s -r --arg did "$DEVICE_ID" '
    [ .[]
      | if type == "array" then .[]
        elif type == "object" and (.devices? != null) then .devices[]
        elif type == "object" then .
        else empty
        end
    ]
    | map(select((.id | tostring) == $did))
    | length
' 2>/dev/null || printf '0')
dm_assert_gt "static subscription exists" "${STATIC_MATCH_COUNT:-0}" "0"

if [ "${STATIC_MATCH_COUNT:-0}" -eq 0 ]; then
    dm_warn "Static subscription not found in mapper API response for device $DEVICE_ID"
    printf '%s' "$STATIC_SUB_JSON" | jq -s '.' || true
fi

dm_print_summary
dm_done "$TEST_TITLE"
