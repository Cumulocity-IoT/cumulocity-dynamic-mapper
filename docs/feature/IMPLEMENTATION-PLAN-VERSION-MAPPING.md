# Implementation Plan: Mapping Versioning (Backend)

**Status:** Plan for review — no code written yet
**Companion to:** [REQUIREMENTS-VERSION-MAPPING.md](REQUIREMENTS-VERSION-MAPPING.md)
**Scope of this plan:** Backend (`dynamic-mapper-service`). Frontend is a follow-up.

This plan maps each requirement/decision to concrete code changes, grounded in the
current codebase. It is sequenced so the system stays runnable and green after each
phase.

---

## 0. Model summary being implemented

Three persisted shapes per mapping line (functional key = `identifier`):

| Shape | Storage | Role |
|-------|---------|------|
| **Runnable record** | existing `d11r_mapping` MO | the **active** version; caches/runtime read it, unchanged (NFR-3) |
| **Version record** | new `d11r_mapping_version` MO | immutable published snapshot; one per version incl. the active one (D-1) |
| **Draft** | a `d11r_mapping_version` MO flagged `draft` (one per line, D-6/D-7) | mutable working copy; edits land here, never on the runnable record (D-8) |

Lifecycle: **edit → draft** · **publish → freeze draft into immutable version + prune (retention)** · **activate → copy a version snapshot into the runnable `d11r_mapping` + rebuild caches (enforce C-1)**.

---

## 1. New & changed types

### 1.1 `Mapping` — add fields (additive, backward-compatible)
File: [Mapping.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java)

Add, each with `@Builder.Default` so legacy `d11r_mapping` objects load cleanly
(the builder already has `@JsonIgnoreProperties(ignoreUnknown = true)` at
[Mapping.java:86](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java#L86)):

```java
@Builder.Default private int versionNumber = 1;     // NFR-1: missing → 1, not 0
@Builder.Default private boolean draftDirty = false; // NFR-1: missing → false
private String versionNote;                          // nullable, no default
```

> Do **not** make these `@NotNull` boxed types without a default (NFR-1 constraint).

### 1.2 `MappingVersion` — new model
New file: `model/MappingVersion.java`. Wraps the snapshot + metadata (FR-2, FR-4):

```
identifier   (String)  – owning mapping line
versionNumber(int)
snapshot     (Mapping) – immutable full config
isDraft      (boolean) – true for the single draft slot, false for published versions
createdAt    (long)
createdBy    (String)
note         (String)
```
Persisted as MO type `d11r_mapping_version`. Add constants
`MAPPING_VERSION_TYPE = "d11r_mapping_version"` / fragment alongside the existing
`MAPPING_TYPE` in
[MappingRepresentation.java:38-39](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/MappingRepresentation.java#L38-L39)
(or a parallel `MappingVersionRepresentation`).

### 1.3 `ServiceConfiguration` — add retention setting (FR-19 / D-4)
File: [ServiceConfiguration.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/configuration/ServiceConfiguration.java)
Add, following the existing `inventoryCacheRetention` / `flowStateRetention` pattern
(`@JsonSetter(nulls = Nulls.SKIP)`, default in ctor, `@Schema` doc):

```java
private Integer mappingVersionRetention = 10;  // keep last N versions per line
```

---

## 2. New persistence + service layer

### 2.1 `MappingVersionRepository` — new
Mirror [MappingRepository.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingRepository.java)
patterns (tenant-scoped `inventoryApi` create/update/delete/query). Responsibilities:
- create/update/delete `d11r_mapping_version` MOs
- query versions by `identifier` (and the single `isDraft=true` slot per line)
- all calls wrapped in `subscriptionsService.callForTenant(...)` for tenant isolation

### 2.2 `MappingVersionService` — new
The lifecycle owner. Methods (sketch):
- `Mapping getDraft(tenant, identifier)` / `Mapping saveDraft(tenant, identifier, Mapping edits)` — FR-1; with optimistic-concurrency check (IMP-2: reject if base ≠ current draft)
- `MappingVersion publish(tenant, identifier, note)` — FR-1a: freeze draft → new immutable version with next `versionNumber`; then `pruneVersions(...)`
- `List<MappingVersion> listVersions(tenant, identifier)` — FR-13
- `MappingVersion getVersion(tenant, identifier, versionNumber)` — FR-14
- `void updateNote(tenant, identifier, versionNumber, note)` — FR-5 / D-3 (note-only)
- `void deleteVersion(tenant, identifier, versionNumber)` — FR-16/17 (block if active)
- `void pruneVersions(tenant, identifier, retentionN)` — FR-19: drop oldest, never the active
- `MappingVersion ensureBackfilled(tenant, Mapping runnable)` — **NFR-1a** lazy v1 backfill, idempotent

---

## 3. Changes to existing service/controller flow

### 3.1 Editing → draft (D-8) — relax the active guard
File: [MappingRepository.java:148-163](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingRepository.java#L148-L163)
- Today `prepareForUpdate` throws if `!allowUpdateWhenActive && mapping.getActive()`.
- New behavior: an edit to an active mapping is **routed to the draft** instead of
  rejected. The runnable record is not updated by `PUT /mapping/{id}` anymore.
- `prepareForDelete` ([:170-180](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingRepository.java#L170-L180))
  is **unchanged** (D-5: active/deployed line still can't be deleted).

File: [MappingController.java:290](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/MappingController.java#L290)
`PUT /mapping/{id}` semantics shift from "update active config" to "save draft"
(delegates to `MappingVersionService.saveDraft`). `createMapping`
([:202](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/MappingController.java#L202))
creates the line + its initial draft.

### 3.2 Activation → version-aware (C-1, FR-6–9)
File: [MappingService.setActivationMapping():340](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java#L340)
- Extend to accept an optional `versionNumber`.
- Steps: load target version snapshot → validate → copy snapshot into the runnable
  `d11r_mapping` (set `versionNumber`, `active=true`) → flip prior active off →
  `rebuildMappingCaches` / `updateCacheAfterChange`
  ([:459](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java#L459),
  [:472](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java#L472)).
- **Atomicity (C-1 / NFR-2):** guard the swap per-line (e.g. per-`identifier` lock)
  so two concurrent activations can't both win; on validation failure leave the
  current active version untouched (FR-9).

### 3.3 New REST endpoints
File: [MappingController.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/MappingController.java)
(class `@RequestMapping("/mapping")`, [:67](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/MappingController.java#L67)).
Reuse `@PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN','ROLE_DYNAMIC_MAPPER_CREATE')")`
for mutating routes:
- `POST   /mapping/{identifier}/publish`              → publish draft (FR-1a)
- `GET    /mapping/{identifier}/version`              → list (FR-13)
- `GET    /mapping/{identifier}/version/{versionNumber}` → fetch (FR-14)
- `PATCH  /mapping/{identifier}/version/{versionNumber}` → edit note only (D-3)
- `DELETE /mapping/{identifier}/version/{versionNumber}` → delete inactive version (FR-16/17)

### 3.4 Activation operation
File: [OperationController.handleActivateMapping():391](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/OperationController.java#L391)
Add optional `versionNumber` to the `Map<String,String> parameters`; absent ⇒ today's
behavior. (No new `Operation` enum value needed —
[Operation.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Operation.java).)

### 3.5 Caches — read path unchanged (NFR-3)
[MappingCacheManager.java](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/cache/MappingCacheManager.java)
keeps caching only the runnable `d11r_mapping` objects. Version/draft records are
**never** loaded into caches. No structural change expected; only activation triggers
a rebuild, as today.

---

## 4. Backward compatibility & migration (NFR-1, NFR-1a)
- New `Mapping` fields are additive with defaults → existing MOs load unchanged.
- **Lazy backfill:** on first load/activation of a legacy mapping with no version
  record, `MappingVersionService.ensureBackfilled` creates a `versionNumber=1`
  record from the current snapshot. Idempotent, no upfront bulk migration.
- Forward/rollback safety: unknown properties already ignored on read.

---

## 5. Phased delivery (each phase compiles + tests green)

| Phase | Content | Why this order |
|-------|---------|----------------|
| **P1 — Model & persistence** | `Mapping` fields; `MappingVersion` + representation/constants; `MappingVersionRepository`; `ServiceConfiguration.mappingVersionRetention`. Unit tests for (de)serialization incl. legacy objects. | Foundation; no behavior change yet. |
| **P2 — Version service + backfill** | `MappingVersionService` (publish, list, get, label, delete, prune, ensureBackfilled). Tests for retention + idempotent backfill. | Core logic, still no controller wiring. |
| **P3 — Activation is version-aware** | Extend `setActivationMapping` + per-line atomic swap; `OperationController` optional `versionNumber`. Tests for C-1 incl. concurrent activation, FR-9 rollback-on-failure. | Makes the single-active invariant real. |
| **P4 — Draft editing (staged/additive)** | `saveDraft`/`getDraft` (optimistic concurrency IMP-2) + new `GET`/`PUT /mapping/{id}/draft` endpoints. **`PUT /mapping/{id}` kept as bridge** (still updates runnable). `draftDirty` not yet persisted on the runnable — derived from draft existence; persistence wired with publish in P5. The hard `PUT`→draft-only flip is deferred to the P5/P6 cutover (user decision: staged, no edit-apply gap). | Depends on version service. |
| **P5 — REST surface (done)** | `POST /mapping/{id}/publish`, `GET /mapping/{id}/version`, `GET /mapping/{id}/version/{n}`, `PATCH .../{n}` (note), `DELETE .../{n}` + authz; publish backfills active config, freezes draft, clears the draft. All version routes keyed by the runnable MO `id` (so the line `identifier` + active version are always resolved server-side). Service-level tests. | Exposes the full draft→publish→activate API. |
| **P6 — UI + edit cutover (code-complete)** | DONE: TS model fields + `MappingVersion`; frontend `MappingService` version/draft API client; `MappingVersionModalComponent` (list/activate/rollback/delete/publish) as a grid "Versions" action; **edit-path cutover (D-8)** — active mappings editable, editor Save → `saveDraft`, edit resolver loads the existing draft so the working copy continues. Verified by `tsc --noEmit` + `ng build` (AOT). REMAINING: (a) optional grid "has draft" badge (needs backend `draftDirty` in `getMappings`); (b) runtime verification against a live tenant; (c) full versioning integration test (backend). | Completes D-8. |
| **§8 scaling fix** | Replace the tenant-wide version scan. Now low-cost via childAdditions because all version routes are id-keyed (parent MO id always in hand). Decide & implement before GA. | Independent of phases above. |

---

## 6. Test strategy
Add `integration/MappingVersioningIntegrationTest.java` alongside
[MappingScenarioIntegrationTest.java](../../dynamic-mapper-service/src/test/java/dynamic/mapper/integration/MappingScenarioIntegrationTest.java)
and reuse its fixtures. Cover the acceptance criteria (§12 of requirements):
1. Edit keeps prior running config retrievable (draft vs active separation).
2. **C-1 under concurrency** — fire two activations on one line, assert exactly one active.
3. Rollback activates an older version and runtime uses it.
4. Forward history survives rollback.
5. Legacy mapping (no version MO) runs unchanged, then gets a v1 record on backfill.
6. Retention prunes oldest, never the active version.
Plus model-level unit tests for legacy-object deserialization defaults.

---

## 7. Risks / watch-items
- **Atomicity of activation** (NFR-2): the per-line swap touches multiple MOs + caches.
  Needs a real lock/guard; the existing code mutates `d11r_mapping` + caches without
  a version concept, so this is genuinely new surface. Highest-risk area.
- **`identifier` vs `id` in routes:** existing CRUD uses MO `id`; deployment uses
  `identifier`. New version routes are keyed by `identifier` — confirm the lookup
  helper exists or add one.
- **Draft as a `d11r_mapping_version` with `isDraft`:** validation rules that today
  run on update must run on **publish/activate** instead, not on draft save (a draft
  may be intentionally incomplete). Decide where `validateMapping` is invoked.
- **Deployment binding (FR-20):** confirm `DeploymentMapService` stays keyed by
  `identifier` so switching versions does not change connector bindings.

## 8. Version query scaling — REVERTED to query-based read
The childAdditions storage was tried and **reverted**: against the real platform
the child-addition *read* (`getManagedObjectApi(parentId).getChildAdditions()`)
returned nothing even though writes succeeded — so the drawer showed no versions.
Runtime testing caught what the mocked tests could not.

- **Current (working) design:** version records are standalone
  `d11r_mapping_version` managed objects, each carrying the owning line's
  functional `identifier`. `MappingVersionService` is keyed by `identifier`;
  `loadVersions` queries by type (`getManagedObjectsByFilter`) and filters by
  identifier in memory. Plain, reliable inventory query.
- `deleteAllVersions(identifier)` deletes a line's records on mapping delete (FR-18);
  `publish` threads its loaded list into prune (one query, not two).
- **Known cost (deferred):** this is the tenant-wide O(M·N) scan flagged earlier —
  correct but not optimal for very large tenants. A proper bounded read (e.g. an
  inventory **query by fragment value** `d11r_mapping_version.identifier eq …`,
  validated against the platform) is the future optimization. childAdditions is
  NOT the answer for the *read* (its `getChildAdditions()` read path is unreliable here).
- **Child-addition registration (additive, write-only):** each version MO is *also*
  registered as a child addition of the runnable mapping MO (via the REST
  `/childAdditions` endpoint in `InventoryFacade.addChildAddition`, which reliably
  establishes the link — only the SDK *read* was unreliable). This makes the
  mapping → versions relationship navigable in the inventory / external tooling.
  It is best-effort (a failed link never fails publish) and does NOT drive the
  read path (still the type query + identifier filter). The parent id is the
  version snapshot's `id`.
```
