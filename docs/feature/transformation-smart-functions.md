# Smart Functions

Smart Functions are the JavaScript-based transformation option (`transformationType:
SMART_FUNCTION`). A mapping supplies a single `onMessage(msg, context)` function that
receives the source message and returns the Cumulocity objects (inbound) or broker
messages (outbound) to emit. Unlike JSONata substitutions, a Smart Function has full
control flow — loops, conditionals, arbitrary JavaScript logic — and is the only
built-in transformation type that can turn one input message into an arbitrary number of
output objects or handle payloads that aren't well-formed JSON.

This page covers what Smart Functions are and how the runtime executes them. For the
`onMessage` entry-point signature, the `msg`/`context` API, and return-type contracts,
see [`docs/smart-functions.md`](../smart-functions.md) (developer reference) and
[`docs/smart-function-type-sync.md`](../smart-function-type-sync.md) (TypeScript↔Java
type parity) — this document does not repeat that API surface.

---

## Requirements

**What it is for.** Expressing a transformation as JavaScript when substitutions or JSONata are
not enough — conditionals, loops, derived values, state across messages.

- **The tenant writes a function with a fixed entry point** that receives the message and returns
  the Cumulocity objects to create. One invocation may return several objects.
- **A shared library is available to every function**, so common helpers are written once; the
  tenant can edit it.
- **A function may keep state between messages**, one state per mapping (a function that needs per-device state keys
  it itself), for deduplication, running statistics, or change detection. The state lives in service memory: it does
  not survive a restart and it expires after a retention period (see *Runtime state*).
- **A function may look data up in Cumulocity**, e.g. resolving a managed object by external ID,
  so a transformation can enrich from inventory.
- **Execution is sandboxed.** A function cannot reach the host, the filesystem, the network, or
  another tenant's data — a tenant runs untrusted code by definition.
- **Execution is bounded.** A function that runs longer than the CPU budget (`maxCPUTimeMS`, default 5 s) or the
  processing pipeline timeout (default 8 s) is stopped, and its failure is attributed to its mapping rather than
  affecting the service — see [reliability.md](reliability.md).
- **A test never touches the persisted state.** Dry Run and Live Test start from an empty state and write nothing back
  (see [mapping-testing-isolation.md](mapping-testing-isolation.md)).
- **Errors must point at the function**, with the line number where it failed, not at mapper
  internals.
- **Both directions are supported**, inbound and outbound.

---

## Implementation

### Why Smart Functions exist

Per [mapping-validation.md](mapping-validation.md), several situations are rejected for
JSONata/DEFAULT transformations and require `SMART_FUNCTION`:

- **Array-rooted templates** — if `sourceTemplate` or `targetTemplate` is a JSON array at
  the root, JSONata substitution (which addresses one target document per message) can't
  express it; only a Smart Function can iterate an array root
  (`Wrong_Transformation_Type_Array_In_Source_Template_Or_Target_Template_Requires_Transformation_Type_Smart_Function`).
- **`MappingType.ANY_PAYLOAD`** — unparsed/raw payloads (not necessarily JSON) require
  `SMART_FUNCTION` or `EXTENSION_JAVA` — a JSONata expression has nothing well-defined to
  evaluate against.
- **`MappingType.SPARKPLUGB`** — Sparkplug B's protobuf-encoded, schema-driven payload
  structure requires `SMART_FUNCTION` unconditionally.

In all three cases, the validator enforces this at `MappingValidator.validate()` time —
see the corresponding rules in [mapping-validation.md](mapping-validation.md) rules 2 and 4.

### Execution model

Smart Function code runs inside a GraalVM polyglot JS `Context`, one per tenant `Engine`,
pooled per mapping+code-hash so repeated invocations reuse a warmed-up context instead of
paying JS parse and AST specialization cost every message. The production image has no JIT: JavaScript runs in
Truffle's AST interpreter (see the `GraalVMContextService` class comment).

#### Context construction and sandboxing

`AbstractEnrichmentProcessor.createGraalContext()`
([`AbstractEnrichmentProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractEnrichmentProcessor.java)) builds each `Context` with an
explicit allowlist rather than unrestricted host access:

```java
Context.Builder builder = Context.newBuilder("js")
        .engine(graalEngine)
        .option("js.text-encoding", "true")
        .allowHostAccess(serviceRegistry.getGraalVMContextService().getHostAccess())
        .allowHostClassLookup(GraalVMContextService::isAllowedHostClass);
```

`GraalVMContextService.isAllowedHostClass()` names the reachable classes (the substitution classes,
`RepairStrategy`, `StandardCharsets`, `Base64`, `String`, `ArrayList`, `Arrays`, `HashMap`, `HashSet`), and
`SCRIPT_ACCESSIBLE_CLASSES` lists what the host-access policy exposes (for example `DataPrepContext`, not its
implementation). The two lists must agree: JavaScript templates call `Java.type('<fqn>')` with a string, so moving a
class compiles but fails at run time.

Only a fixed set of Java classes are reachable from JS via `Java.type(...)`; everything
else — including file/network/process access — is unreachable because it was never
granted, not because a runtime sandbox policy revokes it after the fact. `js.esm-eval-returns-exports` /
experimental options are enabled only when the tenant's `ServiceConfiguration.getSupportESM()`
flag is set, to allow `export function` module syntax in mapping code.

> **Note on the code vs. documented model:** `org.graalvm.polyglot.SandboxPolicy` (e.g.
> `SandboxPolicy.TRUSTED`) is not referenced anywhere in the current
> `dynamic-mapper-service` source. The isolation Smart Functions get today comes from the
> explicit `allowHostAccess`/`allowHostClassLookup` allowlists above, plus process-level
> CPU/wall-clock cancellation (below) — not from a `SandboxPolicy` call. If your mental
> model of this feature says otherwise, treat the code as authoritative.

#### Context pooling and lifecycle

`GraalVMContextService.borrowOrCreateContext()` / `returnContext()`
(`dynamic-mapper-service/src/main/java/dynamic/mapper/core/GraalVMContextService.java`)
implement a per-tenant, per-mapping-code-hash pool of pre-built `PooledGraalContext`s, set
up in `AbstractEnrichmentProcessor.process()`
([`AbstractEnrichmentProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractEnrichmentProcessor.java)):

```java
PooledGraalContext pooledCtx = graalVMContextService.borrowOrCreateContext(
        poolKey, tenant, graalEngine, supportESM, sharedSource, systemSource,
        mapping.getCode(), mapping.getIdentifier());
context.setPooledGraalContext(pooledCtx);
context.setEngineReleaseAction(() -> graalVMContextService.returnContext(poolKey, pooledCtx, graalEngine));
```

`ProcessingContext` — the per-message context, not a separate `ExecutionContext` type —
implements `AutoCloseable` itself and centralizes GraalVM cleanup in its own `close()`
([`ProcessingContext.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/ProcessingContext.java)): if a pooled context is set, `close()` returns
it to the pool via `engineReleaseAction` (or discards it if it was killed) instead of
closing the raw GraalVM `Context` directly; for the non-pooled path it calls
`graalContext.close()` directly. `AbstractFlowProcessor.process()`
([`AbstractFlowProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractFlowProcessor.java)) is the call site that guarantees this
runs — a `try { processSmartMapping(context); } ... finally { context.close(); }` around
every Smart Function invocation, so the pooled/raw context is always released or closed
exactly once per message regardless of success or failure.

#### Cancellation

A running Smart Function can be forcibly stopped mid-execution — e.g. an MQTT-side
timeout — via `Context.close(cancelIfExecuting=true)`, since plain thread interruption is
ignored by GraalVM. `AbstractFlowProcessor.process()` registers a cancel action on the
message's `ProcessingResultWrapper` before invoking the function
([`AbstractFlowProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractFlowProcessor.java)) that either kills the pooled context
(`PooledGraalContext.kill()`) or calls `graalCtx.close(true)` directly. Two limits trigger it: the CPU budget
`maxCPUTimeMS` (default 5000 ms; deadlines are scheduled in `GraalVMContextService` and the blocking kill runs on a
separate virtual thread) and the pipeline timeout `pipelineTimeoutMS` (default 8000 ms, always larger than the CPU
budget). The error names the limit that was hit and adds the JavaScript location (`describeJsLocation()`). On the way out, a
`PolyglotException` whose `isCancelled()`/`isResourceExhausted()` is true is treated as a
timeout/kill rather than a script bug, and any `console.log()` output written before the
kill is salvaged into the processing result when possible.

### Console, logs, and warnings plumbing

Smart Function code gets a `console` object backed by
[`JavaScriptConsole`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/flow/JavaScriptConsole.java), which formats GraalVM `Value` arguments (including nested
objects/arrays, via Jackson) and routes `console.log/warn/error/debug` into the flow
context's log sink — for `SMART_FUNCTION` this is
[`DataPrepContext.addLogMessage`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/DataPrepContext.java), which is
where `context.addWarning(...)` (documented in `docs/smart-functions.md`) also lands
(`DataPrepContext.WARNINGS` / `DataPrepContext.LOGS` state keys).

The actual JS-callable entry point is the
[`JavaScriptProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/flow/JavaScriptProcessor.java) functional interface —
`Value onMessage(Value msg, DataPrepContext context)` — invoked by
`AbstractFlowProcessor.processSmartMapping()` against the compiled mapping code loaded
into the pooled `Context`.

### Runtime state: `SmartFunctionContext`

At runtime, `context` inside a Smart Function is backed by
[`SmartFunctionContext`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/SmartFunctionContext.java), the concrete
`DataPrepContext` implementation used for `SMART_FUNCTION` (as opposed to
`JavaExtensionContext`, used for `EXTENSION_JAVA` — see
[transformation-java-extensions.md](transformation-java-extensions.md)). It is
constructed fresh per message in `AbstractEnrichmentProcessor.process()`
([`AbstractEnrichmentProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractEnrichmentProcessor.java)), seeded with the
mapping's flow state loaded from `FlowStateStore` — this is the backing store
for the `context.getState()`/`setState()` persistence documented in
`docs/smart-functions.md`. `FlowStateStore` is an in-memory map keyed `tenant → mapping identifier → key`. A message loads a copy when it starts and
`SmartFunctionContext.persistState()` (called from `ProcessingContext.close()`) writes back **only the keys that message
changed** through `FlowStateStore.commitChanges`; `clearState()` from the function wipes the mapping's stored state, and a
`null` value removes the key. Two messages writing the same key still race, and the last one wins. The store is
lost on restart, a mapping's state is cleared when the mapping is deleted, and the cleanup job clears **each mapping's** state
once that mapping has not written it for `flowStateRetention` minutes (default 1440; a read does not count; `0`
disables expiry). When the run is a test (`!affectsMappingStatus()`), the context gets an empty state and no store, so
nothing is read or written.

### Engine rotation and warm-up

Each tenant's `Engine` is retired and replaced after `engineRotationThreshold` distinct source compilations (default
1000, a service configuration value), or on demand through the admin `ROTATE_GRAALVM_ENGINE` operation. Activating an
active `SMART_FUNCTION` mapping pre-compiles its code on a virtual thread; a compile failure is published as a
`MAPPING_ACTIVATION_ERROR_EVENT_TYPE` service event (see [mapping-versioning.md](mapping-versioning.md)).

### Where this fits in the pipeline

```mermaid
flowchart LR
    enrich["AbstractEnrichmentProcessor\n(borrow pooled Context,\nbuild SmartFunctionContext)"] --> flow["AbstractFlowProcessor\n(register cancel action,\ncall onMessage via JavaScriptProcessor)"]
    flow --> result["Map returned CumulocityObject[]/\nDeviceMessage[] onto\nProcessingContext output"]
    flow -- finally --> close["ProcessingContext.close()\n(return/close pooled context)"]
```

`AbstractEnrichmentProcessor` (shared with `EXTENSION_JAVA`, see
[transformation-java-extensions.md](transformation-java-extensions.md)) does the GraalVM
setup common to any code-based transformation; `AbstractFlowProcessor` is specific to
`SMART_FUNCTION` and drives the actual `onMessage` invocation and cancellation handling.
