# Java Package Restructuring — Backend Model Classes

**Status:** Implemented on branch `feature/reorganize-java-packages`. §§1–6 landed in `ad4e7d569`
("reorganized java package", 161 paths); the §7 `notification/` consolidation and `service/` split
follow in two further commits.
**Scope:** `dynamic-mapper-service` only. No UI changes, no API changes, no persistence changes.
**Verification:** `mvn -o clean test` across all four modules — **876 tests, 0 failures, BUILD SUCCESS**.

This started as a review question ("are model classes well architected?") and turned into four
changes, one reverted refactor, and a recorded decision on packaging strategy (§7). The most important part of this document is
[§5 What we learned about `ProcessingContext`](#5-what-we-learned-about-processingcontext) —
that finding invalidates guidance that was previously written down in `docs/` and acted on.

---

## 1. Why

Model classes were spread across five locations with no consistent rule:

| Location | Before | Nature |
|----------|--------|--------|
| `model/` | 49 | Persisted/REST domain (`Mapping`, `Substitution`, `API`, `Qos`, `Explorer*`, `MCP*`) |
| `processor/model/` | 32 | Mixed: message data **and** GraalVM runtime machinery |
| `configuration/` | 4 | Feature-local — fine |
| `connector/core/` | 5 | Feature-local — fine |
| inline in a controller | 1 | `StartSessionRequest` inside `ExplorerController` |

Four concrete problems:

1. **A package cycle.** `model` → `processor.model` (10 files) and back again (6 imports).
2. **`processor/model` was not a model package.** Nine of its classes pulled in GraalVM polyglot,
   JSONPath or JSONata. `PooledGraalContext` (a resource pool) and `SmartFunctionContext`
   (366 lines) were filed as "model".
3. **A stalled migration.** `ProcessingContext` had 65 usages against 2 for `PayloadContext` —
   two coexisting designs, no deprecations, no migration pressure.
4. **No DTO/domain split.** 23 model classes carry `@Schema`, controllers return domain `Mapping`
   directly. Defensible at this size, but undocumented. *Not addressed here* — see §8.

---

## 2. Step 1 — break the cycle

Four types moved `processor/model` → `model`. All four are persisted fields of
`Mapping`/`Substitution` or REST payloads, i.e. domain vocabulary, not processor internals:

`MappingType` · `TransformationType` · `RepairStrategy` · `DynamicMapperRequest`

`model/` → `processor/model` is now **zero imports**.

One outbound edge remains and is deliberate: [`ExtensionEntry`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/ExtensionEntry.java)
imports the `processor.extension` SPI interfaces. This is **not** a cycle — `processor/extension`
never imports `model`. It is still a smell (a model class holding a live plugin instance) but was
out of scope.

---

## 3. Step 2 — split data from runtime

New package [`processor/runtime/`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/) (8 classes):

`ProcessingContext` · `OutputCollector` · `ProcessingResultWrapper` · `PooledGraalContext` ·
`SmartFunctionContext` · `SubstitutionContext` · `RoutingContext` · `DeviceContext`

`SubstitutionEvaluation` went to `processor/util/` instead — it is a `final` class with a private
constructor and only static methods, so it was never a model. `processor/model/` is now 16 data classes.

> **Deliberate exception — read before "finishing the job".**
> `JavaExtensionContext` and `DataPrepContext` stayed in `processor/model` **despite** their GraalVM
> coupling. They are the public extension SPI, consumed by `dynamic-mapper-extension` and documented
> in `EXTENSION_MIGRATION_GUIDE.md`. Moving them breaks third-party extension JARs compiled against
> the current package. SPI stability beat package tidiness. If you want them moved, it needs a
> deprecation cycle, not a `git mv`.

---

## 4. Step 4 — feature slices

Follows the existing `connector/` and `notification/` precedent.

| New package | Contents |
|-------------|----------|
| [`explorer/`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/) | `ExplorerController`, `ExplorerService`, `ExplorerMessage`, `ExplorerSession`, `StartSessionRequest` (extracted from the controller) |
| [`ai/`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/ai/) | `AIAgentService`, `AIAgent`, `AIAgentRef`, `MCPServer`, `MCPServers`, `MCPUsage` |

`model/` is down from 49 to 45 files.

**One rename:** `dynamic.mapper.model.Agent` → `ai.AIAgentRef`. It was a one-field class used only
by `AIAgent`, and it shadowed the Cumulocity SDK's `com.cumulocity.model.Agent`, which *is* used in
`C8YAgent` and `DeviceBootstrapService`. The JSON field name is still `agent` — **no wire change**.

REST paths are annotation-driven, so `/explorer/**` is unchanged, and `@SpringBootApplication` on
`dynamic.mapper.App` already scans the new subpackages.

---

## 5. What we learned about `ProcessingContext`

**This section is the reason to read this document.** The previous plan was to "finish" the
five-context decomposition. Reading the adapters showed it should be *removed* instead.

### The thread-safety premise was wrong

`docs/backend/conventions.md` previously said:

> *"For parallel processing, **always** use focused contexts; never mutate `ProcessingContext` directly."*

That is backwards. Safety on the parallel path comes from `ProcessingContext`'s **own fields**:
`requests`/`errors`/`warnings`/`logs` are `CopyOnWriteArrayList`, `processingCache` is a
`ConcurrentSkipListMap`. The parallel route (`direct:processRequestsInParallel`) runs
`SendInboundProcessor` across virtual threads against the **same** `ProcessingContext`, and each leg
calls `addError()`. This is documented at [`ProcessingContext.java:113`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/ProcessingContext.java#L113).

### The "focused contexts" were adapters, and the mutable ones were harmful

They were copy-out/copy-back wrappers on a still-62-field class, not a decomposition:

| Adapter | Kind | Call sites (before) |
|---------|------|---------------------|
| `getRoutingContext()` | read-only projection | 10 |
| `getDeviceContext()` | read-only projection | 6 |
| `getProcessingState()` | copy-out (mutable) | 3 |
| `getPayloadContext()` | read-only projection | 2 |
| `syncFromState()` | copy-back | 1 |
| `getOutputCollector()` | copy-out (mutable) | **0** |
| `syncFromOutputCollector()` | copy-back | **0** |

`syncFromState()` did `clear()`-then-repopulate; `syncFromOutputCollector()` reassigned the list
reference. Both **replace rather than merge** — so concurrent use would have caused lost updates on
the one path that was already correct. The live code had already quietly routed around them with a
hand-rolled *appending* helper.

### What was removed vs kept

**Removed:** `PayloadContext` and `ProcessingState` (both classes), `getPayloadContext()`,
`getProcessingState()`/`syncFromState()`, `getOutputCollector()`/`syncFromOutputCollector()`.
`ProcessingState` threaded through abstract signatures in 6 processor files; all ~19 usages collapsed
onto `context.setIgnoreFurtherProcessing(...)` / `isNeedsRepair()` / `getProcessingCache()` — which is
what the majority of the codebase (`SubstitutionResultInboundProcessor`, `BaseProcessor`,
`DynamicMapperBaseRoutes`) already did. This converged on the existing pattern rather than inventing one.

**Kept:** `RoutingContext` and `DeviceContext` as immutable **read-only projections** (no sync-back),
and `OutputCollector` as a standalone accumulator built with `new OutputCollector()`, passed down,
merged up.

`ProcessingContext` went from 62 fields / 563 lines to **48 fields / 458 lines**.

### The rule now

> Mutate `ProcessingContext` **directly**; its fields are already concurrent. Pass a read-only
> projection when a method only *reads* routing or device data. Do not reintroduce
> copy-out/copy-back wrappers.

Canonical version: [`docs/backend/conventions.md`](../backend/conventions.md), rewritten as part of this work.

---

## 6. Two bugs found on the way

**`syncOutputToContext()` dropped `errors` and `logs`.** It merged only `requests` and `warnings`
([`AbstractExtensibleResultProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractExtensibleResultProcessor.java)).
Latent rather than live — nothing currently adds those to that collector — but it would have bitten
the first extension that did. Fixed to merge all four channels, with a new regression test
(`AbstractExtensibleResultProcessorSyncTest`) that was **verified to fail against the old code**
before the fix was restored.

**`ContextMemoryBenchmark`'s 4 `@Test` methods never ran.** The class name does not match surefire's
`*Test` pattern, so it was silently skipped. It had been "validating" a *75-97% memory reduction*
claim that nobody had ever executed — and that claim was the main justification for the refactor this
work reverted. Deleted. Worth remembering: **a green build is not evidence that a given test ran.**

---

## 7. Strategy: package-by-feature vs package-by-layer

The question came up whether to go all-in on package-by-feature. **Answer: neither pure
strategy — a hybrid, which is broadly what the codebase already has.** This section records the
evidence so the debate does not get re-litigated from taste.

Spring's own [Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
shows a feature layout (`customer/`, `order/`, each with controller + service + repository) as the
typical one, and points to Spring Modulith when boundaries need *enforcing*. That advice assumes
features own **disjoint data**. Ours do not:

| Type | Files | Top-level packages |
|------|-------|--------------------|
| `Mapping` | 80 | 7 |
| `API` | 78 | 9 |
| `ProcessingContext` | 64 | 5 |
| `ServiceConfiguration` | 39 | 9 |

The service layer converges just as hard — **5 of 12 controllers depend on `MappingService`**;
`C8YAgent` is referenced from 8 packages and `ConfigurationRegistry` from 7.

The decisive measurement: **every controller is a leaf (fan-in = `controller` only), but every
service is shared.**

| Class | Referenced from |
|-------|-----------------|
| `ExtensionController`, `DeploymentController`, `MonitoringController`, `CacheController` | `controller` only |
| `DeploymentMapService` | connector, controller, service |
| `MappingStatusService` | controller, service, util |
| `ExtensionInboundRegistry` | core, processor, service |
| `CacheManager` | controller, core, processor |

So slicing the remaining controllers into feature packages is trivially safe but buys nothing — it
relocates thin files. The services, where decoupling would actually pay, *cannot* move without
duplicating them or inventing a `shared/` package that most of the code lands in, which is
package-by-layer wearing a costume.

This is a **pipeline engine with one central domain object**, not a CRUD app with independent
verticals.

### The rule

> Slice by feature where fan-in is low. Keep a shared kernel for what is genuinely cross-cutting.

That is why the existing slices earn their place — `connector/` is a plugin point with 8
interchangeable implementations behind a stable SPI; `processor/` is a cohesive subsystem;
`notification/`, `explorer/`, `ai/` are leaf features. And it is why `model/`, `core/`, `util/` and
`exception/` stay shared: `Mapping` and `C8YAgent` really are used everywhere.

### Change made under this rule

`NotificationSubscriptionController`, `NotificationSubscriptionService` and the two
`NotificationSubscription{Request,Response}` models moved into the existing
[`notification/`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/) package —
the last case where a feature package existed but part of its code still sat in `controller/` and
`service/`. `Device` (37 files, 12 packages) deliberately stayed in `model/`: it is shared kernel,
and is the clearest illustration of the rule.

`/subscription/**` is unchanged. Final counts: `controller/` 12→11, `service/` 17→16, `model/` 45→43,
`notification/` 19→23.

### Second change: splitting `service/`

Applying the same rule to `service/` showed it was two things under one name. Measuring true
fan-in by **import statement** (not textual mention), five of its classes had their data or their
consumers elsewhere, while the rest formed a cohesive mapping cluster.

| Class | Fan-in (importing packages) | Moved to | Why |
|-------|-----------------------------|----------|-----|
| `ServiceConfigurationService` | ai, connector, controller, core, notification | `configuration/` | manages `ServiceConfiguration`, already there |
| `ConnectorConfigurationService` | connector, controller, core | `configuration/` | manages `ConnectorConfiguration`, already there |
| `FlowStateStore` | 7 consumers, **6 inside `processor/`** | `processor/flow/` | Smart Function state |
| `ExtensionInboundRegistry` | core, processor ×3 | `core/` | never touched by the mapping cluster |

The tell for the first two: `ConnectorConfigurationServiceTest` already lived in
`test/…/configuration/` while the class it tests sat in `service/`.

By contrast these are **never imported from outside `service/`** — they are internal collaborators
of `MappingService`, and stayed put: `MappingRepository`, `MappingValidator`,
`MappingVersionService`, `MappingVersionRepository`, `DeviceToClientMapService`.

`service/` is now 16 → 12 files, all of them mapping domain code. That makes a later rename to
`mapping/` a reasonable next step; it was **not** done here.

> **Caveat — this is tidying, not decoupling.** `ConfigurationRegistry` is a service locator
> imported by **54 files**, holding `MappingService`, both configuration services, `C8YAgent`,
> `TenantRegistry`, `GraalVMContextService` and `ConnectorRegistry`. Most consumers reach a service
> through it rather than importing it, so moving packages does not change the dependency graph.
> If real decoupling is the goal, `ConfigurationRegistry` is the thing to address — not folder names.

**Deliberately not done:** mass-migrating `controller/` and `service/` into feature packages, and
renaming `service/` → `mapping/`. On the fan-in evidence above the former is churn without decoupling.

---

## 8. Not done

- **DTO/domain separation.** Domain objects are still the wire contract (23 classes with `@Schema`,
  controllers returning `Mapping`). Fine for a service this size, but it should be a *stated*
  decision — an internal rename is currently a breaking API change.
- **UI model generation.** [`dynamic-mapper-ui/src/shared/mapping/mapping.model.ts`](../../dynamic-mapper-ui/src/shared/mapping/mapping.model.ts)
  (927 lines) is a hand-maintained mirror of the backend model. Candidate for springdoc +
  openapi-generator.
- **`ExtensionEntry` holding a live SPI instance** (see §2).
- **Spring Modulith.** If module boundaries start drifting, `ApplicationModules.of(App.class).verify()`
  checks them at build time instead of relying on package placement. Spring's own docs point to it for
  domain-based structure. Not added — no dependency today, noted as the option if drift becomes real.

---

## 9. Review guide

Most of the 161 changed paths are mechanical import rewrites. The parts that need human eyes:

| Priority | What | Why |
|----------|------|-----|
| **High** | `AbstractFlowResultProcessor`, `AbstractExtensibleResultProcessor` + their 4 subclasses | Real logic change: `ProcessingState` parameter removed, state mutation redirected to `context` |
| **High** | `AbstractExtensibleResultProcessor.syncOutputToContext()` | Behavior change — now merges errors and logs |
| Medium | `docs/backend/conventions.md`, `docs/feature/mapping-processing-inbound.md` | Guidance was previously wrong (§5) |
| Medium | `ai/AIAgentRef.java` + `AIAgentService:160` | The only rename |
| Low | Everything else | `git mv` + import rewrite; the compiler covers it |

`git diff -M` renders the moves as renames, which makes the diff far smaller.

### Compatibility check — extensions are unaffected

Audited, because this is the one thing a package move could plausibly break:

- **`dynamic-mapper-interface`** contains **zero Java sources** — it is a third-party dependency
  aggregator. Nothing to break.
- **`dynamic-mapper-extension`** compiles against `dynamic-mapper-service` (classifier `classes`).
  All 17 of its distinct `dynamic.mapper.*` imports are from packages that did **not** move.
- No moved type appears in any SPI method signature, nor transitively via `Message`,
  `CumulocityObject`, `DeviceMessage`, `ExternalId` or `CumulocityType`. So extensions compiled
  against an older release are also safe.
- One unused `import ...runtime.ProcessingContext` was removed from `ProcessorExtensionOutbound`.

### Known unrelated failure

`mvn clean install` fails at the Docker packaging step:

```
Docker build error: 255 The command '/bin/sh -c apk add --no-cache coreutils' returned a non-zero code: 255
```

This is **pre-existing and environmental** — reproduced on a pristine worktree at `HEAD` (`c25abce33`)
with none of these changes. Likely arm64 + the `alpine:3` base image. Use `mvn clean test` to verify
this work.
