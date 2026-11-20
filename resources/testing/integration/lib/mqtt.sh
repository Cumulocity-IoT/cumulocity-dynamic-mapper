#!/bin/bash
#
# lib/mqtt.sh — MQTT broker selection (g / m), MQTT Service X.509 certificates, MQTT publish/subscribe
#
# Sourced by test-harness.sh (do not source directly; do not execute).

# ── MQTT Broker Defaults ────────────────────────────────────────────────────────
# DM_BROKER_MODE selects which broker the MQTT helpers drive:
#   public           — public HiveMQ/EMQX broker (default; unchanged behaviour)
#   c8y-mqtt-service — the Cumulocity MQTT Service via a CUMULOCITY_MQTT_SERVICE_PULSAR
#                      connector, authenticated with an X.509 client cert (TLS :9883).
export DM_BROKER_MODE="${DM_BROKER_MODE:-public}"
if [ "$DM_BROKER_MODE" = "c8y-mqtt-service" ]; then
    # Endpoint is the tenant domain on the TLS MQTT port. dm_require_mqtt_broker
    # re-derives and is authoritative; these are the standalone/reachability values.
    export MQTT_HOST="${MQTT_HOST:-${DM_C8Y_MQTT_HOST:-${C8Y_DOMAIN:-}}}"
    export MQTT_PORT="${MQTT_PORT:-${DM_C8Y_MQTT_PORT:-9883}}"
    export MQTT_TLS="${MQTT_TLS:-true}"
else
    export MQTT_HOST="${MQTT_HOST:-broker.hivemq.com}"
    export MQTT_PORT="${MQTT_PORT:-1883}"
    export MQTT_TLS="${MQTT_TLS:-false}"
fi
export MQTT_INSECURE="${MQTT_INSECURE:-false}"

# Set by dm_require_mqtt_broker in c8y-mqtt-service mode (see _dm_require_mqtt_service_broker):
_DM_MQTT_SVC_MODE=false        # true → publish/subscribe use cert auth (-i CN, -u tenant, --cert/--key)
_DM_MQTT_CLIENT_ID=""          # MQTT clientId == cert CN (the MQTT Service requires they match)
_DM_MQTT_CERT_SUITE_OWNED=false  # true → cert owned by run-tests.sh; skip per-test cleanup

# Run c8y CLI non-interactively: suppress all confirmation prompts and spinners.
export C8Y_SETTINGS_CI=true

# ── Cumulocity MQTT Service X.509 certificate helpers ───────────────────────────
# The Cumulocity MQTT Service exposes a standard MQTT interface (TLS, port 9883).
# Test clients authenticate with an X.509 client certificate whose CN equals the
# MQTT clientId; the tenant id goes in the MQTT username field. A self-signed cert
# uploaded as a trusted certificate is accepted as its own trust anchor.
_DM_MQTT_CERT_DIR=""
_DM_MQTT_CERT=""        # client certificate (PEM) path — pass to mosquitto --cert
_DM_MQTT_KEY=""         # client private key path     — pass to mosquitto --key
_DM_MQTT_CERT_NAME=""   # name used to register/delete the trusted cert

# Generate a self-signed client cert (CN=<clientId>) and upload it as a trusted
# certificate so the MQTT Service accepts it for X.509 auth. The MQTT clientId
# MUST equal the CN. Sets _DM_MQTT_CERT / _DM_MQTT_KEY / _DM_MQTT_CERT_NAME.
dm_provision_mqtt_service_cert() {   # <clientId> [days=2]
    local _client_id="$1" _days="${2:-2}"
    command -v openssl >/dev/null 2>&1 || dm_error "openssl is required to provision an MQTT Service client certificate"

    _DM_MQTT_CERT_DIR=$(mktemp -d)
    _DM_MQTT_KEY="${_DM_MQTT_CERT_DIR}/${_client_id}.key"
    _DM_MQTT_CERT="${_DM_MQTT_CERT_DIR}/${_client_id}.pem"
    _DM_MQTT_CERT_NAME="$_client_id"

    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$_DM_MQTT_KEY" -out "$_DM_MQTT_CERT" \
        -days "$_days" -subj "/CN=${_client_id}" >/dev/null 2>&1 \
        || dm_error "Failed to generate self-signed certificate for ${_client_id}"

    # Upload as a trusted certificate; auto-register so a device MO is created.
    if ! c8y devicemanagement certificates create \
            --name "$_DM_MQTT_CERT_NAME" \
            --file "$_DM_MQTT_CERT" \
            --autoRegistrationEnabled \
            --force --output json </dev/null >/dev/null 2>&1; then
        dm_error "Failed to upload trusted certificate '${_DM_MQTT_CERT_NAME}'. Does the user have the rights to manage trusted certificates / the 'Mqtt service' permission?"
    fi
    dm_info "Provisioned MQTT Service client cert: CN=${_client_id} (cert=${_DM_MQTT_CERT})"
}

# Delete the uploaded trusted certificate and remove the temp key/cert (best-effort).
dm_cleanup_mqtt_service_cert() {
    if [ -n "${_DM_MQTT_CERT_NAME:-}" ]; then
        c8y devicemanagement certificates delete --id "$_DM_MQTT_CERT_NAME" --force </dev/null >/dev/null 2>&1 || true
        dm_info "Deleted trusted certificate: ${_DM_MQTT_CERT_NAME}"
    fi
    [ -n "${_DM_MQTT_CERT_DIR:-}" ] && rm -rf "$_DM_MQTT_CERT_DIR" 2>/dev/null || true
}

# Echo a usable CA bundle path for server (TLS) verification, or non-zero if none
# is found. Honour MQTT_CAFILE first, then common macOS / Linux / brew locations.
dm_ca_bundle() {
    local _c
    for _c in "${MQTT_CAFILE:-}" \
              /etc/ssl/cert.pem \
              /opt/homebrew/etc/openssl@3/cert.pem \
              /usr/local/etc/openssl@3/cert.pem \
              /etc/ssl/certs/ca-certificates.crt \
              /etc/pki/tls/certs/ca-bundle.crt; do
        [ -n "$_c" ] && [ -f "$_c" ] && { printf '%s' "$_c"; return 0; }
    done
    return 1
}

# ── MQTT helpers ───────────────────────────────────────────────────────────────
# Environment variables:
#   DM_BROKER_MODE (public | c8y-mqtt-service; default public)
#   MQTT_HOST      (default broker.hivemq.com; c8y mode: tenant domain)
#   MQTT_PORT      (default 1883; c8y mode: 9883)
#   MQTT_USER      (optional; public mode only — c8y mode uses the tenant id)
#   MQTT_PASS      (optional; public mode only — c8y mode uses a client cert)
#   MQTT_TLS       (optional, true/false; default false, c8y mode: true)
#   MQTT_CAFILE    (optional path to CA certificate; c8y mode auto-discovers)
#   MQTT_INSECURE  (optional, true/false, default false)
# c8y-mqtt-service mode additionally honours:
#   DM_C8Y_MQTT_HOST / DM_C8Y_MQTT_PORT (override the tenant domain / 9883)

# In c8y-mqtt-service mode: require a CONNECTED CUMULOCITY_MQTT_SERVICE_PULSAR
# connector and provision a client cert, FAILING LOUDLY if unavailable. Sets
# _DM_MQTT_CONNECTOR_ID, _DM_MQTT_CLIENT_ID, _DM_MQTT_SVC_MODE=true, and the cert
# state used by the publish/subscribe helpers. Cert is torn down on exit.
_dm_require_mqtt_service_broker() {
    local _conn_type="CUMULOCITY_MQTT_SERVICE_PULSAR" _status _ca
    _DM_MQTT_SVC_MODE=true

    command -v mosquitto_pub >/dev/null 2>&1 \
        || dm_error "mosquitto_pub not installed — install mosquitto-clients to run MQTT Service tests."
    command -v openssl >/dev/null 2>&1 \
        || dm_error "openssl is required for MQTT Service client-certificate auth."

    # The MQTT Service endpoint is authoritative here (independent of when
    # DM_BROKER_MODE was set relative to sourcing this harness).
    export MQTT_HOST="${DM_C8Y_MQTT_HOST:-${C8Y_DOMAIN:-}}"
    export MQTT_PORT="${DM_C8Y_MQTT_PORT:-9883}"
    export MQTT_TLS=true
    local _host="$MQTT_HOST" _port="$MQTT_PORT" _tenant="${C8Y_TENANT:-}"

    [ -n "$_host" ]   || dm_error "MQTT host unknown — set DM_C8Y_MQTT_HOST or ensure C8Y_DOMAIN is exported."
    [ -n "$_tenant" ] || dm_error "Tenant unknown — ensure C8Y_TENANT is exported by the c8y session."

    nc -z -w 5 "$_host" "$_port" >/dev/null 2>&1 \
        || dm_error "MQTT Service endpoint ${_host}:${_port} is not reachable from this host."

    # CA bundle for TLS server verification (unless explicitly skipping).
    if [ -z "${MQTT_CAFILE:-}" ] && [ "${MQTT_INSECURE:-false}" != "true" ]; then
        _ca="$(dm_ca_bundle || true)"
        [ -n "$_ca" ] || dm_error "No CA bundle found. Set MQTT_CAFILE (e.g. /etc/ssl/cert.pem) or MQTT_INSECURE=true."
        export MQTT_CAFILE="$_ca"
        dm_info "Using CA bundle: $MQTT_CAFILE"
    fi

    # Resolve the singleton Pulsar connector, creating it if none exists, then
    # make sure it is CONNECTED. A missing connector config is a setup gap we fill
    # deterministically; a connector that won't connect (service down / no rights)
    # still FAILS LOUDLY below. The fixed id is shared by all subset tests so they
    # reuse one connector rather than churning the singleton.
    _DM_MQTT_CONNECTOR_ID="$(dm_list_connector_ids_by_type "$_conn_type" | head -n 1 || true)"
    if [ -z "${_DM_MQTT_CONNECTOR_ID:-}" ]; then
        _DM_MQTT_CONNECTOR_ID="${DM_C8Y_MQTT_CONNECTOR_ID:-dmmqttsvc}"
        dm_info "No ${_conn_type} connector found — creating ${_DM_MQTT_CONNECTOR_ID}"
        dm_setup_c8y_mqtt_service_connector "$_DM_MQTT_CONNECTOR_ID" "Dynamic Mapper MQTT Service (test)" "$_conn_type"
    fi
    dm_enable_connector "$_DM_MQTT_CONNECTOR_ID"  >/dev/null 2>&1 || true
    dm_connect_connector "$_DM_MQTT_CONNECTOR_ID" >/dev/null 2>&1 || true
    # Use || true so set -e (from the calling test script) does not silently exit
    # when the connector fails to reach CONNECTED within the timeout. Without it,
    # dm_wait_for_connector_status exits 1 on timeout, the assignment propagates
    # that exit code under set -e, and the script dies with no error message.
    _status="$(dm_wait_for_connector_status "$_DM_MQTT_CONNECTOR_ID" "CONNECTED" 45 3)" || true
    [ "$_status" = "CONNECTED" ] \
        || dm_error "${_conn_type} connector ${_DM_MQTT_CONNECTOR_ID} is not CONNECTED (status=$_status) — check the MQTT Service is reachable and the user has the 'Mqtt service' permission."

    # Reuse a run-provisioned cert when run-tests.sh has exported DM_MQTT_SVC_*
    # (DM_REUSE_MQTT_CERT=true, the default). Otherwise provision a fresh per-test cert.
    if [ -n "${DM_MQTT_SVC_CLIENT_ID:-}" ] && \
       [ -f "${DM_MQTT_SVC_CERT:-}" ] && \
       [ -f "${DM_MQTT_SVC_KEY:-}" ]; then
        _DM_MQTT_CLIENT_ID="$DM_MQTT_SVC_CLIENT_ID"
        _DM_MQTT_CERT="$DM_MQTT_SVC_CERT"
        _DM_MQTT_KEY="$DM_MQTT_SVC_KEY"
        _DM_MQTT_CERT_NAME="$DM_MQTT_SVC_CERT_NAME"
        _DM_MQTT_CERT_DIR="${DM_MQTT_SVC_CERT_DIR:-}"
        _DM_MQTT_CERT_SUITE_OWNED=true
        dm_info "Reusing run MQTT cert: CN=${_DM_MQTT_CLIENT_ID} (cert=${_DM_MQTT_CERT})"
    else
        _DM_MQTT_CLIENT_ID="dmtest$$"
        dm_provision_mqtt_service_cert "$_DM_MQTT_CLIENT_ID"
        dm_wait 5 "for the trusted certificate to be registered"
    fi
    trap _dm_on_exit EXIT

    dm_success "MQTT Service ready: connector=${_DM_MQTT_CONNECTOR_ID}, clientId=${_DM_MQTT_CLIENT_ID}, ${_host}:${_port} (cert auth)"
}

# Skip the calling test if the MQTT broker is unreachable or mosquitto_pub is
# not installed.  Call this once, right after dm_wait_for_service.
dm_require_mqtt_broker() {
    if [ "${DM_BROKER_MODE:-public}" = "c8y-mqtt-service" ]; then
        _dm_require_mqtt_service_broker
        return
    fi
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _cfg _match_count _matching_ids _statuses _connected_count _first_id _first_match _proto _user
    if ! command -v mosquitto_pub >/dev/null 2>&1; then
        dm_skip_exit "mosquitto_pub not installed — install mosquitto-clients to run MQTT tests."
    fi
    # Quick TCP reachability check (3-second timeout)
    if ! nc -z -w 3 "$_host" "$_port" >/dev/null 2>&1; then
        dm_skip_exit "MQTT broker not reachable at ${_host}:${_port}." \
                     "Set MQTT_HOST / MQTT_PORT / MQTT_USER / MQTT_PASS and retry."
    fi

    # Ensure the selected publish target matches at least one enabled MQTT
    # connector in Dynamic Mapper. Otherwise tests publish to the wrong broker
    # and inbound mappings never receive any message.
    _cfg=$(dm_api_json_array GET /configuration/connector/instance | jq -rs 'map(select(type=="object"))' 2>/dev/null || printf '[]')
    _match_count=$(printf '%s' "$_cfg" | jq -r --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
        ] | length' 2>/dev/null || printf '0')
    if [ "${_match_count:-0}" -lt 1 ]; then
        dm_skip_exit "No enabled MQTT connector is configured for ${_host}:${_port}." \
                     "Update MQTT_HOST/MQTT_PORT to match the mapper connector configuration (or update connector config)."
    fi

    _matching_ids=$(printf '%s' "$_cfg" | jq -r --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
          | (.identifier // empty)
        ] | .[]' 2>/dev/null || true)
    
    # Store the first matching connector ID for deployment operations
    _DM_MQTT_CONNECTOR_ID=$(printf '%s\n' "$_matching_ids" | head -n 1)

    _first_match=$(printf '%s' "$_cfg" | jq -c --arg host "$_host" --arg port "$_port" '
        [ .[]
          | select((.connectorType // "") == "MQTT")
          | select((.enabled // false) == true)
          | select((.properties.mqttHost // "") == $host)
          | select((.properties.mqttPort | tostring) == $port)
        ] | first // {}' 2>/dev/null || printf '{}')

    # If the connector config specifies MQTT auth/protocol and the test env does not,
    # inherit sensible defaults to avoid publish/subscribe mismatches.
    _user=$(printf '%s' "$_first_match" | jq -r '.properties.user // empty' 2>/dev/null || printf '')
    if [ -n "$_user" ] && [ -z "${MQTT_USER:-}" ]; then
        export MQTT_USER="$_user"
        dm_info "Using MQTT_USER from connector configuration for test publish/sub: ${MQTT_USER}"
    fi

    _proto=$(printf '%s' "$_first_match" | jq -r '.properties.protocol // empty' 2>/dev/null || printf '')
    if [ "$_proto" = "mqtts://" ] && [ -z "${MQTT_TLS:-}" ]; then
        export MQTT_TLS=true
        dm_info "Using MQTT_TLS=true from connector protocol mqtts://"
    fi

    _statuses=$(dm_api GET /monitoring/status/connectors | jq -c '
        if type == "array" then
            .
        elif type == "object" then
            if ((.connectorIdentifier // empty) != "" and (.status // empty) != "") then
                [.]  # single status object
            else
                [to_entries[] | .value]  # map keyed by connector id
            end
        else
            []
        end' 2>/dev/null || printf '[]')
    [ -z "${_statuses:-}" ] && _statuses='[]'
    _connected_count=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
        | jq -r --argjson statuses "$_statuses" '
            [ .[] as $id
              | $statuses[]
              | select((.connectorIdentifier // "") == $id)
              | select((.status // "") == "CONNECTED")
            ] | length' 2>/dev/null || printf '0')
    [ -z "${_connected_count:-}" ] && _connected_count=0

    if [ "${_connected_count:-0}" -lt 1 ]; then
        _first_id=$(printf '%s\n' "$_matching_ids" | head -n 1)
        if [ -n "${_first_id:-}" ]; then
            dm_info "Matching MQTT connector is not CONNECTED; attempting connect: ${_first_id}"
            dm_connect_connector "$_first_id" || true
            dm_wait 8 "waiting for connector to connect"

            _statuses=$(dm_api GET /monitoring/status/connectors | jq -c '
                if type == "array" then
                    .
                elif type == "object" then
                    if ((.connectorIdentifier // empty) != "" and (.status // empty) != "") then
                        [.]  # single status object
                    else
                        [to_entries[] | .value]  # map keyed by connector id
                    end
                else
                    []
                end' 2>/dev/null || printf '[]')
            [ -z "${_statuses:-}" ] && _statuses='[]'
            _connected_count=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
                | jq -r --argjson statuses "$_statuses" '
                    [ .[] as $id
                      | $statuses[]
                      | select((.connectorIdentifier // "") == $id)
                      | select((.status // "") == "CONNECTED")
                    ] | length' 2>/dev/null || printf '0')
            [ -z "${_connected_count:-}" ] && _connected_count=0

            # Fallback to direct endpoint when aggregate status payload is empty
            # or parsing failed transiently.
            if [ "${_connected_count:-0}" -lt 1 ] && [ -n "${_first_id:-}" ]; then
                if [ "$(dm_get_connector_status "$_first_id" | jq -r '.status // empty' 2>/dev/null || printf '')" = "CONNECTED" ]; then
                    _connected_count=1
                    dm_info "Confirmed CONNECTED via direct connector status: ${_first_id}"
                fi
            fi
        fi
    fi

    if [ "${_connected_count:-0}" -lt 1 ]; then
        local _matching_statuses
        _matching_statuses=$(printf '%s\n' "$_matching_ids" | jq -R -s 'split("\n") | map(select(length > 0))' \
            | jq -c --argjson statuses "$_statuses" '
                [ .[] as $id
                  | { id: $id, status: (($statuses[] | select((.connectorIdentifier // "") == $id) | .status) // "UNKNOWN") }
                ]' 2>/dev/null || printf '[]')
        dm_warn "Matching connector statuses for ${_host}:${_port}: ${_matching_statuses}"
        dm_skip_exit "No matching MQTT connector for ${_host}:${_port} is CONNECTED." \
                     "Connect the matching connector in Dynamic Mapper and retry."
    fi

    dm_info "MQTT broker reachable at ${_host}:${_port} — proceeding."
}

_dm_mqtt_append_tls_args() {  # <array_name>
    # NOTE: appends to the named array via eval rather than a `local -n` nameref —
    # namerefs require bash 4.3+, and macOS ships bash 3.2 where `local -n` fails
    # with "invalid option" (exit 2), aborting the caller under `set -e`.
    local _arr_name=$1
    local _tls="${MQTT_TLS:-false}"
    local _insecure="${MQTT_INSECURE:-false}"
    local _cafile="${MQTT_CAFILE:-}"

    [ "$_tls" = "true" ] || return 0

    eval "${_arr_name}+=(--tls-version tlsv1.2)"
    if [ -n "$_cafile" ]; then
        eval "${_arr_name}+=(--cafile \"\$_cafile\")"
    fi
    if [ "$_insecure" = "true" ]; then
        eval "${_arr_name}+=(--insecure)"
    fi
}

# Append client identity + credentials to a mosquitto arg array. In
# c8y-mqtt-service mode this is X.509 cert auth (clientId == cert CN, tenant in
# the username field); in public mode it is the optional MQTT_USER/MQTT_PASS plus
# the caller-supplied default client id.
#   $1 = array name   $2 = default client id for public mode ("" = none)
_dm_mqtt_append_auth_args() {  # <array_name> <public_default_client_id>
    local _arr_name=$1 _public_cid=$2
    if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
        # clientId MUST equal the cert CN, and the tenant id goes in the username.
        eval "${_arr_name}+=(-i \"\$_DM_MQTT_CLIENT_ID\" -u \"\$C8Y_TENANT\")"
        [ -n "${_DM_MQTT_CERT:-}" ] && eval "${_arr_name}+=(--cert \"\$_DM_MQTT_CERT\")"
        [ -n "${_DM_MQTT_KEY:-}" ]  && eval "${_arr_name}+=(--key \"\$_DM_MQTT_KEY\")"
    else
        [ -n "${MQTT_USER:-}" ] && eval "${_arr_name}+=(-u \"\$MQTT_USER\")"
        [ -n "${MQTT_PASS:-}" ] && eval "${_arr_name}+=(-P \"\$MQTT_PASS\")"
        [ -n "$_public_cid" ]   && eval "${_arr_name}+=(-i \"\$_public_cid\")"
    fi
    # Must end with a success status: the conditionals above can leave $? = 1
    # (e.g. publish passes an empty client id), which would abort a caller running
    # under `set -e` before mosquitto_pub/sub is ever invoked.
    return 0
}

# Guard against MQTT Service constraints (QoS 2 / retained are rejected). No-op
# in public mode. Fails loudly so a migrated test surfaces the unsupported usage.
_dm_mqtt_guard_qos() {  # <qos>
    [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ] || return 0
    [ "${1:-0}" = "2" ] && dm_error "QoS 2 is not supported by the Cumulocity MQTT Service — use QoS 0 or 1."
    return 0
}

# Require the connector to be CONNECTED right now, polling briefly.
#
# A connector that was CONNECTED when the mapping was activated can have dropped
# again by the time the payload goes out (public brokers disconnect clients, and a
# duplicate clientId takes the session over). The broker then silently discards the
# message, and the test fails 20s later on its result assertion with nothing in the
# output pointing at the connection. Fail here instead, where the cause is visible.
#
# Set _DM_MQTT_SKIP_CONNECTOR_CHECK=true for publishes that must not abort the run
# (e.g. the readiness probe).
_dm_mqtt_require_connector_connected() {   # [timeout_secs=15] [connector_identifier]
    local _timeout="${1:-15}" _cid="${2:-${_DM_MQTT_CONNECTOR_ID:-}}"
    [ "${_DM_MQTT_SKIP_CONNECTOR_CHECK:-false}" = "true" ] && return 0
    [ -z "${_cid:-}" ] && return 0   # no connector in play (e.g. broker-only helpers)

    local _elapsed=0 _status
    while [ "$_elapsed" -lt "$_timeout" ]; do
        _status=$(dm_get_connector_status "$_cid" | jq -r '.status // "UNKNOWN"' 2>/dev/null || printf 'UNKNOWN')
        [ "$_status" = "CONNECTED" ] && return 0
        [ "$_elapsed" -eq 0 ] && dm_warn "Connector $_cid is $_status just before publishing — waiting up to ${_timeout}s for it to reconnect"
        sleep 2
        _elapsed=$((_elapsed + 2))
    done
    dm_error "Connector $_cid is not CONNECTED (status=${_status:-UNKNOWN}) at publish time — the broker would drop the message"
}

# QoS 1 by default: mosquitto_pub then waits for the broker's PUBACK, so a publish
# that never reached the broker fails here rather than looking like a mapping bug.
# (It does not make the broker queue the message for a disconnected connector —
# that is what _dm_mqtt_require_connector_connected above is for.)
dm_mqtt_publish() {     # <topic> <payload> [qos=1]
    local _topic=$1 _payload=$2 _qos=${3:-1}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    _dm_mqtt_guard_qos "$_qos"
    _dm_mqtt_require_connector_connected
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -m "$_payload" -q "$_qos")
    _dm_mqtt_append_auth_args _args ""
    _dm_mqtt_append_tls_args _args
    mosquitto_pub "${_args[@]}"
    dm_info "Published to $_topic (broker=$_host:$_port, qos=$_qos)"
}

# Subscribe and capture at most 1 message with a timeout.
# Prints the received message to stdout.  Returns 1 on timeout.
# NOTE (c8y-mqtt-service mode): the clientId is fixed to the cert CN, so only ONE
# mosquitto client (publish OR subscribe) may be connected at a time — adequate
# for the migrated inbound (publish-only) and outbound (subscribe-only) tests.
dm_mqtt_subscribe_one() {   # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -C 1 -W "$_timeout")
    # Unique client id avoids any collision with the broker connector or other
    # clients on a shared public broker (a collision shows up as MOSQ_ERR_PROTOCOL).
    # In c8y mode the auth helper overrides this with the cert CN.
    _dm_mqtt_append_auth_args _args "dmtest-sub-$$-${RANDOM}"
    _dm_mqtt_append_tls_args _args
    # Do NOT swallow stderr — callers capture it for diagnostics (e.g. exit 2 =
    # MOSQ_ERR_PROTOCOL, 27 = timeout). Suppressing it hid the real failure.
    mosquitto_sub "${_args[@]}"
}

# Like dm_mqtt_subscribe_one but prints "<topic> <payload>" (mosquitto_sub -v),
# so callers can assert the *topic* a message arrived on — needed to verify
# broker-side topic resolution such as _externalId_ token replacement, where the
# resolved external id appears in the topic rather than the payload.
dm_mqtt_subscribe_one_verbose() {   # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -C 1 -W "$_timeout" -v)
    _dm_mqtt_append_auth_args _args "dmtest-sub-$$-${RANDOM}"
    _dm_mqtt_append_tls_args _args
    mosquitto_sub "${_args[@]}"
}

# Like dm_mqtt_subscribe_one but prints the payload as lowercase hex (mosquitto_sub -F %x, no spaces,
# no trailing newline from the payload), so callers can assert the exact BYTES a message carried:
# a text payload without quotes or JSON wrapping, or a binary payload that is not valid UTF-8.
dm_mqtt_subscribe_one_hex() {   # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    local _args=(-h "$_host" -p "$_port" -t "$_topic" -C 1 -W "$_timeout" -F "%x")
    _dm_mqtt_append_auth_args _args "dmtest-sub-$$-${RANDOM}"
    _dm_mqtt_append_tls_args _args
    mosquitto_sub "${_args[@]}"
}

# The lowercase hex of a string's bytes, as dm_mqtt_subscribe_one_hex prints a payload.
dm_hex_of() {   # <string>
    printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'
}

# Prime a topic subscription to reduce timing races where a test publishes before
# the background subscriber is fully established.
#
# In C8Y MQTT Service mode the clientId is fixed to the cert CN, so only one
# connection is allowed at a time.  A separate probe publisher would immediately
# kick the probe subscriber (same clientId), causing a spurious timeout (exit 27).
# In that mode we skip the probe entirely — the real subscriber starts right after.
dm_mqtt_probe_subscription() {  # <topic> [timeout_secs=10]
    local _topic=$1 _timeout=${2:-10}
    local _probe_file _probe_err _probe_pid _probe_rc

    if [ "${_DM_MQTT_SVC_MODE:-false}" = "true" ]; then
        dm_info "MQTT readiness probe skipped (C8Y MQTT Service mode — single clientId)"
        return 0
    fi

    _probe_file=$(mktemp)
    _probe_err=$(mktemp)

    ( dm_mqtt_subscribe_one "$_topic" "$_timeout" > "$_probe_file" 2>"$_probe_err" ) &
    _probe_pid=$!
    sleep 1

    _DM_MQTT_SKIP_CONNECTOR_CHECK=true \
        dm_mqtt_publish "$_topic" '{"_dmProbe":"ready"}' 0 >/dev/null 2>&1 || true

    set +e
    wait "$_probe_pid"
    _probe_rc=$?
    set -e

    if [ "$_probe_rc" -ne 0 ]; then
        dm_warn "MQTT readiness probe subscribe exited $_probe_rc; stderr: $(tr '\n' ' ' < "$_probe_err" 2>/dev/null | head -c 300)"
    else
        dm_info "MQTT readiness probe received"
    fi

    rm -f "$_probe_file" "$_probe_err"
    return "$_probe_rc"
}
