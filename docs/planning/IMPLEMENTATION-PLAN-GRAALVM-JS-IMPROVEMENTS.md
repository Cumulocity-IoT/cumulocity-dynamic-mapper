# Implementation Plan: GraalVM JS integration — hardening and memory

**Status:** Part 1 implemented (host-access allow-list, `atob`/`btoa` as host functions, benchmark).
Part 2 (memory measurement and tuning): runtime verified (§4.1), misleading comments corrected,
context-pool measurement done (§4.2). Heap sizing and the rotation decision are next (§4.3).
**Scope:** Backend only — `core/GraalVMContextService.java`, `processor/AbstractFlowProcessor.java`
and their tests.
**Motivation:** Reduce operating cost through lower memory consumption. Startup time is not a goal
(it happens once). Context: a rewrite of the backend in Rust was assessed and rejected (§1).

---

## 1. Decision record: why not Rust, why not another JS engine (yet)

The backend is about 75k lines of Java (309 files, 158 test classes). The cost of a rewrite is
driven by dependencies with no good Rust equivalent, not by line count. Blockers:

| Blocker | Why |
|---|---|
| Smart Functions run on GraalVM JS | Scripts and shipped templates call into Java via `Java.type`. Rust has no equivalent embedding; `boa` is incomplete, `rquickjs`/`deno_core` have no Java interop. |
| JSONata | `dashjoin/jsonata-java`; the Rust ports are incomplete, so edge-case semantics would drift. |
| Cumulocity microservice SDK | Multi-tenant subscriptions, service-user credentials, Notification 2.0 — no Rust SDK. |

**Not a blocker: the Java extension API.** `ProcessorExtensionInbound/Outbound` (writing an
extension as a Java JAR) is out of scope for a Rust port: that way of writing extensions would not
be migrated and would not be supported. Smart Functions (JavaScript) are the supported extension
mechanism going forward, so the API cost is not counted in the estimate below. This is a
product decision with a consequence: customers with existing Java extensions would have to
rewrite them as Smart Functions, so it needs a deprecation path and communication if a port is
ever pursued.

Rough estimate for a faithful port: 6–12 engineer-months plus 2–3 months of risk buffer, with the
JS runtime and JSONata parity as the main risks. For a **memory** goal that is not justified:
most of the footprint is not Java object overhead (GraalJS contexts, caches, broker clients,
JVM baseline would all remain in some form).

Alternatives considered for the JS engine, none adopted yet:

| Option | Verdict |
|---|---|
| GraalJS, interpreter-only on a plain JDK | Probably what runs today (see §4.1). |
| QuickJS via JNI or WASM (Chicory) | Much lower per-context memory, but no `Java.type`; needs a host bridge. The only realistic engine swap. Worth a spike **after** §4 measurements. |
| Rhino / Nashorn | ES5-level, unmaintained. Rejected. |
| V8 (`javet`, `j2v8`) | Not a memory win. Rejected. |

---

## 2. Implemented: explicit host-access allow-list

**Before:** `HostAccess.newBuilder().allowPublicAccess(true)…` — every public member of every Java
object reachable from a script was callable, including `getClass()` and anything returned from an
allowed class.

**After:** `GraalVMContextService.HOST_ACCESS` is built once from `SCRIPT_ACCESSIBLE_CLASSES`.
`allowPublicAccess(false)`, `allowAccessInheritance(true)`, and for each listed class its public
constructors, methods and fields are allowed (never members declared by `Object`).

| Group | Classes |
|---|---|
| Mapper API | `DataPrepContext` (the script-facing `context`), `InputMessage`, `ExternalId`, `JavaScriptConsole`, `SubstitutionContext`, `SubstitutionResult`, `SubstituteValue` (+ `TYPE`), `RepairStrategy` |
| JDK, reachable via `Java.type` | `String`, `ArrayList`, `HashMap`, `HashSet`, `Arrays`, `Base64` (+ `Encoder`, `Decoder`), `StandardCharsets`, `Charset`, `ByteBuffer` |

Notes:

- `SmartFunctionContext` is deliberately **not** listed. Scripts reach it through the
  `DataPrepContext` interface (inherited access), so internal setters — `setClientId`,
  `setConfig`, `clearState`, `getTesting` — are no longer callable from scripts.
- `isAllowedHostClass` (what `Java.type` may load) and `SCRIPT_ACCESSIBLE_CLASSES` (what may be
  called) are two lists that must agree. A class added to one usually belongs in the other.
- The cancellation helper (`__cancellationHelper`) was an anonymous class; the old test comments
  note that anonymous/package-private classes fail with "Unknown identifier", so it likely never
  worked from JS. It is now a `ProxyObject` with a `ProxyExecutable`, which needs no host access.

**Compatibility risk:** a customer Smart Function that calls methods on a Java class outside the
list (or on an object returned by a non-listed class, e.g. `StringBuilder`) now fails. If a known
script needs one, add the class to `SCRIPT_ACCESSIBLE_CLASSES` and `isAllowedHostClass`.

---

## 3. Implemented: `atob` / `btoa` as injected host functions

**Before:** a JS polyfill evaluated in every context, doing four `Java.type` lookups
(`Base64`, `String`, `StandardCharsets`, `Arrays`). It was duplicated in
`GraalVMContextService` and `AbstractFlowProcessor`.

**After:** `GraalVMContextService.installBase64Functions(Context)` binds two static
`ProxyExecutable`s on the global scope. They call `java.util.Base64` directly:

- no script evaluation and no `Java.type` lookups per context,
- no host-access entries,
- independent of `js.text-encoding`,
- one implementation, used by the pooled path and the non-pooled fallback.

Semantics are unchanged: `btoa` encodes as UTF-8 first; `atob` returns the UTF-8 decoded string
(not a binary string as in browsers). Whitespace in the `atob` input is ignored; other invalid
input throws.

### 3.1 Rejected intermediate: pure-JS implementation

A pure-JS version (own UTF-8 codec, no Java at all) was implemented first and benchmarked. Under
the interpreter-only engine it is 25–480× slower than the host function — a 1 MB decode takes
about 1.2 s and 8 MB about 10 s, enough to hit the CPU budget. It is kept **only** in the
benchmark as the reference.

### 3.2 Benchmark results

`Base64PolyfillBenchmarkTest` (opt-in, §6). Median of 7 runs, milliseconds, production
`HostAccess`, shared Engine with `engine.WarnInterpreterOnly=false`, UTF-8 text with 1-, 2- and
3-byte characters:

| Payload | Op | Host function (now) | `Java.type` (before) | Pure JS (rejected) |
|---|---|---|---|---|
| 1 KB | btoa | 0.10 | 0.67 (6.6×) | 2.41 (24×) |
| 1 KB | atob | 0.13 | 0.41 (3.2×) | 3.78 (29×) |
| 64 KB | btoa | 0.31 | 1.13 (3.7×) | 49.5 (160×) |
| 64 KB | atob | 0.92 | 0.81 (0.9×) | 74.0 (81×) |
| 1 MB | btoa | 1.58 | 6.12 (3.9×) | 755 (478×) |
| 1 MB | atob | 4.62 | 6.89 (1.5×) | 1,207 (261×) |
| 8 MB | btoa | 11.6 | 53.6 (4.6×) | not measured |
| 8 MB | atob | 36.2 | 56.4 (1.6×) | not measured |

The host function is faster than the `Java.type` version in nearly every row (the 64 KB `atob`
row is within noise) because it skips the byte-array / `ByteBuffer` round trip. Numbers are from
one developer machine; compare ratios, not absolutes.

### 3.3 Left as is

The stored SYSTEM code template still contains its own `typeof btoa === 'undefined'`-guarded
polyfill. It is dead code now (the global is already defined). Removing it needs a template
version bump and tenants to run "Reset System Templates", so it was not touched.

---

## 4. Memory measurement and tuning

### 4.1 Which GraalVM runtime is in use — verified: interpreter-only

Evidence:

- `src/main/docker/Dockerfile` installs Alpine's stock `openjdk25` and builds a `jlink` JDK from a
  fixed module list that contains neither `jdk.graal.compiler` nor `jdk.internal.vm.ci`. The
  `-XX:+EnableJVMCI -XX:+UseJVMCICompiler` block in `pom.xml` is commented out.
- `resources/testing/performance/graalvm-memory/verify-graalvm-runtime.sh` on a stock Zulu 25 JDK (the same kind of
  runtime): Truffle reports "fallback runtime that does not support runtime compilation … JVMCI
  is not enabled", `engine.TraceCompilation` is an unknown option, and a 3e7-iteration loop stays
  at about 460–520 ms across four runs (no speed-up).
- Still open: run the script with `--image <image>` against the actual built image.

Consequences:

- No JIT-compiled JS code exists; the comments claiming Graal JIT output fills Metaspace were
  wrong. **Corrected** in `GraalVMContextService` and `MappingService`.
- The warm-up front-loads class initialisation, source parsing and AST specialisation, not JIT.
  The "~7 s → ~1 s" figure predates this finding and has not been re-measured.
- Metaspace is capped at a fixed 300 MB start / 500 MB max by the entrypoint (`metaspace` block
  of the packaging plugin), not a share of container memory.

User-facing texts that carried the old wording (the `@Schema` descriptions of `engineRotationThreshold`
and `engineMaxAgeMinutes` (since removed) in `ServiceConfiguration`, `resources/openAPI/openapi.json` and
`Models/ServiceConfiguration.md`, the Service Configuration screen, and the Engine section of
`dynamic-mapper-ui/public/docs/smartfunction.md`) were updated together with the rotation default
change in §4.3.

### 4.2 Measurements

**Synthetic probe** (`resources/testing/performance/graalvm-memory`): 300 distinct tiny sources in one Engine —
Metaspace +3 MB, not released by `Engine.close()`; heap +23 MB, released when the contexts close
(Engine close adds nothing). So the Metaspace theory behind rotation is not supported.

**Engine retention per distinct source** (`verify-graalvm-runtime.sh --sources N`, one Engine, tiny
sources, contexts closed, Engine still open; and after closing it and dropping references):

| Distinct sources | Heap held by the Engine | Per source | Metaspace |
|---|---|---|---|
| 300 | +2.5 MB | ~8 KB | +3.0 MB |
| 1,500 | +9.8 MB | ~6.5 KB | +3.8 MB |
| 4,000 | +24.7 MB | ~6.2 KB | +3.9 MB |

- Closing the Engine (4,000 sources) released the 24 MB; Metaspace was unaffected and does not grow
  with the number of sources (it levels off at about +4 MB).
- An earlier version of this probe reported "closing the Engine frees nothing"; that was an artefact —
  the probe still referenced the closed Engine. Fixed.
- With the shipped templates (about 2–4 KB of source each) the pool test left about 2.6 MB behind for
  50 distinct sources, i.e. roughly **50 KB per source** — rough and noisy, but about 8× the tiny-source
  figure. So an Engine's held heap is bounded by roughly 6–50 KB × the number of distinct sources.

**Real code** (`GraalVMContextMemoryMeasurementTest`, opt-in, §6): the real
`GraalVMContextService`, the real SHARED and SYSTEM templates, the 17 shipped Smart Function
templates as mappings (varied per mapping), M mappings × K idle pooled contexts, heap after full
GC. Retained heap per context is roughly constant:

| Mappings | Contexts per mapping | Contexts | Heap retained | Per context |
|---|---|---|---|---|
| 1 | 1 | 1 | 1.1 MB | ~1.1 MB (one-time class/init cost) |
| 10 | 4 | 40 | 3.9 MB | ~100 KB |
| 10 | 20 | 200 | 16.7 MB | ~86 KB |
| 50 | 4 | 200 | 17.9 MB | ~92 KB |
| 50 | 20 | 1,000 | 83.0 MB | ~85 KB |

- Closing the pool released 80.4 of those 83 MB, so the contexts are not leaked.
- Extrapolation at ~85 KB: 50 mappings × 20 contexts × 5 tenants = ~415 MB; 100 × 20 × 10 tenants
  = ~1.7 GB. That is the **upper bound**: the pool only grows to what concurrency requested.
- Caveats: the stubbed `onMessage` call throws part-way for most templates (the stub payload does
  not fit them), so AST specialisation from real traffic is under-counted; and this is one JVM on
  one machine. Re-run it with representative mappings before trusting the absolute numbers.

### 4.3 Candidate changes, in revised order

The measurement demotes the pool: at ~85 KB per context even the worst case is far below the
heap the JVM is allowed to use.

1. **Resource request (the cost lever; no code change).** The committed `cumulocity.json` requests
   0.5 CPU / 2 GB. (An uncommitted local edit of that file read 8 CPU / 8 GB when this plan was
   first drafted; the earlier "6.5 GB heap" figure was derived from that edit and does not describe
   the committed configuration.) `entrypoint.sh` derives `-Xmx` from `MEMORY_LIMIT` (about 81 % of
   it: 90 % for the app minus a 10 % metaspace share) and sets a fixed `-XX:MaxMetaspaceSize=500m`.
   With `MEMORY_LIMIT` set it overwrites `JAVA_MEM`, and its computed `-Xmx` comes after `JAVA_OPTS`
   on the command line, so the memory request is effectively the only knob. Measurements (§4.4)
   show a live set of about 75 MB, so the request can be lowered in stages.
2. **Engine rotation — done: keep it as a loose safety net.** The Metaspace rationale is not supported
   (§4.2); what rotation reclaims is Engine-held heap, about 6–50 KB per distinct source. At the old
   default of 100 that is at most 0.6–5 MB per tenant, while every rotation re-warms all active mappings
   and closes the pools. The default threshold was therefore raised from **100 to 1000**
   (`ServiceConfiguration`, `GraalVMContextService.ENGINE_ROTATION_THRESHOLD`, the UI default and its hint,
   OpenAPI, and the in-app Smart Function docs), bounding the held heap at about 6–50 MB per tenant.
   Tenants that already stored a value keep it until it is changed in Service Configuration. The
   time-based trigger (`engineMaxAgeMinutes`, off by default; the 24 h constant was dead code) was
   **removed**: time does not add memory to an Engine, and the `ROTATE_GRAALVM_ENGINE` operation
   remains for on-demand rotation. Old stored values are ignored (`FAIL_ON_UNKNOWN_PROPERTIES` is
   off). Open: rotation under load on the real service was not
   exercised (the live runs compiled only 3 sources); the in-app docs previously showed a log example
   with +175 MB between rotations across 39 mappings that these measurements did not reproduce — if that
   came from a real run, find out which runtime it was.
3. **Pool size.** Still worth bounding (default 20 idle per mapping, ~1.7 MB per mapping at that
   size) and adding idle eviction, but it is a tidy-up, not the main saving.
4. **Per-context footprint.** Keep shared and system code small; they are evaluated into every
   context.
5. **Engine sharing across tenants.** Saves memory but weakens isolation — decide explicitly.
6. **Engine swap (QuickJS)** only if the above is not enough; see §1.

---

### 4.4 Live-set measurement (local run, 3 Smart Function mappings)

All raw results and a consolidated write-up: `resources/testing/performance/graalvm-memory/` (`RESULTS.md`, `results/`).

Setup (`resources/testing/performance/graalvm-memory/README-live-set-measurement.md`): local mapper, production-like
flags (`-Xmx2g`, G1, string deduplication, `MaxMetaspaceSize=500m`, NMT on), mappings 02/03/04 of the
integration suite's `smartfunction` group, 30 devices each, 4 publishers through HiveMQ, sampled every
60 s (`live-set-run1-3mappings-4publishers.csv`).

| Metric | Result |
|---|---|
| Live heap (after full GC) | 67 MB at start, 75–76 MB from minute 6, flat for the remaining 35 min (74.4 MB at the end) |
| Committed heap | constant 180 MB |
| Metaspace | 107 MB at start, 116.4 MB steady |
| RSS (macOS `ps`) | 220–380 MB, no trend |

Reading: no leak and no growth once the caches filled; the live set is two orders of magnitude below
any configured heap limit. Metaspace (about 117 MB) is larger than the synthetic probe suggested
(33 MB) because it includes Spring and the SDK, but it is flat.

**Second run, load verified** (`live-set-run2-3mappings-8publishers-verified.csv`, 8 publishers, driver Step 5b):
the mapper's own counters confirmed the messages were processed — 1,074 published, 1,074 received,
0 errors (mapping 02: 349 received / 349 processed, 03: 348 / 348, 04: 377 / 377).

| Metric (second run) | Result |
|---|---|
| Live heap | 67.5 MB idle, 70–76 MB during the load, settles at 74.7 MB and stays flat for the 25 minutes after it; max 76.6 MB |
| Committed heap | constant 144 MB |
| Metaspace | 110 MB at start, 116.5 MB steady |
| RSS (macOS `ps`) | 200–363 MB, no trend |

The first run's result therefore stands as a loaded steady state, not an idle one. About 7 MB stays
retained after the load (caches and the like) and does not grow.

Caveats — still open:

- **The load was light**: about 1.8 messages per second (public-broker TLS connections are slow).
  Production traffic of hundreds per second raises allocation churn and the number of concurrently
  pooled contexts (about 85 KB each, §4.2); the live set itself is dominated by caches and should
  grow far less than the rate does.
- Only 3 mappings, 90 devices, one tenant. No engine rotation happened (it triggers after 100 unique
  source compilations), so rotation is still untested.
- macOS `ps` RSS is not comparable to a Linux container's (expect somewhat higher there); confirm on
  the platform's memory graph.
- Production multi-tenant traffic can be larger; reduce the request in steps, not in one jump.

Recommendation: live set about 75 MB, Metaspace about 117 MB, plus stacks, code cache and native
buffers, comes to roughly 0.5 GB resident here. A **1 GB** memory request (`-Xmx` then about 0.8 GB)
leaves about twofold headroom for this load shape; 1.5 GB is the cautious intermediate step from
the committed 2 GB. Try it on a non-critical tenant with realistic traffic, watch the platform
memory graph and container restarts for a few days, and treat the CPU request separately (not
measured).

---

## 5. Test coverage

| Test | Covers |
|---|---|
| `GraalVMHostAccessAllowListTest` (35 tests) | Closed by default (`getClass`, unlisted class members, unlisted public field, `Java.type` still restricted); legitimate patterns still open (`InputMessage`, collections, Substitute types, `Base64` via `Java.type`); `atob`/`btoa` parity with Java `Base64` for empty, padded, umlaut, CJK and emoji inputs; whitespace/padding tolerance; invalid input and invalid UTF-8 handling; allow-list never contains `Object`, `Class` or `Runtime`. |
| `GraalVMContextCancellationRaceTest` | Test bridge changed from a host class to a `ProxyObject` (the allow-list does not cover test classes). |
| `FlowOutboundProcessorTest` | Three `eval`-count assertions lowered by one: the polyfill is no longer evaluated. |
| Existing sandbox and processor tests | Unchanged and passing (`TenantRegistryGraalVMSandboxSecurityTest`, `AbstractFlowProcessorTest`, `SmartFunction*Test`, `Flow*ProcessorTest`). |

Full backend suite after the change: 1,318 tests (6 skipped) plus 105 more, 0 failures.

---

## 6. Running the benchmark

```bash
cd dynamic-mapper-service
mvn test -Dtest=Base64PolyfillBenchmarkTest -Dbenchmark=true
# larger top payload (default 8 MB):
mvn test -Dtest=Base64PolyfillBenchmarkTest -Dbenchmark=true -Dbenchmark.maxMb=20

# context-pool memory (writes target/graalvm-memory-measurement.txt):
mvn test -Dtest=GraalVMContextMemoryMeasurementTest -Dbenchmark=true
# runtime / Metaspace probe, outside Maven:
resources/testing/performance/graalvm-memory/verify-graalvm-runtime.sh [--sources N] [--nmt] [--image IMAGE]
```

Opt-in via `@EnabledIfSystemProperty`, so normal builds report it as skipped. The class name ends
in `Test` on purpose. Use a quiet machine; pure JS is measured only up to 1 MB because beyond that
a single run takes many seconds.

---

## 7. Open questions

- Does the built image match the Dockerfile analysis? Run the probe with `--image`. (§4.1)
- Do any customer Smart Functions call Java methods outside the new allow-list?
- Is sharing one Engine across tenants acceptable from an isolation standpoint? (§4.3)
- Remove the dead polyfill from the SYSTEM template together with the next template version bump?
