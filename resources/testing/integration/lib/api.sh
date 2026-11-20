#!/bin/bash
#
# lib/api.sh — device helpers, Dynamic Mapper REST API helpers, Cumulocity data / inventory helpers
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── Device helpers ─────────────────────────────────────────────────────────────
_DM_LAST_DEVICE_ID=""
_DM_LAST_DEVICE_NAME=""

# Create a device.  Sets _DM_LAST_DEVICE_ID and _DM_LAST_DEVICE_NAME.
dm_create_device() {    # <name> <type>
    local _name=$1 _type=$2 _json
    # </dev/null prevents go-c8y-cli from entering pipeline mode when stdin is a
    # closed pipe (happens when called from run-tests.sh's while-read loop).
    _json=$(c8y devices create --name "$_name" --type "$_type" \
        --force --output json </dev/null 2>/dev/null || true)
    _DM_LAST_DEVICE_ID=$(printf '%s' "$_json" | jq -r '.id // empty' 2>/dev/null || printf '')
    _DM_LAST_DEVICE_NAME=$(printf '%s' "$_json" | jq -r '.name // empty' 2>/dev/null || printf "$_name")
    dm_info "Created device: ${_DM_LAST_DEVICE_NAME:-$_name} (id=$_DM_LAST_DEVICE_ID)"
}

# Delete a device; silently ignores missing devices.
dm_delete_device() {    # <id>
    local _id=$1
    [ -z "$_id" ] && return 0
    c8y devices delete --id "$_id" --force </dev/null 2>/dev/null || true
    dm_info "Deleted device: $_id"
}

# Send a single temperature measurement for a device.
dm_send_measurement() {     # <device_id> <temp_value> [unit=C]
    local _device=$1 _value=$2 _unit=${3:-C}
    c8y measurements create \
        --device "$_device" \
        --data "c8y_TemperatureMeasurement.T.value=${_value},c8y_TemperatureMeasurement.T.unit=${_unit},type='c8y_TemperatureMeasurement'" \
        --force </dev/null
    dm_info "Measurement sent (device=$_device, temp=${_value} ${_unit})"
}

# ── Dynamic Mapper API helpers ─────────────────────────────────────────────────

# Call the Dynamic Mapper REST API.
# Returns the JSON response, or '{}' on failure (error printed as warning).
#
# Two non-obvious requirements for c8y calls here:
#   - </dev/null : when a c8y call without --data runs inside a `while read` loop,
#     it inherits the loop's piped stdin. go-c8y-cli then enters "pipeline mode",
#     tries to read items from stdin, finds none ("something is not being
#     detected"), and SKIPS the request entirely (exit 0, silent no-op). Feeding
#     /dev/null keeps it out of pipeline mode so the request is always sent.
#   - --force : skip go-c8y-cli's confirmation prompt for destructive methods
#     (DELETE) in non-interactive runs. Harmless for GET/POST/PUT.
dm_api() {      # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '{}'
        fi
    else
        if _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '{}'
        fi
    fi
}

# Same as dm_api but returns '[]' on failure (useful for list endpoints).
dm_api_json_array() {   # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '[]'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '[]'
        fi
    else
        if _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --force \
                --output json </dev/null 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '[]'
        else
            [ -s "$_err" ] && dm_warn "dm_api ${_method} ${_path}: $(tr '\n' ' ' <"$_err" | head -c 400)"
            rm -f "$_err"
            printf '[]'
        fi
    fi
}

# Strict API call helper. Exits on any API error to avoid false-positive test progress.
dm_api_must() {     # <method> <path> [json_body]
    local _method=$1 _path=$2 _body=${3:-} _err _out
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        if printf '%s' "$_body" | grep -Eq '^[[:space:]]*\['; then
            if _out=$(printf '%s\n' "$_body" | c8y api --method "$_method" \
                    ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                    --template "input" \
                    --header 'Content-Type: application/json' \
                    --output json 2>"$_err"); then
                rm -f "$_err"
                [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
            else
                local _msg=""
                [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
                rm -f "$_err"
                dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
            fi
        elif _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --data "$_body" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            local _msg=""
            [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
            rm -f "$_err"
            dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
        fi
    else
        if _out=$(c8y api --method "$_method" \
                ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
                --header 'Content-Type: application/json' \
                --output json 2>"$_err"); then
            rm -f "$_err"
            [ -n "$_out" ] && printf '%s\n' "$_out" || printf '{}'
        else
            local _msg=""
            [ -s "$_err" ] && _msg="$(tr '\n' ' ' <"$_err" | head -c 500)"
            rm -f "$_err"
            dm_error "dm_api_must ${_method} ${_path} failed${_msg:+: $_msg}"
        fi
    fi
}

# Assert that a request is REJECTED (non-2xx, so c8y exits non-zero). The error text is kept in
# _DM_LAST_REJECTION for diagnostics. Unlike dm_api this does not swallow the failure, which is
# the whole point: the request must not succeed.
_DM_LAST_REJECTION=""
dm_assert_rejected() {   # <label> <method> <path> [json_body]
    local _label=$1 _method=$2 _path=$3 _body=${4:-} _err _rc=0
    _err=$(mktemp)
    if [ -n "$_body" ]; then
        c8y api --method "$_method" ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" --data "$_body" \
            --header 'Content-Type: application/json' --force --output json </dev/null >/dev/null 2>"$_err" || _rc=$?
    else
        c8y api --method "$_method" ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}${_path}" \
            --force --output json </dev/null >/dev/null 2>"$_err" || _rc=$?
    fi
    _DM_LAST_REJECTION=$(tr '\n' ' ' <"$_err" | head -c 300)
    rm -f "$_err"
    if [ "$_rc" -ne 0 ]; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[${_label}] rejected (${_DM_LAST_REJECTION:-exit $_rc})"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[${_label}] request unexpectedly succeeded"
    fi
}

# ── C8Y data helpers ───────────────────────────────────────────────────────────
# Return a UTC ISO-8601 timestamp. Pass a negative offset in seconds for the past.
# Examples: dm_now          -> now
#           dm_now -120     -> 2 minutes ago
dm_now() {  # [offset_seconds]
    local _offset=${1:-0}
    if [ "$_offset" -lt 0 ] 2>/dev/null; then
        date -u -v"${_offset}S" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null \
            || date -u -d "$(( -1 * _offset )) seconds ago" +"%Y-%m-%dT%H:%M:%SZ"
    else
        date -u +"%Y-%m-%dT%H:%M:%SZ"
    fi
}

# Look up the C8Y internal device id by external id / type.
dm_lookup_device_by_ext_id() {  # <externalId> <externalIdType>
    c8y identity get \
    --name "$1" --type "$2" \
        --output json 2>/dev/null \
    | jq -r '.managedObject.id // empty' 2>/dev/null \
    | head -n 1 || printf ''
}

dm_count_measurements_since() {     # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y measurements list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].measurements // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_count_events_since() {   # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y events list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].events // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_count_alarms_since() {   # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y alarms list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].alarms // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_count_operations_since() {   # <device_id> <since_iso8601>
    [ -z "${1:-}" ] && { printf '0'; return 0; }
    c8y operations list \
        --device "$1" --dateFrom "$2" \
        --pageSize 200 --output json 2>/dev/null \
        | jq -s '
            def rows:
                if length == 0 then
                    []
                elif length == 1 then
                    if (.[0] | type) == "array" then
                        .[0]
                    elif (.[0] | type) == "object" then
                        (.[0].data // .[0].operations // [.[0]])
                    else
                        []
                    end
                else
                    .
                end;
            rows | length
        ' 2>/dev/null || printf '0'
}

dm_assert_measurement_count_gt() {  # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_measurements_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

dm_assert_event_count_gt() {    # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_events_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

dm_assert_alarm_count_gt() {    # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_alarms_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

dm_assert_operation_count_gt() {    # <label> <device_id> <since_iso8601> <min_count>
    local _label=$1 _device=$2 _since=$3 _min=$4 _count
    _count=$(dm_count_operations_since "$_device" "$_since")
    dm_assert_gt "$_label" "${_count:-0}" "$(( _min - 1 ))"
}

_DM_LAST_DEVICE_ID=""

dm_wait_for_device_by_ext_id() {  # <ext_id> <ext_id_type> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _timeout=${3:-30} _interval=${4:-2}
    local _elapsed=0 _devid
    _DM_LAST_DEVICE_ID=""
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _DM_LAST_DEVICE_ID="$_devid"
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

# Poll until a device (resolved by external id) has at least <min_count>
# measurements, or until <timeout> seconds elapse. Returns 0 on success, 1 on
# timeout. Tolerates the device not existing yet (mapper may create it lazily).
dm_wait_for_measurement_count() {  # <ext_id> <ext_id_type> <min_count> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _min=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _devid _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _count=$(c8y measurements list --device "$_devid" \
                --pageSize 200 --output json 2>/dev/null \
                | jq -s 'length' 2>/dev/null || printf '0')
            [ "${_count:-0}" -ge "$_min" ] 2>/dev/null && return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

dm_wait_for_event_count() {  # <ext_id> <ext_id_type> <min_count> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _min=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _devid _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _count=$(c8y events list --device "$_devid" \
                --pageSize 200 --output json 2>/dev/null \
                | jq -s 'length' 2>/dev/null || printf '0')
            [ "${_count:-0}" -ge "$_min" ] 2>/dev/null && return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

dm_wait_for_alarm_count() {  # <ext_id> <ext_id_type> <min_count> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _min=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _devid _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _count=$(c8y alarms list --device "$_devid" \
                --pageSize 200 --output json 2>/dev/null \
                | jq -s 'length' 2>/dev/null || printf '0')
            [ "${_count:-0}" -ge "$_min" ] 2>/dev/null && return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

dm_wait_for_operation_count() {  # <ext_id> <ext_id_type> <min_count> [timeout=30] [interval=2]
    local _extid=$1 _type=$2 _min=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _devid _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _devid=$(dm_lookup_device_by_ext_id "$_extid" "$_type")
        if [ -n "$_devid" ]; then
            _count=$(c8y operations list --device "$_devid" \
                --pageSize 200 --output json 2>/dev/null \
                | jq -s 'length' 2>/dev/null || printf '0')
            [ "${_count:-0}" -ge "$_min" ] 2>/dev/null && return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

# Poll for a measurement and record a pass/fail assertion (for use with
# dm_print_summary). Wraps dm_wait_for_measurement_count.
dm_assert_measurement_present() {  # <label> <ext_id> <ext_id_type> [min=1] [timeout=30]
    local _label=$1 _extid=$2 _type=$3 _min=${4:-1} _timeout=${5:-30}
    if dm_wait_for_measurement_count "$_extid" "$_type" "$_min" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] >= $_min measurement(s) found"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] no measurement found after ${_timeout}s"
    fi
}

dm_assert_event_present() {  # <label> <ext_id> <ext_id_type> [min=1] [timeout=30]
    local _label=$1 _extid=$2 _type=$3 _min=${4:-1} _timeout=${5:-30}
    if dm_wait_for_event_count "$_extid" "$_type" "$_min" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] >= $_min event(s) found"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] no event found after ${_timeout}s"
    fi
}

dm_assert_alarm_present() {  # <label> <ext_id> <ext_id_type> [min=1] [timeout=30]
    local _label=$1 _extid=$2 _type=$3 _min=${4:-1} _timeout=${5:-30}
    if dm_wait_for_alarm_count "$_extid" "$_type" "$_min" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] >= $_min alarm(s) found"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] no alarm found after ${_timeout}s"
    fi
}

dm_assert_operation_present() {  # <label> <ext_id> <ext_id_type> [min=1] [timeout=30]
    local _label=$1 _extid=$2 _type=$3 _min=${4:-1} _timeout=${5:-30}
    if dm_wait_for_operation_count "$_extid" "$_type" "$_min" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] >= $_min operation(s) found"
    else
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] no operation found after ${_timeout}s"
    fi
}

# Poll until a mapping has processed more messages than baseline, or timeout.
# Useful for verifying that a connector accepted and processed messages after
# deployment/activation. Returns 0 on success, 1 on timeout.
dm_wait_for_mapping_processing() {  # <mapping_id> <baseline_count> [timeout=15] [interval=1]
    local _mapping_id=$1 _baseline=${2:-0} _timeout=${3:-15} _interval=${4:-1}
    local _elapsed=0 _count
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _count=$(dm_mapping_received_count "$_mapping_id")
        if [ "${_count:-0}" -gt "$_baseline" ]; then
            return 0
        fi
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

dm_get_latest_measurement() {  # <ext_id> <ext_id_type> <measurement_type>
    local _extid=$1 _extidtype=$2 _meastype=$3
    local _device_id _resp _row
    [ -z "$_extid" ] && { printf '{}'; return 0; }
    _device_id=$(dm_lookup_device_by_ext_id "$_extid" "$_extidtype")
    [ -z "$_device_id" ] && { printf '{}'; return 0; }

    # Prefer a deterministic single-record fetch from the measurements REST API.
    _resp=$(c8y api --method GET \
        --url "/measurement/measurements?source=${_device_id}&type=${_meastype}&pageSize=1&revert=true" \
        --output json 2>/dev/null || printf '{}')
    _row=$(printf '%s' "$_resp" | jq -c '
        if (.measurements // null) != null and ((.measurements | type) == "array") then
            (.measurements[0] // {})
        elif (.data // null) != null and ((.data | type) == "array") then
            (.data[0] // {})
        elif type == "array" then
            (.[0] // {})
        elif type == "object" then
            .
        else
            {}
        end
    ' 2>/dev/null || printf '{}')

    # Fallback for environments where query handling differs.
    if [ "$_row" = "{}" ]; then
        _row=$(c8y measurements list \
            --device "$_device_id" \
            --type "$_meastype" \
            --pageSize 1 --output json 2>/dev/null \
            | jq -s '
                def rows:
                    if length == 0 then
                        []
                    elif length == 1 then
                        if (.[0] | type) == "array" then
                            .[0]
                        elif (.[0] | type) == "object" then
                            (.[0].data // .[0].measurements // [.[0]])
                        else
                            []
                        end
                    else
                        .
                    end;
                (rows[0] // {})
            ' 2>/dev/null || printf '{}')
    fi

    printf '%s\n' "$_row"
}

# ── Inventory / managed-object helpers ─────────────────────────────────────────
# Used by INVENTORY-targetAPI tests that modify device *metadata* (the managed
# object itself: name, type, custom fragments) rather than creating
# measurements/events/alarms.

# Fetch a device's full managed object as JSON ({} if the id is empty/not found).
dm_get_managed_object() {  # <device_id>
    [ -z "${1:-}" ] && { printf '{}'; return 0; }
    c8y inventory get --id "$1" --output json 2>/dev/null \
        | jq -c '.' 2>/dev/null || printf '{}'
}

# Read a single field from a device's managed object using a jq filter.
# The filter is applied to the managed-object root, e.g. '.type' or
# '.dmtest_Meta.site'. Prints the raw value (empty string if absent).
dm_get_mo_field() {  # <device_id> <jq_filter>
    local _id=$1 _filter=$2
    [ -z "${_id:-}" ] && { printf ''; return 0; }
    dm_get_managed_object "$_id" \
        | jq -r "(${_filter}) // empty" 2>/dev/null || printf ''
}

# Assert a managed-object field equals an expected value (records pass/fail).
dm_assert_mo_field_eq() {  # <label> <device_id> <jq_filter> <expected>
    local _label=$1 _id=$2 _filter=$3 _expected=$4 _actual
    _actual=$(dm_get_mo_field "$_id" "$_filter")
    dm_assert_eq "$_label" "$_expected" "$_actual"
}

# Poll until a managed-object field reaches the expected value, or timeout.
# Returns 0 on match, 1 on timeout. Useful because inventory updates triggered
# by a mapping are eventually-consistent.
dm_wait_for_mo_field() {  # <device_id> <jq_filter> <expected> [timeout=30] [interval=2]
    local _id=$1 _filter=$2 _expected=$3 _timeout=${4:-30} _interval=${5:-2}
    local _elapsed=0 _actual
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _actual=$(dm_get_mo_field "$_id" "$_filter")
        [ "$_actual" = "$_expected" ] && return 0
        sleep "$_interval"
        _elapsed=$((_elapsed + _interval))
    done
    return 1
}

# Poll for an expected managed-object field value and record a pass/fail
# assertion (wraps dm_wait_for_mo_field). Prefer this over dm_assert_mo_field_eq
# when the value is set asynchronously by the mapper.
dm_assert_mo_field_eventually() {  # <label> <device_id> <jq_filter> <expected> [timeout=30]
    local _label=$1 _id=$2 _filter=$3 _expected=$4 _timeout=${5:-30} _actual
    if dm_wait_for_mo_field "$_id" "$_filter" "$_expected" "$_timeout"; then
        _DM_PASS_COUNT=$((_DM_PASS_COUNT + 1))
        dm_success "[$_label] ${_filter}='$_expected'"
    else
        _actual=$(dm_get_mo_field "$_id" "$_filter")
        _DM_FAIL_COUNT=$((_DM_FAIL_COUNT + 1))
        dm_fail "[$_label] ${_filter} expected='$_expected' actual='$_actual' after ${_timeout}s"
    fi
}
