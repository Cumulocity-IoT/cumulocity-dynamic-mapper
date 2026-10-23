# Implementation Plan: GraalVM JS integration — hardening and memory

**Status:** Part 1 implemented (host-access allow-list, `atob`/`btoa` as host functions, benchmark).
Part 2 (memory measurement and tuning) is planned, not started.
**Scope:** Backend only — `core/GraalVMContextService.java`, `processor/AbstractFlowProcessor.java`
and their tests.
**Motivation:** Reduce operating cost through lower memory consumption. Startup time is not a goal
(it happens once). Context: a rewrite of the backend in Rust was assessed and rejected (§1).

---

## 1. Decision record: why not Rust, why not another JS engine (yet)

The backend is about 75k lines of Java (309 files, 158 test classes). The cost of a rewrite is
driven by dependencies with no good Rust equivalent, not by line count:

| Blocker | Why |
|---|---|
| Smart Functions run on GraalVM JS | Scripts and shipped templates call into Java via `Java.type`. Rust has no equivalent embedding; `boa` is incomplete, `rquickjs`/`deno_core` have no Java interop. |
| JSONata | `dashjoin/jsonata-java`; the Rust ports are incomplete, so edge-case semantics would drift. |
| Cumulocity microservice SDK | Multi-tenant subscriptions, service-user credentials, Notification 2.0 — no Rust SDK. |
| Java extension API | `ProcessorExtensionInbound/Outbound` JARs cannot be loaded by a Rust backend. |

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

## 4. Planned: memory measurement and tuning

Nothing in this section is done. The ordering matters: measure before changing anything.

### 4.1 Verify which GraalVM runtime is in use

The pom depends on `org.graalvm.polyglot:polyglot` and `js`, and the Engine sets
`engine.WarnInterpreterOnly=false`, which is only needed on a JDK **without** the Graal compiler.
If production is interpreter-only, then:

- the comments in `GraalVMContextService` about Graal JIT output filling Metaspace may not
  describe what happens, and the warm-up measures AST specialization, not JIT;
- the engine-rotation logic (`ENGINE_ROTATION_THRESHOLD = 100`, `ENGINE_MAX_AGE = 24 h`) is built
  on that Metaspace theory and may be rotating for little gain.

Check the deployed image's JDK and run with `-XX:NativeMemoryTracking=summary`.

### 4.2 Measure

Run the `memtest` profile (`application-memtest.yml`) with N tenants × M mappings and break
memory into: pooled contexts, Engine/Truffle, Metaspace, other heap, native. Note that
`ContextMemoryBenchmark` never ran (Surefire skips non-`*Test` names) — do not rely on it.

### 4.3 Candidate changes, in expected order of impact

1. **Context pool size.** Pool key is `tenant:mappingId:codeHash`, up to
   `DEFAULT_POOL_SIZE = 20` idle contexts each. 50 mappings could mean up to 1,000 idle contexts
   per tenant, each with its own globals plus shared and system code evaluated into it. Lower the
   default (2–4), add idle eviction, consider sizing to real concurrency.
2. **Per-context footprint.** Keep shared and system code small; they are evaluated into every
   context.
3. **Rotate on measured size** instead of a fixed count/age — or drop rotation if §4.1 shows it
   buys nothing.
4. **Engine sharing across tenants.** One Engine per tenant today. Sharing would save memory but
   weakens tenant isolation — a trade-off to decide explicitly, not by default.
5. **Resource request.** `cumulocity.json` requests 8 CPUs and 8 GB. If measurement shows real use
   far below that, lowering the request is the most direct cost saving, independent of any
   GraalVM change.
6. **Engine swap (QuickJS)** only if 1–5 are not enough; see §1.

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
```

Opt-in via `@EnabledIfSystemProperty`, so normal builds report it as skipped. The class name ends
in `Test` on purpose. Use a quiet machine; pure JS is measured only up to 1 MB because beyond that
a single run takes many seconds.

---

## 7. Open questions

- Which JDK does the production image run on, and is the Graal compiler present? (§4.1)
- Do any customer Smart Functions call Java methods outside the new allow-list?
- Is sharing one Engine across tenants acceptable from an isolation standpoint? (§4.3)
- Remove the dead polyfill from the SYSTEM template together with the next template version bump?
