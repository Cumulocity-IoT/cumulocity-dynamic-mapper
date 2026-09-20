# Smart Function Type Sync

This document describes the strategy for keeping the TypeScript type definitions
in `dynamic-mapper-smart-function/src/types/` in sync with the Java runtime API
exposed to JavaScript via GraalVM.

> The same contract is also described by the AI prompts, the in-app docs, the editor's
> completion provider and the JS templates. [contract-sync.md](contract-sync.md) is the umbrella
> strategy covering all nine surfaces, with runnable checks; this page is the TypeScript detail.

---

## 1. What "sync" means here

The TypeScript file
[smart-function-dynamic-mapper.types.ts](../dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts)
is a **hand-written contract** that documents the API Smart Functions see at
runtime. It is not generated from the Java source — but since 2026-09 the build compares the two:
`SmartFunctionApiContractTest` fails when the `msg` interfaces and `InputMessage.java` disagree,
and the editor's autocomplete is generated from this file rather than written beside it. "Sync"
means every public method, field, and constant a JavaScript Smart Function can call at runtime has
a matching declaration in the TypeScript types — no more, no less.

| Java source of truth | TypeScript counterpart |
|---|---|
| `DataPrepContext.java` | `DataPrepContext` in `dataprep.types.ts` |
| `SmartFunctionContext.java` | `SmartFunctionContext` in `smart-function-dynamic-mapper.types.ts` |
| `InputMessage.java` | `DynamicMapperDeviceMessage` + `OutboundMessage` |
| `ExternalId.java` | `ExternalId` in `dataprep.types.ts` |
| `ExternalSource.java` | `ExternalSource` in `smart-function-dynamic-mapper.types.ts` |
| Templates in `resources/templates/` | Code examples in JSDoc comments |
| Test scripts in `resources/testing/integration/` | `*.spec.ts` tests |
| In-app docs `dynamic-mapper-ui/public/docs/smartfunction.md` | JSDoc and inline docs |

---

## 2. Current sync status (as of 2026-09-20)

All gaps identified in the audits have been resolved. See Section 6 for the history.
The resources listed below are in sync, and most of the comparison is now done by the build
rather than by reading — see [contract-sync.md §3](contract-sync.md).

### 2.1 Confirmed in sync

**Context methods (`SmartFunctionContext` ↔ `DataPrepContext` + `SmartFunctionContext.java`)**

| TypeScript | Java | Notes |
|---|---|---|
| `getState(key)` | `DataPrepContext.getState(String)` | V1 — returns `any` |
| `getState(key, default)` | `DataPrepContext.getState(String, Object)` | V1 2-arg; V2 typed |
| `setState(key, value)` | `DataPrepContext.setState(String, Value)` | |
| `getStateAll()` | `DataPrepContext.getStateAll()` | |
| `getStateKeySet()` | `DataPrepContext.getStateKeySet()` | returns `string[]` |
| `getConfig()` | `DataPrepContext.getConfig()` | |
| `getClientId()` | `DataPrepContext.getClientId()` | |
| `getExternalId()` | `DataPrepContext.getExternalId()` | outbound only |
| `getTesting()` | `DataPrepContext.getTesting()` | skip side-effects in test cycle |
| `getManagedObject(id)` | `SmartFunctionContext.getManagedObject(String)` | |
| `getManagedObjectByExternalId(ext)` | `SmartFunctionContext.getManagedObjectByExternalId(ExternalId\|Value)` | |
| `getDTMAsset(id)` | `DataPrepContext.getDTMAsset(String)` | placeholder |
| `addWarning(msg)` | `DataPrepContext.addWarning(String)` / `SmartFunctionContext` override | stores under `_WARNINGS_` key |
| `logMessage(msg)` | `DataPrepContext.logMessage(String)` | `@deprecated` — prefer `console.log` |
| `addLogMessage(msg)` | `DataPrepContext.addLogMessage(String)` | `@deprecated` — prefer `console.log` |
| `clearState()` | `DataPrepContext.clearState()` | drops all state for the mapping |

`SmartFunctionContext` also declares `setClientId` and `setConfig`, which JavaScript *can* call —
`allowPublicAccess(true)` exposes them — but they exist for the host to populate the context before
the function runs. They are deliberately absent from the TypeScript and from the docs, and
`SmartFunctionApiContractTest` keeps them out via its `HOST_ONLY` set.

**Input message fields (`DynamicMapperDeviceMessage` + `OutboundMessage` ↔ `InputMessage.java`)**

| TypeScript | Java | Set by |
|---|---|---|
| `msg.payload` | `InputMessage.payload` | Both processors |
| `msg.topic` | `InputMessage.topic` | Both processors |
| `msg.clientId` | `InputMessage.clientId` | Inbound processor; `null` for outbound |
| `msg.sourceId` | `InputMessage.sourceId` | Outbound processor; typed `never` on `DynamicMapperDeviceMessage` since inbound passes `null` |
| `msg.cumulocityType` | `InputMessage.cumulocityType` | Outbound processor; typed `never` on `DynamicMapperDeviceMessage` since inbound passes `null` |
| `msg.time` | `InputMessage.time` | Both processors (`Instant.now().toString()`) |
| `msg.transportId` | `InputMessage.transportId` | Inbound processor (`context.getConnectorIdentifier()`); `null` for outbound |
| `msg.transportFields` | `InputMessage.transportFields` | Inbound processor; carries the Kafka record **key** as `{"key": ...}`, empty map when the transport has none |

**Templates**

- All 18 templates use field style (`msg.payload`, `msg.topic`) — getter style (`msg.getPayload()`) removed.
- Timestamp fallbacks use `msg.time` (14 of 18 templates); no template uses `new Date().toISOString()`.
  `InputMessage.time` **is** set by both processors, so the earlier claim that it was unset — and the
  resulting advice to use `new Date()` — was wrong and has been reversed. Verified 2026-09-19.

**Output types**

- `CumulocityObject` fields ↔ `CumulocityObject.java` (`cumulocityType`, `action`, `payload`, `externalSource`, `contextData`, `sourceId`, `targetPath`)
- `DeviceMessage` fields ↔ `DeviceMessage.java` (`payload`, `topic`, `clientId`, `transportId`, `transportFields`, `retain`, `time`, `externalSource`, `action`, `sourceId`)
- `ExternalSource` ↔ `ExternalSource.java`

---

## 3. Ownership

| Artifact | Owner | Change trigger |
|---|---|---|
| `DataPrepContext.java` | Backend | Add / remove / rename any method callable from JavaScript |
| `SmartFunctionContext.java` | Backend | Same, plus state / config / device-lookup logic changes |
| `InputMessage.java` | Backend | Add / remove a public field visible as `msg.*` in JavaScript |
| `dataprep.types.ts` | Shared | Any change to `DataPrepContext.java` methods |
| `smart-function-dynamic-mapper.types.ts` | Shared | Any change to `SmartFunctionContext.java`, `InputMessage.java`, `CumulocityObject.java`, `DeviceMessage.java`, `ExternalSource.java` |
| `resources/templates/*.js` | Backend | Any API surface change; all templates must stay runnable |
| `smart-function-dynamic-mapper.types.spec.ts` | Shared | Any type change; new methods need test coverage |
| `dynamic-mapper-ui/public/docs/smartfunction.md` | Frontend | TypeScript types or templates change (context-API coverage is build-enforced) |
| `docs/smart-functions.md` | Shared | Any of the above change |

---

## 4. Future sync steps

Use this checklist whenever a Smart Function API change is made.

### 4.1 Adding a new method or field to the Java API

1. **Java** — implement the method on `SmartFunctionContext.java`, and add it to
   `DataPrepContext.java` as well if Java Extensions should have it too.
   - **The concrete class is what JavaScript sees.** The runtime configures
     `HostAccess.newBuilder().allowPublicAccess(true)`, which exposes every public method of the
     object's class whether or not an interface declares it — `setClientId` and `setConfig` reach
     JavaScript exactly this way. An earlier version of this page claimed the opposite ("only on
     the concrete class → NOT visible → always add to the interface"); that was wrong.
   - The interface still matters, just for a different reason: `DataPrepContext` is what Java
     Extensions are written against, so a method added only to `SmartFunctionContext` is available
     to Smart Functions but not to extension authors. Decide which audience you mean.
2. **TypeScript** — mirror the method in `SmartFunctionContext` in `smart-function-dynamic-mapper.types.ts`.
   - Also add to `SmartFunctionContextV2` if applicable.
3. **Mock helpers** — implement the method in both `createMockRuntimeContext` and `createMockRuntimeContextV2`.
   - The TypeScript compiler will enforce this: a missing mock implementation causes a type error.
4. **Tests** — add a test case in `smart-function-dynamic-mapper.types.spec.ts` exercising the new method.
5. **Templates** — update at least one template in `resources/templates/` to demonstrate the new capability.
6. **Docs** — update `docs/smart-functions.md` if the method is user-facing.
7. **Verify** — run `npm test` in `dynamic-mapper-smart-function/` to confirm no regressions.

### 4.2 Adding a new `InputMessage` field (changes `msg.*`)

1. **Java** — add the public field (and its getter alias) to `InputMessage.java`.
   - Both `msg.field` (direct) and `msg.getField()` (getter) are supported; Java must expose both.
   - Set the field in `FlowInboundProcessor.createInputMessage` and/or
     `FlowOutboundProcessor.createInputMessage`. Both call the same constructor, so you are choosing
     what the *other* direction passes; `null` is a legitimate answer, and it must be matched by a
     `never` in step 2.
2. **TypeScript** — add the field to **both** `DynamicMapperDeviceMessage` and `OutboundMessage` in
   `smart-function-dynamic-mapper.types.ts`. The direction that does not receive it declares
   `never`, not nothing — omission then always means "forgotten". Both halves are build-enforced:
   the contract test compares the declared fields against `InputMessage.java`, and compares the
   `never`s against what each processor actually passes.
3. **Mock helpers** — update `createMockInputMessage` / `createMockOutboundMessage` if the field is needed in tests.
4. **Templates** — update any template that would benefit from the new field.
5. **Docs** — update the `msg` fields table in `docs/smart-functions.md`.

### 4.3 Renaming or removing a method

1. **Java** — keep the old method and mark it `@Deprecated`. Add the new name alongside it.
2. **TypeScript** — mark the old name `@deprecated` in the JSDoc and add the new name.
3. **Templates** — update all templates to use the new name immediately (templates are the canonical example).
4. **Gate** — no list to maintain: once the templates use the new name, `npm run check:templates` (§5.3) fails on any template that goes back to the old one, because the old name is gone from the types.
5. After one major version, remove the deprecated Java method and its TypeScript counterpart together.

### 4.4 Template-only change (new example, bug fix)

1. Change the template in `resources/templates/`.
2. Verify the template runs correctly against a local instance using the corresponding script in `resources/testing/integration/` (e.g., `test-inbound-json-smartfunction.sh`).
3. Use **field style only** (`msg.payload`, `msg.topic`). Getter style (`msg.getPayload()`) is deprecated and must not appear in templates.
4. Use `msg.time` as the timestamp fallback. Do NOT use `new Date().toISOString()`. Pattern:
   `var time = payload["time"] || msg.time;`
   Note what `msg.time` actually is: `Instant.now().toString()` at the moment the runtime builds the
   input message — i.e. **processing time, not connector receive time**. Prefer a timestamp from the
   payload whenever the device sends one.
5. If the template demonstrates a new pattern, add a matching JSDoc `@example` to the relevant TypeScript type.

### 4.5 TypeScript-only change (improve generics, add JSDoc, deprecate)

1. Change the TypeScript file.
2. Run `npm test` in `dynamic-mapper-smart-function/` — the spec file acts as a compile-time contract test.
3. Update JSDoc `@example` blocks to match the canonical template style.
4. If a method is deprecated, ensure the Java side marks it `@Deprecated` in the same PR.

---

## 5. Automated checks

### 5.1 TypeScript compilation (already in CI)

```bash
cd dynamic-mapper-smart-function && npm test
```

The spec file `smart-function-dynamic-mapper.types.spec.ts` compiles against the live types.
Any method added to the interface but missing from the mock helpers causes a compile-time error,
forcing the developer to implement it before CI passes.

### 5.2 Java reflection contract test (implemented 2026-09-19)

Shipped as `dynamic-mapper-service/src/test/java/dynamic/mapper/processor/SmartFunctionApiContractTest.java`
and run by the normal `mvn test`. Eight checks; the ones that matter here:

- the context API JavaScript sees matches a pinned canonical list, so any addition, removal or
  rename fails the build with a message naming the other surfaces to update;
- the TypeScript `msg` interfaces declare exactly the public fields of `InputMessage.java`;
- the fields each direction leaves unset are exactly the ones its interface declares `never`,
  established by running both processors rather than by reading their source.

```bash
cd dynamic-mapper-service && mvn test -Dtest=SmartFunctionApiContractTest
```

It reflects over the **concrete** `SmartFunctionContext` class, not `DataPrepContext` — see §4.1
for why that distinction is the whole point.

### 5.3 Template type-check (implemented 2026-09-19)

Templates are now type-checked against the real types rather than grepped for banned patterns:

```bash
cd dynamic-mapper-smart-function && npm run check:templates
```

`tsconfig.templates.json` compiles the templates with `checkJs` and the types mapped in; each
template carries `// @ts-check` and a JSDoc `@param` pair naming `msg` and `context`. Phantom
context methods and phantom `msg` fields both fail the build. Wired into `pretest` and into the
`smart-function-contract` job in `.github/workflows/ci.yml`. GraalVM is unaffected — the additions
are comments only.

### 5.3c Editor autocomplete is generated (implemented 2026-09-19)

The mapping editor's completion and hover data is no longer hand-maintained beside the types; it is
produced from them by `dynamic-mapper-smart-function/scripts/generate-editor-api.cjs` into
`dynamic-mapper-ui/src/shared/mapping/generated/smart-function-api.generated.ts`, which
`stepper.model.ts` imports as `SMART_FUNCTION_API`. CI regenerates and fails on any diff.

```bash
cd dynamic-mapper-smart-function && npm run generate:editor-api
npm run check:generated   # the output must also compile against the UI's ClassOrEnum
```

This is the only surface that *cannot* drift, because there is no second copy to compare. Full
chain in [contract-sync.md §3.7](contract-sync.md).

### 5.4 In-app documentation (partly enforced)

`doc-smartfunction.component.html` no longer exists; the in-app docs are Markdown under
`dynamic-mapper-ui/public/docs/`, rendered by the doc module. `smartfunction.md` is checked in both
directions by the contract test — it may not describe a context method the runtime lacks, and it
may not omit one the runtime has. That coverage check is what found six undocumented methods in the
2026-09-19 audit.

What is still unchecked is the *prose*: an accurate method name wrapped in a wrong explanation
passes. Nothing mechanical will catch that.

---

## 6. Resolved gaps (history)

The following issues were found during the initial audit (2026-06) and have been fixed.

| Gap | Issue | Resolution |
|---|---|---|
| A | `DynamicMapperDeviceMessage` declared `time`, `transportId`, `transportFields` — believed unset by `InputMessage.java` | **Superseded.** All three *are* set (inbound sets `time`, `transportId` and `transportFields`; outbound sets `time`). They were re-added and the templates reverted to `msg.time`. The original diagnosis was wrong — see the 2026-09-19 row below. |
| B | `addWarning` was `private` in `SmartFunctionContext.java` — not accessible from JavaScript; `logMessage`/`addLogMessage` missing from TypeScript | Made `addWarning` public + `@Override`; added `addWarning` to `DataPrepContext.java` interface; added `logMessage`, `addLogMessage` to TypeScript. |
| C | `getTesting()` declared in `DataPrepContext.java` but missing from TypeScript | Added `getTesting(): boolean` to `SmartFunctionContext`, `SmartFunctionContextV2`, and both mock helpers. |
| D | `getStateKeySet()` declared in `DataPrepContext.java` but missing from TypeScript | Added `getStateKeySet(): string[]` to `SmartFunctionContext`, `SmartFunctionContextV2`, and both mock helpers. |
| E | `docs/smart-functions.md` documented phantom methods (`getDevice`, `getCache`, `setCache`, `log`) | Rewrote the entire document against the live API. |
| F | All 15 templates used deprecated getter style (`msg.getPayload()`, `msg.getTopic()`) | Replaced every getter call with field-style access (`msg.payload`, `msg.topic`) across all templates. |

### Later audits

| Date | Scope | Outcome |
|---|---|---|
| 2026-09-19 | Re-audit against the runtime | `OutboundMessage` was missing 5 of 8 `InputMessage` fields; `clearState` missing from both context interfaces; gap A above found to be a misdiagnosis. All fixed. Details in [contract-sync.md §7](contract-sync.md). |
| 2026-09-20 | Populated-ness, and this page | Inbound `sourceId` / `cumulocityType` were typed as strings although `FlowInboundProcessor` passes `null` — now `never`, and enforced. This page was corrected too: §4.1 had claimed that a method only on the concrete class is invisible to JavaScript, which is false under `allowPublicAccess(true)`; §5.2 still called the contract test "recommended" three months after it shipped; and §5.4 targeted a component that had been deleted. |
