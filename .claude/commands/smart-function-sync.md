# Smart Function Sync Audit

Audit the Smart Function API surface to verify that the Java source and TypeScript declarations are in sync. Follow the strategy described in `docs/smart-function-type-sync.md`.

## Steps

### 1. Read the Java source of truth

Read these files in full:

- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/DataPrepContext.java`
- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/SmartFunctionContext.java`
- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/InputMessage.java`

Extract:
- All **public method signatures** from `DataPrepContext` (the interface) — these are callable from JavaScript via GraalVM.
- Any **additional public methods** on `SmartFunctionContext` that are not on the interface (also callable).
- All **public fields** on `InputMessage` — these become `msg.*` in Smart Functions.

### 2. Read the TypeScript declarations

Read these files in full:

- `dynamic-mapper-smart-function/src/types/dataprep.types.ts`
- `dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts`

Extract:
- All methods declared on `SmartFunctionContext` and `SmartFunctionContextV2` interfaces.
- All fields declared on `DynamicMapperDeviceMessage` (inbound `msg`) and `OutboundMessage` (outbound `msg`).

### 3. Read the mock helpers

In `smart-function-dynamic-mapper.types.ts` (or its spec file), find `createMockRuntimeContext`, `createMockRuntimeContextV2`, `createMockInputMessage`, `createMockOutboundMessage`. Check that every interface method/field is implemented in the corresponding mock.

The spec file is at:
`dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.spec.ts`

### 4. Diff and report

Produce a report with four sections:

**A. Methods in Java but missing from TypeScript**
List each Java method that has no matching TypeScript declaration. These are gaps that break IntelliSense and may surprise developers.

**B. Methods in TypeScript but absent from Java (phantoms)**
List each TypeScript declaration that does not match any public method on `DataPrepContext` or `SmartFunctionContext`. These may be stale docs or errors.

**C. `msg.*` field mismatches**
Compare `InputMessage.java` public fields with `DynamicMapperDeviceMessage` and `OutboundMessage`. Report fields present in Java but missing in TS, and vice versa.

**D. Mock helpers out of date**
Report any interface method that is declared in TypeScript but not implemented in the mock helpers (`createMockRuntimeContext` / `createMockRuntimeContextV2`).

If all four sections are empty, print: **All in sync — no gaps found.**

### 5. Show the applicable checklist

Based on what gaps were found (if any), print the relevant section from `docs/smart-function-type-sync.md`:
- New Java method missing from TS → Section 4.1
- New `InputMessage` field missing from TS → Section 4.2
- Deprecated/renamed method → Section 4.3
- Template-only issue → Section 4.4
- TypeScript-only issue → Section 4.5

Also remind the developer to run `npm test` in `dynamic-mapper-smart-function/` after any TypeScript change.
