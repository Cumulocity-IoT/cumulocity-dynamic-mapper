#!/bin/bash
#
# test-outbound-group-subscription-desired-state: PUT /subscription/group is a desired-state update
#
# The endpoint takes the FULL set of subscribed groups; the backend diffs it against the current
# state. Removing a group must unsubscribe its devices, except those that are still a reason to
# stay subscribed: a member of another subscribed group, or matching a configured type. Steps:
#   1. create groups A and B and four devices
#        dev-a   in A only
#        dev-b   in B only
#        dev-ab  in A and B
#        dev-t   in B only, but of a type that has a type subscription
#   2. PUT [A, B]  -> existing members of both groups get subscribed
#   3. PUT [A]     -> dev-b unsubscribed; dev-a, dev-ab (still in A) and dev-t (type) keep theirs
#   4. PUT []      -> an empty list is valid and clears everything group-based; dev-t stays (type)
#   5. PUT without a device list -> rejected (a missing list is not the same as an empty one)
#
# Prerequisites: outbound mapping enabled; c8y CLI authenticated.
#
# Usage: ./test-outbound-group-subscription-desired-state.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Group subscription — desired-state update"

GROUP_A_NAME="dmtest-gds-group-a-$$"
GROUP_B_NAME="dmtest-gds-group-b-$$"
TYPE_T="dmtest-gds-type-$$"
GROUP_A_ID=""
GROUP_B_ID=""
DEV_A=""; DEV_B=""; DEV_AB=""; DEV_T=""
ORIGINAL_TYPES='[]'
WAIT_TIMEOUT=60

cleanup() {
    dm_info "Cleaning up ..."
    dm_api PUT /subscription/group '{"api":"MEASUREMENT","devices":[]}' >/dev/null 2>&1 || true
    _DM_TYPE_SUB_VERIFY=false dm_set_type_subscriptions MEASUREMENT "$ORIGINAL_TYPES" || true
    local _d
    for _d in "$DEV_A" "$DEV_B" "$DEV_AB" "$DEV_T"; do
        [ -n "$_d" ] && dm_delete_device "$_d" || true
    done
    for _g in "$GROUP_A_ID" "$GROUP_B_ID"; do
        [ -n "$_g" ] && c8y devicegroups delete --id "$_g" --force </dev/null 2>/dev/null || true
    done
}

# ── Local helpers ──────────────────────────────────────────────────────────────

new_device() {   # <name> <type> — prints the device id
    dm_create_device "$1" "$2" >&2
    printf '%s' "$_DM_LAST_DEVICE_ID"
}

assign() {   # <group_id> <device_id>
    c8y inventory children assign --id "$1" --child "$2" --childType asset --force </dev/null >/dev/null
}

# PUT the desired set of groups; prints the response JSON. Arguments are group ids.
put_groups() {
    local _devices='[]' _g
    for _g in "$@"; do
        _devices=$(printf '%s' "$_devices" | jq -c --arg id "$_g" '. + [{id: $id, name: ("group-" + $id)}]')
    done
    dm_api_must PUT /subscription/group "{\"api\":\"MEASUREMENT\",\"devices\":${_devices}}"
}

# Number of times <group_id> appears in GET /subscription/group (0 or 1).
group_subscribed() {   # <group_id>
    dm_api GET /subscription/group | jq -s -r --arg gid "$1" '
        [ .[] | if type == "array" then .[] elif type == "object" then . else empty end
          | select((.id | tostring) == $gid) ] | length' 2>/dev/null || printf '0'
}

# Group membership is expressed by the dynamic device subscription. Devices can carry other
# subscriptions that group removal deliberately leaves alone — notably
# DynamicMapperCacheInventorySubscription, created when the device enters the inventory cache and
# removed only when the cache evicts it — so count only the dynamic one, not every subscription.
DYNAMIC_SUBSCRIPTION="DynamicMapperDynamicDeviceSubscription"

dynamic_sub_count() {   # <device_id>
    dm_device_subscriptions_json "$1" \
        | jq -r --arg s "$DYNAMIC_SUBSCRIPTION" '[ .[] | select(.subscription == $s) ] | length' 2>/dev/null \
        || printf '0'
}

# Poll until dynamic_sub_count satisfies the condition; sets _LAST_DYNAMIC_COUNT.
_LAST_DYNAMIC_COUNT=0
wait_for_dynamic_subs() {   # <device_id> <present|absent>
    local _elapsed=0
    while [ "$_elapsed" -lt "$WAIT_TIMEOUT" ]; do
        _LAST_DYNAMIC_COUNT=$(dynamic_sub_count "$1")
        if [ "$2" = present ] && [ "$_LAST_DYNAMIC_COUNT" -gt 0 ]; then return 0; fi
        if [ "$2" = absent ] && [ "$_LAST_DYNAMIC_COUNT" -eq 0 ]; then return 0; fi
        sleep 3
        _elapsed=$((_elapsed + 3))
    done
    _LAST_DYNAMIC_COUNT=$(dynamic_sub_count "$1")
    return 1
}

assert_subscribed() {   # <label> <device_id>
    wait_for_dynamic_subs "$2" present || true
    dm_assert_gt "$1" "$_LAST_DYNAMIC_COUNT" "0"
}

assert_unsubscribed() {   # <label> <device_id>
    wait_for_dynamic_subs "$2" absent || true
    dm_assert_eq_zero "$1" "$_LAST_DYNAMIC_COUNT"
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_validate_only_exit

dm_step 2 "Creating groups A and B and the four devices"
GROUP_A_ID=$(c8y devicegroups create --name "$GROUP_A_NAME" --force --output json </dev/null | jq -r '.id')
GROUP_B_ID=$(c8y devicegroups create --name "$GROUP_B_NAME" --force --output json </dev/null | jq -r '.id')
[ -n "$GROUP_A_ID" ] && [ -n "$GROUP_B_ID" ] || dm_error "Groups were not created"
dm_info "Groups: A=$GROUP_A_ID B=$GROUP_B_ID"

DEV_A=$(new_device  "dmtest-gds-dev-a-$$"  "dmtest-gds-plain")
DEV_B=$(new_device  "dmtest-gds-dev-b-$$"  "dmtest-gds-plain")
DEV_AB=$(new_device "dmtest-gds-dev-ab-$$" "dmtest-gds-plain")
DEV_T=$(new_device  "dmtest-gds-dev-t-$$"  "$TYPE_T")
for _d in "$DEV_A" "$DEV_B" "$DEV_AB" "$DEV_T"; do
    [ -n "$_d" ] || dm_error "A device was not created"
done

assign "$GROUP_A_ID" "$DEV_A"
assign "$GROUP_A_ID" "$DEV_AB"
assign "$GROUP_B_ID" "$DEV_B"
assign "$GROUP_B_ID" "$DEV_AB"
assign "$GROUP_B_ID" "$DEV_T"

ORIGINAL_TYPES=$(_dm_type_subscription_types_json)
dm_set_type_subscriptions MEASUREMENT "$(printf '%s' "$ORIGINAL_TYPES" | jq -c --arg t "$TYPE_T" '. + [$t] | unique')"

dm_step 3 "PUT [A, B] subscribes the existing members of both groups"
RESPONSE=$(put_groups "$GROUP_A_ID" "$GROUP_B_ID")
dm_assert_eq "No device reported as failed" "0" "$(printf '%s' "$RESPONSE" | jq '(.failedDeviceIds // []) | length')"
assert_subscribed "dev-a subscribed"  "$DEV_A"
assert_subscribed "dev-b subscribed"  "$DEV_B"
assert_subscribed "dev-ab subscribed" "$DEV_AB"
assert_subscribed "dev-t subscribed"  "$DEV_T"
dm_assert_eq "Group A listed" "1" "$(group_subscribed "$GROUP_A_ID")"
dm_assert_eq "Group B listed" "1" "$(group_subscribed "$GROUP_B_ID")"

dm_step 4 "PUT [A] removes B: only devices with no remaining reason lose their subscription"
put_groups "$GROUP_A_ID" >/dev/null
assert_unsubscribed "dev-b (only in the removed group) unsubscribed" "$DEV_B"
dm_assert_eq "Group B no longer listed" "0" "$(group_subscribed "$GROUP_B_ID")"
dm_assert_eq "Group A still listed" "1" "$(group_subscribed "$GROUP_A_ID")"
dm_assert_gt "dev-a keeps its subscription" "$(dynamic_sub_count "$DEV_A")" "0"
dm_assert_gt "dev-ab keeps its subscription (still in group A)" "$(dynamic_sub_count "$DEV_AB")" "0"
dm_assert_gt "dev-t keeps its subscription (covered by its type)" "$(dynamic_sub_count "$DEV_T")" "0"

dm_step 5 "PUT [] is valid and clears all group subscriptions"
put_groups >/dev/null
assert_unsubscribed "dev-a unsubscribed"  "$DEV_A"
assert_unsubscribed "dev-ab unsubscribed" "$DEV_AB"
dm_assert_eq "Group A no longer listed" "0" "$(group_subscribed "$GROUP_A_ID")"
dm_assert_gt "dev-t still subscribed (type subscription remains)" "$(dynamic_sub_count "$DEV_T")" "0"

dm_step 6 "A request without a device list is rejected"
dm_assert_rejected "Missing devices field" PUT /subscription/group '{"api":"MEASUREMENT"}'

dm_done "$TEST_TITLE"
dm_print_summary
