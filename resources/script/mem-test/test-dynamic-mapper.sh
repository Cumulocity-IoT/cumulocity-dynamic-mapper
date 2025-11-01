#!/bin/bash
# File: resources/script/mem-test/run-dynamic-mapper-for-test.sh

# Configuration
PROJECT_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROFILE="memtest"
HEAP_DUMP_PATH="/tmp/heapdumps"
GC_LOG_PATH="/tmp/gc-logs"

# Extract version from pom.xml
extract_version() {
    local pom_file="${PROJECT_ROOT}/pom.xml"
    
    if [ ! -f "${pom_file}" ]; then
        echo "ERROR: pom.xml not found at ${pom_file}" >&2
        exit 1
    fi
    
    # Try to extract revision property
    local version=$(grep -A 5 '<properties>' "${pom_file}" | grep '<revision>' | sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' | tr -d '[:space:]')
    
    # If revision not found, try to get version directly
    if [ -z "${version}" ]; then
        version=$(grep '<version>' "${pom_file}" | head -1 | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | tr -d '[:space:]')
    fi
    
    # If still not found, try alternative grep method
    if [ -z "${version}" ]; then
        version=$(grep 'revision' "${pom_file}" | grep -oP '<revision>\K[^<]+' | head -1)
    fi
    
    if [ -z "${version}" ]; then
        echo "ERROR: Could not extract version from pom.xml" >&2
        exit 1
    fi
    
    echo "${version}"
}

VERSION=$(extract_version)
JAR_FILE="${PROJECT_ROOT}/dynamic-mapper-service/target/dynamic-mapper-service-${VERSION}.jar"

# Create directories
mkdir -p ${HEAP_DUMP_PATH}
mkdir -p ${GC_LOG_PATH}

# Get timestamp
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo "=========================================="
echo "Starting dynamic-mapper-service"
echo "Version: ${VERSION}"
echo "Profile: ${PROFILE}"
echo "Java Version: $(java --version | head -1)"
echo "=========================================="
echo "Project root: ${PROJECT_ROOT}"
echo "JAR file: ${JAR_FILE}"
echo "Heap dumps: ${HEAP_DUMP_PATH}"
echo "GC logs: ${GC_LOG_PATH}"
echo "JMX port: 9010"
echo "=========================================="

# Load environment variables
if [ -f "setup-env.sh" ]; then
    source setup-env.sh
else
    echo "ERROR: setup-env.sh not found"
    exit 1
fi

# Check if JAR exists
if [ ! -f "${JAR_FILE}" ]; then
    echo "ERROR: JAR file not found: ${JAR_FILE}"
    echo ""
    echo "Please build the project first:"
    echo "  cd ${PROJECT_ROOT}"
    echo "  mvn clean package -DskipTests"
    exit 1
fi

echo "Setting environment variables..."
echo "✓ Environment variables set"
echo ""
echo "Starting service..."
echo ""

java \
  -Dcom.sun.management.jmxremote=true \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.local.only=false \
  -Djava.rmi.server.hostname=localhost \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=${HEAP_DUMP_PATH}/oom_heapdump_${TIMESTAMP}.hprof \
  -XX:+UseG1GC \
  -Xlog:gc*:file=${GC_LOG_PATH}/gc_${TIMESTAMP}.log:time,level,tags \
  -Xms512m \
  -Xmx2048m \
  -XX:MaxMetaspaceSize=512m \
  -Dgraalvm.locatorDisabled=false \
  -Dpolyglot.engine.WarnInterpreterOnly=false \
  -Dspring.profiles.active=${PROFILE} \
  -jar ${JAR_FILE}