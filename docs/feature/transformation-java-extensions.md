# Java Extensions

Java Extensions (`transformationType: EXTENSION_JAVA`) let a mapping delegate its entire
transformation to a compiled Java class instead of a JSONata substitution list or a
JavaScript Smart Function. An extension is packaged as a JAR, uploaded to Cumulocity as a
binary, and dynamically loaded into the microservice at runtime — either bundled
internally (shipped with the service) or uploaded externally by a tenant.

Extensions are the right tool when transformation logic needs full Java (existing
libraries, binary protocol parsing, complex control flow) rather than JSONata's
expression language or GraalVM JavaScript.

## The extension interfaces

An extension implements one of two marker interfaces from `dynamic-mapper-interface`
(source lives under `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/`
and is re-exported into the `dynamic-mapper-interface` module via a `build-helper-maven-plugin`
`add-source` binding — see `dynamic-mapper-interface/pom.xml:242,266-274`):

- [`ProcessorExtensionInbound<O>`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/ProcessorExtensionInbound.java) — `CumulocityObject[] onMessage(Message<O> message, JavaExtensionContext context)`. Broker message → array of Cumulocity objects (measurement, event, alarm, operation, managed object).
- [`ProcessorExtensionOutbound<O>`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension/ProcessorExtensionOutbound.java) — `DeviceMessage[] onMessage(Message<O> message, JavaExtensionContext context) throws ProcessingException`. Cumulocity payload → array of broker messages.

Both follow the same "SMART function pattern" as Smart Functions: a pure function that
returns what to emit, rather than an imperative API that calls back into a C8Y client
directly.

## `JavaExtensionContext` / `DataPrepContext`

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
| `getConfig()` → `Value` | `getConfigAsMap()` → `Map<String, Object>` |
| `getManagedObjectByExternalId(ExternalId)` → `Value` | `getManagedObjectAsMap(ExternalId)` → `Map<String, Object>` (cache-first lookup) |
| — | `getMapping()` → the full `Mapping` configuration |
| — | `getTenant()` |
| `addWarning(String)` / `addLogMessage(String)` | `addWarning(String)` / `addLog(String)` |

State persists across invocations for the same mapping (`context.setNativeState`), the
same mechanism `docs/smart-functions.md` describes for Smart Function `context.setState`
— both are backed by `FlowStateStore`, loaded once per message
(`ExtensibleInboundProcessor.processWithExtensionInbound()`,
[`ExtensibleInboundProcessor.java:117`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/ExtensibleInboundProcessor.java#L117)) and written back through the same store.

The concrete implementation wired into `JavaExtensionContext` is
[`JavaExtensionContextImpl`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/flow/JavaExtensionContextImpl.java), which wraps a `ProcessingContext` and delegates the
`DataPrepContext` half of the interface to a `DataPrepContext` instance when one is
available, logging a warning and returning a safe default when it is not (e.g.
`getClientId()`/`setState()` on outbound extensions where no flow context exists).

## Loading and lookup

[`ExtensionManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/ExtensionManager.java) owns the lifecycle of extension JARs per tenant:

- `loadProcessorExtensions(tenant)` iterates every extension managed object
  (`extensionsComponent.get()`), downloads external ones from the Cumulocity binaries API
  into a temp file, builds a `URLClassLoader` over it (internal extensions instead reuse
  `ExtensionManager.class.getClassLoader()`), and reads an `extension-internal.yaml` /
  `extension-external.yaml` manifest resource that lists each extension's implementation
  class, event name, description, and parameters.
- Each listed class is instantiated once via its no-arg constructor and reused for every
  subsequent message; its direction (`INBOUND`/`OUTBOUND`) is auto-detected from which
  marker interface it implements.
- External extension classes must live under a configured allowed package
  (`ExtensionConfiguration.getExternalExtensionsAllowedPackage()`); loading external JARs
  at all is gated by `app.externalExtensionsEnabled` (`ExtensionConfiguration.isExternalExtensionsEnabled()`).
- Registered extensions are tracked in
  [`ExtensionInboundRegistry`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/ExtensionInboundRegistry.java) as `Extension` → `Map<eventName, ExtensionEntry>`,
  where `ExtensionEntry` (
  [`ExtensionEntry.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/ExtensionEntry.java)) holds the instantiated
  `ProcessorExtensionInbound`/`Outbound` object plus load status (`loaded`, `message`) for
  the UI to surface.

At processing time, `AbstractExtensibleProcessor.getProcessorExtensionInbound()` /
`getProcessorExtensionOutbound()`
([`AbstractExtensibleProcessor.java:98-143`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractExtensibleProcessor.java#L98-L143)) resolve a mapping's configured
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

## Processing flow

```mermaid
flowchart LR
    ctrl["ExtensibleInboundProcessor /\nExtensibleOutboundProcessor"] --> resolve["AbstractExtensibleProcessor:\nresolve ExtensionEntry by\nextensionName + eventName"]
    resolve --> ctx["Build JavaExtensionContextImpl\n(wraps ProcessingContext +\nDataPrepContext, loads FlowStateStore state)"]
    ctx --> call["extension.onMessage(message, context)"]
    call --> results{"CumulocityObject[] /\nDeviceMessage[] returned?"}
    results -- non-empty --> store["context.setExtensionResult(results)"]
    results -- null/empty --> err["ProcessingException:\n'returned null or empty array'"]
```

`ExtensibleInboundProcessor.processWithExtensionInbound()`
([`ExtensibleInboundProcessor.java:104-158`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/ExtensibleInboundProcessor.java#L104-L158)) is the concrete
inbound flow: it validates the extension's declared direction matches the processing
direction, builds a `JavaExtensionContextImpl`, invokes `onMessage`, and either stores the
returned `CumulocityObject[]` on the `ProcessingContext` for the next Camel step, or — if
the extension returns `null`/empty — raises a `ProcessingException` and sets
`ignoreFurtherProcessing`. A distinct `AbstractMethodError` catch block detects the
specific case of an extension JAR compiled against an older `ProcessorExtensionInbound`
interface signature and reports it as an incompatibility rather than a cryptic linkage
error.

`AbstractExtensibleProcessor` (the shared base for both directions,
[`AbstractExtensibleProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractExtensibleProcessor.java)) provides the Template Method skeleton
(`process()` → `processWithExtension()` → subclass-specific `handleProcessingError()`)
used by both `ExtensibleInboundProcessor` and `ExtensibleOutboundProcessor`.

## Hardening (commit `eda07ef59`)

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

## Related validation rule

`MappingValidator` requires the `extension` field to be set whenever
`transformationType == EXTENSION_JAVA` — see rule 3,
`Extension_Must_Be_Defined_For_Extension_Java_Mapping`, in
[mapping-validation.md](mapping-validation.md). This is checked at mapping create/update
time, independently of whether the referenced extension is actually loaded — a mapping
can reference an extension that fails to load later (surfaced via `ExtensionEntry.loaded`
/ `message`), which is a runtime concern handled by the lookup checks in
`AbstractExtensibleProcessor`, not a validation-time concern.
