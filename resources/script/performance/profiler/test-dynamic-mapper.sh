#!/bin/bash
# File: resources/script/performance/profiler/test-dynamic-mapper.sh
#
# Runs the service locally with Spring profiles memtest + dev.
# Credentials are loaded from application-dev.properties (packaged in the JAR).
#
# Required env vars (set automatically by go-c8y-cli / c8y shell):
#   C8Y_BASEURL or C8Y_URL  - Cumulocity tenant URL
#
# Optional env vars:
#   ASYNC_PROFILER_JAR      - path to libasyncProfiler.so for flame-graph capture

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
PROFILE="memtest,dev"
HEAP_DUMP_PATH="/tmp/heapdumps"
GC_LOG_PATH="/tmp/gc-logs"

extract_version() {
    local pom_file="${PROJECT_ROOT}/pom.xml"

    if [ ! -f "${pom_file}" ]; then
        echo "ERROR: pom.xml not found at ${pom_file}" >&2
        exit 1
    fi

    local version
    version=$(grep -A 5 '<properties>' "${pom_file}" | grep '<revision>' | sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' | tr -d '[:space:]')

    if [ -z "${version}" ]; then
        version=$(grep '<version>' "${pom_file}" | head -1 | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | tr -d '[:space:]')
    fi

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

mkdir -p "${HEAP_DUMP_PATH}"
mkdir -p "${GC_LOG_PATH}"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Resolve Java 21 — required because the JAR is compiled with class file version 65.0.
# Priority: JAVA_HOME env var > java_home utility (macOS) > PATH fallback.
resolve_java() {
    # 1. Explicit JAVA_HOME already set to 21+
    if [ -n "${JAVA_HOME}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
        return
    fi
    # 2. SDKMan — glob avoids find's extended-attribute traversal issues on macOS
    if [ -d "${HOME}/.sdkman/candidates/java" ]; then
        local best="" best_minor=0
        for j in "${HOME}/.sdkman/candidates/java"/21.*/bin/java; do
            if [ -x "${j}" ]; then
                # Pick the highest 21.x patch
                local minor
                minor=$(echo "${j}" | grep -oE '21\.[0-9]+' | cut -d. -f2)
                if [ "${minor:-0}" -ge "${best_minor}" ]; then
                    best_minor="${minor:-0}"
                    best="${j}"
                fi
            fi
        done
        [ -n "${best}" ] && echo "${best}" && return
    fi
    # 3. macOS java_home utility — verify it actually returns 21+
    if [ -x /usr/libexec/java_home ]; then
        local jh
        jh=$(/usr/libexec/java_home -v 21 2>/dev/null)
        if [ -n "${jh}" ] && [ -x "${jh}/bin/java" ]; then
            local v
            v=$("${jh}/bin/java" -version 2>&1 | grep -oE 'version "[0-9]+' | grep -oE '[0-9]+$')
            [ "${v:-0}" -ge 21 ] 2>/dev/null && echo "${jh}/bin/java" && return
        fi
    fi
    # 4. Homebrew (Apple Silicon and Intel)
    for prefix in /opt/homebrew /usr/local; do
        local j="${prefix}/opt/openjdk@21/bin/java"
        [ -x "${j}" ] && echo "${j}" && return
    done
    # 5. Fall back to whatever is on PATH and warn
    echo "java"
}

JAVA_BIN=$(resolve_java)
JAVA_ACTUAL_VERSION=$("${JAVA_BIN}" -version 2>&1 | head -1)
JAVA_MAJOR=$(echo "${JAVA_ACTUAL_VERSION}" | grep -oE '[0-9]+\.[0-9]+|[0-9]+' | head -1 | cut -d. -f1)
# Treat "1.x" style (pre-9) versions
[ "${JAVA_MAJOR}" = "1" ] && JAVA_MAJOR=$(echo "${JAVA_ACTUAL_VERSION}" | grep -oE '1\.[0-9]+' | cut -d. -f2)

if [ "${JAVA_MAJOR:-0}" -lt 21 ] 2>/dev/null; then
    echo "ERROR: Java 21+ is required (found ${JAVA_ACTUAL_VERSION})."
    echo "Set JAVA_HOME to a Java 21 installation, or install via:"
    echo "  brew install openjdk@21"
    echo "  sdk install java 21-tem"
    exit 1
fi

echo "=========================================="
echo "Starting dynamic-mapper-service"
echo "Version: ${VERSION}"
echo "Profile: ${PROFILE}"
echo "Java:    ${JAVA_ACTUAL_VERSION}  [${JAVA_BIN}]"
echo "=========================================="
echo "Project root: ${PROJECT_ROOT}"
echo "JAR file: ${JAR_FILE}"
echo "Heap dumps: ${HEAP_DUMP_PATH}"
echo "GC logs: ${GC_LOG_PATH}"
echo "JMX port: 9010"
echo "=========================================="

# Resolve tenant URL from c8y session env vars
C8Y_BASEURL="${C8Y_BASEURL:-${C8Y_URL:-${C8Y_HOST}}}"
if [ -z "${C8Y_BASEURL}" ]; then
    echo "ERROR: C8Y_BASEURL is not set. Run: eval \$(c8y sessions login)"
    exit 1
fi

echo "Tenant URL: ${C8Y_BASEURL}"
echo "=========================================="

if [ ! -f "${JAR_FILE}" ]; then
    echo "ERROR: JAR file not found: ${JAR_FILE}"
    echo ""
    echo "Please build the project first:"
    echo "  cd ${PROJECT_ROOT}"
    echo "  mvn clean package -DskipTests"
    exit 1
fi

# Optional: attach async-profiler if ASYNC_PROFILER_JAR is set
EXTRA_JAVA_ARGS=()
if [ -n "${ASYNC_PROFILER_JAR}" ] && [ -f "${ASYNC_PROFILER_JAR}" ]; then
    EXTRA_JAVA_ARGS+=("-agentpath:${ASYNC_PROFILER_JAR}=start,file=/tmp/profile_${TIMESTAMP}.jfr,jfr")
    echo "Async-profiler: enabled (output: /tmp/profile_${TIMESTAMP}.jfr)"
else
    echo "Async-profiler: disabled (set ASYNC_PROFILER_JAR env var to enable)"
fi
echo "=========================================="
echo ""
echo "Starting service..."
echo ""

"${JAVA_BIN}" \
  -Dcom.sun.management.jmxremote=true \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.local.only=false \
  -Djava.rmi.server.hostname=localhost \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="${HEAP_DUMP_PATH}/oom_heapdump_${TIMESTAMP}.hprof" \
  -XX:+UseG1GC \
  -Xlog:gc*:file="${GC_LOG_PATH}/gc_${TIMESTAMP}.log":time,level,tags \
  -Xms512m \
  -Xmx2048m \
  -XX:MaxMetaspaceSize=512m \
  -Dgraalvm.locatorDisabled=false \
  -Dpolyglot.engine.WarnInterpreterOnly=false \
  -Dspring.profiles.active="${PROFILE}" \
  -Dspring.config.additional-location="file:${PROJECT_ROOT}/dynamic-mapper-service/src/main/resources/" \
  -DC8Y.baseURL="${C8Y_BASEURL}" \
  -Dspring.autoconfigure.exclude="com.cumulocity.microservice.autoconfigure.MicroserviceSubscriptionAutoConfiguration,com.cumulocity.microservice.notification.autoconfigure.MicroserviceSubscriptionNotificationAutoConfiguration" \
  "${EXTRA_JAVA_ARGS[@]+"${EXTRA_JAVA_ARGS[@]}"}" \
  -jar "${JAR_FILE}" &

SERVICE_PID=$!
echo "Service PID: ${SERVICE_PID}"
echo "(attach VisualVM / JFR with: jcmd ${SERVICE_PID} JFR.start filename=/tmp/flight_${TIMESTAMP}.jfr)"
wait "${SERVICE_PID}"
