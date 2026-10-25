#!/usr/bin/env bash
#
# Samples the LIVE SET (heap in use right after a full GC) of a running JVM over time, so -Xmx and
# the container memory request can be chosen from data instead of from the 90%-of-limit default.
#
# Each tick: `jcmd <pid> GC.run` (a full, stop-the-world collection) then `jcmd <pid> GC.heap_info`.
# Heap "used" straight after a full GC is what the application really holds on to.
#
# Usage:
#   ./measure-live-set.sh [--pid PID] [--interval SEC] [--duration SEC] [--out FILE.csv]
#
#   --pid PID        JVM to sample (default: the dynamic-mapper-service process, if exactly one)
#   --interval SEC   seconds between samples (default 60)
#   --duration SEC   stop after this long; 0 = until Ctrl-C (default 0)
#   --out FILE       CSV output (default results/live-set-<timestamp>.csv next to this script)
#
# Use it on a LOCAL run only: GC.run pauses the application. Needs a full JDK (jcmd), e.g. the
# sdkman JDK used for the build — the production image has no jcmd.
#
# CSV columns: timestamp, elapsed_s, live_heap_mb, heap_committed_mb, metaspace_used_mb, rss_mb

set -euo pipefail

PID=""
INTERVAL=60
DURATION=0
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --pid)      PID=$2; shift 2 ;;
        --interval) INTERVAL=$2; shift 2 ;;
        --duration) DURATION=$2; shift 2 ;;
        --out)      OUT=$2; shift 2 ;;
        -h|--help)  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.sdkman/candidates/java/25.0.3-zulu" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-zulu"
fi
JCMD="${JAVA_HOME:+$JAVA_HOME/bin/}jcmd"
command -v "$JCMD" >/dev/null 2>&1 || [ -x "$JCMD" ] || { echo "jcmd not found (need a full JDK)" >&2; exit 1; }

if [ -z "$PID" ]; then
    # jcmd lists the main class / jar; the service shows up as dynamic.mapper.App or the jar name.
    CANDIDATES=$("$JCMD" -l | grep -iE "dynamic[.-]mapper" | grep -v "JCmd" | awk '{print $1}' || true)
    COUNT=$(printf '%s\n' "$CANDIDATES" | grep -c . || true)
    if [ "$COUNT" -ne 1 ]; then
        echo "Found $COUNT dynamic-mapper JVMs; pass --pid. jcmd -l says:" >&2
        "$JCMD" -l >&2
        exit 1
    fi
    PID=$CANDIDATES
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$OUT" ]; then
    mkdir -p "$HERE/results"
    OUT="$HERE/results/live-set-$(date +%Y%m%d-%H%M%S).csv"
fi

echo "Sampling PID $PID every ${INTERVAL}s -> $OUT"
echo "timestamp,elapsed_s,live_heap_mb,heap_committed_mb,metaspace_used_mb,rss_mb" > "$OUT"
START=$(date +%s)

# "...used 123456K..." -> MB with one decimal; empty when the line is missing (never fails the script).
heap_mb() {   # <text> <used|committed>
    printf '%s\n' "$1" | grep -iE "garbage-first heap|^ *(PS|Shenandoah|ZHeap|def new|Heap)" | head -1 \
        | sed -nE "s/.* $2 ([0-9]+)K.*/\1/p" | awk '{printf "%.1f", $1/1024}' || true
}
# Metaspace is not part of GC.heap_info on current JDKs; the perf counter is in bytes.
metaspace_mb() {   # <pid>
    "$JCMD" "$1" PerfCounter.print 2>/dev/null | sed -nE 's/^sun\.gc\.metaspace\.used=([0-9]+)$/\1/p' \
        | awk '{printf "%.1f", $1/1048576}' || true
}

summary() {
    echo
    echo "== Live set summary ($OUT) =="
    awk -F, 'NR>1 && $3!="" {
        n++; if (n==1) {first=$3; t0=$2} last=$3; t1=$2
        if ($3>max) max=$3; if (min==""||$3<min) min=$3
        if ($6>rssmax) rssmax=$6; if ($5>meta) meta=$5
    } END {
        if (n==0) { print "no samples"; exit }
        printf "samples: %d over %.0f min\n", n, (t1-t0)/60
        printf "live heap MB: first %.0f, last %.0f, min %.0f, max %.0f\n", first, last, min, max
        if (t1>t0) printf "trend: %+.0f MB/hour (%s)\n", (last-first)/((t1-t0)/3600), ((last-first)/((t1-t0)/3600) > 25 ? "still growing — keep sampling, or look for a leak" : "flat")
        printf "peak metaspace MB: %.0f, peak RSS MB: %.0f\n", meta, rssmax
        h=int(max*2); if (h<512) h=512
        printf "rule of thumb: -Xmx about 2x the max live set = %d MB (never below 512)\n", h
    }' "$OUT"
    echo "Container memory request ~ Xmx + metaspace + 300-400 MB (stacks, code cache, native); confirm with RSS above."
}
trap 'summary; exit 0' INT TERM

while true; do
    NOW=$(date +%s)
    ELAPSED=$((NOW - START))
    "$JCMD" "$PID" GC.run >/dev/null 2>&1 || { echo "jcmd GC.run failed — is PID $PID still running?" >&2; summary; exit 1; }
    INFO=$("$JCMD" "$PID" GC.heap_info 2>/dev/null || true)
    LIVE=$(heap_mb "$INFO" used)
    COMMITTED=$(heap_mb "$INFO" committed)
    META=$(metaspace_mb "$PID")
    RSS=$(ps -o rss= -p "$PID" 2>/dev/null | awk '{printf "%.0f", $1/1024}' || true)
    printf '%s,%s,%s,%s,%s,%s\n' "$(date +%H:%M:%S)" "$ELAPSED" "${LIVE:-}" "${COMMITTED:-}" "${META:-}" "${RSS:-}" >> "$OUT"
    printf '%s  t=%5ss  live heap %7s MB  (committed %s)  metaspace %s MB  rss %s MB\n' \
        "$(date +%H:%M:%S)" "$ELAPSED" "${LIVE:-?}" "${COMMITTED:-?}" "${META:-?}" "${RSS:-?}"
    if [ "$DURATION" -gt 0 ] && [ "$ELAPSED" -ge "$DURATION" ]; then
        break
    fi
    sleep "$INTERVAL"
done
summary
