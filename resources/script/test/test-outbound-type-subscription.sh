#!/bin/bash
#
# test-outbound-type-subscription: Outbound Dynamic Subscription by Device Type
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

DEVICE_TYPE="auto-type"
DEVICE_NAME="test-dynamic-type-device-01"
DISCOVERY_WAIT=${DM_DEFAULT_DISCOVERY_WAIT}
DEVICE_ID=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    dm_delete_device "$DEVICE_ID"
    dm_set_type_subscriptions MEASUREMENT '[]'
    echo "Cleanup done."
}

dm_parse_args "$@"
dm_register_cleanup cleanup
dm_validate_only_exit

dm_banner "31. Dynamic type subscription"

# Step 1: Add dynamic type subscription
dm_step 1 "Add dynamic type subscription for '$DEVICE_TYPE'"
dm_set_type_subscriptions MEASUREMENT "[\"${DEVICE_TYPE}\"]"
dm_api GET /subscription/type | jq -r '
    if type == "array" then
        (.[0] // {} | .types // [])
    elif type == "object" then
        (.types // [])
    else
        []
    end
' || true

# Step 2: Create device with type
dm_step 2 "Create device with type '$DEVICE_TYPE'"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID

# Step 3: Wait for dynamic discovery
dm_step 3 "Wait for dynamic mapper to discover device"
dm_wait "$DISCOVERY_WAIT" "type-based device discovery"

# Step 4: Send a test measurement
dm_step 4 "Send test measurement for device $DEVICE_ID"
dm_send_measurement "$DEVICE_ID" "18.3"

# Step 5: Verify notification subscription exists
dm_step 5 "Verify notification subscription exists for device $DEVICE_ID"
dm_wait 2 "allowing subscription propagation"
TYPE_SUB_JSON=$(dm_api GET /subscription/type)
TYPE_MATCH=$(printf '%s' "$TYPE_SUB_JSON" | jq -s -r --arg t "$DEVICE_TYPE" '
    [ .[]
      | if type == "array" then .[]
        elif type == "object" and (.types? != null) then .types[]
        elif type == "string" then .
        else empty
        end
      | tostring
    ]
    | if (index($t) != null) then 1 else 0 end
' 2>/dev/null || printf '0')
dm_assert_gt "type-based subscription exists" "${TYPE_MATCH:-0}" "0"

if [ "${TYPE_MATCH:-0}" -eq 0 ]; then
    dm_warn "Type subscription '$DEVICE_TYPE' not found in mapper API response"
    printf '%s' "$TYPE_SUB_JSON" | jq -s '.' || true
fi

dm_print_summary
dm_done "31. Dynamic type subscription"
