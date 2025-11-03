#!/bin/bash
# File: resources/script/performance/profiler/test-generator.sh

BASE_URL="http://localhost:8080"
ENDPOINT="/test/mapping"
ITERATIONS=1
REPORT_INTERVAL=1
TEST_REQUEST_DIR="resources/script/performance/profiler"

# Check if request file name is provided
if [ -z "$1" ]; then
    echo "ERROR: Test request file name not provided"
    echo "Usage: $0 <request-file-name> [iterations] [report-interval]"
    echo "Example: $0 test-inbound-request_01.json 1000 100"
    exit 1
fi

TEST_REQUEST_FILE="${TEST_REQUEST_DIR}/$1"

# Optional parameters
if [ -n "$2" ]; then
    ITERATIONS=$2
fi

if [ -n "$3" ]; then
    REPORT_INTERVAL=$3
fi

echo "=========================================="
echo "Load Test: FlowProcessorOutboundProcessor"
echo "Service: dynamic-mapper-service-6.1.1-SNAPSHOT"
echo "=========================================="
echo "Endpoint: ${BASE_URL}${ENDPOINT}"
echo "Request file: $1"
echo "Total iterations: ${ITERATIONS}"
echo "Report interval: ${REPORT_INTERVAL}"
echo "=========================================="
echo ""

# Load environment variables
if [ -f "setup-env.sh" ]; then
    source setup-env.sh
else
    echo "ERROR: setup-env.sh not found"
    exit 1
fi

# Check test request file
if [ ! -f "${TEST_REQUEST_FILE}" ]; then
    echo "ERROR: Test request file not found: ${TEST_REQUEST_FILE}"
    exit 1
fi

echo "Using auth: ${C8Y_TENANT}/${C8Y_USER}"
echo ""

# Test endpoint
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
    echo "ERROR: Authentication failed"
    exit 1
fi

echo "✓ Endpoint responding (HTTP ${response})"
echo ""

# Counters
success=0
failed=0
start_time=$(date +%s)

echo "Starting load test..."
echo ""

# Run load test
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
    fi
    
    # Progress report
    if [ $((i % REPORT_INTERVAL)) -eq 0 ]; then
        current_time=$(date +%s)
        elapsed=$((current_time - start_time))
        
        if [ ${elapsed} -gt 0 ]; then
            rate=$((i / elapsed))
        else
            rate=0
        fi
        
        echo "Progress: ${i}/${ITERATIONS} (${success} success, ${failed} failed, ${rate} req/s)"
        
        # Suggest GC in VisualVM
        if [ $((i % 500)) -eq 0 ]; then
            echo "  → Perform GC in VisualVM and check memory usage"
        fi
    fi
    
    sleep 0.05
done

end_time=$(date +%s)
total_time=$((end_time - start_time))

echo ""
echo "=========================================="
echo "Load Test Complete"
echo "=========================================="
echo "Request file: $1"
echo "Total iterations: ${ITERATIONS}"
echo "Successful: ${success}"
echo "Failed: ${failed}"

if [ ${ITERATIONS} -gt 0 ]; then
    success_rate=$(echo "scale=2; ${success}*100/${ITERATIONS}" | bc 2>/dev/null || echo "N/A")
    echo "Success rate: ${success_rate}%"
fi

if [ ${total_time} -gt 0 ]; then
    avg_rate=$((ITERATIONS / total_time))
    echo "Total time: ${total_time} seconds"
    echo "Average rate: ${avg_rate} req/s"
else
    echo "Total time: < 1 second"
fi

echo "=========================================="

if [ ${failed} -gt $((ITERATIONS / 10)) ]; then
    echo ""
    echo "⚠️  WARNING: More than 10% of requests failed!"
fi