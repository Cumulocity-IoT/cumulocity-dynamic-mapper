#!/usr/bin/env python3
"""Reads one integer per line and reports what arrived, tolerant of reordering and duplicates.

A value is MISSED only if a higher value arrived and it is still absent GRACE seconds later.
Duplicates are counted, not printed one by one. Ctrl-C prints a summary.
Env: GRACE=30 (seconds to wait for late values).
"""
import os
import signal
import sys
import threading
import time

GRACE = float(os.environ.get("GRACE", "30"))
lock = threading.Lock()
count = {}          # value -> times received
pending = {}        # value -> time a higher value was first seen while this one was absent
missed = set()
highest = None
dups = 0


def ts():
    return time.strftime("%H:%M:%S")


def say(msg):
    print(f"{ts()} {msg}", flush=True)


def sweep():
    while True:
        time.sleep(1)
        with lock:
            late = sorted(v for v, t in pending.items() if time.time() - t >= GRACE)
            for v in late:
                del pending[v]
                missed.add(v)
            if late:
                say(f"MISSED {fmt(late)}  (not received within {GRACE:.0f}s)")


def fmt(values):
    out, start, prev = [], None, None
    for v in values:
        if start is None:
            start = prev = v
        elif v == prev + 1:
            prev = v
        else:
            out.append(f"{start}" if start == prev else f"{start}..{prev}")
            start = prev = v
    if start is not None:
        out.append(f"{start}" if start == prev else f"{start}..{prev}")
    return ", ".join(out)


def summary(*_):
    with lock:
        say(f"summary: distinct={len(count)} duplicates={dups} missed={fmt(sorted(missed)) or 'none'} "
            f"waiting={fmt(sorted(pending)) or 'none'}")
    os._exit(0)


def main():
    global highest, dups
    signal.signal(signal.SIGINT, summary)
    signal.signal(signal.SIGTERM, summary)
    threading.Thread(target=sweep, daemon=True).start()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        v = int(float(line))
        with lock:
            if v in count:
                count[v] += 1
                dups += 1
                continue
            count[v] = 1
            if highest is None:
                highest = v
                say(f"received {v} (first)")
            elif v > highest:
                if v > highest + 1:
                    for m in range(highest + 1, v):
                        pending.setdefault(m, time.time())
                    say(f"received {v}  (waiting for {fmt(range(highest + 1, v))})")
                else:
                    say(f"received {v}")
                highest = v
            elif v in pending:
                del pending[v]
                say(f"received {v}  (late, out of order)")
            elif v in missed:
                missed.discard(v)
                say(f"received {v}  (arrived after being reported missed)")
            else:
                say(f"received {v}  (older than the first value seen)")
    summary()


main()
