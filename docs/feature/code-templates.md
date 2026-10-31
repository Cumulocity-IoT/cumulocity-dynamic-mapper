# Code templates

Code templates are the JavaScript sources a tenant keeps in its service configuration. There are
three kinds: a **system** template with the runtime bindings, a **shared** template with the tenant's own
helper code, and **per-type** templates that give a new Smart Function mapping its starting code.
They are edited on the *Configuration → Code templates* page.

---

## Requirements

**What it is for.** Giving every Smart Function a working start, a place for helper code that all
Smart Functions of a tenant can call, and a stable runtime layer that the product owns.

- **The system template belongs to the product.** It holds the bindings and helpers the runtime needs.
  It cannot be edited or deleted by users. It is replaced by the shipped version at every service start,
  so a fix shipped with a release reaches every tenant without a manual step.
- **The shared template belongs to the tenant.** It is the one place for tenant-wide helper code. The
  product never overwrites it, and it cannot be deleted, only emptied. Saving it makes the new code available to Smart Functions without a
  restart.
- **Per-type templates are starting points.** A new Smart Function mapping starts from a template
  chosen for its direction. Changing a template later does not change mappings created from it.
- **Shipped templates are protected, user templates are not.** Templates that come with the product
  (except the shared one) cannot be edited or deleted. Templates a user creates, including duplicates
  of shipped ones, can be edited and deleted.
- **There is one default per type.** Exactly one shipped template per direction is the default, and it
  is the one a new mapping starts with. A user-created template can never be the default.
- **New shipped templates arrive by upgrade.** A new release adds templates that a tenant lacks. It
  never replaces a template the tenant already has, apart from the system template.
- **A reset restores the shipped set.** *Re-initialize system code templates* replaces all protected
  templates with the shipped versions and keeps the shared template and user-created templates.
  It is an administrator action and is recorded as an event.
- **Only administrators change templates.** Everyone with access can read them.
- **Templates travel with the service configuration.** They are exported, imported, and wiped
  together with it; see [import-export.md](import-export.md).

**Non-goals.** Templates are not versioned and have no history. There is no per-mapping link back to the
template a mapping started from. Code templates for the retired "Substitution as code" and for
JSONata are not offered.

---

## Implementation

### Data

`ServiceConfiguration.codeTemplates` is a `Map<id, CodeTemplate>` stored with the rest of the service
configuration (tenant option `service.configuration`). A [`CodeTemplate`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/configuration/CodeTemplate.java)
has `id`, `name`, `description`, `templateType`, `code` (Base64), `internal`, `readonly`, `defaultTemplate`,
and optional `samplePayload` and `sampleTopic`. `direction` is derived from the type and read-only on the
wire.

[`TemplateType`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/configuration/TemplateType.java)
values in use are `INBOUND_SMART_FUNCTION`, `OUTBOUND_SMART_FUNCTION`, `SHARED`, `SYSTEM`. Four more
(`INBOUND`, `OUTBOUND`, and the two `*_SUBSTITUTION_AS_CODE`) are kept only so stored data still
deserializes.

### Ids and flags

| Template | Id | `internal` (no DELETE) | `readonly` (no PUT) | Note |
|---|---|---|---|---|
| System | `SYSTEM` | true | true | Replaced from the classpath at every start. |
| Shared | `SHARED` | false | false | Never touched by the product after the first load. |
| Shipped default per type | the type name, for example `INBOUND_SMART_FUNCTION` | true | true | `@defaultTemplate true` in the file header. |
| Other shipped | random 8-character id | true | true | `internal` and `readonly` come from `TemplateType.isFrameworkOwnedWhenShipped()`, not from the file. |
| User created | the id in the request | from the request | from the request | |

The shipped files are in [`src/main/resources/templates`](../../dynamic-mapper-service/src/main/resources/templates)
(`template-SYSTEM.js`, `template-SHARED.js`, `template-SMART-INBOUND-NN.js`, `template-SMART-OUTBOUND-NN.js`).
Each starts with a JSDoc header of annotations (`@name`, `@description`, `@templateType`, `@defaultTemplate`,
`@samplePayload`, `@sampleTopic`).

### Lifecycle

| When | What | Code |
|---|---|---|
| First start with no templates | Load every `template*.js` | `BootstrapService` calls `initCodeTemplates(config, false)` |
| Later starts | Add shipped templates that are missing; match on `templateType` plus case-folded `@name` | `addMissingInternalTemplates` |
| Later starts | Fill `samplePayload` and `sampleTopic` on stored templates that lack them | same method |
| Every start | Replace `SYSTEM` if its code differs from the shipped file | `refreshSystemTemplate`, before the GraalVM engine compiles the sources |
| `POST /operation` `INIT_CODE_TEMPLATES` | Remove every `internal` template except `SHARED`, then reload from the classpath | `initCodeTemplates(config, true)`, then a `CODE_TEMPLATE_INIT_EVENT_TYPE` event |
| First read through `/code` on an empty map | Same as first start, and saves | `ConfigurationController.getCodeTemplates` |

The loader keeps an existing template with the same id and skips the file ("Preserving existing template").
That is why a reset first removes protected templates: otherwise nothing would be replaced.

### Endpoints

| Method and path | Role | Behavior |
|---|---|---|
| `GET /configuration/code` | any | All templates, keyed by id. |
| `GET /configuration/code/{id}` | any | One template or `404`. |
| `POST /configuration/code` | Admin | Create. `409` if the id exists. `defaultTemplate` is forced to `false` unless the id equals the type name. |
| `PUT /configuration/code/{id}` | Admin | Update or create. `400` if body id and path id differ, `406` if the stored template is `readonly`. |
| `DELETE /configuration/code/{id}` | Admin | `404` if missing, `406` if `internal`. |

On `PUT` and `POST` the service rewrites the header from the fields (`rectifyHeaderInCodeTemplate`). A
hand edit of `@name` or `@description` in the header is adopted first (`adoptHeaderMetadataEdits`), and
`samplePayload` and `sampleTopic` are kept when a client omits them.

### How the code is used

- **`SYSTEM` and `SHARED`.** [`GraalVMContextService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/GraalVMContextService.java)
  reads both from the service configuration when it creates the tenant's engine, strips module syntax, and
  evaluates them as plain scripts, so every top-level declaration is visible to all mapping code in the same
  context. `updateGraalsSourceShared` and `updateGraalsSourceSystem` rebuild the cached source on `PUT`,
  `POST`, and `DELETE` of those ids, update the engine's copy of the configuration, and evict pooled
  contexts that have the old code preloaded.
- **Per-type templates.** The UI loads all templates, filters them by direction and transformation type,
  and copies the chosen template's code into the mapping. After that the mapping owns its code
  ([`MappingStepperService`](../../dynamic-mapper-ui/src/mapping/service/mapping-stepper.service.ts),
  [`code-template.component.ts`](../../dynamic-mapper-ui/src/configuration/code-template/code-template.component.ts)).
  The UI disables Save for `readonly`, Delete for `internal`, and Duplicate for `SHARED` and `SYSTEM`.

### Traps

- **`SHARED` cannot be deleted.** It is `internal = false`, so the generic rule would allow it, but
  `DELETE /configuration/code/SHARED` answers 406: clear its code instead. A tenant that deleted it before
  this rule still works: engine creation and enrichment treat a missing `SHARED` as empty code.
- **The Java class names in `SYSTEM` are strings.** Moving a class breaks Smart Functions at run time,
  not at compile time. That is the reason `SYSTEM` is refreshed at start. Tenants keep stale stored copies of
  other shipped templates until a reset.
- **A reset removes anything flagged `internal`.** That includes a user template that was created with
  `internal: true` through the API.
- **`PUT` on `SYSTEM` or `SHARED` does not validate the code.** The engine source is rebuilt from
  whatever is saved; a syntax error surfaces on the next Smart Function run.
- **The service configuration import replaces the whole template map** and does not go through the
  checks above; see [import-export.md](import-export.md).
