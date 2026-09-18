# Review: the Code Template feature

**Scope:** backend (`ServiceConfigurationService`, `ConfigurationController`, `TemplateType`,
`CodeTemplate`, `templates/*.js`) and UI (`configuration/code-template/`,
`configuration/shared/configuration.model.ts`, `mapping/service/mapping-stepper.service.ts`,
`shared/mapping/util.ts`).
**Status:** the defects in §2 are fixed; §4 lists what was deliberately left.

---

## 1. What the feature is

A code template is a named blob of JavaScript, base64-encoded, stored **per tenant** inside
`ServiceConfiguration.codeTemplates` (a `Map<String, CodeTemplate>`). Templates are seeded from
`src/main/resources/templates/template-*.js` on the classpath, and each file's JSDoc header
carries its metadata as annotations (`@name`, `@templateType`, `@internal`, `@readonly`,
`@defaultTemplate`).

They serve three different purposes, which is the root of most of the confusion:

| `templateType` | Role | Owner |
|---|---|---|
| `SYSTEM` | Preamble evaluated before every Smart Function: `Java.type(...)` bindings, `atob`/`btoa` polyfills. | Framework (`@internal`, `@readonly`) |
| `SHARED` | Helpers the tenant wants available to all Smart Functions. | **Customer** (`@internal false`, `@readonly false`) |
| `INBOUND_SMART_FUNCTION` / `OUTBOUND_SMART_FUNCTION` | Starting points copied into a new mapping. Edits here never affect existing mappings. | Shipped samples + customer copies |

The SYSTEM/SHARED distinction is load-bearing and correctly declared in the files themselves, but
nothing in the type system expresses it — both are just `CodeTemplate` rows in the same map,
separated only by two booleans.

---

## 2. Defects found and fixed

### 2.1 Saving a template erased fields it never displayed (UI)

`decodeCodeTemplates()` rebuilt each template field-by-field rather than copying it. The rebuilt
object **omitted `direction`** and **hard-coded `defaultTemplate: false`**. Because the editor
PUTs the decoded object straight back, *saving any default template silently cleared its own
`defaultTemplate` flag*.

That flag is not cosmetic: `addMissingInternalTemplates()` consults it
(`anyMatch(t -> t.templateType == type && t.defaultTemplate)`) to decide whether a newly shipped
template of that type still needs a slot. With the flag wiped, a later release's template can be
skipped on startup.

The same hand-rolled decode existed a second time in `MappingStepperService.loadCodeTemplates()`,
with the same two defects. Both now call one `decodeCodeTemplate()` in `configuration.model.ts`
which spreads the source object, so fields added later keep working by default.

### 2.2 Two documented status codes were unreachable (backend)

`deleteCodeTemplate` did `codeTemplates.get(id)` and then read `result.internal` with no null
check, inside a `try { … } catch (Exception)`. So:

- deleting an unknown template threw an NPE that became a **500**, and the method's own
  `if (result == null) return 404` at the bottom was **dead code**;
- the deliberate `ResponseStatusException(406)` for internal templates was caught by that same
  blanket handler and re-thrown as a **500** — `ResponseStatusException` is a `RuntimeException`.

`createCodeTemplate` already had the right shape (`catch (ResponseStatusException) { throw ex; }`
before the general handler); `delete` did not. Both checks now happen before the `try`.

### 2.3 `readonly` was enforced only in the browser (backend)

Nothing on the server consulted `CodeTemplate.readonly`. The sole protection for the SYSTEM
template was a `[disabled]` binding on the UI's save button, so `PUT /configuration/code/SYSTEM`
would happily replace the runtime's Java bindings and break every Smart Function mapping in the
tenant — the exact failure mode seen in the field this release. It now returns **406**, and the
endpoint's `@ApiResponses` documents it.

Not a privilege-escalation (the endpoint already requires `ROLE_DYNAMIC_MAPPER_ADMIN`), but an
integrity gap on a surface where the blast radius is the whole tenant.

### 2.4 Smaller UI robustness issues

- `decodeCodeTemplates()` never cleared its target `Map`, so a template deleted since the last
  refresh stayed in memory and selectable. The map is now rebuilt.
- `onSelectCodeTemplate()` used a non-null assertion on a `Map.get()` that can legitimately miss;
  `onValueCodeChange()` then wrote through it unguarded. The field is now optional and guarded,
  matching the `if (!this.codeTemplateDecoded) return;` every sibling handler already used.

---

## 3. Design observations (no code change)

**`direction` is redundant.** `CodeTemplate.direction` is derivable from `templateType`
(`INBOUND_*`/`OUTBOUND_*`), and `loadTemplate()` already derives it that way when the annotation is
absent. `buildSystemSection()` does not even emit `@direction` any more, though the migration code
still strips it — the source notes it is "redundant — derivable from @templateType prefix". It is
kept because removing a persisted field needs a migration, not because it carries information.

**`TemplateType` mixes a role and a direction.** `SYSTEM` and `SHARED` describe *what a template
is for*; `INBOUND_SMART_FUNCTION` encodes *direction × transformation type*. Hence
`TEMPLATE_TYPE_LOOKUP` in the UI, which maps `(direction, transformationType) -> TemplateType` and
must return `undefined` for every non-Smart-Function combination. Four of the eight enum constants
are deprecated.

**Two booleans, three states.** `internal` blocks deletion, `readonly` blocks modification, and
in practice they always move together — every shipped template is either both or neither. A single
`origin: SHIPPED | CUSTOM` would express it once, and would have made §2.3 hard to write
incorrectly.

---

## 4. Deliberately not changed

- **The `internal`/`readonly` pair.** Collapsing them is a persisted-model change needing a
  migration; the review's job was to make the existing contract hold.
- **`TemplateType`'s deprecated constants.** They still deserialize existing tenant data.
- **`direction`.** Same reason — persisted.
- **`updateCodeTemplate` remains an upsert.** PUT-creates is defensible for a keyed resource and
  something may rely on it; the behaviour is now documented rather than silently true.
