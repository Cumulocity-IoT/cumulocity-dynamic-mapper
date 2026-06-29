#!/bin/bash
# File: resources/script/performance/profiler/test-generator.sh
#
# Bootstrap credentials are fetched automatically via:
#   c8y microservices getBootstrapUser --id dynamic-mapper-service
#
# Override with env vars if needed:
#   MAPPER_TENANT, MAPPER_USER, MAPPER_PASSWORD
#   MAPPER_SERVICE  - microservice name (default: dynamic-mapper-service)
#   MAPPER_URL      - local service base URL (default: http://localhost:8080)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

# Portable millisecond timestamp — date +%s%3N is GNU-only (not on macOS)
if date +%s%3N 2>&1 | grep -qE '^[0-9]+$'; then
    ms_now() { date +%s%3N; }
elif command -v gdate &>/dev/null; then
    ms_now() { gdate +%s%3N; }
else
    ms_now() { perl -MTime::HiRes -e 'print int(Time::HiRes::time()*1000), "\n"'; }
fi

BASE_URL="${MAPPER_URL:-http://localhost:8080}"
ENDPOINT="/test/mapping"
ITERATIONS=1
REPORT_INTERVAL=1
DELAY_MS=50

extract_version() {
    local pom_file="${PROJECT_ROOT}/pom.xml"
    if [ ! -f "${pom_file}" ]; then echo "unknown"; return; fi
    local version
    version=$(grep -A 5 '<properties>' "${pom_file}" | grep '<revision>' | sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' | tr -d '[:space:]')
    if [ -z "${version}" ]; then
        version=$(grep '<version>' "${pom_file}" | head -1 | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | tr -d '[:space:]')
    fi
    echo "${version:-unknown}"
}

if [ -z "$1" ]; then
    echo "ERROR: Test request file name not provided"
    echo "Usage: $0 <request-file-name> [iterations] [report-interval] [delay-ms]"
    echo "Example: $0 test-inbound-request_01.json 1000 100 50"
    exit 1
fi

TEST_REQUEST_FILE="${SCRIPT_DIR}/$1"
[ -n "$2" ] && ITERATIONS=$2
[ -n "$3" ] && REPORT_INTERVAL=$3
[ -n "$4" ] && DELAY_MS=$4

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
VERSION=$(extract_version)

echo "=========================================="
echo "Load Test: dynamic-mapper-service"
echo "Version: ${VERSION}"
echo "=========================================="
echo "Endpoint: ${BASE_URL}${ENDPOINT}"
echo "Request file: $1"
echo "Total iterations: ${ITERATIONS}"
echo "Report interval: ${REPORT_INTERVAL}"
echo "Delay between requests: ${DELAY_MS}ms"
echo "Auth: ${DISPLAY_USER} (Basic)"
echo "=========================================="
echo ""

if [ ! -f "${TEST_REQUEST_FILE}" ]; then
    echo "ERROR: Test request file not found: ${TEST_REQUEST_FILE}"
    exit 1
fi

curl_post() {
    local out
    out=$(curl -s -w "\n%{http_code}" \
      -X POST "${BASE_URL}${ENDPOINT}" \
      -H "accept: application/json" \
      -H "${MAPPER_AUTH_HEADER}" \
      -H "content-type: application/json" \
      -d @"$1" 2>/dev/null)
    local http_code body
    http_code=$(echo "${out}" | tail -1)
    body=$(echo "${out}" | sed '$d')
    echo "${http_code}|${body}"
}

# Test endpoint availability
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

# Counters and timing
success=0
failed=0
total_latency_ms=0
min_latency_ms=999999
max_latency_ms=0
start_time=$(date +%s)

echo "Starting load test..."
echo ""

for i in $(seq 1 "${ITERATIONS}"); do
    req_start=$(ms_now)

    probe=$(curl_post "${TEST_REQUEST_FILE}")
    response="${probe%%|*}"
    body="${probe#*|}"

    req_end=$(ms_now)
    latency_ms=$((req_end - req_start))
    total_latency_ms=$((total_latency_ms + latency_ms))
    [ "${latency_ms}" -lt "${min_latency_ms}" ] && min_latency_ms=${latency_ms}
    [ "${latency_ms}" -gt "${max_latency_ms}" ] && max_latency_ms=${latency_ms}

    if [ "${response}" = "200" ] || [ "${response}" = "201" ] || [ "${response}" = "204" ]; then
        ((success++))
    else
        ((failed++))
        if [ "${failed}" -le 3 ]; then
            echo "  Error ${failed}: HTTP ${response} — ${body}"
        fi
    fi

    if [ $((i % REPORT_INTERVAL)) -eq 0 ]; then
        current_time=$(date +%s)
        elapsed=$((current_time - start_time))
        rate=$([ "${elapsed}" -gt 0 ] && echo $((i / elapsed)) || echo 0)
        avg_lat=$((total_latency_ms / i))
        echo "Progress: ${i}/${ITERATIONS} | success=${success} failed=${failed} | ${rate} req/s | avg=${avg_lat}ms min=${min_latency_ms}ms max=${max_latency_ms}ms"

        if [ $((i % 500)) -eq 0 ]; then
            echo "  → Perform GC in VisualVM / JFR snapshot and check memory usage"
        fi
    fi

    if [ "${DELAY_MS}" -gt 0 ]; then
        sleep "0.$(printf '%03d' "${DELAY_MS}")"
    fi
done

end_time=$(date +%s)
total_time=$((end_time - start_time))
avg_latency_ms=$((total_latency_ms / ITERATIONS))

echo ""
echo "=========================================="
echo "Load Test Complete"
echo "=========================================="
echo "Request file:    $1"
echo "Total requests:  ${ITERATIONS}"
echo "Successful:      ${success}"
echo "Failed:          ${failed}"
echo "Total time:      ${total_time}s"

if [ "${total_time}" -gt 0 ]; then
    echo "Throughput:      $((ITERATIONS / total_time)) req/s"
fi

success_rate=$(awk "BEGIN { printf \"%.1f\", ${success}*100/${ITERATIONS} }")
echo "Success rate:    ${success_rate}%"
echo ""
echo "Latency (ms):    avg=${avg_latency_ms}  min=${min_latency_ms}  max=${max_latency_ms}"
echo "=========================================="

if [ "${failed}" -gt $((ITERATIONS / 10)) ]; then
    echo ""
    echo "WARNING: More than 10% of requests failed!"
    exit 1
fi
