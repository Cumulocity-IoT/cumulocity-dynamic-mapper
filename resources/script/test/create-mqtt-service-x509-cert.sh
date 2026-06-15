#!/bin/bash
#
# create-mqtt-service-x509-cert: provision / clean up a Cumulocity MQTT Service
# X.509 client certificate.
#
# The Cumulocity MQTT Service authenticates clients with an X.509 client
# certificate whose CN equals the MQTT clientId (the tenant id goes in the MQTT
# username field). A self-signed certificate uploaded as a *trusted certificate*
# is accepted as its own trust anchor, so a single self-signed cert is all a test
# client needs.
#
# Unlike test-cumulocity-mqtt-service.sh (which provisions a cert and tears it
# down within one run via the harness exit trap), this script splits the phases
# across separate invocations:
#
#   create  — generate a self-signed cert (CN=<clientId>) locally and persist the
#             cert/key + metadata to a stable dir.
#   upload  — upload the generated cert to the tenant as a trusted certificate
#             (generates one first if it does not exist yet).
#   cleanup — delete the uploaded trusted certificate (best-effort: does NOT fail
#             if nothing was uploaded) and remove the local files.
#
# State (cert, key, the registered cert name, and an "uploaded" marker) is kept
# under DM_CERT_DIR so the invocations can be run independently — even from
# different shells.
#
# The clientId / CN MUST be alphanumeric: the MQTT Service derives subscriber
# names from it and Cumulocity rejects non-alphanumeric names with HTTP 422.
#
# Prerequisites:
#   - c8y CLI authenticated (active session or C8Y_HOST/C8Y_USER/C8Y_PASSWORD)
#   - openssl installed
#   - the session user may manage trusted certificates ('Mqtt service' permission)
#
# Usage:
#   ./create-mqtt-service-x509-cert.sh create  [clientId]   # generate locally
#   ./create-mqtt-service-x509-cert.sh upload  [clientId]   # upload to tenant
#   ./create-mqtt-service-x509-cert.sh cleanup [clientId]   # delete + remove files
#   ./create-mqtt-service-x509-cert.sh                      # defaults to 'create'
#
# Environment overrides:
#   DM_MQTT_CLIENT_ID   clientId / cert CN (default: dmx509testclient)
#   DM_CERT_DIR         where cert/key/metadata are stored
#                       (default: ${TMPDIR:-/tmp}/dm-mqtt-service-x509)
#   DM_CERT_DAYS        certificate validity in days (default: 2)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

# ── Configuration ────────────────────────────────────────────────────────────────
# CN MUST be alphanumeric (see header) — the MQTT clientId equals this value.
CLIENT_ID_DEFAULT="dmx509testclient"
CERT_DIR="${DM_CERT_DIR:-${TMPDIR:-/tmp}/dm-mqtt-service-x509}"
CERT_DAYS="${DM_CERT_DAYS:-2}"
CERT_NAME_FILE="${CERT_DIR}/cert-name.txt"
UPLOADED_MARKER="${CERT_DIR}/uploaded"

# ── Argument parsing ─────────────────────────────────────────────────────────────
# Positional: <command> [clientId]. Command defaults to 'create'.
COMMAND="${1:-create}"
CLIENT_ID="${2:-${DM_MQTT_CLIENT_ID:-$CLIENT_ID_DEFAULT}}"

usage() {
    cat <<EOF
Usage:
  $(basename "$0") create  [clientId]   Generate a self-signed X.509 client cert
                                        (CN=clientId) locally.
  $(basename "$0") upload  [clientId]   Upload the generated cert to the tenant as
                                        a trusted certificate (generates one first
                                        if it does not exist yet).
  $(basename "$0") cleanup [clientId]   Delete the uploaded trusted certificate
                                        (best-effort) and remove the local files.

  clientId defaults to '\$DM_MQTT_CLIENT_ID' or '${CLIENT_ID_DEFAULT}'.
  For 'cleanup', clientId is read from ${CERT_NAME_FILE} when omitted.
EOF
}

# Paths of the cert/key for the current CLIENT_ID.
_cert_path() { printf '%s' "${CERT_DIR}/${CLIENT_ID}.pem"; }
_key_path()  { printf '%s' "${CERT_DIR}/${CLIENT_ID}.key"; }

# ── create ───────────────────────────────────────────────────────────────────────
# Generate a self-signed cert locally (no tenant interaction).
do_create() {
    dm_banner "Generate Cumulocity MQTT Service X.509 Certificate"

    dm_step "Validating prerequisites ..."
    command -v openssl >/dev/null 2>&1 \
        || dm_error "openssl is required to generate the client certificate."

    local _key _cert
    _key="$(_key_path)"
    _cert="$(_cert_path)"

    dm_step "Generating self-signed certificate (CN=${CLIENT_ID}, ${CERT_DAYS} days) ..."
    mkdir -p "$CERT_DIR"
    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$_key" -out "$_cert" \
        -days "$CERT_DAYS" -subj "/CN=${CLIENT_ID}" >/dev/null 2>&1 \
        || dm_error "Failed to generate self-signed certificate for ${CLIENT_ID}"

    # Persist the cert name so 'upload'/'cleanup' can find it without an argument.
    printf '%s\n' "$CLIENT_ID" > "$CERT_NAME_FILE"

    dm_success "Generated certificate for CN=${CLIENT_ID}."
    dm_info "Certificate: $_cert"
    dm_info "Private key: $_key"
    dm_info "Upload with: $(basename "$0") upload ${CLIENT_ID}"
    dm_done "Generate Cumulocity MQTT Service X.509 Certificate"
}

# ── upload ───────────────────────────────────────────────────────────────────────
# Upload the generated cert to the tenant as a trusted certificate. Generates one
# first if it does not exist yet, so 'upload' can be used standalone.
do_upload() {
    dm_banner "Upload Cumulocity MQTT Service X.509 Certificate"

    dm_step "Validating prerequisites ..."
    command -v c8y >/dev/null 2>&1 \
        || dm_error "c8y CLI is required to upload the trusted certificate."
    dm_check_session

    local _cert
    _cert="$(_cert_path)"
    if [ ! -f "$_cert" ]; then
        dm_info "No certificate at ${_cert} yet — generating one first."
        do_create
    fi

    dm_step "Uploading '${CLIENT_ID}' as a trusted certificate ..."
    # --autoRegistrationEnabled so a device MO is created for the cert's CN.
    if ! c8y devicemanagement certificates create \
            --name "$CLIENT_ID" \
            --file "$_cert" \
            --autoRegistrationEnabled \
            --force --output json </dev/null >/dev/null 2>&1; then
        dm_error "Failed to upload trusted certificate '${CLIENT_ID}'. Does the user have the rights to manage trusted certificates / the 'Mqtt service' permission?"
    fi

    # Record that this cert name is uploaded so 'cleanup' knows what to remove.
    printf '%s\n' "$CLIENT_ID" > "$CERT_NAME_FILE"
    printf '%s\n' "$CLIENT_ID" > "$UPLOADED_MARKER"

    dm_success "Trusted certificate '${CLIENT_ID}' uploaded."
    dm_info "Use for MQTT auth: clientId/CN=${CLIENT_ID}, --cert ${_cert} --key $(_key_path)"
    dm_info "Tear down with:    $(basename "$0") cleanup ${CLIENT_ID}"
    dm_done "Upload Cumulocity MQTT Service X.509 Certificate"
}

# ── cleanup ──────────────────────────────────────────────────────────────────────
do_cleanup() {
    dm_banner "Clean up Cumulocity MQTT Service X.509 Certificate"

    command -v c8y >/dev/null 2>&1 \
        || dm_error "c8y CLI is required to delete the trusted certificate."
    dm_check_session

    # Was a clientId given explicitly (positional arg or env override)?
    local _explicit=false
    { [ -n "${2:-}" ] || [ -n "${DM_MQTT_CLIENT_ID:-}" ]; } && _explicit=true

    # If no clientId was passed explicitly, fall back to the persisted name.
    local _name="$CLIENT_ID"
    if [ "$_explicit" = "false" ] && [ -f "$CERT_NAME_FILE" ]; then
        _name="$(tr -d '[:space:]' < "$CERT_NAME_FILE")"
        [ -n "$_name" ] && dm_info "Resolved cert name from state file: ${_name}"
    fi
    : "${_name:=$CLIENT_ID}"

    # Only attempt a delete when something was actually uploaded — unless a
    # clientId was given explicitly, in which case we always try (the upload may
    # have happened in another run/shell without leaving a marker here).
    if [ "$_explicit" = "false" ] && [ ! -f "$UPLOADED_MARKER" ]; then
        dm_info "No upload on record for '${_name}' — nothing to delete from the tenant."
    else
        dm_step "Deleting trusted certificate '${_name}' ..."
        # Best-effort: never fail if the cert was never uploaded / already gone.
        if c8y devicemanagement certificates delete --id "$_name" --force </dev/null >/dev/null 2>&1; then
            dm_success "Deleted trusted certificate: ${_name}"
        else
            dm_warn "Trusted certificate '${_name}' not found or already deleted."
        fi
    fi

    dm_step "Removing local certificate files ..."
    if [ -d "$CERT_DIR" ]; then
        rm -rf "$CERT_DIR"
        dm_info "Removed ${CERT_DIR}"
    else
        dm_info "No local certificate directory at ${CERT_DIR} — nothing to remove."
    fi

    dm_done "Clean up Cumulocity MQTT Service X.509 Certificate"
}

# ── Dispatch ─────────────────────────────────────────────────────────────────────
case "$COMMAND" in
    create)        do_create "$@" ;;
    upload)        do_upload "$@" ;;
    cleanup|clean) do_cleanup "$@" ;;
    -h|--help|help) usage ;;
    *)
        dm_warn "Unknown command: ${COMMAND}"
        usage
        exit 1
        ;;
esac
