#!/bin/bash
# File: resources/script/mem-test/warmup-outbound.sh

BASE_URL="http://localhost:8080"
ENDPOINT="/test/mapping"
ITERATIONS=5
TEST_REQUEST_FILE="resources/script/mem-test/test-outbound-request.json"

echo "=========================================="
echo "Warmup: FlowProcessorOutboundProcessor"
echo "Endpoint: ${BASE_URL}${ENDPOINT}"
echo "Iterations: ${ITERATIONS}"
echo "=========================================="

# Load environment variables
if [ -f "./setup-env.sh" ]; then
    source ./setup-env.sh
else
    echo "ERROR: setup-env.sh not found"
    exit 1
fi

# Check if test request file exists
if [ ! -f "${TEST_REQUEST_FILE}" ]; then
    echo "ERROR: Test request file not found: ${TEST_REQUEST_FILE}"
    exit 1
fi

echo ""
echo "Using auth: ${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"
echo ""

# Test endpoint availability
echo "Testing endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "${BASE_URL}${ENDPOINT}" \
  -H "accept: application/json" \
  -H "authorization: Basic ${AUTH_TOKEN}" \
  -H "content-type: application/json" \
  -d @"${TEST_REQUEST_FILE}" 2>/dev/null)

if [ "$response" = "000" ]; then
    echo "ERROR: Cannot connect to service"
    exit 1
fi

if [ "$response" = "401" ] || [ "$response" = "403" ]; then
    echo "ERROR: Authentication failed (${response})"
    echo "Check your credentials in environment variables"
    exit 1
fi

echo "✓ Endpoint responding (HTTP ${response})"
echo ""

# Warmup iterations
echo "Starting warmup..."
success=0
failed=0
start_time=$(date +%s)

for i in $(seq 1 ${ITERATIONS}); do
    response=$(curl -s -o /dev/null -w "%{http_code}" \
      -X POST "${BASE_URL}${ENDPOINT}" \
      -H "accept: application/json" \
      -H "authorization: Basic ${AUTH_TOKEN}" \
      -H "content-type: application/json" \
      -d @"${TEST_REQUEST_FILE}" 2>/dev/null)
    
    if [ "$response" = "200" ] || [ "$response" = "201" ] || [ "$response" = "204" ]; then
        ((success++))
    else
        ((failed++))
        if [ "$failed" -eq 1 ]; then
            echo "  First failure: HTTP ${response}"
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
echo "Total: ${ITERATIONS}"
echo "Success: ${success}"
echo "Failed: ${failed}"
echo "Time: ${elapsed} seconds"

if [ ${success} -gt 0 ]; then
    success_rate=$(echo "scale=2; ${success}*100/${ITERATIONS}" | bc 2>/dev/null || echo "N/A")
    echo "Success rate: ${success_rate}%"
fi

echo "=========================================="

if [ ${failed} -gt $((ITERATIONS / 2)) ]; then
    echo ""
    echo "⚠️  WARNING: More than 50% of requests failed!"
    exit 1
fi