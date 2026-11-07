# JSONata Transformation

JSONata (`transformationType: JSONATA`) is the declarative transformation option for mappings whose source and target
templates are plain JSON objects. New mappings default to `SMART_FUNCTION`; JSONata is chosen explicitly (the
transformation type selector is shown in Expert Mode only). Each field of the target is populated by evaluating a
JSONata expression (the `pathSource`) against the deserialized source payload and writing
the result to a target path (`pathTarget`). It requires no code — mappings are configured
declaratively as a list of substitutions in the mapping editor.

---

## Requirements

**What it is for.** Extracting and reshaping values declaratively, without writing code — suited to simple
field-to-field mappings. It is not the default for new mappings (Smart Function is).
`DEFAULT` is the deprecated legacy name for the same engine and still runs in existing mappings.

- **A substitution pairs a source expression with a target path.** The expression is evaluated
  against the incoming payload; the result is written into the target template.
- **Expressions may compute**, not just select: arithmetic, string handling, conditionals, dates.
- **One substitution must define the device identifier**, so the message can be attributed.
- **A substitution may expand an array** into several Cumulocity objects.
- **Missing or null values have a defined outcome** the mapping chooses — leave the field, remove
  it, or fail the message — rather than an implicit one.
- **The result is type-checked against what the target expects**, so a mapping fails with a clear
  message rather than producing an object Cumulocity rejects.
- **Expressions are testable in the editor** against a sample payload, showing the extracted value.
- **An expression that cannot be parsed fails the message** with an error naming the expression, the target path and
  the position of the syntax error; it is never silently treated as "no value". An expression that fails on one
  message's data (for example a failed number conversion) gives that substitution no value and adds a warning, so the
  rest of the message is still processed and the warning shows in test results.

---

## Implementation

### Where it runs

Extraction logic lives in
[`AbstractJSONataExtractionProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractJSONataExtractionProcessor.java),
shared identically by inbound and outbound. Each direction wires up its own thin
processor bean:

- [`JSONataInboundProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/JSONataInboundProcessor.java) — broker payload → Cumulocity object.
- [`JSONataOutboundProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/outbound/processor/JSONataOutboundProcessor.java) — Cumulocity payload → broker message.

Both simply extend the abstract class with no direction-specific overrides beyond their
constructor — extraction, evaluation, error handling, and failure-count bookkeeping are
identical for both directions (`AbstractJSONataExtractionProcessor.java`, comment on
`JSONataInboundProcessor`).

```mermaid
flowchart LR
    payload["Deserialized payload"] --> loop["for each Substitution\nin mapping.substitutions"]
    loop --> extract["expr = jsonata(pathSource)\nexpr.evaluate(payloadObject)"]
    extract --> arr{"Result is array\nand expandArray=true?"}
    arr -- yes --> iter["processSubstitute() per element"]
    arr -- no --> repair{"Result is array,\nrepairStrategy set?"}
    repair -- USE_FIRST/LAST_VALUE_OF_ARRAY --> reduce["reduce to scalar"]
    repair -- other --> keep["keep as-is"]
    iter --> cache["ProcessingContext.processingCache\n[pathTarget] += SubstituteValue"]
    reduce --> cache
    keep --> cache
```

### Evaluation

`AbstractJSONataExtractionProcessor.extractFromSource()`
([`AbstractJSONataExtractionProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractJSONataExtractionProcessor.java))
iterates `mapping.getSubstitutions()` and, for each `Substitution`, calls
`extractContentFromPayload()`:

```java
var expr = jsonata(substitution.getPathSource());
return expr.evaluate(payloadObject);
```

using `com.dashjoin:jsonata`'s static `Jsonata.jsonata(...)` factory. Failures are split by JSONata error code:

- A **syntax error** (code `S...`) throws an internal `ExpressionException`, which `extractFromSource()` wraps into a
  `ProcessingException`: the message fails, the error carries the expression, the target path and the position, and the
  failure counts toward the mapping's status (unless the run is a test).
- Any other `JException` (type or dynamic errors, depending on the data) and any other exception is logged, the
  substitution gets a `null` value, and the description is added to `context.getWarnings()`. The remaining
  substitutions still run. A path that is simply absent is not an error in JSONata: the result is `undefined`, which
  arrives as `null` and is then handled by the substitution's `repairStrategy`.

The extracted value is then handled by `processSubstitution()`
([`AbstractJSONataExtractionProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractJSONataExtractionProcessor.java)):

- If the result is an array **and** `substitution.getExpandArray()` is set, every element
  is processed individually via `SubstitutionEvaluation.processSubstitute()` — this is
  how one inbound message can fan out into multiple Cumulocity measurements/events/etc.
  from a single substitution.
- Otherwise, if the result is an array and a repair strategy is
  `USE_FIRST_VALUE_OF_ARRAY` / `USE_LAST_VALUE_OF_ARRAY`, it is reduced to a single
  scalar element; any other repair strategy leaves the array as-is (e.g. when the target
  field itself expects an array value).
- The resulting `SubstituteValue`(s) are appended to
  `context.getProcessingCache()` keyed by `pathTarget`, so multiple substitutions can
  target the same JSON path across a message and later be merged into the final target
  document (elsewhere in the pipeline, outside this processor).

### Substitution model: `pathSource` / `pathTarget`

A `Substitution` (`dynamic.mapper.model.Substitution`) is the unit of configuration for a
JSONata mapping:

| Field | Meaning |
|---|---|
| `pathSource` | A JSONata expression evaluated against the deserialized source payload. |
| `pathTarget` | The dot/bracket path in the target document the extracted value is written to. |
| `expandArray` | When true and the extracted value is an array, iterate its elements as separate substitutions instead of writing the array as one value. |
| `repairStrategy` | How to handle the two edge cases: an array result that isn't expanded (`USE_FIRST_VALUE_OF_ARRAY` / `USE_LAST_VALUE_OF_ARRAY`, or leave as-is), and a missing/null value when writing into the target template (`IGNORE`, `REMOVE_IF_MISSING_OR_NULL`, `CREATE_IF_MISSING`). `DEFAULT` writes the value as-is and fails with `Path: <pathTarget> not found!` when the path is not in the target template. See [`jsonata.md`](../../dynamic-mapper-ui/public/docs/jsonata.md#jsonata-repair-strategy). |

Both `pathSource` and `pathTarget` are plain strings; there is no compiled/typed schema —
correctness is checked at save time by [mapping-validation.md](mapping-validation.md): valid JSON templates, the
identifier rule, and that every `pathSource` parses as JSONata (see *Substitution expressions must be runnable*).
Whether a path resolves against a real payload is only known at run time.

### Identity substitution: `_IDENTITY_.externalId` / `_IDENTITY_.c8ySourceId`

Every mapping needs exactly one (inbound) or at least one (outbound) substitution that
tells the pipeline which device the message is about — see rule 5 in
[mapping-validation.md](mapping-validation.md). This is expressed with the reserved
`_IDENTITY_` token rather than a real payload path:

```json
{ "pathSource": "$.deviceId", "pathTarget": "_IDENTITY_.externalId" }
```

(inbound — the external ID extracted from the payload becomes the device identity), or

```json
{ "pathSource": "_IDENTITY_.externalId", "pathTarget": "$.deviceId" }
```

(outbound — the device's resolved external ID is written into the outgoing payload).

`_IDENTITY_.c8ySourceId` is the equivalent for addressing a device by its internal
Cumulocity managed-object ID instead of an external ID. Enrichment processors
(`AbstractEnrichmentProcessor` and its inbound/outbound subclasses) recognize this token
and resolve the actual device before/after the JSONata substitution pass runs — it is not
evaluated by the JSONata engine itself, since `_IDENTITY_` is not a real field of either
payload.

`AbstractEnrichmentProcessor.normalizeTestPayload()`
([`AbstractEnrichmentProcessor.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractEnrichmentProcessor.java)) shows the outbound test-UI
side of this: the UI always sends `_IDENTITY_.c8ySourceId` in test payloads, and this
method rewrites it in-place into the real API-specific identifier field the enrichment
processor expects (`source.id` for EVENT/ALARM/MEASUREMENT, `id` for INVENTORY, `deviceId`
for OPERATION) before the mapping's normal substitution/extraction logic runs.

### How this differs from Smart Functions and Java Extensions

| | JSONata | Smart Functions | Java Extensions |
|---|---|---|---|
| Configuration | Declarative list of `Substitution`s | JavaScript `onMessage(msg, context)` | Java class implementing `ProcessorExtensionInbound`/`Outbound` |
| Can iterate/produce multiple C8Y objects from one message | Only via `expandArray` on a single field | Yes — arbitrary control flow, returns an array | Yes — arbitrary control flow, returns an array |
| Handles array-rooted or non-JSON payloads (`ANY_PAYLOAD`, `SPARKPLUGB`) | No — validation requires `SMART_FUNCTION` (or `EXTENSION_JAVA` for `ANY_PAYLOAD`) instead, see [mapping-validation.md](mapping-validation.md) rule 4 | Yes | Yes (for `ANY_PAYLOAD`) |
| Runtime | Pure expression evaluation, no sandboxing needed | GraalVM polyglot JS context | Plain JVM, dynamically loaded classes |
| Processor class | `AbstractJSONataExtractionProcessor` (+ `JSONataInboundProcessor`/`JSONataOutboundProcessor`) | `AbstractFlowProcessor` (+ flow processors), see [transformation-smart-functions.md](transformation-smart-functions.md) | `AbstractExtensibleProcessor` (+ extensible processors), see [transformation-java-extensions.md](transformation-java-extensions.md) |

See [`docs/backend/architecture.md`](../backend/architecture.md) for how these three
processor families sit under the shared `AbstractEnrichmentProcessor` step in the
processor chain.
