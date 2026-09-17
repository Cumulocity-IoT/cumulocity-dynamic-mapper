# Implementation Plan: one `commitMapping()` entry point

**Status:** Implemented — step 1 of §3 (`commitMapping()` plus its test matrix) landed; call sites migrated in steps 2-4.
**Scope:** Frontend only — `mapping/unified-editor/mapping-unified-editor.component.ts`,
`mapping/grid/mapping.component.ts`, `mapping/service/mapping-stepper.service.ts`.
**Supersedes:** §3.6 (Phase 6) of
[IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md](IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md),
which scoped this out precisely because it needs the design below. Step 4 of
[restructure-ui.md](restructure-ui.md).

---

## 0. Why this is worth doing, and why it was deferred

Two code paths save a mapping:

| | Path | Shape |
|---|---|---|
| Unified editor | `onCommitButton()` | ~110 lines. Validates, encodes, saves draft **or** creates, persists the deployment, composes a success message. |
| Stepper's parent | `grid/mapping.component.onCommitMapping()` | ~95 lines. Same sequence, but tracks `mappingPersisted` / `saveFailed` / `validationError` as separate flags because it owns a *controlled child* and must decide whether to close it. |

They were reconciled once already as a point fix (§0 of the dedup plan) — meaning they had
diverged, in a save path that touches drafts, deployments, and an optimistic-concurrency token.
Keeping them in step by review is the failure mode; one code path removes it.

It was deferred because the two callers need **different things on failure**, and that difference
is real rather than incidental (§1).

---

## 1. The asymmetry that has to be designed around

**On a validation failure the unified editor redirects to a tab.** Four early returns in
`onCommitButton()` set `this.activeTabIndex`:

| Failure | Goes to |
|---------|---------|
| No connector selected | `TAB_CONNECTOR` |
| `mappingTopic` missing (INBOUND) | `TAB_GENERAL_SETTINGS` |
| `propertyFormly.invalid` | `TAB_GENERAL_SETTINGS` |
| extension name/event invalid | `TAB_SELECT_TEMPLATES` |

**The stepper needs none of this.** `cdk-stepper` enforces linear completion, so the user cannot
reach Save with an earlier step invalid. Its parent instead decides whether to *close the editor*
(`showConfigMapping = false`) or keep it open so edits survive a rejected save.

A shared `commitMapping()` therefore cannot just return a boolean. It has to report **which
precondition failed**, and let each caller react in its own idiom.

Second asymmetry: both callers finish with
`subscriptionService.validateSubscriptionOutbound(direction)`, but at different moments — the
parent only after it decides to close the child. (An earlier draft of this section said the
unified editor did not call it at all; it does, added when the two paths were reconciled as a
point fix.) It stays in the callers, not the shared path.

---

## 2. Proposed contract

Keep the service free of navigation. It reports *what happened*; callers decide *where to go*.

```ts
export enum CommitBlocker {
  NO_CONNECTOR       = 'NO_CONNECTOR',
  MAPPING_TOPIC      = 'MAPPING_TOPIC',
  PROPERTY_FORM      = 'PROPERTY_FORM',
  EXTENSION_SELECTION = 'EXTENSION_SELECTION',
  ENCODING           = 'ENCODING',        // encodeMappingForCommit returned { error }
}

export type CommitResult =
  | { status: 'blocked'; blocker: CommitBlocker; message?: string }
  | { status: 'rejected'; error: MappingValidationError }   // server said no; keep editor open
  | { status: 'failed'; message: string }                   // anything else; keep editor open
  | { status: 'saved'; contentChanged: boolean; deploymentChanged: boolean };
```

`commitMapping()` owns: the four precondition checks, `encodeMappingForCommit`, the
`saveDraft` vs `createMapping` branch, `updateDefinedDeploymentMapEntry`, and
`refreshMappings`. It owns **no** `activeTabIndex`, no `showConfigMapping`, no
`validateSubscriptionOutbound`.

Each caller maps the result:

```ts
// unified editor
const r = await this.stepperService.commitMapping(...);
if (r.status === 'blocked') { this.activeTabIndex = TAB_FOR[r.blocker]; return; }
if (r.status === 'rejected') { await this.showValidationIssues(r.error); return; }
if (r.status === 'failed')   { return; }               // alert already raised
this.alertService.success(successMessageFor(r));       // see §3

// stepper parent
if (r.status !== 'saved') {
  if (r.status === 'rejected') await this.showValidationIssues(mapping, r.error);
  return;                                              // editor stays open
}
this.showConfigMapping = false;
this.subscriptionService.validateSubscriptionOutbound(direction);
```

`TAB_FOR` is a small const map in the unified editor — the only place that knows tabs exist.

### Where the alerts live

Both paths raised their own alerts, with wording that had already drifted (the parent said
"Failed to save draft for X", the editor "Failed to save mapping X"). The **failure** alerts moved
into the service, so one wording exists.

The plan originally kept the **success** message in each caller, on the grounds that only the
unified editor composed it from `contentChanged` × `deploymentChanged`. That difference dissolved
once the parent gained `deploymentChanged` (see §4), so the wording lives in one exported
`commitSuccessMessage(result, name, editorMode)` instead. The parent gains the combined and
connector-only variants it previously had no way to express.

---

## 3. Sequencing

1. ~~**Add `commitMapping()` alongside the existing code**, unused, unit-tested against the
   `CommitResult` matrix.~~ Done — 25 tests in `mapping-stepper.service.spec.ts` covering every
   blocker, both save modes, deployment written/skipped/failed, both rejection paths, and a test
   pinning that `lastUpdate` is echoed back unstamped.
2. ~~**Move the unified editor onto it.**~~ Done. `onCommitButton()` went from ~110 lines to ~45,
   all of them request assembly and result-to-UI mapping.
3. ~~**Move the stepper parent onto it**~~ — done; `mappingPersisted` / `saveFailed` /
   `validationError` are gone, replaced by the result status. This also moved the encode step off
   the stepper *child*: it now emits a {@link CommitEditorState} and the grid commits it, so the
   child no longer half-owns the sequence.
4. ~~**Delete the two inline sequences.**~~ Done — no `*.component.ts` under `mapping/` calls
   `saveDraft` / `createMapping` / `updateDefinedDeploymentMapEntry` any more, except the bulk
   `import-modal`, which is a separate flow with no editor and no deployment.

### Tests moved with the behaviour

Following §1's lesson from [restructure-ui.md](restructure-ui.md) — behaviour that moves without
its test is how the last round of failures went unnoticed:

- the stepper child's two `encodeMappingForCommit` specs became one "emits its editor state" spec
  plus one asserting it encodes and persists nothing itself;
- the unified editor's persistence assertions were replaced by request-assembly and
  result-mapping specs (including a table-driven check that each blocker lands on its tab).

---

## 4. Risks

- **The save path has never had an executed regression run.** §4 of the dedup plan is still marked
  *not yet executed*. Step 1 above (unit tests before any call-site change) is the mitigation, but
  a manual pass over draft-save, create, copy, connector-only change, and a rejected save is
  warranted before merging.
- **`lastUpdate` must not be stamped.** Both paths carry a comment saying so — it is the
  optimistic-concurrency token echoed back unchanged on a draft save. The shared path must keep
  that property, and a test should pin it.
- **Deployment is only persisted when the mapping exists.** The parent guarded on
  `mappingPersisted`; the editor guarded on `deploymentChanged || mode !== UPDATE`.

  **Resolved: `mappingPersisted && (deploymentChanged || mode !== UPDATE)`** — the conjunction,
  which is the editor's condition with the parent's existence check made explicit. Working
  through the cases, the two conditions already agree everywhere except one: an UPDATE where the
  connectors were not touched. There the parent issued a deployment `PUT` anyway. That is not a
  no-op — the backend live-reconciles subscriptions across *all* connectors on that call — so the
  editor's condition is the correct one and the parent's write is dropped.

  This is the one behavioural change in Phase 6. It required giving the stepper's parent the
  connector baseline it never tracked (`snapshotConnectors()` at each of the three places it
  assigns `deploymentMapEntry`); the editor already had it as `initialDeploymentConnectors`.

---

### Saving no longer leaves the unified editor

Reported after Phase 6 landed: saving from the substitution tab drops you back to the grid, so you
cannot go on to the Testing tab. Checked against history first — this was **not** a regression.
The editor has had exactly one Save button, in a global footer outside the tab blocks, bound to
`onCommitButton()` since the editor was introduced, and it has left the editor since `864fd163a`
(May 2026, which only swapped `location.back()` for `navigateToGrid()`). Phase 6 preserved that.

Changed anyway, deliberately: **a successful commit now keeps the unified editor open.** Leaving
is Cancel's job. The stepper parent still closes its controlled child, which is right — it is a
modal-ish flow, not a page.

Staying open means the editor is showing stale baselines, so `rebaselineAfterSave()` adopts the
server's copy. `CommitResult.saved` gained a `persisted: Mapping` for this. The load-bearing field
is `lastUpdate`: the server issues a fresh token on every draft save, so an editor that keeps the
old one fails its *next* save with "modified concurrently". Re-taking the content and connector
snapshots is what stops that next save from writing a redundant draft or rewriting the deployment.

`Cancel` keeps its label. It still means "leave without saving pending edits", and "Close" would
imply those edits are safe.

### Smaller alignments that fell out of the merge

Neither is a design decision, but both are behaviour changes worth knowing about when running the
§4 regression matrix:

- **The grid list now refreshes after a failed save too.** The stepper parent already did this;
  the unified editor did not. The shared path keeps the parent's behaviour.
- **An UPDATE that changed nothing now refreshes the list anyway.** The editor previously
  refreshed only when the content changed.

### Found while doing this, not changed

`grid/mapping.component.ts::updateMapping()` stamps `mapping.lastUpdate = Date.now()` on the grid
row before cloning it into `mappingToUpdate`. It is harmless today only because that path routes to
the unified editor, which loads its own copy through the `MappingEditData` resolver — so the
stamped clone never reaches a save. It is exactly the hazard the "do NOT stamp lastUpdate" comments
warn about, and it would start defeating the optimistic-concurrency check the moment update went
back through the stepper. Left alone deliberately: it is a save-path behaviour change outside
Phase 6's scope.

---

## 5. Non-goals

- Reconciling the controlled-child vs routed-page boundary — still out of scope, as in the parent plan.
- Any change to `MappingService` / backend APIs.
- Merging the two editor components themselves.
