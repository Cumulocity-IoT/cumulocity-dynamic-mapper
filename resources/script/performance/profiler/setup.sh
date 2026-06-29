#!/bin/bash
# File: resources/script/performance/profiler/setup.sh
#
# One-time setup for local profiling tests.
# Creates the required device + external ID in C8Y and loads the inbound
# Smart Function mapping into the locally running mapper service.
#
# Prerequisites:
#   - go-c8y-cli session active:  eval $(c8y sessions login)
#   - mapper service running:     ./test-dynamic-mapper.sh
#
# Idempotent: safe to re-run; existing resources are detected and skipped.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${MAPPER_URL:-http://localhost:8080}"

# External ID used in the test payload and topic
EXT_ID="sensor-berlin-01"
EXT_ID_TYPE="c8y_Serial"
MAPPING_FILE="${SCRIPT_DIR}/test-inbound-mapping_03.json"
REQUEST_FILE="${SCRIPT_DIR}/test-inbound-request_03.json"

# ── Bootstrap auth (same pattern as test-generator / warmup-outbound) ──────────
resolve_bootstrap_auth() {
    local svc="${MAPPER_SERVICE:-dynamic-mapper-service}"
    if [ -z "${MAPPER_TENANT:-}" ] || [ -z "${MAPPER_USER:-}" ] || [ -z "${MAPPER_PASSWORD:-}" ]; then
        if ! command -v c8y &>/dev/null; then
            echo "ERROR: c8y CLI not found." >&2; exit 1
        fi
        echo "Fetching bootstrap credentials for '${svc}'..." >&2
        local json
        json=$(c8y microservices getBootstrapUser --id "${svc}" --output json 2>/dev/null)
        [ -z "${json}" ] && { echo "ERROR: Could not fetch bootstrap credentials." >&2; exit 1; }
        MAPPER_TENANT=$(echo "${json}" | python3 -c "import sys,json; print(json.load(sys.stdin)['tenant'])")
        MAPPER_USER=$(echo "${json}"   | python3 -c "import sys,json; print(json.load(sys.stdin)['name'])")
        MAPPER_PASSWORD=$(echo "${json}" | python3 -c "import sys,json; print(json.load(sys.stdin)['password'])")
    fi
    MAPPER_AUTH_TOKEN=$(echo -n "${MAPPER_TENANT}/${MAPPER_USER}:${MAPPER_PASSWORD}" | base64)
    MAPPER_AUTH_HEADER="Authorization: Basic ${MAPPER_AUTH_TOKEN}"
}

echo "=========================================="
echo "Setup: dynamic-mapper profiling environment"
echo "Tenant URL: ${C8Y_BASEURL:-${C8Y_URL:-<from c8y session>}}"
echo "Mapper URL: ${BASE_URL}"
echo "External ID: ${EXT_ID} (${EXT_ID_TYPE})"
echo "=========================================="

# ── Step 1: Resolve or create device ───────────────────────────────────────────
echo ""
echo "Step 1: Device"

DEVICE_ID=$(c8y identity get \
    --name "${EXT_ID}" \
    --type "${EXT_ID_TYPE}" \
    --output json 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('managedObject',{}).get('id',''))" 2>/dev/null || true)

if [ -n "${DEVICE_ID}" ]; then
    echo "  ✓ Device already exists (id=${DEVICE_ID}, externalId=${EXT_ID})"
else
    echo "  Creating device '${EXT_ID}' ..."
    DEVICE=$(c8y inventory create \
        --name "Sensor Berlin 01" \
        --type "c8y_Sensor" \
        --data "c8y_IsDevice={}" \
        --data 'c8y_Sensor={"type":{"voltage":true}}' \
        --force \
        --output json 2>/dev/null)
    DEVICE_ID=$(echo "${DEVICE}" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
    echo "  ✓ Device created: id=${DEVICE_ID}"

    echo "  Binding external ID '${EXT_ID}' ..."
    c8y identity create \
        --name "${EXT_ID}" \
        --type "${EXT_ID_TYPE}" \
        --device "${DEVICE_ID}" \
        --force \
        --output json > /dev/null 2>&1
    echo "  ✓ External ID bound: ${EXT_ID} → ${DEVICE_ID}"
fi

# ── Step 2: Patch deviceId in test request file ────────────────────────────────
echo ""
echo "Step 2: Patch test request file"

if [ ! -f "${REQUEST_FILE}" ]; then
    echo "  ERROR: ${REQUEST_FILE} not found" >&2; exit 1
fi

python3 - "${REQUEST_FILE}" "${DEVICE_ID}" << 'PYEOF'
import sys, json

path, device_id = sys.argv[1], sys.argv[2]
with open(path) as f:
    data = json.load(f)

payload = json.loads(data['payload'])
payload['deviceId'] = device_id
data['payload'] = json.dumps(payload)

with open(path, 'w') as f:
    json.dump(data, f, indent=2)
print(f"  ✓ Patched deviceId={device_id} in {path.split('/')[-1]}")
PYEOF

# ── Step 3: Load mapping into local mapper service ─────────────────────────────
echo ""
echo "Step 3: Load mapping into local service"

if [ ! -f "${MAPPING_FILE}" ]; then
    echo "  ERROR: ${MAPPING_FILE} not found" >&2; exit 1
fi

resolve_bootstrap_auth

# The mapping file is a JSON array — extract the first element
MAPPING_JSON=$(python3 -c "import sys,json; print(json.dumps(json.load(open('${MAPPING_FILE}'))[0]))")
MAPPING_IDENTIFIER=$(echo "${MAPPING_JSON}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('identifier','?'))")

# Check if mapping already exists
EXISTING=$(curl -s \
    -H "accept: application/json" \
    -H "${MAPPER_AUTH_HEADER}" \
    "${BASE_URL}/mapping" 2>/dev/null \
    | python3 -c "
import sys, json
mappings = json.load(sys.stdin)
if isinstance(mappings, list):
    for m in mappings:
        if m.get('identifier') == '${MAPPING_IDENTIFIER}':
            print(m.get('id',''))
" 2>/dev/null || true)

if [ -n "${EXISTING}" ]; then
    echo "  ✓ Mapping '${MAPPING_IDENTIFIER}' already exists (id=${EXISTING})"
else
    RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X POST "${BASE_URL}/mapping" \
        -H "accept: application/json" \
        -H "${MAPPER_AUTH_HEADER}" \
        -H "content-type: application/json" \
        -d "${MAPPING_JSON}" 2>/dev/null)
    HTTP_CODE=$(echo "${RESPONSE}" | tail -1)
    BODY=$(echo "${RESPONSE}" | sed '$d')
    if [ "${HTTP_CODE}" = "200" ] || [ "${HTTP_CODE}" = "201" ]; then
        MAPPING_ID=$(echo "${BODY}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id','?'))" 2>/dev/null || echo "?")
        echo "  ✓ Mapping loaded: id=${MAPPING_ID}, identifier=${MAPPING_IDENTIFIER}"
    else
        echo "  ERROR: Failed to load mapping (HTTP ${HTTP_CODE})" >&2
        echo "  Body: ${BODY}" >&2
        exit 1
    fi
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "=========================================="
echo "Setup complete"
echo "=========================================="
echo "Device ID:    ${DEVICE_ID}"
echo "External ID:  ${EXT_ID} (${EXT_ID_TYPE})"
echo "Mapping:      ${MAPPING_IDENTIFIER}"
echo ""
echo "Run load test:"
echo "  ./test-generator.sh test-inbound-request_03.json 1000 100 50"
echo "=========================================="
