# Java Extensions

Java Extensions (`transformationType: EXTENSION_JAVA`) let a mapping delegate its entire
transformation to a compiled Java class instead of a JSONata substitution list or a
JavaScript Smart Function. An extension is packaged as a JAR, uploaded to Cumulocity as a
binary, and dynamically loaded into the microservice at runtime — either bundled
internally (shipped with the service) or uploaded externally by a tenant.

Extensions are the right tool when transformation logic needs full Java (existing
libraries, binary protocol parsing, complex control flow) rather than JSONata's
expression language or GraalVM JavaScript.

---

## Requirements

**What it is for.** Payload formats that cannot reasonably be handled in JavaScript or JSONata —
a proprietary binary protocol, a Protobuf schema, anything needing a Java library.

- **A tenant can upload compiled Java** implementing a published interface, and use it as a
  mapping's transformation.
- **The published interface is versioned and separate from the service**, so an extension compiles
  against a stable artifact rather than service internals.
- **Extensions are per tenant.** An uploaded extension is visible and loadable only within the
  tenant that uploaded it.
- **Both directions are supported.**
- **An extension declares the events it handles**, and a mapping selects one of them.
- **A failing extension must not destabilise the service**: its errors are attributed to the
  mapping that used it and reported with enough detail to locate the fault in the extension's own
  code.
- **Uploading, listing and removing extensions is a tenant operation**, not a redeployment.

---

## Implementation

### The extension interfaces

An extension implements one of two marker interfaces from `dynamic-mapper-interface`
(source lives under `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/`
and is re-exported into the `dynamic-mapper-interface` module via a `build-helper-maven-plugin`
`add-source` binding in `dynamic-mapper-interface/pom.xml`); the module `dynamic-mapper-extension` holds sample
extensions (`processor/extension/external/inbound` and `outbound`) built against it:

- [`ProcessorExtensionInbound<O>`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/ProcessorExtensionInbound.java) — `CumulocityObject[] onMessage(Message<O> message, JavaExtensionContext context)`. Broker message → array of Cumulocity objects (measurement, event, alarm, operation, managed object).
- [`ProcessorExtensionOutbound<O>`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/ProcessorExtensionOutbound.java) — `DeviceMessage[] onMessage(Message<O> message, JavaExtensionContext context) throws ProcessingException`. Cumulocity payload → array of broker messages.

Both follow the same "SMART function pattern" as Smart Functions: a pure function that
returns what to emit, rather than an imperative API that calls back into a C8Y client
directly.

### `JavaExtensionContext` / `DataPrepContext`

The context parameter is
[`JavaExtensionContext`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/JavaExtensionContext.java), which extends
[`DataPrepContext`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/DataPrepContext.java) — the same base interface `SmartFunctionContext` implements for
Smart Functions (see [transformation-smart-functions.md](transformation-smart-functions.md)).
`DataPrepContext` itself is GraalVM-`Value`-typed (`getState(key): Value`, `getConfig(): Value`,
etc.); `JavaExtensionContext` adds pure-Java equivalents so an extension never needs to
depend on `org.graalvm.polyglot`:

| `DataPrepContext` (shared) | `JavaExtensionContext` addition (Java-native) |
|---|---|
| `getState(key)` / `setState(key, Value)` → `Value` | `getNativeState(key)` / `setNativeState(key, Object)` / `getNativeStateAll()` → plain `Object`/`Map` |
| `getConfig()` → `Value` (default method) | `getConfigAsMap()` → `Map<String, Object>` (read-only `mappingId`, `mappingName`, `tenant`, `topic`, `targetAPI`, `debug`, `clientId`; not persisted) |
| `getManagedObjectByExternalId(ExternalId)` → `Value` | `getManagedObjectAsMap(ExternalId)` → `Map<String, Object>` (cache-first lookup) |
| — | `getMapping()` → the full `Mapping` configuration |
| `getClientId()` | `getExternalId()` → the resolved external ID, outbound mappings with `useExternalId` only |
| — | `getTenant()` |
| `addWarning(String)` / `addLogMessage(String)` | `addWarning(String)` / `addLog(String)` |

State persists across invocations for the same mapping (`context.setNativeState`), like Smart Function
`context.setState` (see [`flow-state.md`](../../dynamic-mapper-ui/public/docs/flow-state.md)), backed by `FlowStateStore`.
Both extensible processors build the `JavaExtensionContextImpl` with the mapping's state loaded from the store (an empty
state and no store for a test run), call `extension.onMessage`, and then call `persistNativeState()`. That writes back only
the keys the invocation changed, through `FlowStateStore.commitChanges`, so a message that only reads cannot revert what
concurrent messages wrote; `setNativeState(key, null)` removes the key and `clearState()` wipes the mapping's stored state.
Two points are deliberate. State is written also when the extension returns an empty result, because deduplication
extensions record what they saw and return nothing. It is **not** written when `onMessage` throws: the message fails and is
redelivered, and state from the failed attempt would make the retry look like a duplicate. (Smart Functions still write on
failure; see [flow-state-review.md](../improvements/flow-state-review.md).) Nested lists, maps, and sets are deep-copied on
load, because the stored objects are shared by messages running in parallel. Two messages writing the same key still race.
Earlier versions loaded the state but never saved it, and outbound extensions had no store at all.

The concrete implementation wired into `JavaExtensionContext` is
[`JavaExtensionContextImpl`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/flow/JavaExtensionContextImpl.java), which wraps a `ProcessingContext` and delegates the
`DataPrepContext` half of the interface to a `DataPrepContext` instance when one is
available, logging a warning and returning a safe default when it is not (e.g.
`getClientId()`/`setState()` on outbound extensions where no flow context exists).

### Upload, listing and removal

The UI (`dynamic-mapper-ui/src/extension/`) uploads the JAR to the Cumulocity binary repository and creates a managed
object carrying the `d11r_processorExtension` fragment (`name`, `external`, ...); the service is not involved in the
upload. The size cap (50 MB) is enforced in `AddExtensionComponent` only. The extension grid then triggers the
admin-only `RELOAD_EXTENSIONS` operation (`ExtensionManager.reloadExtensions`) so the service loads it. The service
exposes `GET /extension` and `GET /extension/{extensionName}` (`ROLE_DYNAMIC_MAPPER_ADMIN` or `ROLE_DYNAMIC_MAPPER_CREATE`) and
`DELETE /extension/{extensionName}` (`ROLE_DYNAMIC_MAPPER_ADMIN`). Per-tenant isolation comes from reading the
extension managed objects as the calling tenant; the class loaders and the registry are keyed by tenant.

`DELETE` is guarded in `ExtensionController`:

| Status | Condition |
|--------|-----------|
| 404 | No extension with that name is registered. |
| 400 | The extension is built-in. Only external extensions can be deleted. |
| 409 | At least one mapping, active or not, references the extension. The message lists the mapping names. |

The UI shows the message of a failed delete as a warning.

### Loading and lookup

[`ExtensionManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/ExtensionManager.java) owns the lifecycle of extension JARs per tenant:

- `loadProcessorExtensions(tenant)` iterates every extension managed object
  (`extensionsComponent.get()`), downloads external ones from the Cumulocity binaries API
  into a `.jar` temp file, builds a `URLClassLoader` over it (internal extensions instead reuse
  `ExtensionManager.class.getClassLoader()`), and reads an `extension-internal.yaml` /
  `extension-external.yaml` manifest resource that lists each extension's implementation
  class, event name, description, and parameters. A managed object whose
  `d11r_processorExtension` fragment has no `name` is skipped with a warning.
- Each listed class is instantiated once via its no-arg constructor and reused for every
  subsequent message, so implementations must be thread-safe. Its direction (`INBOUND`/`OUTBOUND`)
  is detected from the interface it implements. A class cannot implement both, because the
  two `onMessage` methods have the same parameters and different return types.
- External extension classes must live under a configured allowed package
  (`ExtensionConfiguration.getExternalExtensionsAllowedPackage()`, property
  `APP.externalExtensionsAllowedPackage`, default `dynamic.mapper.processor.extension.external`); loading external JARs
  at all is gated by `APP.externalExtensionsEnabled` (`ExtensionConfiguration.isExternalExtensionsEnabled()`, default
  `true`). The package check only constrains where the class lives; the loaded code runs in the service's own JVM
  with no further sandbox.
- Registered extensions are tracked in
  [`ExtensionInboundRegistry`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/ExtensionInboundRegistry.java) as `Extension` → `Map<eventName, ExtensionEntry>`,
  where `ExtensionEntry` (
  [`ExtensionEntry.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/extension/ExtensionEntry.java)) holds the instantiated
  `ProcessorExtensionInbound`/`Outbound` object plus load status (`loaded`, `message`) for
  the UI to surface. `loaded` and `message` are part of the REST response but are ignored when a
  mapping that embeds the entry is read or stored (`@JsonIgnoreProperties(allowGetters = true)`).

#### Reload and load failures

`loadProcessorExtensions(tenant)` and `reloadExtensions(tenant)` are the same operation. They run under a
per-tenant lock and build the new set of extensions without touching the registry. They then replace the
registry content in one step (`ExtensionInboundRegistry.replaceExtensions`) and close the previous classloaders
afterwards. A mapping that processes a message during a reload therefore keeps using the previous
extension and does not fail with "Extension not found". A call that is already running on the old classloader
can still fail with a class loading error, which the processors report as a `ProcessingException`.

Failures never abort the load of the other extensions:

| Failure | Result |
|---------|--------|
| JAR cannot be downloaded, `extension-external.yaml` is missing or invalid | The extension is registered with `loaded = NOT_LOADED` and the reason in `Extension.message`. The UI shows it on the extension card. |
| Manifest lists no extensions | `NOT_LOADED`, message `No extensions defined in <file>`. |
| Class not found, missing dependency, failing static initializer, no public no-arg constructor, constructor throws | That entry gets `loaded = false` and a message. The other entries load, so the extension status is `PARTIALLY`. |
| Class does not implement `ProcessorExtensionInbound` or `ProcessorExtensionOutbound`, or is outside the allowed package | Same as above. |

The extension status is `COMPLETE` when all entries loaded, `PARTIALLY` when some did, and `NOT_LOADED` when none did.

At processing time, `AbstractExtensibleProcessor.getProcessorExtensionInbound()` /
`getProcessorExtensionOutbound()`
([`AbstractExtensibleProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractExtensibleProcessor.java)) resolve a mapping's configured
`extension` field (`{extensionName, eventName}` — required by validation, see rule 3 in
[mapping-validation.md](mapping-validation.md)) against the registry:

```java
Extension registeredExtension = extensionInboundRegistry.getExtension(tenant, extensionName);
if (registeredExtension == null) {
    throwExtensionNotFoundException(tenant, extension);   // explicit check, not an NPE
}
ExtensionEntry registeredEntry = registeredExtension.getExtensionEntries().get(eventName);
if (registeredEntry == null) {
    throwExtensionEventNotFoundException(tenant, extension);  // extension exists, event does not
}
```

Both failure modes raise a specific `ProcessingException` with a clear message rather
than propagating a `NullPointerException` — see [Hardening](#hardening-commit-eda07ef59)
below.

### Processing flow

```mermaid
flowchart LR
    ctrl["ExtensibleInboundProcessor /\nExtensibleOutboundProcessor"] --> resolve["AbstractExtensibleProcessor:\nresolve ExtensionEntry by\nextensionName + eventName"]
    resolve --> ctx["Build JavaExtensionContextImpl\n(wraps ProcessingContext +\nDataPrepContext, loads FlowStateStore state)"]
    ctx --> call["extension.onMessage(message, context)"]
    call --> results{"CumulocityObject[] /\nDeviceMessage[] returned?"}
    results -- non-empty --> store["context.setExtensionResult(results)"]
    results -- null/empty --> err["ignore the message:\nsetIgnoreFurtherProcessing(true)"]
```

`ExtensibleInboundProcessor.processWithExtensionInbound()`
([`ExtensibleInboundProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/ExtensibleInboundProcessor.java)) is the concrete
inbound flow: it validates the extension's declared direction matches the processing
direction, builds a `JavaExtensionContextImpl` (with the mapping's loaded state, see above), invokes `onMessage`, and either stores the
returned `CumulocityObject[]` on the `ProcessingContext` for the next processor, or — if
the extension returns `null`/empty — logs at info level and sets
`ignoreFurtherProcessing`, without an error. An empty result means "nothing to do" (for example
deduplication), as for Smart Functions, so it does not count toward the failure streak. A distinct `AbstractMethodError` catch block detects the
specific case of an extension JAR compiled against an older `ProcessorExtensionInbound`
interface signature and reports it as an incompatibility rather than a cryptic linkage
error. Any other `LinkageError`, for example a `NoClassDefFoundError` after the JAR was replaced or deleted
while a message was processed, is reported as a `ProcessingException` in both directions.

The lookup reads the entry from the registry, not the copy stored in the mapping, which can be stale. It fails with a
specific message when the extension or event is unknown, when the entry is not loaded (the message includes the load
failure), or when the entry implements the other direction.

`AbstractExtensibleProcessor` (the shared base for both directions,
[`AbstractExtensibleProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractExtensibleProcessor.java)) provides the Template Method skeleton
(`process()` → `processWithExtension()` → subclass-specific `handleProcessingError()`)
used by both `ExtensibleInboundProcessor` and `ExtensibleOutboundProcessor`.

### Hardening (commit `eda07ef59`)

Commit `eda07ef593c7c548fb1ab7ae637b9ef2d409b156` ("Harden Java Extension feature: safer
lookup, resource cleanup, UI polish") changed the following in
`ExtensionManager`/`AbstractExtensibleProcessor` (backend side):

- Replaced NPE-driven extension/event lookup with the explicit
  `throwExtensionNotFoundException` / `throwExtensionEventNotFoundException` checks shown
  above, each producing a specific, actionable error message.
- Classloader/resource cleanup: `ExtensionClassLoaderHolder` now tracks each external
  extension's `URLClassLoader` **together with** the temp jar file backing it, so both are
  closed/deleted together. Re-registering an already-loaded extension closes the previous
  classloader first (`loadProcessorExtensions`); deleting an extension
  (`deleteProcessorExtension`) and tenant offboarding/shutdown
  (`cleanupTenantExtensions`/`cleanupAllExtensions`) all route through the same
  `closeClassLoaderAndDeleteFile` helper, which explicitly deletes the temp jar rather
  than relying solely on `File.deleteOnExit()` (which only fires at JVM shutdown and would
  otherwise leak temp files for the life of the process).
- `externalExtensionsEnabled` is now enforced at load time — external extensions are
  skipped entirely when the flag is off, rather than only being checked at some later
  point.
- `deleteProcessorExtension` was fixed to match extensions by the same
  `d11r_processorExtension.name` fragment property used at load time (previously it could
  mismatch against the top-level managed object name).
- The unused `ExtensionResultProcessor` class (396 lines, a duplicate of logic now in
  `ExtensibleInboundProcessor`/`ExtensibleOutboundProcessor`) was removed.

The same commit's frontend changes (not detailed here) fixed stale upload-error copy,
capped upload size, required extension/event selection before creating an
`EXTENSION_JAVA` mapping, and added loading/empty/warning states to the extension picker
in the mapping stepper.

### Related validation rule

`MappingValidator` requires the `extension` field to be set whenever
`transformationType == EXTENSION_JAVA` — see rule 3,
`Extension_Must_Be_Defined_For_Extension_Java_Mapping`, in
[mapping-validation.md](mapping-validation.md). This is checked at mapping create/update
time, independently of whether the referenced extension is actually loaded — a mapping
can reference an extension that fails to load later (surfaced via `ExtensionEntry.loaded`
/ `message`), which is a runtime concern handled by the lookup checks in
`AbstractExtensibleProcessor`, not a validation-time concern.
