#!/bin/bash
#
# test-outbound-type-subscription-removal: dropping a type from the filter unsubscribes its devices
#
# PUT /subscription/type takes the full list of types. A type that disappears from it must also
# lose the subscriptions of its existing devices (done in the background) — unless a device is
# still a member of a subscribed group, which remains a reason to stay subscribed. Steps:
#   1. create devices
#        dev-t   of type T (to be removed)
#        dev-tg  of type T, member of a subscribed group
#        dev-o   of type O (stays configured)
#   2. configure types [T, O], resync both, subscribe the group
#        -> all three devices are subscribed
#   3. PUT types [O]
#        -> dev-t loses its subscription; dev-tg (group) and dev-o (type O) keep theirs
#   4. PUT types [] -> dev-o loses its subscription too; dev-tg still has its group
#
# Prerequisites: outbound mapping enabled; c8y CLI authenticated.
#
# Usage: ./test-outbound-type-subscription-removal.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Type subscription — removal unsubscribes devices"

TYPE_T="dmtest-tsr-removed-$$"
TYPE_O="dmtest-tsr-kept-$$"
GROUP_ID=""
DEV_T=""; DEV_TG=""; DEV_O=""
ORIGINAL_TYPES='[]'
WAIT_TIMEOUT=60

cleanup() {
    dm_info "Cleaning up ..."
    dm_api PUT /subscription/group '{"api":"MEASUREMENT","devices":[]}' >/dev/null 2>&1 || true
    _DM_TYPE_SUB_VERIFY=false dm_set_type_subscriptions MEASUREMENT "$ORIGINAL_TYPES" || true
    local _d
    for _d in "$DEV_T" "$DEV_TG" "$DEV_O"; do
        [ -n "$_d" ] && dm_delete_device "$_d" || true
    done
    [ -n "$GROUP_ID" ] && c8y devicegroups delete --id "$GROUP_ID" --force </dev/null 2>/dev/null || true
}

# ── Local helpers ──────────────────────────────────────────────────────────────

new_device() {   # <name> <type> — prints the device id
    dm_create_device "$1" "$2" >&2
    printf '%s' "$_DM_LAST_DEVICE_ID"
}

resync() {   # <type>
    c8y api --method POST --url "${DM_SERVICE}/subscription/type/resync/$1" \
        --force --output json </dev/null >/dev/null \
        || dm_error "Resync of type $1 was rejected"
}

assert_subscribed() {   # <label> <device_id>
    if dm_wait_for_subscription_present "$2" "$WAIT_TIMEOUT" 3; then
        dm_assert_gt "$1" "$_DM_LAST_SUB_COUNT" "0"
    else
        dm_assert_gt "$1" "0" "0"
    fi
}

assert_unsubscribed() {   # <label> <device_id>
    if dm_wait_for_subscription_absent "$2" "$WAIT_TIMEOUT" 3; then
        dm_assert_eq_zero "$1" "0"
    else
        dm_assert_eq_zero "$1" "$_DM_LAST_SUB_COUNT"
    fi
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_validate_tools
dm_wait_for_service
dm_validate_only_exit

ORIGINAL_TYPES=$(_dm_type_subscription_types_json)

dm_step 2 "Creating devices and a group"
GROUP_ID=$(c8y devicegroups create --name "dmtest-tsr-group-$$" --force --output json </dev/null | jq -r '.id')
[ -n "$GROUP_ID" ] || dm_error "Group was not created"

DEV_T=$(new_device  "dmtest-tsr-dev-t-$$"  "$TYPE_T")
DEV_TG=$(new_device "dmtest-tsr-dev-tg-$$" "$TYPE_T")
DEV_O=$(new_device  "dmtest-tsr-dev-o-$$"  "$TYPE_O")
for _d in "$DEV_T" "$DEV_TG" "$DEV_O"; do
    [ -n "$_d" ] || dm_error "A device was not created"
done
c8y inventory children assign --id "$GROUP_ID" --child "$DEV_TG" --childType asset --force </dev/null >/dev/null

dm_step 3 "Configuring types [T, O], resyncing both and subscribing the group"
dm_set_type_subscriptions MEASUREMENT \
    "$(printf '%s' "$ORIGINAL_TYPES" | jq -c --arg t "$TYPE_T" --arg o "$TYPE_O" '. + [$t, $o] | unique')"
resync "$TYPE_T"
resync "$TYPE_O"
dm_api_must PUT /subscription/group \
    "{\"api\":\"MEASUREMENT\",\"devices\":[{\"id\":\"${GROUP_ID}\",\"name\":\"dmtest-tsr-group-$$\"}]}" >/dev/null
assert_subscribed "dev-t subscribed"  "$DEV_T"
assert_subscribed "dev-tg subscribed" "$DEV_TG"
assert_subscribed "dev-o subscribed"  "$DEV_O"

dm_step 4 "Removing type T from the filter"
dm_set_type_subscriptions MEASUREMENT \
    "$(printf '%s' "$ORIGINAL_TYPES" | jq -c --arg o "$TYPE_O" '. + [$o] | unique')"
assert_unsubscribed "dev-t (type removed, no other reason) unsubscribed" "$DEV_T"
dm_assert_gt "dev-tg keeps its subscription (still in a subscribed group)" "$(dm_device_subscription_count "$DEV_TG")" "0"
dm_assert_gt "dev-o keeps its subscription (type still configured)" "$(dm_device_subscription_count "$DEV_O")" "0"

dm_step 5 "Clearing the type filter"
dm_set_type_subscriptions MEASUREMENT "$ORIGINAL_TYPES"
assert_unsubscribed "dev-o unsubscribed" "$DEV_O"
dm_assert_gt "dev-tg still subscribed through its group" "$(dm_device_subscription_count "$DEV_TG")" "0"

dm_done "$TEST_TITLE"
dm_print_summary
