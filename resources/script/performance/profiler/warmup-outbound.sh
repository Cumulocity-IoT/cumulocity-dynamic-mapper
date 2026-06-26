#!/bin/bash
# File: resources/script/performance/profiler/warmup-outbound.sh
#
# Bootstrap credentials are fetched automatically via:
#   c8y microservices getBootstrapUser --id dynamic-mapper-service
#
# Override with env vars if needed:
#   MAPPER_TENANT, MAPPER_USER, MAPPER_PASSWORD
#   MAPPER_SERVICE  - microservice name (default: dynamic-mapper-service)
#   MAPPER_URL      - local service base URL (default: http://localhost:8080)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

BASE_URL="${MAPPER_URL:-http://localhost:8080}"
ENDPOINT="/test/mapping"
ITERATIONS=5
DEFAULT_REQUEST_FILE="${SCRIPT_DIR}/test-outbound-request_01.json"

TEST_REQUEST_FILE="${1:-${DEFAULT_REQUEST_FILE}}"
[ -n "$2" ] && ITERATIONS=$2

resolve_bootstrap_auth() {
    local svc="${MAPPER_SERVICE:-dynamic-mapper-service}"
    if [ -z "${MAPPER_TENANT}" ] || [ -z "${MAPPER_USER}" ] || [ -z "${MAPPER_PASSWORD}" ]; then
        if ! command -v c8y &>/dev/null; then
            echo "ERROR: c8y CLI not found. Set MAPPER_TENANT, MAPPER_USER, MAPPER_PASSWORD manually." >&2
            exit 1
        fi
        echo "Fetching bootstrap credentials for '${svc}'..." >&2
        local json
        json=$(c8y microservices getBootstrapUser --id "${svc}" --output json 2>/dev/null)
        if [ -z "${json}" ]; then
            echo "ERROR: Could not fetch bootstrap credentials for '${svc}'" >&2
            exit 1
        fi
        MAPPER_TENANT=$(echo "${json}" | python3 -c "import sys,json; print(json.load(sys.stdin)['tenant'])")
        MAPPER_USER=$(echo "${json}"   | python3 -c "import sys,json; print(json.load(sys.stdin)['name'])")
        MAPPER_PASSWORD=$(echo "${json}" | python3 -c "import sys,json; print(json.load(sys.stdin)['password'])")
    fi
    MAPPER_AUTH_TOKEN=$(echo -n "${MAPPER_TENANT}/${MAPPER_USER}:${MAPPER_PASSWORD}" | base64)
    MAPPER_AUTH_HEADER="Authorization: Basic ${MAPPER_AUTH_TOKEN}"
}

resolve_bootstrap_auth
DISPLAY_USER="${MAPPER_TENANT}/${MAPPER_USER}"

echo "=========================================="
echo "Warmup: dynamic-mapper outbound processor"
echo "Endpoint: ${BASE_URL}${ENDPOINT}"
echo "Request file: ${TEST_REQUEST_FILE}"
echo "Iterations: ${ITERATIONS}"
echo "Auth: ${DISPLAY_USER} (Basic)"
echo "=========================================="

if [ ! -f "${TEST_REQUEST_FILE}" ]; then
    echo "ERROR: Test request file not found: ${TEST_REQUEST_FILE}"
    exit 1
fi

echo ""
curl_post() {
    local out body http_code
    out=$(curl -s -w "\n%{http_code}" \
      -X POST "${BASE_URL}${ENDPOINT}" \
      -H "accept: application/json" \
      -H "${MAPPER_AUTH_HEADER}" \
      -H "content-type: application/json" \
      -d @"$1" 2>/dev/null)
    http_code=$(echo "${out}" | tail -1)
    body=$(echo "${out}" | sed '$d')
    echo "${http_code}|${body}"
}

echo "Testing endpoint..."
probe=$(curl_post "${TEST_REQUEST_FILE}")
response="${probe%%|*}"
body="${probe#*|}"

if [ "${response}" = "000" ]; then
    echo "ERROR: Cannot connect to service at ${BASE_URL}"
    exit 1
fi
if [ "${response}" = "401" ] || [ "${response}" = "403" ]; then
    echo "ERROR: Authentication failed (HTTP ${response})"
    echo "Your Bearer token may have expired. Re-run: eval \$(c8y sessions login)"
    exit 1
fi

echo "✓ Endpoint responding (HTTP ${response})"
if [ "${response}" != "200" ] && [ "${response}" != "201" ] && [ "${response}" != "204" ]; then
    echo "  Response body: ${body}"
fi
echo ""

echo "Starting warmup..."
success=0
failed=0
start_time=$(date +%s)

for i in $(seq 1 "${ITERATIONS}"); do
    probe=$(curl_post "${TEST_REQUEST_FILE}")
    response="${probe%%|*}"
    body="${probe#*|}"

    if [ "${response}" = "200" ] || [ "${response}" = "201" ] || [ "${response}" = "204" ]; then
        ((success++))
    else
        ((failed++))
        if [ "${failed}" -le 3 ]; then
            echo "  Failure ${failed}: HTTP ${response}"
            echo "  Body: ${body}"
        fi
    fi

    if [ $((i % 10)) -eq 0 ]; then
        echo "  Progress: ${i}/${ITERATIONS} (Success: ${success}, Failed: ${failed})"
    fi

    sleep 0.1
done

end_time=$(date +%s)
elapsed=$((end_time - start_time))

echo ""
echo "=========================================="
echo "Warmup Complete"
echo "=========================================="
echo "Total:    ${ITERATIONS}"
echo "Success:  ${success}"
echo "Failed:   ${failed}"
echo "Time:     ${elapsed}s"

if [ "${success}" -gt 0 ]; then
    success_rate=$(awk "BEGIN { printf \"%.1f\", ${success}*100/${ITERATIONS} }")
    echo "Success rate: ${success_rate}%"
fi

echo "=========================================="

if [ "${failed}" -gt $((ITERATIONS / 2)) ]; then
    echo ""
    echo "WARNING: More than 50% of requests failed!"
    exit 1
fi
