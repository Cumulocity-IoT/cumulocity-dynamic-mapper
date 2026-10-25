#!/usr/bin/env bash
#
# Verifies which GraalVM/Truffle runtime the dynamic mapper really runs on, and whether closing a
# GraalVM Engine reclaims Metaspace — the premise behind the engine rotation in
# GraalVMContextService (see docs/planning/IMPLEMENTATION-PLAN-GRAALVM-JS-IMPROVEMENTS.md §4.1).
#
# Usage:
#   ./verify-graalvm-runtime.sh [--sources N] [--nmt] [--image IMAGE]
#
#   --sources N   distinct JS sources to load in the memory experiment (default 300; the service
#                 rotates its Engine after 100)
#   --nmt         also run the JVM with Native Memory Tracking and print the summary at exit
#   --image IMG   additionally inspect the JDK inside a built container image (needs docker)
#
# Environment:
#   JAVA_HOME     JDK to probe (default: the sdkman JDK 25 used for the build, if present).
#                 Run it once per JDK you care about, e.g. the production-like one and a GraalVM JDK.
#   HEAP          -Xmx for the probe (default 512m, small enough to make growth visible)
#
# The probe uses the exact polyglot/js versions of dynamic-mapper-service (resolved offline from
# ~/.m2 via Maven), so run `mvn -o dependency:resolve` there once if they are missing.

set -euo pipefail

SOURCES=300
NMT=false
IMAGE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --sources) SOURCES=$2; shift 2 ;;
        --nmt)     NMT=true; shift ;;
        --image)   IMAGE=$2; shift 2 ;;
        -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
SERVICE="$REPO/dynamic-mapper-service"
MVN="${MVN:-$(command -v mvn || echo /opt/homebrew/bin/mvn)}"

if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.sdkman/candidates/java/25.0.3-zulu" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-zulu"
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
HEAP="${HEAP:-512m}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/graalvm-probe.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "=============================================================="
echo " GraalVM runtime verification"
echo "=============================================================="
echo "JDK under test : $("$JAVA" -version 2>&1 | head -1)  ($JAVA)"

# ── 0. Optional: the JDK inside the production image ──────────────────────────
if [ -n "$IMAGE" ]; then
    echo
    echo "== 0. JDK inside image $IMAGE =="
    docker run --rm --entrypoint sh "$IMAGE" -c '
        java -version 2>&1 | head -2
        echo "--- Graal / JVMCI modules:"
        java --list-modules | grep -iE "graal|vm\.ci" || echo "NONE — no Graal compiler in this image"
        echo "--- jcmd available:"
        command -v jcmd || echo "no (jdk.jcmd module not in the jlink image)"
    '
fi

# ── Classpath of the service's GraalVM dependencies ───────────────────────────
echo
echo "Resolving polyglot/js classpath from dynamic-mapper-service (offline)..."
( cd "$SERVICE" && "$MVN" -q -o dependency:build-classpath \
    -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeGroupIds=org.graalvm.polyglot,org.graalvm.js,org.graalvm.truffle,org.graalvm.regex,org.graalvm.shadowed,org.graalvm.sdk,org.graalvm.nativeimage,org.graalvm.collections,org.graalvm.word,org.graalvm.wasm \
    >"$WORK/mvn.log" 2>&1 ) || {
    echo "Could not resolve the classpath; Maven output:" >&2
    tail -20 "$WORK/mvn.log" >&2
    exit 1
}
CP="$(cat "$WORK/cp.txt")"
if [ -z "$CP" ]; then
    echo "Classpath came out empty — is org.graalvm.polyglot in ~/.m2?" >&2
    exit 1
fi

# ── Run the probe ─────────────────────────────────────────────────────────────
JVM_FLAGS=(-Xmx"$HEAP" -XX:+UseG1GC)
if $NMT; then
    JVM_FLAGS+=(-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics)
fi
echo "JVM flags      : ${JVM_FLAGS[*]}"

"$JAVA" "${JVM_FLAGS[@]}" -cp "$CP" "$HERE/RuntimeProbe.java" "$SOURCES"

echo
echo "Next steps:"
echo "  * Run again with the GraalVM JDK (JAVA_HOME=...) to see what the Metaspace/heap numbers"
echo "    look like when the Graal compiler IS active."
echo "  * --nmt prints the JVM's native memory breakdown at exit (Class, Code, Arena, ...)."
echo "  * Production uses -XX:MaxMetaspaceSize=500m and a heap sized from MEMORY_LIMIT"
echo "    (dynamic-mapper-service/target/docker-work/resources/entrypoint.sh)."
