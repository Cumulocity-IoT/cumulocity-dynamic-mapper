# GraalVM Smart Function memory — consolidated results

Memory and runtime measurements for the Smart Function (GraalVM JS) integration, collected October 2026
while looking for ways to lower the service's memory consumption (operating cost).
Background, decisions and the resulting changes: [docs/planning/IMPLEMENTATION-PLAN-GRAALVM-JS-IMPROVEMENTS.md](../../../../docs/planning/IMPLEMENTATION-PLAN-GRAALVM-JS-IMPROVEMENTS.md).
Raw outputs are in [results/](results/); every number below can be found there.

**Environment (all runs):** macOS arm64, OpenJDK 25.0.3 (Zulu25.34+17-CA), GraalVM polyglot/js 25.4.x on the
stock JDK, G1. The production image is also a stock OpenJDK (Alpine `openjdk25`, `jlink`), so the same
runtime. Absolute numbers are from one machine; compare ratios.

## Summary

| Question | Result | Source |
|---|---|---|
| Is a Graal compiler (JIT) active? | **No — interpreter-only.** No `jdk.graal.compiler`/JVMCI modules; Truffle reports the fallback runtime; the hot loop does not speed up (about 540–560 ms on every repeat). | `runtime-probe-*` |
| Does Metaspace grow with the number of mapping sources? | **No.** +3.0 MB for 300 sources, +3.9 MB for 4,000; closing the Engine does not change it. | `runtime-probe-*` |
| What does an Engine hold per distinct source? | About **6–8 KB** of heap per small source (roughly 50 KB for template-sized sources), released by closing the Engine. | `runtime-probe-*`, `context-pool-measurement.txt` |
| What does a pooled context cost? | About **85 KB** of heap each, constant from 40 to 1,000 contexts; closing the pool releases it. | `context-pool-measurement.txt` |
| Live heap of the running service under load | **67–77 MB** (after full GC), flat for 47–78 minutes; no leak. | `live-set-run1/2` |
| Metaspace of the running service | **107–117 MB**, flat. | `live-set-run1/2` |
| Process resident size (macOS `ps`) | 180–381 MB, no trend. | `live-set-run1/2` |
| `atob`/`btoa` implementation | Host function is fastest; pure JS is 22–540× slower under the interpreter. | `base64-benchmark.txt` |

Consequences already applied: Smart Function `atob`/`btoa` became host functions, the host-access
allow-list, a corrected description of the runtime, the engine rotation default raised from 100 to 1000, and
the time-based rotation removed. Open: lower the memory request in steps (committed value 2 GB).

## 1. Runtime and Engine retention probe

`runtime-probe-300-sources.txt`, `runtime-probe-4000-sources.txt` — `resources/testing/performance/graalvm-memory/verify-graalvm-runtime.sh`,
`-Xmx1g`. Tiny synthetic sources, each in its own Context on one shared Engine.

Runtime (same in both runs): `java.vendor.version Zulu25.34+17-CA`; modules `jdk.graal.compiler`,
`jdk.internal.vm.compiler`, `jdk.internal.vm.ci` absent; `engine.TraceCompilation` unknown; hot loop of 3e7
iterations at 558 / 540 / 550 / 560 ms (300-source run) and 557 / 540 / 546 / 547 ms (4,000-source run) —
no speed-up. **Verdict: interpreter-only.**

| Distinct sources | Heap, contexts open | Heap, contexts closed (Engine open) | Heap, Engine closed and references dropped | Metaspace delta |
|---|---|---|---|---|
| 300 | +23.2 MB | +2.6 MB (≈ 9 KB/source) | back to baseline (−1.9 MB retained) | +3.0 MB |
| 4,000 | +289.6 MB | +24.7 MB (≈ 6 KB/source) | back to baseline (−24.0 MB retained) | +3.9 MB |

Reading: the Engine's retained heap is linear in the number of distinct sources and is released only by
closing it. Metaspace does not grow with sources. (An earlier version of this probe reported that closing
the Engine frees nothing; the probe still referenced the closed Engine. Fixed — numbers above are from the
corrected probe.)

## 2. Context pool, real service code

`context-pool-measurement.txt` — `GraalVMContextMemoryMeasurementTest` (opt-in), the real
`GraalVMContextService`, the real SHARED/SYSTEM templates and the 17 shipped Smart Function templates as
mappings (varied per mapping), M mappings × K idle pooled contexts, heap after full GC.

| Mappings | Contexts each | Contexts | Idle heap | Per context | Freed on pool close |
|---|---|---|---|---|---|
| 1 | 1 | 1 | 0.8 MB | ~840 KB (one-time class/init cost) | – |
| 10 | 4 | 40 | 3.9 MB | ~100 KB | 3.1 MB |
| 10 | 20 | 200 | 16.6 MB | ~85 KB | 14.8 MB |
| 50 | 4 | 200 | 17.6 MB | ~90 KB | 17.1 MB |
| 50 | 20 | 1,000 | 82.9 MB | ~85 KB | 80.4 MB |

Caveats: most `onMessage` calls threw part-way (the stub payload does not fit the templates), so AST
specialisation from real traffic is under-counted. With 17 distinct template bodies, the ~2.6 MB left after
closing the 1,000-context pool for 50 distinct sources gives the rough ~50 KB-per-template-source figure.

## 3. Live-set measurement, running service

`live-set-run1-3mappings-4publishers.csv`, `live-set-run2-3mappings-8publishers-verified.csv` — local mapper
(`-Xmx2g`, G1, string deduplication, `MaxMetaspaceSize=500m`, NMT on), connected to the tenant, driven by
`run-live-set-load.sh`: mappings 02/03/04 of the integration suite's `smartfunction` group (getManagedObjectByExternalId
lookups, per-device flow state, implicit device creation), 30 devices each, published to HiveMQ over TLS.
Sampled by `measure-live-set.sh` (`GC.run` + `GC.heap_info`, every 30–60 s).

| | Run 1 | Run 2 |
|---|---|---|
| Publishers | 4 | 8 |
| Samples / duration | 48 / 47 min | 151 / 78 min |
| Live heap first → last (min – max) | 67.2 → 74.6 MB (67.2 – 76.6) | 73.7 → 74.6 MB (67.5 – 76.9) |
| Committed heap | 180 MB, constant | 144 MB, constant |
| Metaspace first → peak | 106.9 → 116.6 MB | 110.4 → 116.6 MB |
| RSS (macOS) | 204 – 381 MB | 180 – 363 MB |
| Load verified by the mapper's counters | not available (Step 5b did not exist yet) | **1,074 published, 1,074 received, 0 errors** (02: 349/349, 03: 348/348, 04: 377/377) |

Reading: the live set settles at about 75 MB within the first minutes of load and stays flat for the rest of
the run, including long after the load ended; about 7 MB stays retained (caches). Run 1's load is supported
by the publisher's counts (about 4,740 messages sent, 0 failed) and by the matching step in live heap, but
its processing was only confirmed in run 2.

Limits: the load was light (about 1.8 messages per second in run 2), only 3 mappings, 90 devices, one tenant;
no engine rotation happened (it needs 1,000 unique compilations by default). macOS `ps` RSS is not comparable
to a Linux container's.

## 4. atob / btoa

`base64-benchmark.txt` — `Base64PolyfillBenchmarkTest` (opt-in), median of 7 runs in ms, production host
access, interpreter-only Engine.

| Payload | Op | Host function (now) | `Java.type` (before) | Pure JS (rejected) |
|---|---|---|---|---|
| 1 KB | btoa | 0.11 | 0.67 (6.3×) | 2.36 (22×) |
| 1 KB | atob | 0.12 | 0.43 (3.4×) | 4.20 (34×) |
| 64 KB | btoa | 0.38 | 1.10 (2.9×) | 49.2 (129×) |
| 64 KB | atob | 0.88 | 0.62 (0.7×) | 74.2 (84×) |
| 1 MB | btoa | 1.45 | 6.04 (4.2×) | 781.6 (540×) |
| 1 MB | atob | 5.44 | 7.86 (1.4×) | 1,209.6 (222×) |
| 8 MB | btoa | 11.49 | 53.64 (4.7×) | not measured |
| 8 MB | atob | 35.94 | 55.74 (1.6×) | not measured |

## Corrections made along the way

- The committed `cumulocity.json` requests 0.5 CPU / 2 GB. An uncommitted local edit read 8 CPU / 8 GB when the
  first estimates were drafted; the "6.5 GB heap" figure came from it and was wrong for the committed value.
- The class comment and the in-app docs said Graal JIT output fills Metaspace. The runtime is interpreter-only
  and Metaspace stays flat; texts corrected. The in-app docs' old example log (Metaspace 112 → 287 MB over 39
  mappings) was not reproduced; if it came from a real run, find out which runtime produced it.

## Reproducing

The scripts live in this directory (`verify-graalvm-runtime.sh`, `measure-live-set.sh`, `run-live-set-load.sh`,
`RuntimeProbe.java`), with their guide in [README-live-set-measurement.md](README-live-set-measurement.md).
New live-set CSVs are written to `results/` by default. The opt-in tests are in
`dynamic-mapper-service/src/test/java/dynamic/mapper/core/` (`-Dbenchmark=true`; the class names end in `Test` on
purpose because Surefire skips other names):

```bash
# runtime / Engine retention probe (outside Maven)
HEAP=1g resources/testing/performance/graalvm-memory/verify-graalvm-runtime.sh --sources 4000
# context pool, real service code
cd dynamic-mapper-service && mvn test -Dtest=GraalVMContextMemoryMeasurementTest -Dbenchmark=true
# atob/btoa
cd dynamic-mapper-service && mvn test -Dtest=Base64PolyfillBenchmarkTest -Dbenchmark=true
# live set: local service, then in two more terminals
resources/testing/performance/graalvm-memory/measure-live-set.sh --interval 60
resources/testing/performance/graalvm-memory/run-live-set-load.sh --local --devices 30 --duration 1800 --publishers 8
```
