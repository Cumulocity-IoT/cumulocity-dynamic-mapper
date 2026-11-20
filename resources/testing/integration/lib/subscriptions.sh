#!/bin/bash
#
# lib/subscriptions.sh — static, type and group subscription helpers
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── Subscription helpers ───────────────────────────────────────────────────────
_DM_LAST_SUB_COUNT=0

# Count notification2 subscriptions for a device.
# Sets _DM_LAST_SUB_COUNT and prints the raw subscription list JSON.
dm_count_subscriptions() {  # <device_id>
    # Raw REST via dm_device_subscriptions_json: the c8y CLI list rendering (array vs. NDJSON vs.
    # bare object) made the count come out as 0 whenever a device had more than one subscription.
    local _subs
    _subs=$(dm_device_subscriptions_json "$1")
    _DM_LAST_SUB_COUNT=$(printf '%s' "$_subs" | jq -r 'length' 2>/dev/null || printf '0')
    printf '%s' "$_subs"
}

# Print a summary line listing subscription names for a device.
dm_show_subscriptions() {   # <device_id>
    dm_count_subscriptions "$1" \
        | jq -r '
            if type == "array" then
                .[] | (.subscription // .subscriptionName // .id // empty)
            elif type == "object" then
                (.subscriptions // .data // [])[] | (.subscription // .subscriptionName // .id // empty)
            else
                empty
            end
        ' 2>/dev/null \
        || dm_warn "Could not retrieve subscription names for device $1"
}

# Assert that at least one notification2 subscription exists for <device_id>.
dm_assert_has_subscription() {  # <label> <device_id>
    dm_count_subscriptions "$2" >/dev/null
    dm_assert_gt "$1" "$_DM_LAST_SUB_COUNT" "0"
}

# Assert that NO notification2 subscription exists for <device_id>.
dm_assert_no_subscription() {   # <label> <device_id>
    dm_count_subscriptions "$2" >/dev/null
    dm_assert_eq_zero "$1" "$_DM_LAST_SUB_COUNT"
}

# Wait until at least one notification2 subscription exists for <device_id>.
dm_wait_for_subscription_present() {  # <device_id> [timeout_secs=30] [interval_secs=1]
    local _device=$1 _timeout=${2:-30} _interval=${3:-1} _elapsed=0
    while [ "$_elapsed" -lt "$_timeout" ]; do
        dm_count_subscriptions "$_device" >/dev/null
        if [ "${_DM_LAST_SUB_COUNT:-0}" -gt 0 ]; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    dm_count_subscriptions "$_device" >/dev/null
    return 1
}

# Wait until no notification2 subscriptions exist for <device_id>.
# Current entry count of a mapper cache (INVENTORY_CACHE | INBOUND_ID_CACHE | OUTBOUND_ID_CACHE),
# the same figure the Monitoring > Cache statistic page shows. Outputs -1 when it cannot be read.
dm_cache_size() {   # <cache_id>
    dm_api GET "/cache?cacheId=$1" | jq -r 'if type == "number" then . else -1 end' 2>/dev/null \
        || printf -- '-1'
}

dm_wait_for_subscription_absent() {  # <device_id> [timeout_secs=30] [interval_secs=1]
    local _device=$1 _timeout=${2:-30} _interval=${3:-1} _elapsed=0
    while [ "$_elapsed" -lt "$_timeout" ]; do
        dm_count_subscriptions "$_device" >/dev/null
        if [ "${_DM_LAST_SUB_COUNT:-0}" -eq 0 ]; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    dm_count_subscriptions "$_device" >/dev/null
    return 1
}

# Create a static subscription for a single device and fail hard on API errors.
dm_create_static_subscription_must() {  # <api> <device_id> <device_name>
    local _api=$1 _id=$2 _name=$3
    dm_api_must POST /subscription \
        "{\"api\": \"${_api}\", \"devices\": [{\"id\": \"${_id}\", \"name\": \"${_name}\"}]}" >/dev/null
    dm_info "Created static subscription (api=${_api}, device=${_id})"
}

# Convert a notification2 subscriptions response to a unique JSON array of names.
dm_subscription_names_json() {  # <subscriptions_json>
    printf '%s' "$1" | jq -c '
        def rows:
          if type == "array" then .
          elif type == "object" then
            if ((.subscriptions // null) != null and ((.subscriptions | type) == "array")) then .subscriptions
            elif ((.data // null) != null and ((.data | type) == "array")) then .data
            elif ((.subscription // .subscriptionName // .id // empty) | tostring | length) > 0 then [.]
            else [] end
          else [] end;
        rows
        | map(.subscription // .subscriptionName // .id // empty)
        | map(select(length > 0))
        | unique
    ' 2>/dev/null || printf '[]'
}

_DM_LAST_SUBSCRIPTION_NAME=""

# Create a static subscription and best-effort resolve its created name for cleanup.
# Sets _DM_LAST_SUBSCRIPTION_NAME. Do NOT call via $() — dm_info/dm_warn write to stdout
# and would be captured instead of appearing on the terminal. Read $_DM_LAST_SUBSCRIPTION_NAME
# directly after the call.
dm_create_static_subscription_resolve_name() {  # <api> <device_id> <device_name> [propagation_wait_secs=5]
    local _api=$1 _id=$2 _name=$3 _wait=${4:-5}
    local _before_subs _after_subs _before_names_json _after_names_json _resolved

    _before_subs=$(c8y notification2 subscriptions list --source "$_id" --output json 2>/dev/null || printf '[]')
    _before_names_json=$(dm_subscription_names_json "$_before_subs")

    dm_create_static_subscription_must "$_api" "$_id" "$_name"
    dm_wait "$_wait" "for subscription propagation"

    _after_subs=$(c8y notification2 subscriptions list --source "$_id" --output json 2>/dev/null || printf '[]')
    _after_names_json=$(dm_subscription_names_json "$_after_subs")

    _resolved=$(jq -nr \
        --argjson before "$_before_names_json" \
        --argjson after  "$_after_names_json" \
        '($after - $before | .[0]) // ($after[0] // "")')

    _DM_LAST_SUBSCRIPTION_NAME="${_resolved:-}"

    if [ -z "${_DM_LAST_SUBSCRIPTION_NAME:-}" ]; then
        dm_warn "Could not resolve created static subscription name; cleanup may skip explicit subscription deletion."
    else
        dm_info "Resolved static subscription name for cleanup: $_DM_LAST_SUBSCRIPTION_NAME"
    fi
}

# Delete a named static subscription for a device; silently ignores errors.
dm_delete_static_subscription() {  # <device_id> <subscription_name>
    local _id=$1 _name=$2 _encoded_name
    [ -z "$_id" ] && return 0
    _encoded_name=$(jq -rn --arg n "$_name" '$n|@uri')
    dm_api DELETE "/subscription/${_id}?subscription=${_encoded_name}" >/dev/null 2>&1 || true
    dm_info "Deleted static subscription for device $_id (name=$_name)"
}

# Read the configured type subscriptions as a sorted, compact JSON array.
#
# GET /subscription/type has three shapes in the wild: the documented object
# ({"types":[...]}), a bare array, and a plain string. It also answers {} when the
# tenant's management subscription exists but carries no type filter — the state that
# made "0 is not > 0" look like a discovery failure rather than a filter that had not
# landed yet. All of them normalise to an array here.
_dm_type_subscription_types_json() {
    dm_api GET /subscription/type | jq -cs '
        [ .[]
          | if type == "array" then .[]
            elif type == "object" and (.types? != null) then .types[]
            elif type == "string" then .
            else empty
            end
          | tostring
        ] | sort' 2>/dev/null || printf '[]'
}

# Print what the mapper and C8Y each think the type subscription is.
_dm_dump_type_subscription_diagnostics() {  # <reason>
    local _raw _nsr
    dm_warn "Type subscription: $1"
    _raw=$(dm_api GET /subscription/type | jq -cs '.' 2>/dev/null || printf '[]')
    dm_warn "Mapper GET /subscription/type: ${_raw}"
    _nsr=$(c8y api --method GET \
            --url "/notification2/subscriptions?subscription=DynamicMapperManagementSubscription&context=tenant" \
            --force --raw </dev/null 2>/dev/null \
        | jq -c '[.subscriptions[]? | {id, context, subscription, subscriptionFilter}]' 2>/dev/null || printf '[]')
    dm_warn "C8Y notification2 management subscription: ${_nsr}"
}

# Overwrite the type-subscription list for a given C8Y API.
# Pass '[]' to clear all type subscriptions.
# Example: dm_set_type_subscriptions MEASUREMENT '["auto-type"]'
#
# The PUT is verified by reading the list back, because the write is not immediately
# visible: the mapper deletes the existing management subscription and creates a new one
# carrying the type filter, and a GET issued right after can still answer with the
# pre-delete state. Polling here means a filter that never landed fails at the point of
# cause instead of surfacing later as "no subscription for the device".
#
# Set _DM_TYPE_SUB_VERIFY=false to warn instead of aborting — for cleanup paths, which
# must not die half-way through a trap.
dm_set_type_subscriptions() {   # <api> <types_json_array>
    local _api=$1 _types=$2
    dm_api_must PUT /subscription/type \
        "{\"api\": \"${_api}\", \"types\": ${_types}}" >/dev/null
    dm_info "Set type subscriptions (api=${_api}): ${_types}"

    local _want _got _elapsed=0 _timeout=15
    _want=$(printf '%s' "$_types" | jq -c 'map(tostring) | sort' 2>/dev/null || printf '[]')
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _got=$(_dm_type_subscription_types_json)
        if [ "$_got" = "$_want" ]; then
            dm_success "Type subscriptions confirmed: ${_got}"
            return 0
        fi
        sleep 2
        _elapsed=$((_elapsed + 2))
    done

    if [ "${_DM_TYPE_SUB_VERIFY:-true}" = "false" ]; then
        dm_warn "Type subscriptions did not read back as ${_want} (got ${_got:-[]}) — ignored on this path"
        return 0
    fi
    _dm_dump_type_subscription_diagnostics "PUT ${_want} did not stick within ${_timeout}s (read back ${_got:-[]})"
    dm_error "Type subscription list did not take effect: expected ${_want}, got ${_got:-[]}"
}

# Wait until <type> appears in the configured type subscriptions. Returns 1 on timeout.
dm_wait_for_type_subscription() {   # <type> [timeout=20] [interval=2]
    local _type=$1 _timeout=${2:-20} _interval=${3:-2}
    local _elapsed=0 _types
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _types=$(_dm_type_subscription_types_json)
        if printf '%s' "$_types" | jq -e --arg t "$_type" 'index($t) != null' >/dev/null 2>&1; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

# Counted assertion: <type> is among the configured type subscriptions.
dm_assert_type_subscription_present() {   # <label> <type> [timeout=20]
    local _label=$1 _type=$2 _timeout=${3:-20}
    if dm_wait_for_type_subscription "$_type" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] type '$_type' is subscribed"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] type '$_type' not subscribed after ${_timeout}s"
        _dm_dump_type_subscription_diagnostics "type '${_type}' missing after ${_timeout}s"
    fi
}
