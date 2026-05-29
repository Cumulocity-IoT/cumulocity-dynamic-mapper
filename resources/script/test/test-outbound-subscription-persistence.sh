#!/bin/bash
#
# test-outbound-subscription-persistence: Subscriptions Survive Microservice Restart
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

DM_MICROSERVICE_NAME="dynamic-mapper-service"
STATIC_DEVICE_NAME="test-restart-static-device"
STATIC_DEVICE_TYPE="test-restart-static"
DYNAMIC_DEVICE_NAME="test-restart-dynamic-device"
DYNAMIC_DEVICE_TYPE="auto-restart-type"
STATIC_SUBSCRIPTION_NAME="DynamicMapperStaticDeviceSubscription"
STARTUP_WAIT=${DM_DEFAULT_STARTUP_WAIT}
DISCOVERY_WAIT=15
STATIC_DEVICE_ID=""
DYNAMIC_DEVICE_ID=""

cleanup() {
    echo ""
    echo "=== Cleanup ==="
    dm_delete_static_subscription "$STATIC_DEVICE_ID" "$STATIC_SUBSCRIPTION_NAME"
    dm_delete_device "$STATIC_DEVICE_ID"
    dm_delete_device "$DYNAMIC_DEVICE_ID"
    dm_set_type_subscriptions MEASUREMENT '[]'
    echo "Cleanup done."
}

if [ "${1}" = "--cleanup" ]; then
    trap cleanup EXIT
fi

dm_banner "Outbound Subscription Persistence After Restart"

# Step 1: Create static device and subscription
dm_step 1 "Set up static subscription"
dm_create_device "$STATIC_DEVICE_NAME" "$STATIC_DEVICE_TYPE"
STATIC_DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_create_static_subscription_must "MEASUREMENT" "$STATIC_DEVICE_ID" "$STATIC_DEVICE_NAME"
dm_info "Static subscription created."

# Step 2: Create dynamic type subscription and device
dm_step 2 "Set up dynamic type subscription for '$DYNAMIC_DEVICE_TYPE'"
dm_set_type_subscriptions MEASUREMENT "[\"${DYNAMIC_DEVICE_TYPE}\"]"
dm_create_device "$DYNAMIC_DEVICE_NAME" "$DYNAMIC_DEVICE_TYPE"
DYNAMIC_DEVICE_ID=$_DM_LAST_DEVICE_ID
dm_wait "$DISCOVERY_WAIT" "dynamic device discovery"

# Step 3: Record subscriptions before restart
dm_step 3 "Record subscriptions before restart"
dm_info "Static device ($STATIC_DEVICE_ID):"
dm_show_subscriptions "$STATIC_DEVICE_ID"
dm_info "Dynamic device ($DYNAMIC_DEVICE_ID):"
dm_show_subscriptions "$DYNAMIC_DEVICE_ID"
dm_info "Type subscriptions in dynamic mapper:"
dm_api GET /subscription/type | jq -r '
    if type == "array" then
        (.[0] // {} | .types // [])
    elif type == "object" then
        (.types // [])
    else
        []
    end
' || true

# Step 4: Restart the microservice
dm_step 4 "Restart microservice by disabling and re-enabling"
dm_info "Disabling microservice '$DM_MICROSERVICE_NAME' ..."
c8y microservices disable --id "$DM_MICROSERVICE_NAME" --force
dm_wait 5 "service shutdown"
dm_info "Re-enabling microservice '$DM_MICROSERVICE_NAME' ..."
c8y microservices enable --id "$DM_MICROSERVICE_NAME" --force

# Step 5: Wait for the service to come back up
dm_step 5 "Wait for microservice to restart and initialize"
dm_wait "$STARTUP_WAIT" "initial startup period"
dm_wait_for_service

# Step 6: Send test messages for both devices
dm_step 6 "Send test measurements after restart"
dm_send_measurement "$STATIC_DEVICE_ID"  "30.0"
dm_send_measurement "$DYNAMIC_DEVICE_ID" "31.0"

# Step 7: Verify subscriptions are still active
dm_step 7 "Verify subscriptions still active after restart"
dm_wait 2 "allowing subscription propagation"

dm_info "Checking static subscription (device $STATIC_DEVICE_ID):"
STATIC_SUB_JSON=$(dm_api GET "/subscription?subscription=${STATIC_SUBSCRIPTION_NAME}")
STATIC_MATCH_COUNT=$(printf '%s' "$STATIC_SUB_JSON" | jq -s -r --arg did "$STATIC_DEVICE_ID" '
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
dm_assert_gt "static subscription survives restart" "${STATIC_MATCH_COUNT:-0}" "0"
if [ "${STATIC_MATCH_COUNT:-0}" -eq 0 ]; then
    dm_warn "Static subscription not found in mapper API response for device $STATIC_DEVICE_ID"
    printf '%s' "$STATIC_SUB_JSON" | jq -s '.' || true
fi

dm_info "Checking dynamic subscription (device $DYNAMIC_DEVICE_ID):"
TYPE_SUB_JSON=$(dm_api GET /subscription/type)
TYPE_MATCH=$(printf '%s' "$TYPE_SUB_JSON" | jq -s -r --arg t "$DYNAMIC_DEVICE_TYPE" '
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
dm_assert_gt "dynamic subscription survives restart" "${TYPE_MATCH:-0}" "0"
if [ "${TYPE_MATCH:-0}" -eq 0 ]; then
    dm_warn "Type subscription '$DYNAMIC_DEVICE_TYPE' not found in mapper API response"
    printf '%s' "$TYPE_SUB_JSON" | jq -s '.' || true
fi

dm_print_summary
dm_done "Outbound Subscription Persistence After Restart"
