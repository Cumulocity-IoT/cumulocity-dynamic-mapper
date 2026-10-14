#!/bin/bash
#
# test-outbound-type-subscription-resync: backfill of pre-existing devices into a type subscription
#
# A type subscription only catches devices created AFTER the type filter exists
# (Notification 2.0 does not replay history). POST /subscription/type/resync/{type}
# backfills devices that already existed. Steps:
#   1. create a device of type T            (before the type filter)
#   2. add T to the type subscription       -> device must NOT be subscribed yet
#   3. POST /subscription/type/resync/T     -> 202, device becomes subscribed
#   4. resync again                         -> idempotent (no duplicate subscriptions)
#   5. resync for a type that is not configured -> rejected (400)
#
# Prerequisites: outbound mapping enabled; c8y CLI authenticated.
#
# Usage: ./test-outbound-type-subscription-resync.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="38. Type subscription resync (backfill)"

DEVICE_TYPE="dmtest-resync-type-$$"
UNCONFIGURED_TYPE="dmtest-unconfigured-type-$$"
DEVICE_NAME="dmtest-resync-device-$$"
DEVICE_ID=""
ORIGINAL_TYPES='[]'

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$DEVICE_ID" ] && dm_delete_device "$DEVICE_ID" || true
    # Restore the type list that existed before the test (read-modify-write was done at start).
    _DM_TYPE_SUB_VERIFY=false dm_set_type_subscriptions MEASUREMENT "$ORIGINAL_TYPES" || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_validate_only_exit

ORIGINAL_TYPES=$(_dm_type_subscription_types_json)

dm_step 2 "Creating device of type '$DEVICE_TYPE' BEFORE the type filter exists"
dm_create_device "$DEVICE_NAME" "$DEVICE_TYPE"
DEVICE_ID=$_DM_LAST_DEVICE_ID
[ -n "$DEVICE_ID" ] || dm_error "Device was not created"

dm_step 3 "Adding '$DEVICE_TYPE' to the type subscription (keeping existing types)"
NEW_TYPES=$(printf '%s' "$ORIGINAL_TYPES" | jq -c --arg t "$DEVICE_TYPE" '. + [$t] | unique')
dm_set_type_subscriptions MEASUREMENT "$NEW_TYPES"
dm_wait "$DM_DEFAULT_DISCOVERY_WAIT" "give discovery a chance to (wrongly) pick the device up"
dm_assert_eq "Pre-existing device is NOT subscribed by the type filter alone" "0" "$(dm_device_subscription_count "$DEVICE_ID")"

dm_step 4 "POST /subscription/type/resync/$DEVICE_TYPE"
c8y api --method POST --url "${DM_SERVICE}/subscription/type/resync/${DEVICE_TYPE}" \
    --force --output json </dev/null >/dev/null \
    || dm_error "Resync request was rejected"
COUNT_AFTER_FIRST=0
for _i in $(seq 1 20); do
    COUNT_AFTER_FIRST=$(dm_device_subscription_count "$DEVICE_ID")
    [ "${COUNT_AFTER_FIRST:-0}" -gt 0 ] && break
    sleep 3
done
dm_assert_gt "Pre-existing device subscribed after resync" "${COUNT_AFTER_FIRST:-0}" 0
dm_info "Subscriptions: $(dm_device_subscriptions_json "$DEVICE_ID")"

dm_step 5 "Resync again — must be idempotent"
c8y api --method POST --url "${DM_SERVICE}/subscription/type/resync/${DEVICE_TYPE}" \
    --force --output json </dev/null >/dev/null \
    || dm_error "Second resync request was rejected"
dm_wait 15 "second backfill"
dm_assert_eq "Subscription count unchanged by second resync" "$COUNT_AFTER_FIRST" "$(dm_device_subscription_count "$DEVICE_ID")"

dm_step 6 "Resync for a type that is not configured must be rejected"
if c8y api --method POST --url "${DM_SERVICE}/subscription/type/resync/${UNCONFIGURED_TYPE}" \
        --force --output json </dev/null >/dev/null 2>&1; then
    _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
    dm_fail "[Unconfigured type rejected] request unexpectedly succeeded"
else
    _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
    dm_success "[Unconfigured type rejected]"
fi

dm_done "$TEST_TITLE"
dm_print_summary
