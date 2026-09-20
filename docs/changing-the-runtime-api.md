# Changing the Smart Function API

What to do when you change a POJO that Smart Functions or Java Extensions can see — adding a field
to `InputMessage`, a method to `SmartFunctionContext`, a value to an enum.

The background is in [contract-sync.md](contract-sync.md). This page is the procedure.

---

## Start here: the POJO, never the TypeScript

The Java runtime is the contract. Everything else — the TypeScript types, the editor's
autocomplete, the templates, the docs, the AI prompts — is a **mirror** of it.

Changing a mirror first does not work. A field added to `DynamicMapperDeviceMessage` that no
processor populates is `undefined` at runtime, and TypeScript will cheerfully tell users it exists.

> **One rule:** change the Java class, make a processor populate it, *then* propagate outwards.

---

## The procedure

### 1. Change the POJO, and populate it

Both halves matter. Declaring the field is not enough — it stays `null` until a processor sets it.

| Adding to | Also do this |
|---|---|
| `InputMessage` | Pass it in **both** `FlowInboundProcessor.createInputMessage` and `FlowOutboundProcessor.createInputMessage`. They share one constructor, so decide what the other direction passes — `null` is a legitimate answer, and the TypeScript side then declares `never`. |
| `SmartFunctionContext` | Implement it on the concrete class — that is the object handed to the engine, and `allowPublicAccess(true)` exposes its public methods whether or not an interface declares them. Add it to `DataPrepContext` as well only if Java Extensions should have it too. |
| `CumulocityObject` / `DeviceMessage` | These are what user code *returns*, so the consuming processor (`FlowResultInboundProcessor` / `FlowResultOutboundProcessor`) has to read the new field or nothing happens. |
| An enum (`CumulocityType`, `MappingAction`, `Destination`, `RepairStrategy`, `API`) | Check the JSON value the enum serialises to — that string, not the Java constant name, is what every mirror must use. |

That is the whole Java side. Everything below is propagation.

### 2. Mirror into the TypeScript types

`dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts`

| You changed | Mirror into |
|---|---|
| `InputMessage` | `DynamicMapperDeviceMessage` **and** `OutboundMessage` — both must account for every field; use `never` for one a direction never receives |
| `SmartFunctionContext` | `SmartFunctionContext`, `SmartFunctionContextV2`, **and both mock helpers** in the same file |
| `CumulocityObject` / `DeviceMessage` | the interface of the same name |
| An enum | the matching union — `C8yObjectType`, `C8yObjectAction`, `C8yDestination`, `C8yChildReference`. Keep it a **named** export; the generator cannot emit an entry for an inline union |

### 3. Regenerate the editor's autocomplete

```bash
cd dynamic-mapper-smart-function && npm run generate:editor-api
```

This reads the type definitions with the TypeScript compiler and rewrites
`dynamic-mapper-ui/src/shared/mapping/generated/smart-function-api.generated.ts`, which
`stepper.model.ts` imports as `SMART_FUNCTION_API` — that array *is* the editor's autocomplete.
Never edit it by hand; commit the result. The full chain is in
[contract-sync.md §3.7](contract-sync.md).

### 4. Update the prose

- `dynamic-mapper-ui/public/docs/smartfunction.md` — the user-facing reference
- `dynamic-mapper-service/src/main/resources/prompts/*.txt` — what the AI is told; for a
  `_CONTEXT_DATA_` key, put it in the **right direction's** section
- A template in `dynamic-mapper-service/src/main/resources/templates/` if the change deserves an
  example — optional, but templates are what users copy

### 5. Run the checks

```bash
cd dynamic-mapper-service     && mvn test          # contract tests + everything else
cd dynamic-mapper-smart-function && npm test       # type-checks the templates, then unit tests
cd dynamic-mapper-ui          && npm test && npm run build
```

To iterate faster on just the contract:

```bash
cd dynamic-mapper-service && mvn test -Dtest=SmartFunctionApiContractTest
```

---

## What fails if you skip a step

Eight checks in `SmartFunctionApiContractTest`, plus the template and generator guards. The point
of the table is to show you will be told, not to be memorised.

| Skipped | What fails | Message you get |
|---|---|---|
| Mirror an `InputMessage` field into TypeScript | contract test | `DynamicMapperDeviceMessage does not declare runtime field(s) [x]` |
| Remove a TypeScript field the runtime dropped | contract test | `declares field(s) [x] that InputMessage.java does not have` |
| Regenerate the editor table | CI job | `Generated editor API is stale. Run npm run generate:editor-api` |
| Document a new context method | contract test | `context method(s) missing from …smartfunction.md: [x]` |
| Remove a context method the docs still describe | contract test | `documents context method(s) the runtime does not have: [x]` |
| Prompt references something that does not exist | contract test | `teaches context method(s) the runtime does not have: [x]` |
| Declare a field as present that the direction never passes | contract test | `the fields declared `never` must be exactly the ones this direction leaves unset` |
| Template uses a field the types do not declare | `npm run check:templates` | `Property 'x' does not exist on type 'DynamicMapperDeviceMessage'` |
| Change the context API at all | contract test | canonical-list failure naming every surface to update |

That last one is the safety net for everything else: it cannot tell whether you updated the docs
and prompts, but it stops the change passing silently.

---

## Where the net still has holes

Stated plainly so you know when to be careful rather than trusting a green build:

| Not checked | Consequence | Mitigation |
|---|---|---|
| **Java enum ↔ TypeScript union** | Adding `Destination.ARCHIVE` in Java and forgetting `C8yDestination` leaves the editor and types a value short | Diff them by hand when touching an enum |
| **`CumulocityObject` / `DeviceMessage` ↔ their TypeScript interfaces** | Only `InputMessage` has the exact-mirror check; the return types rely on review | Same |
| **TypeScript `SmartFunctionContext` ↔ the Java class** | The canonical list pins the *Java* side, so an addition is noticed — but nothing verifies the TypeScript actually gained it | The canonical-list failure names it; do not just update the list |

These are the same mechanism as the `InputMessage` check and could be closed the same way; they
are open because nothing has gone wrong there yet, not because they are hard.

What *is* now checked is whether a field is actually populated: the contract test runs both
processors and requires the fields each direction leaves unset to be exactly the ones its
TypeScript interface declares `never`. So "I declared it but no processor sets it" fails the build
rather than surfacing as `undefined` in someone's mapping.

---

## Worked example: adding `correlationId` to `msg`

1. `InputMessage.java` — add `public final String correlationId;`, its getter, and a constructor
   parameter.
2. `FlowInboundProcessor.createInputMessage` — pass the real value.
   `FlowOutboundProcessor.createInputMessage` — pass `null` if outbound has no such thing.
3. Types — add `correlationId?: string` to `DynamicMapperDeviceMessage`, and
   `correlationId?: never` to `OutboundMessage` with a comment saying why. The `never` has to match
   the `null` you passed in step 2; the contract test compares them by running both processors.
4. `npm run generate:editor-api`.
5. Add it to the `msg` fields table in `smartfunction.md`; mention it in the prompt if the AI
   should use it.
6. Run the three test commands above.

Skip step 3 and the build tells you, by name, for both interfaces.
