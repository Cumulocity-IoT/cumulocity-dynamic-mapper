# Smart Function Type Sync

This document describes the strategy for keeping the TypeScript type definitions
in `dynamic-mapper-smart-function/src/types/` in sync with the Java runtime API
exposed to JavaScript via GraalVM.

---

## 1. What "sync" means here

The TypeScript file
[smart-function-dynamic-mapper.types.ts](../dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts)
is a **hand-written contract** that documents the API Smart Functions see at
runtime. It has no build-time link to the Java source. "Sync" means every public
method, field, and constant a JavaScript Smart Function can call at runtime has a
matching declaration in the TypeScript types — no more, no less.

| Java source of truth | TypeScript counterpart |
|---|---|
| `DataPrepContext.java` | `DataPrepContext` in `dataprep.types.ts` |
| `SmartFunctionContext.java` | `SmartFunctionContext` in `smart-function-dynamic-mapper.types.ts` |
| `InputMessage.java` | `DynamicMapperDeviceMessage` + `OutboundMessage` |
| `ExternalId.java` | `ExternalId` in `dataprep.types.ts` |
| `ExternalSource.java` | `ExternalSource` in `smart-function-dynamic-mapper.types.ts` |
| Templates in `resources/templates/` | Code examples in JSDoc comments |
| Test scripts in `resources/script/test/` | `*.spec.ts` tests |
| Angular docs in `doc-smartfunction.component.html` | JSDoc and inline docs |

---

## 2. Current sync status (as of 2026-06)

All gaps identified in the initial audit have been resolved. See Section 6 for the
history. The resources listed below are now in sync.

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

**Input message fields (`DynamicMapperDeviceMessage` ↔ `InputMessage.java`)**

| TypeScript | Java | Set by |
|---|---|---|
| `msg.payload` | `InputMessage.payload` | Both processors |
| `msg.topic` | `InputMessage.topic` | Both processors |
| `msg.clientId` | `InputMessage.clientId` | Inbound processor; `null` for outbound |
| `msg.sourceId` | `InputMessage.sourceId` | Outbound processor; `null` for inbound |
| `msg.cumulocityType` | `InputMessage.cumulocityType` | Outbound processor; `null` for inbound |
| `msg.time` | `InputMessage.time` | Both processors (`Instant.now().toString()`) |
| `msg.transportId` | `InputMessage.transportId` | Inbound processor (`context.getConnectorIdentifier()`); `null` for outbound |
| `msg.transportFields` | `InputMessage.transportFields` | Inbound processor; carries the Kafka record **key** as `{"key": ...}`, empty map when the transport has none |

**Templates**

- All 15 templates use field style (`msg.payload`, `msg.topic`) — getter style (`msg.getPayload()`) removed.
- All timestamp fallbacks use `new Date().toISOString()` — `msg.time` references removed (field not set by runtime).

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
| `doc-smartfunction.component.html` | Frontend | TypeScript types or templates change |
| `docs/smart-functions.md` | Shared | Any of the above change |

---

## 4. Future sync steps

Use this checklist whenever a Smart Function API change is made.

### 4.1 Adding a new method or field to the Java API

1. **Java** — add the method to `DataPrepContext.java` (interface) and implement it in `SmartFunctionContext.java`.
   - If the method is only on the concrete class (not the interface), it will NOT be visible from JavaScript via GraalVM — always add to the interface.
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
   - Set the field in `FlowInboundProcessor.createInputMessage` or `FlowOutboundProcessor.createInputMessage` as appropriate.
2. **TypeScript** — add the field to `DynamicMapperDeviceMessage` (inbound) or `OutboundMessage` (outbound) in `smart-function-dynamic-mapper.types.ts`.
3. **Mock helpers** — update `createMockInputMessage` / `createMockOutboundMessage` if the field is needed in tests.
4. **Templates** — update any template that would benefit from the new field.
5. **Docs** — update the `msg` fields table in `docs/smart-functions.md`.

### 4.3 Renaming or removing a method

1. **Java** — keep the old method and mark it `@Deprecated`. Add the new name alongside it.
2. **TypeScript** — mark the old name `@deprecated` in the JSDoc and add the new name.
3. **Templates** — update all templates to use the new name immediately (templates are the canonical example).
4. **Lint gate** — add the old name to the banned-patterns list in Section 5.3 so templates never regress.
5. After one major version, remove the deprecated Java method and its TypeScript counterpart together.

### 4.4 Template-only change (new example, bug fix)

1. Change the template in `resources/templates/`.
2. Verify the template runs correctly against a local instance using the corresponding script in `resources/script/test/` (e.g., `test-inbound-json-smartfunction.sh`).
3. Use **field style only** (`msg.payload`, `msg.topic`). Getter style (`msg.getPayload()`) is deprecated and must not appear in templates.
4. Use `msg.time` as the timestamp fallback — it is set by the connector at receive time. Do NOT use `new Date().toISOString()`. Pattern: `var time = payload["time"] || msg.time;`
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

### 5.2 Java reflection contract test (recommended)

Add a JUnit test that enumerates every public method on `DataPrepContext` and asserts it matches
a canonical list. A PR adding a Java method without updating the list fails CI immediately.

```java
// SmartFunctionContractTest.java
@Test
void dataPrepContextMethodsMatchCanonicalList() {
    List<String> actual = Arrays.stream(DataPrepContext.class.getMethods())
        .map(Method::getName)
        .filter(n -> !n.startsWith("lambda$"))
        .distinct().sorted().toList();

    List<String> EXPECTED = List.of(
        "addLogMessage", "addWarning", "clearState",
        "getClientId", "getConfig", "getDTMAsset", "getExternalId",
        "getManagedObject", "getManagedObjectByExternalId",
        "getState", "getStateAll", "getStateKeySet",
        "getTesting", "logMessage", "setState"
    );

    assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED);
}
```

When this list and the TypeScript interface diverge, the test is the single source of truth:
update both together.

### 5.3 Template lint check (recommended)

A simple grep script that fails if deprecated patterns appear in any template:

```bash
#!/usr/bin/env bash
# lint-templates.sh — run from repo root
TEMPLATES=dynamic-mapper-service/src/main/resources/templates

echo "Checking for deprecated getter style..."
if grep -rEn "msg\.(getPayload|getTopic|getClientId|getSourceId|getCumulocityType)\(\)" "$TEMPLATES"; then
  echo "ERROR: getter-style msg access is deprecated. Use msg.payload, msg.topic, etc."
  exit 1
fi

echo "Checking for removed context methods..."
if grep -rn "context\.getDevice\|context\.getCache\|context\.setCache\|context\.log(" "$TEMPLATES"; then
  echo "ERROR: phantom context methods found. Use getManagedObjectByExternalId, getState, setState, console.log."
  exit 1
fi

echo "Checking for obsolete new Date().toISOString() fallback..."
if grep -rn "new Date().toISOString()" "$TEMPLATES"; then
  echo "ERROR: new Date() fallback is obsolete. Use msg.time (set by the connector at receive time)."
  exit 1
fi

echo "All template checks passed."
```

Integrate into the Maven build via `exec-maven-plugin` or as a CI step.

### 5.4 Angular doc component (future)

`doc-smartfunction.component.html` is rendered in the Angular app. A Cypress or Playwright test
asserting that code blocks reference the canonical method names (e.g. `getManagedObjectByExternalId`,
`getState`) would prevent the UI docs from silently drifting.

---

## 6. Resolved gaps (history)

The following issues were found during the initial audit (2026-06) and have been fixed.

| Gap | Issue | Resolution |
|---|---|---|
| A | `DynamicMapperDeviceMessage` declared `time`, `transportId`, `transportFields` — none are set by `InputMessage.java` | Removed from TypeScript; added `sourceId` and `cumulocityType` which are actually set. Templates changed to use `new Date().toISOString()` instead of `msg.time`. |
| B | `addWarning` was `private` in `SmartFunctionContext.java` — not accessible from JavaScript; `logMessage`/`addLogMessage` missing from TypeScript | Made `addWarning` public + `@Override`; added `addWarning` to `DataPrepContext.java` interface; added `logMessage`, `addLogMessage` to TypeScript. |
| C | `getTesting()` declared in `DataPrepContext.java` but missing from TypeScript | Added `getTesting(): boolean` to `SmartFunctionContext`, `SmartFunctionContextV2`, and both mock helpers. |
| D | `getStateKeySet()` declared in `DataPrepContext.java` but missing from TypeScript | Added `getStateKeySet(): string[]` to `SmartFunctionContext`, `SmartFunctionContextV2`, and both mock helpers. |
| E | `docs/smart-functions.md` documented phantom methods (`getDevice`, `getCache`, `setCache`, `log`) | Rewrote the entire document against the live API. |
| F | All 15 templates used deprecated getter style (`msg.getPayload()`, `msg.getTopic()`) | Replaced every getter call with field-style access (`msg.payload`, `msg.topic`) across all templates. |
