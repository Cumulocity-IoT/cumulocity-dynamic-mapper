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

**`direction` is redundant.** *(Resolved — see §5.)* `CodeTemplate.direction` is derivable from
`templateType`, and it is now derived rather than declared.

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

## 4. Folder structure and naming

Omitted from the first pass of this review; added after the fact.

### Healthy

- **Tab factories follow one convention.** `<thing>-tab.factory.ts` at the feature root:
  `configuration/code-template-tab.factory.ts` alongside `configuration/configuration-tab.factory.ts`,
  matching `mapping/mapping-tab.factory.ts` and `monitoring/*-tab.factory.ts`. The factory sitting
  beside the folder it registers, rather than inside it, is consistent across all seven.
- **`shared/component/code-template/` is placed correctly.** `ManageTemplateComponent` (the
  name/describe modal) is used by both `configuration/code-template/` and the mapping editors, so
  it belongs in `shared/`, not in the configuration feature.
- **The feature splits cleanly by role**: the screen in `configuration/code-template/`, the
  cross-feature model in `configuration/shared/configuration.model.ts`, the reusable modal in
  `shared/component/`, the consumer logic in `MappingStepperService`.

### Fixed

- **`shared/confirmation/` → `shared/component/confirmation/`.** Every other shared component
  topic (`code-explorer`, `code-template`, `formly`, `json-editor`, `renderer`, `select`) lives
  under `shared/component/`; this one sat beside it. It is used by the code-template screen's
  "Init system code templates" confirmation, among others. Three import sites plus the barrel.
- **`code-template.component.css` → `code-template.component.style.css`.** Its two siblings in
  `configuration/` (`service-configuration`, `import-service-configuration-modal`) both use
  `.component.style.css`. Repo-wide the suffix is split 8/7, so this is local consistency only,
  not a repo convention.

### Left alone, with reasons

- **`shared/component/code-template/manage-template.component.ts`** — the file name does not repeat
  its folder. That is the same shape as `shared/component/renderer/label.renderer.component.ts`: a
  topic folder holding a specific component. Not an inconsistency.
- **`configuration/shared/configuration.model.ts`** — a folder holding one file whose name repeats
  its parent. It is the deliberate cross-feature import target (`configuration/index.ts` re-exports
  it, and `MappingStepperService` imports from it), so `shared/` is carrying meaning here rather
  than being filler. Twelve importing files; churn without benefit.
- **The `.component.css` / `.component.style.css` split repo-wide** — genuinely 8 vs 7, no dominant
  convention to normalise toward. Picking one would be a 15-file rename decided by a coin toss.

### Adjacent findings, out of scope for this review

Noted rather than changed, since they belong to the mapping stepper rather than code templates:

- `mapping/step-template/mapping-template-step.component.ts` and
  `mapping/step-transformation/mapping-transformation-step.component.ts` carry a `-step` suffix
  their folder already states. Their three siblings (`step-connector`, `step-property`,
  `step-testing`) do not, so the dominant convention is to drop it.
- `mapping/step-property/` is singular while its component is `mapping-properties.component.ts`.
- `shared/component/json-editor/jsoneditor.component.ts` hyphenates differently from its own
  folder and from every other `json-editor` reference in the repo.

---

## 5. Deliberately not changed

- **The `internal`/`readonly` pair.** Collapsing them is a persisted-model change needing a
  migration; the review's job was to make the existing contract hold.
- **`TemplateType`'s deprecated constants.** They still deserialize existing tenant data.
- ~~**`direction`.**~~ Superseded — see §6, which made it a derived getter rather than a stored
  field, closing the `@templateType`/`@direction` overlap noted in §3.
- **`updateCodeTemplate` remains an upsert.** PUT-creates is defensible for a keyed resource and
  something may rely on it; the behaviour is now documented rather than silently true.

---

## 6. Follow-up: `direction` made derived

The overlap between `@templateType` and `@direction` noted in §3 is now closed. The two could
disagree, and nothing read `direction` anyway — the UI derives `templateType` from
`(direction, transformationType)` via `TEMPLATE_TYPE_LOOKUP`, and filters on `templateType` alone.

- `TemplateType` carries its own `Direction`, replacing the `startsWith("INBOUND")` prefix parse in
  `loadTemplate()`. The prefix parse was a latent trap: a future `INBOUND_FOO` would have inherited
  a direction by naming coincidence.
- `SHARED` and `SYSTEM` report `null`, not `Direction.UNSPECIFIED` — the value tenants already have
  stored for them, so no payload changes.
- `CodeTemplate.direction` is no longer a stored field. It is a derived getter serialized as
  `READ_ONLY`, so the wire shape is unchanged for readers while a PUT can no longer put the two out
  of step.
- `@direction` and `@defaultTemplate false` are gone from the shipped headers. Neither was ever
  emitted by `buildSystemSection()`, so both were already being stripped on load — removing them
  changes nothing at runtime. `@direction` stays in `SYSTEM_ANNOTATIONS` purely so legacy stored
  headers still get the line removed on their next rectify.
- A missing `@internal`/`@readonly` now warns instead of defaulting silently to `false`. That
  default is what let two templates ship as non-internal, escape the `initCodeTemplates()` purge,
  and accumulate a duplicate per "Init system code templates" click.

Not breaking: no tenant migration is required and every serialized payload is byte-identical to
before.

On §3's "two booleans, three states" — all 18 shipped templates do set `internal` and `readonly`
to the same value, so the pair is de-facto redundant today. Collapsing it into an `origin` field
still needs a persisted-model migration, so it stays as-is.
