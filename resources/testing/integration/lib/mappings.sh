#!/bin/bash
#
# lib/mappings.sh — Smart Function code helpers and mapping lifecycle / deployment helpers
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── Smart Function helpers ────────────────────────────────────────────────────
_DM_SUPPORT_ESM=""

# Read supportESM from the tenant service configuration.
# Prints 'true' or 'false'. Falls back to 'false' when unavailable.
dm_get_support_esm() {
    if [ -n "${_DM_SUPPORT_ESM:-}" ]; then
        printf '%s\n' "$_DM_SUPPORT_ESM"
        return 0
    fi

    local _cfg _esm
    _cfg=$(dm_api GET /configuration/service)
    _esm=$(printf '%s' "$_cfg" | jq -r '.supportESM // false' 2>/dev/null || printf 'false')

    case "$_esm" in
        true|false) _DM_SUPPORT_ESM="$_esm" ;;
        *) _DM_SUPPORT_ESM="false" ;;
    esac

    printf '%s\n' "$_DM_SUPPORT_ESM"
}

# Wrap Smart Function source code for runtime mode.
# In ESM mode we require explicit export.
dm_wrap_onmessage_code() { # <code_without_export>
    local _base_code=$1
    if [ "$(dm_get_support_esm)" = "true" ]; then
        printf '%s\n\nexport { onMessage };\n' "$_base_code"
    else
        printf '%s\n' "$_base_code"
    fi
}

# ── Mapping helpers ────────────────────────────────────────────────────────────
_DM_LAST_MAPPING_ID=""
_DM_MQTT_CONNECTOR_ID=""  # Will be set by dm_require_mqtt_broker

# Resolve a processor extension class/name to a full extension entry payload.
# Output: JSON object containing extensionName, eventName, fqnClassName, extensionType, direction
dm_resolve_extension_entry() {   # <processor_extension_name_or_fqn> [direction]
    local _needle=${1:-}
    local _direction=${2:-}
    local _all _entry

    [ -z "$_needle" ] && return 1
    _all=$(dm_api GET /extension)

    _entry=$(printf '%s' "$_all" | jq -cer --arg n "$_needle" --arg d "$_direction" '
        to_entries
        | map(
            .key as $extName
            | (.value.extensionEntries // {})
            | to_entries
            | map(
                .value
                | . + { extensionName: $extName }
            )
        )
        | add // []
        | map(
            select(
                (.extensionName == $n)
                or (.eventName == $n)
                or (.fqnClassName == $n)
                or ((.fqnClassName // "") | endswith("." + $n))
            )
            | if ($d == "") then . else select((.direction // "") == $d) end
        )
        | .[0]
        | {
            extensionName: .extensionName,
            eventName: .eventName,
            fqnClassName: .fqnClassName,
            extensionType: .extensionType,
            direction: .direction
        }
    ' 2>/dev/null || printf '')

    [ -n "$_entry" ] && printf '%s\n' "$_entry"
}

# Require a processor extension to be registered on the tenant, resolving it by
# eventName/fqn (NOT by a hardcoded extensionName — that is assigned at upload
# time and is environment-specific). On success stores the resolved entry in
# _DM_RESOLVED_EXTENSION; build the mapping's `extension` field from it via:
#     jq -cn --argjson extension "$_DM_RESOLVED_EXTENSION" '{ ..., extension: $extension }'
# On absence the test exits with DM_EXIT_SKIP (default 42), which run-tests.sh
# classifies as SKIP (not PASS). These tests need the dynamic-mapper-extension
# JAR uploaded to the tenant.
_DM_RESOLVED_EXTENSION=""
dm_require_extension() {   # <eventName_or_fqn> [direction]
    local _needle=$1 _direction=${2:-} _entry _evt
    _entry=$(dm_resolve_extension_entry "$_needle" "$_direction" 2>/dev/null || true)
    _evt=$(printf '%s' "$_entry" | jq -r '.eventName // empty' 2>/dev/null || printf '')
    if [ -z "$_entry" ] || [ -z "$_evt" ]; then
        dm_skip_exit "Processor extension '${_needle}'${_direction:+ (${_direction})} is not registered on this tenant." \
                     "Upload the dynamic-mapper-extension JAR (see docs/extensions.md), then re-run."
    fi
    _DM_RESOLVED_EXTENSION="$_entry"
    dm_info "Resolved extension '${_needle}' -> $(printf '%s' "$_entry" | jq -r '.extensionName + ":" + .eventName' 2>/dev/null || printf '%s' "$_needle")"
}

# Normalize legacy mapping payloads used by tests to current backend contract.
dm_normalize_mapping_payload() {   # <json_body>
    local _raw=${1:-}
    local _normalized _has_extension _legacy_ext _direction _resolved

    _normalized="$_raw"

    # Backward compatibility: BINARY was removed, use ANY_PAYLOAD (PROTOBUF_INTERNAL is retired too).
    _normalized=$(printf '%s' "$_normalized" | jq -c '
        if .mappingType == "BINARY" or .mappingType == "PROTOBUF_INTERNAL" then .mappingType = "ANY_PAYLOAD" else . end
    ' 2>/dev/null || printf '%s' "$_normalized")

    # Backward compatibility: processorExtensionName -> extension object.
    # Skip if extension object is already present (new API contract)
    _has_extension=$(printf '%s' "$_normalized" | jq -r '.extension | if . == null then "no" else "yes" end' 2>/dev/null || printf 'no')
    if [ "$_has_extension" = "no" ]; then
        _legacy_ext=$(printf '%s' "$_normalized" | jq -r '.processorExtensionName // empty' 2>/dev/null || printf '')
        if [ -n "$_legacy_ext" ]; then
            _direction=$(printf '%s' "$_normalized" | jq -r '.direction // empty' 2>/dev/null || printf '')
            _resolved=$(dm_resolve_extension_entry "$_legacy_ext" "$_direction" 2>/dev/null || true)

            if [ -n "$_resolved" ]; then
                _normalized=$(printf '%s' "$_normalized" | jq -c --argjson ext "$_resolved" '
                    .extension = $ext
                    | del(.processorExtensionName)
                ' 2>/dev/null || printf '%s' "$_normalized")
                dm_info "Resolved legacy extension reference: $_legacy_ext -> $(printf '%s' "$_resolved" | jq -r '.extensionName + ":" + .eventName' 2>/dev/null || printf 'extension')"
            else
                dm_warn "Could not resolve legacy extension reference: $_legacy_ext (extension might not be registered)"
            fi
        fi
    fi

    # Force debug logging on every test mapping so the message flow is traceable
    # in the backend log. Overridable: set DM_MAPPING_DEBUG=false to keep each
    # mapping's own debug value.
    if [ "${DM_MAPPING_DEBUG:-true}" = "true" ]; then
        _normalized=$(printf '%s' "$_normalized" | jq -c '.debug = true' 2>/dev/null || printf '%s' "$_normalized")
    fi

    printf '%s\n' "$_normalized"
}

# Create a mapping via POST /mapping. The raw JSON body is the only argument.
# Stores the new mapping id in _DM_LAST_MAPPING_ID.
dm_create_mapping() {   # <json_body>
    local _json _payload
    _payload=$(dm_normalize_mapping_payload "$1")
    _json=$(dm_api POST /mapping "$_payload")
    _DM_LAST_MAPPING_ID=$(printf '%s' "$_json" | jq -er '.id // empty' 2>/dev/null || printf '')
    if [ -z "${_DM_LAST_MAPPING_ID:-}" ]; then
        dm_fail "Mapping creation failed — API returned: $(printf '%s' "$_json" | head -c 200)"
        return 1
    fi
    dm_info "Created mapping: id=$_DM_LAST_MAPPING_ID"
}

# Deactivate then delete a mapping by id (silently ignores errors).
dm_delete_mapping() {   # <id>
    [ -z "$1" ] && return 0
    dm_deactivate_mapping "$1" 2>/dev/null || true
    dm_api DELETE "/mapping/$1" >/dev/null 2>&1 || true
    dm_info "Deleted mapping: $1"
}

dm_activate_mapping() {     # <id>
    dm_api POST /operation \
        "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"$1\",\"active\":\"true\"}}" >/dev/null
    dm_info "Activated mapping: $1"
}

dm_deactivate_mapping() {   # <id>
    dm_api POST /operation \
        "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"$1\",\"active\":\"false\"}}" >/dev/null
    dm_info "Deactivated mapping: $1"
}

# Return the messagesReceived count for a mapping id from monitoring stats.
# Uses jq -s (slurp) because dm_api_json_array may output NDJSON (one object
# per line) rather than a plain JSON array, so we collect all lines first.
dm_mapping_received_count() {   # <mapping_id>
    # Always outputs a number.  flatten(1) normalises both NDJSON (slurped to
    # [obj…]) and the [] fallback (slurped to [[]]). map()[0] is null when no
    # match; jq null-propagation makes .messagesReceived null → // 0 = 0.
    dm_api_json_array GET /monitoring/status/mapping/statistic \
        | jq -rs --arg id "$1" \
            'flatten(1) | (map(select(.id == $id))[0].messagesReceived) // 0' 2>/dev/null \
        || printf '0'
}

# Return one counter of a mapping's runtime status (messagesReceived, messagesProcessed,
# messagesFiltered, errors, ...). Always outputs a number (0 when the mapping has no stats yet).
# Inbound filterMapping / filterInventory mismatches count as messagesFiltered; an outbound
# mapping whose filter does not match is not selected at all, so none of its counters move.
dm_mapping_stat() {   # <mapping_id> <field>
    dm_api_json_array GET /monitoring/status/mapping/statistic \
        | jq -rs --arg id "$1" --arg f "$2" \
            'flatten(1) | (map(select(.id == $id))[0][$f]) // 0' 2>/dev/null \
        || printf '0'
}

dm_assert_mapping_received_gt() {   # <label> <mapping_id> <baseline>
    local _count _baseline _stats
    _count=$(dm_mapping_received_count "$2")
    _count=${_count:-0}
    _baseline=${3:-0}
    dm_assert_gt "$1" "$_count" "$_baseline"
    # Print diagnostics whenever assertion failed (value not > baseline)
    if [ "$_count" -le "$_baseline" ] 2>/dev/null; then
        _stats=$(dm_api_json_array GET /monitoring/status/mapping/statistic \
            | jq -rs --arg id "$2" '.[] | select(.id == $id)' 2>/dev/null || true)
        if [ -n "$_stats" ]; then
            dm_warn "Mapping stats for id=$2: $_stats"
        else
            dm_warn "Mapping id=$2 not found in /monitoring/status/mapping/statistic (no messages received at all)"
        fi
    fi
}

# Deploy a mapping to an arbitrary connector by identifier. Inbound mappings are
# only processed by a connector they are deployed to (the deployment map is keyed
# by mapping identifier and lists the connector identifiers). Resolves the mapping
# identifier from its id when needed. Fails hard on API error.
dm_deploy_mapping_to_connector() {  # <mapping_id> <connector_identifier>
    local _mapping_ref=$1 _conn=$2 _mapping_json _deployment_key _resolved
    [ -z "${_conn:-}" ] && dm_error "dm_deploy_mapping_to_connector: connector identifier required"
    _mapping_json=$(dm_api GET "/mapping/${_mapping_ref}" 2>/dev/null || printf '{}')
    _resolved=$(printf '%s' "$_mapping_json" | jq -r '.identifier // empty' 2>/dev/null || printf '')
    _deployment_key="${_resolved:-$_mapping_ref}"

    # go-c8y-cli --data rejects arrays. Piping + --template "input" causes c8y to
    # iterate array elements, sending each element as a separate body (wrong).
    # A constant Jsonnet template with </dev/null (no pipeline mode) is evaluated
    # once and used directly as the request body — the correct approach for arrays.
    # PUT with constant Jsonnet template (no stdin = no pipeline-mode iteration).
    # go-c8y-cli --data rejects JSON arrays; piping + --template "input" iterates
    # array elements as separate requests; a constant template with </dev/null is
    # evaluated once and sent directly as the request body.
    local _err _put_exit _retry_msg=""
    _err=$(mktemp)
    local _put_body; _put_body=$(printf '["%s"]' "${_conn}")
    c8y api --method PUT \
            ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}/deployment/defined/${_deployment_key}" \
            --template "${_put_body}" \
            --header 'Content-Type: application/json' \
            --force --output rawResponse </dev/null 2>"$_err" >/dev/null; _put_exit=$?
    if [ "${_put_exit}" -ne 0 ]; then
        [ -s "$_err" ] && _retry_msg="$(tr '\n' ' ' <"$_err" | head -c 400)"
        rm -f "$_err"
        dm_error "Deploy PUT failed for key=${_deployment_key}${_retry_msg:+: $_retry_msg}"
    fi
    rm -f "$_err"

    # Verify: GET /deployment/defined/{key} returns List<String>.
    # --output json silently discards string-array responses; --raw returns the raw HTTP body.
    local _raw_resp _assigned
    _raw_resp=$(c8y api --method GET \
            ${DM_SERVICE_HOST:+--host $DM_SERVICE_HOST} --url "${DM_SERVICE}/deployment/defined/${_deployment_key}" \
            --header 'Content-Type: application/json' \
            --force --raw </dev/null 2>/dev/null)
    local _deployment="${_raw_resp:-[]}"
    _assigned=$(printf '%s' "$_deployment" | jq -r --arg cid "${_conn}" '
        if type == "array" then (index($cid) != null)
        elif type == "string" then . == $cid
        else false end' 2>/dev/null || printf 'false')
    [ "${_assigned:-false}" = "true" ] \
        || dm_error "Deployment did not stick for mapping ${_mapping_ref} (key=${_deployment_key}): connector ${_conn} not assigned; response=${_deployment}"
    dm_info "Deployed mapping ${_mapping_ref} (key=${_deployment_key}) -> ${_conn} (verified)"
}

# Deploy a mapping to the MQTT connector (uses _DM_MQTT_CONNECTOR_ID set by dm_require_mqtt_broker).
# Requires: dm_require_mqtt_broker must be called first to initialize the connector ID.
dm_deploy_mapping_to_mqtt_connector() {  # <mapping_id>
    if [ -z "${_DM_MQTT_CONNECTOR_ID:-}" ]; then
        dm_fail "MQTT connector ID not set — call dm_require_mqtt_broker first"
        return 1
    fi
    dm_deploy_mapping_to_connector "$1" "${_DM_MQTT_CONNECTOR_ID}"
}

# Assert that the connector has the topic this test needs actually subscribed.
# Call this after activating the mapping and before publishing the test payload.
#
# Pass the mapping id: the mapping's own mappingTopic must then appear in the
# connector's subscription map with a count > 0. Without it the check degrades to
# "at least one topic subscribed", which any unrelated active mapping in the tenant
# satisfies — that weaker check let a test publish into a topic the connector had
# never subscribed and fail much later with a puzzling "no measurement found".
dm_assert_mqtt_topics_active() {   # [mapping_id] [connector_identifier]
    local _mapping_ref="${1:-}"
    local _cid="${2:-${_DM_MQTT_CONNECTOR_ID:-}}"
    [ -z "${_cid:-}" ] && dm_error "Connector ID not set for active topic assertion"

    local _connected
    _connected=$(dm_get_connector_status "$_cid" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
    if [ "$_connected" != "CONNECTED" ]; then
        dm_error "Connector $_cid is not CONNECTED (status=$_connected)"
    fi

    # The per-MQTT-topic subscription count below reflects the public-broker model
    # (one MQTT subscription per mapping topic). The Cumulocity MQTT Service Pulsar
    # connector consumes a single internal Pulsar topic regardless of the MQTT
    # topic filters, so that count does not apply — the verified deployment +
    # measurement assertion are the real signal. Assert CONNECTED only here.
    if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
        dm_info "Connector $_cid CONNECTED (c8y-mqtt-service mode — skipping per-topic subscription count)"
        return 0
    fi

    # Resolve the mapping's own topic, so the assertion is about this test's topic
    # and not about whatever else happens to be active in the tenant.
    local _expected_topic=""
    if [ -n "$_mapping_ref" ]; then
        _expected_topic=$(dm_api GET "/mapping/${_mapping_ref}" 2>/dev/null \
            | jq -r '.mappingTopic // empty' 2>/dev/null || printf '')
        if [ -z "$_expected_topic" ]; then
            dm_warn "Could not resolve mappingTopic for mapping ${_mapping_ref} — falling back to the any-topic check"
        fi
    fi

    # Runtime subscription updates are asynchronous. Poll briefly before failing.
    local _topic_count=0 _sub_map='{}' _attempt _found=false
    for _attempt in 1 2 3 4 5 6 7 8; do
        _sub_map=$(dm_api_must GET "/monitoring/subscription/${_cid}")
        _topic_count=$(printf '%s' "$_sub_map" | jq -r '
            if type == "object" then
                (to_entries | map(select((.value | tonumber? // 0) > 0)) | length)
            else
                0
            end' 2>/dev/null || printf '0')

        if [ -n "$_expected_topic" ]; then
            _found=$(printf '%s' "$_sub_map" | jq -r --arg t "$_expected_topic" '
                if type == "object" then (((.[$t] // 0) | tonumber? // 0) > 0)
                else false end' 2>/dev/null || printf 'false')
            [ "${_found:-false}" = "true" ] && break
        elif [ "${_topic_count:-0}" -gt 0 ]; then
            break
        fi
        sleep 1
    done

    if [ -n "$_expected_topic" ] && [ "${_found:-false}" != "true" ]; then
        _dm_dump_subscription_diagnostics "$_cid" "$_sub_map" \
            "topic '${_expected_topic}' is not subscribed after activation"
        dm_error "Connector $_cid has not subscribed the mapping topic '${_expected_topic}' after activation (mapping ${_mapping_ref})"
    fi

    if [ -z "$_expected_topic" ] && [ "${_topic_count:-0}" -lt 1 ]; then
        _dm_dump_subscription_diagnostics "$_cid" "$_sub_map" \
            "0 active inbound subscriptions after activation"
        dm_error "No active MQTT topic subscriptions detected for connector $_cid after mapping activation"
    fi

    if [ -n "$_expected_topic" ]; then
        dm_info "Connector $_cid subscribed topic '${_expected_topic}' (active inbound topics: $_topic_count)"
    else
        dm_info "Connector $_cid active inbound topic subscriptions: $_topic_count"
    fi
}

# Print the deployment / subscription / statistics snapshots that explain why a
# subscription assertion failed.
_dm_dump_subscription_diagnostics() {   # <connector_identifier> <subscription_map_json> <reason>
    local _cid=$1 _sub_map=$2 _reason=$3
    local _deploy _mapping_stats
    _deploy=$(dm_api_must GET "/deployment/defined" | jq -c . 2>/dev/null || printf '{}')
    _mapping_stats=$(dm_api GET "/monitoring/status/mapping/statistic" | jq -c . 2>/dev/null || printf '[]')
    dm_warn "Connector $_cid: ${_reason}. subscriptionMap=${_sub_map}"
    dm_warn "Deployment map snapshot: ${_deploy}"
    dm_warn "Mapping statistics snapshot: ${_mapping_stats}"
}
