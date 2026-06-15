# Requirements Specification: Mapping Versioning

**Status:** Draft
**Date:** 2026-06-15
**Feature:** Maintain multiple versions of a single mapping

---

## 1. Purpose & Scope

Today a mapping is a single mutable record. Every edit overwrites the previous
state in place; the only temporal information retained is the `lastUpdate`
timestamp ([Mapping.java:263-265](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java#L263-L265)).
There is no way to:

- review what a mapping looked like before a change,
- roll back to a known-good configuration after a faulty edit,
- safely prepare and review a change while the current version keeps running.

This document specifies requirements for **version management of mappings**: the
ability to keep multiple historical/alternative versions of one logical mapping,
with exactly one version active at any time.

**In scope**
- Conceptual model for a "mapping version" and a "mapping line" (the logical mapping that owns versions).
- Lifecycle: create version, activate version, roll back, delete version.
- Backend persistence strategy.
- Impact on activation, deployment, and runtime processing.
- API and UI requirements at a high level.

**Out of scope (for now)**
- Branching / merging of versions (linear history only).
- Cross-tenant version sharing.
- Diff/merge tooling beyond simple side-by-side compare (nice-to-have, see §10).

---

## 2. Glossary

| Term | Meaning |
|------|---------|
| **Mapping line** | The logical, long-lived mapping that a user creates and references. Identified by the stable functional `identifier` (e.g. `l19zjk`). Owns one or more versions. |
| **Mapping version** | An immutable snapshot of a mapping's full configuration (templates, substitutions, code, topics, flags) at a point in time. |
| **Active version** | The single version of a mapping line that is currently loaded into the processing caches and evaluated at runtime. |
| **Draft version** | A version that has been created/edited but is not active. |
| **Version number** | A monotonically increasing integer scoped to a mapping line (1, 2, 3, …). |

---

## 3. Current State (baseline)

Grounding facts that constrain the design:

- A `Mapping` ([Mapping.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java))
  has a Cumulocity-generated managed-object `id` (line 94) **and** a functional
  `identifier` (line 98). The `identifier` is the stable key used in the
  deployment map and for external references.
- Mappings persist as Cumulocity inventory managed objects of type
  `d11r_mapping`, managed by
  [MappingService.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java)
  (`createMapping`, `updateMapping`, `getMapping`, `deleteMapping`).
- Activation is a single boolean `active` (line 149), toggled via
  `setActivationMapping(...)` ([MappingService.java:340](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java#L340))
  and the `ACTIVATE_MAPPING` operation.
- An **active mapping cannot be updated or deleted** until deactivated
  (enforced in `MappingRepository.prepareForUpdate` / `prepareForDelete`).
- Deployment (which connectors a mapping binds to) is tracked **separately** from
  activation, keyed by `identifier`, in `DeploymentMapService`.
- There is **no** existing `version`, `snapshot`, `draft`, or `history` concept
  anywhere in the codebase.

---

## 4. Core Constraint

> **C-1 — Single active version.** For any given mapping line, **at most one
> version may be active at any time.** Activating a version implicitly
> deactivates the previously active version of the same line. There is never a
> state in which two versions of the same logical mapping are both loaded into
> the processing caches.

This is the central invariant the whole design must guarantee, including under
concurrent requests and across the inbound/outbound caches.

---

## 5. Should versions be maintained in the backend?

**Decision: Yes — versions are persisted and managed server-side.** Rationale:

1. **Rollback requires durable history.** A purely client-side / export-based
   approach (user keeps JSON backups) does not survive UI sessions, does not
   support multi-user teams, and cannot be audited. The primary driver —
   recovering from a bad change — demands server persistence.
2. **The runtime is server-side.** Activation, the processing caches, and
   deployment all live in the microservice. The "active version" is inherently a
   backend concept; the source of truth for which version is running must be the
   backend.
3. **Multi-tenancy & isolation already exist server-side.** Versions inherit the
   per-tenant inventory isolation already provided by `MappingService` /
   `C8YAgent` — no new isolation mechanism needed.
4. **Consistency of the single-active invariant (C-1).** Enforcing "exactly one
   active version" is only reliable if the backend owns the transition. A client
   cannot atomically guarantee it.

### Persistence options considered

| Option | Description | Verdict |
|--------|-------------|---------|
| **A. Versions embedded in one MO** | Store all versions as an array inside the existing `d11r_mapping` managed object. | ❌ Managed-object size grows unbounded; whole object rewritten on every edit; concurrent edits clash; query/listing of a single version is awkward. |
| **B. Separate MO per version** | Each version is its own managed object (e.g. type `d11r_mapping_version`) linked to a parent "mapping line" MO via `identifier` + `versionNumber`. | ✅ **Recommended.** Bounded objects, natural listing, reuses existing inventory CRUD patterns, easy retention/pruning. |
| **C. External store** | New DB/blob store outside Cumulocity inventory. | ❌ Adds infrastructure & backup concerns; breaks the "everything in the tenant's inventory" model the service relies on. |

**Recommendation: Option B.** Keep the active version as the existing
`d11r_mapping` object (so the runtime/cache path is unchanged), and store
inactive/historical versions as separate, lighter managed objects keyed by
`(identifier, versionNumber)`. See §8 for the data-model detail and the open
question OQ-1 on whether the active version is *also* duplicated as a version
record for uniformity.

---

## 6. Functional Requirements

### 6.1 Version creation
- **FR-1** Editing a mapping shall create a **new version** rather than mutating
  the active version in place. (See OQ-2 for whether every save = new version, or
  only an explicit "save as new version" action.)
- **FR-2** Each version shall capture the **complete** mapping configuration
  needed to run it independently: templates (`sourceTemplate`, `targetTemplate`),
  `substitutions`, `code`, topics, `transformationType`, `mappingType`, flags,
  `filterMapping`, `extension`, `qos`, etc.
- **FR-3** Each version shall be assigned a **version number** that is unique and
  monotonically increasing within its mapping line.
- **FR-4** Each version shall record **metadata**: creation timestamp, the user
  who created it, and an optional free-text **change note / label**.
- **FR-5** A version, once created, shall be **immutable**. Further edits produce
  a new version. (Exception: lightweight metadata like the change-note label may
  be editable — see OQ-3.)

### 6.2 Activation (the single-active invariant)
- **FR-6** A user shall be able to **activate** a specific version of a mapping line.
- **FR-7** Activating version *N* shall **atomically deactivate** the previously
  active version of the same line (enforces **C-1**).
- **FR-8** Activation shall swap the running configuration in the inbound/outbound
  processing caches so that subsequent messages are processed by version *N*.
- **FR-9** If activation of a new version fails validation, the **currently active
  version shall remain active and unchanged** (no partial transition).
- **FR-10** Deactivating the active version shall leave the mapping line with **no
  active version** (mapping line dormant). This is a valid state.

### 6.3 Rollback
- **FR-11** A user shall be able to **roll back** to any prior version by
  activating it. Rollback is just activation of an older version; it does **not**
  delete the versions created after it.
- **FR-12** Rollback shall preserve forward history (the "newer" versions remain
  available to re-activate).

### 6.4 Listing & retrieval
- **FR-13** A user shall be able to **list all versions** of a mapping line, with
  version number, metadata, change note, and a clear indicator of which one is
  active.
- **FR-14** A user shall be able to **retrieve the full configuration** of any
  single version.
- **FR-15** The existing "list all mappings" view shall, by default, show **one
  row per mapping line** representing its active (or latest) version — not one row
  per version — to avoid cluttering the grid.

### 6.5 Deletion & retention
- **FR-16** A user shall be able to **delete an individual inactive version**.
- **FR-17** The **active version cannot be deleted** while active (consistent with
  the existing "active mapping cannot be deleted" rule). It must be deactivated or
  another version activated first.
- **FR-18** Deleting the **entire mapping line** shall delete all of its versions.
- **FR-19** The system shall support a **retention policy** (configurable cap,
  e.g. keep last *N* versions) to bound storage growth; pruning shall never delete
  the active version. (See OQ-4 on default and configurability.)

### 6.6 Deployment interaction
- **FR-20** Deployment configuration (connector bindings, keyed by `identifier`)
  shall belong to the **mapping line**, not to individual versions — activating a
  different version shall **not** change which connectors the mapping is bound to.
  (Confirm against `DeploymentMapService`, which is keyed by `identifier`.)

---

## 7. Non-Functional Requirements

- **NFR-1 (Backward compatibility).** Existing mappings (single `d11r_mapping`
  objects with no version data) must continue to work. On first load they are
  treated as version 1 / the active version. No data migration should be required
  to keep running; version history simply starts accumulating from the upgrade.
- **NFR-2 (Concurrency / C-1 safety).** The activation swap must be safe under
  concurrent API calls and consistent with the existing thread-safety model
  (focused contexts, thread-safe caches). Two simultaneous activations on the same
  line must not leave two versions active.
- **NFR-3 (Performance).** The hot processing path must not pay for versioning:
  only the active version is in the cache; historical versions are loaded lazily
  on demand (listing/compare/rollback).
- **NFR-4 (Storage bounds).** Version storage must be bounded by the retention
  policy (FR-19); a mapping edited thousands of times must not grow without limit.
- **NFR-5 (Auditability).** Each version transition (create, activate, roll back,
  delete) should be attributable to a user and timestamp.
- **NFR-6 (Tenant isolation).** Versions are tenant-scoped, inheriting existing
  multi-tenant isolation.

---

## 8. Proposed Data Model (informative)

Aligned with Option B (§5).

**Mapping line / active version** — keeps the existing `d11r_mapping` managed
object as the active, runnable record. Add fields to `Mapping`:

| Field | Type | Notes |
|-------|------|-------|
| `versionNumber` | `int` | Version number of the currently active configuration. |
| `versionLabel` | `String` (optional) | Free-text change note for the active version. |

**Historical version** — new managed object type, e.g. `d11r_mapping_version`:

| Field | Type | Notes |
|-------|------|-------|
| `identifier` | `String` | Functional id of the owning mapping line (the join key). |
| `versionNumber` | `int` | Unique within the line. |
| `snapshot` | full mapping config | Immutable copy of all runnable fields (FR-2). |
| `createdAt` | `long` | Creation timestamp. |
| `createdBy` | `String` | User. |
| `label` | `String` | Optional change note. |

Activation = copy the chosen version's snapshot into the active `d11r_mapping`
object, set its `versionNumber`, flip `active`, and rebuild caches — within the
existing `setActivationMapping` flow extended to be version-aware.

> Note: §8 is a sketch to make the requirements concrete; the final schema is a
> design decision, pending OQ-1.

---

## 9. API Requirements (high level)

Extends the existing mapping/operation controllers. Indicative shapes:

- `GET /mapping/{identifier}/version` — list versions of a mapping line (FR-13).
- `GET /mapping/{identifier}/version/{versionNumber}` — fetch one version (FR-14).
- `POST /mapping/{identifier}/version` — create a new version from a payload (FR-1).
- `POST /operation` `ACTIVATE_MAPPING` extended with an optional `versionNumber`
  parameter; absent = current behavior. Activating a version enforces C-1 (FR-6–9).
- `DELETE /mapping/{identifier}/version/{versionNumber}` — delete an inactive
  version (FR-16/17).

Exact paths/keys (managed-object `id` vs functional `identifier`) to be finalized
in design; note the existing split where CRUD uses `id` and deployment uses
`identifier`.

---

## 10. UI Requirements (high level)

- **UR-1** A "Versions" view per mapping showing the list with active indicator,
  version number, author, timestamp, and change note (FR-13).
- **UR-2** Activate / roll-back action from the versions list, with confirmation
  that it will deactivate the current version (surfaces C-1).
- **UR-3** When editing, prompt for / capture an optional change note (FR-4).
- **UR-4** The mapping grid stays one-row-per-line by default (FR-15), with a way
  to drill into versions.
- **UR-5 (nice-to-have)** Side-by-side compare of two versions.

---

## 11. Open Questions

- **OQ-1** Is the active version *also* stored as a `d11r_mapping_version` record
  (uniform history, one extra write per activation), or only as the live
  `d11r_mapping` object (less duplication, but "active" is a special case)?
- **OQ-2** Does **every save** create a new version, or only an explicit
  "save as version / publish" action, with intermediate edits being a single
  mutable draft? (Affects version count / retention pressure.)
- **OQ-3** Are version labels/notes editable after creation, or fully immutable?
- **OQ-4** Default retention cap (FR-19) and whether it is tenant-configurable via
  service configuration.
- **OQ-5** Should there be a "dirty draft" distinct from committed versions, so a
  user can park work-in-progress without it counting as a version?
- **OQ-6** Behavior when deleting a mapping line that is currently active/deployed
  — require deactivate+undeploy first (consistent with today), or cascade?

---

## 12. Acceptance Criteria (summary)

1. Editing a mapping never destroys the previously running configuration; the
   prior version is retrievable.
2. At most one version of a mapping line is ever active (C-1), verified under
   concurrent activation requests.
3. A user can roll back to any prior version and the runtime processes messages
   with that version afterward.
4. Forward history survives a rollback.
5. Existing (pre-versioning) mappings continue to run unchanged after upgrade.
6. Version storage stays bounded per the retention policy; the active version is
   never pruned.
