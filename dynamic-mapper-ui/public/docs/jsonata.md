---
title: Defining a substitution using JSONata
---

A substitution is a rule that copies content from the source payload (usually your custom JSON payload) to the
target payload (Cumulocity JSON payload).
When defining substitutions, templates for the source and target payloads are used.
At runtime, these rules are applied to the actual payload received from your broker. To define a substitution:

1. Select a node in the source payload (e.g., `_TOPIC_LEVEL_[1]`).
2. Select a node in the target payload (e.g., `source.id`).
3. Press **Add substitution**.
4. The substitution is added to the table of existing substitutions.

:::info Understanding JSONata
JSONata is a powerful query and transformation language for JSON. It supports path expressions, predicates,
functions, and aggregations. For simple mappings, you can use dot notation (e.g., `sensor.temperature`). For
complex transformations, explore [JSONata documentation](https://jsonata.org/).
:::

At runtime, this substitution copies the value at `_TOPIC_LEVEL_[1]` to `source.id` in the target payload.

![Substitution annotation](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png "Screenshot showing step 4 for defining substitutions using JSONata.")

For more advanced mapping rules, e.g. extracting parts of a string for a name (40404-psid-device100-w2w2),
expressions in JSONata can be used.
To use this option you have to toggle the button **Toggle expert mode**.

:::info Expert Mode Examples
- String manipulation: `$split(deviceName, "-")[2]` extracts "device100" from "40404-psid-device100-w2w2"
- Conditional logic: `temperature > 50 ? "hot" : "normal"`
- Date formatting: `$now()` for current timestamp
- Array operations: `sensors[type="temperature"].value`
:::

![Defining Substitutions in ExpertMode](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_ExpertMode.png "Screenshot showing the definition of substitutions in ExpertMode using JSONata.")

### Repair strategy {#jsonata-repair-strategy}

Every substitution has a **repair strategy** that controls what happens at runtime when the `pathTarget` doesn't
already exist in the target template, or the `pathSource` expression evaluates to a missing/null value or an array.

| Strategy | Description |
|---|---|
| `DEFAULT` | Process the substitution as defined, without any special handling. Use this only when `pathTarget` already exists as a literal key in the target template — using `DEFAULT` for a path that isn't already there causes a "Path not found" error at runtime. |
| `CREATE_IF_MISSING` | Create the target node if it doesn't already exist. Use this whenever `pathTarget` is a dynamic or computed segment that isn't a literal path in the target template (e.g. a key derived from source data, or any node that needs to be created at runtime). This is also required for every `_CONTEXT_DATA_.*` target, since those are never literal keys in the target template. |
| `REMOVE_IF_MISSING_OR_NULL` | Remove the target node entirely if the source evaluation returns undefined, null, or empty. Use this to omit a field from the output rather than writing a null/empty value into it. |
| `IGNORE` | Skip this substitution entirely if the source path evaluation is missing or null — the target node is left completely untouched (whatever was already in the template, e.g. a default value, stays as-is). If the source value is present, the substitution behaves like `DEFAULT`. |
| `USE_FIRST_VALUE_OF_ARRAY` | If the extracted source content is an array, use only its first element as the value. Only meaningful when **Expand as array** is switched off for this substitution — see below. |
| `USE_LAST_VALUE_OF_ARRAY` | If the extracted source content is an array, use only its last element as the value. Only meaningful when **Expand as array** is switched off for this substitution — see below. |

**Expand as array** controls what happens when a substitution's `pathSource` extracts an array from the source payload:

- **Switched on:** the array is expanded — the mapper generates one target document per array element, using the
  same target template for each. Use this for multi-value/multi-device messages, e.g. a single payload containing
  several measurements.
- **Switched off (default):** the array is not expanded into multiple documents. By default the whole array is
  written to `pathTarget` as-is; set the repair strategy to `USE_FIRST_VALUE_OF_ARRAY` or `USE_LAST_VALUE_OF_ARRAY`
  instead if you want to reduce it to a single scalar element.

:::info
Because expanding into multiple documents already uses every array element, the editor disables
`USE_FIRST_VALUE_OF_ARRAY` / `USE_LAST_VALUE_OF_ARRAY` whenever **Expand as array** is switched on for a substitution.
:::
