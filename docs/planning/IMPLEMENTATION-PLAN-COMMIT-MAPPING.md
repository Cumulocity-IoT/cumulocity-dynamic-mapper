# Implementation Plan: one `commitMapping()` entry point

**Status:** Design. Not started.
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

Second asymmetry: the parent also calls
`subscriptionService.validateSubscriptionOutbound(direction)` after a successful close. The
unified editor does not. That belongs to the caller, not the shared path.

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

Today both paths raise their own alerts, with wording that has already drifted (the parent says
"Failed to save draft for X", the editor "Failed to save mapping X"). Put the **failure** alerts
in the service, so one wording exists. Keep the **success** message in the caller: the unified
editor composes it from `contentChanged` × `deploymentChanged`, and the parent does not — that is
a genuine difference, and `CommitResult.saved` carries both flags so either can build it.

---

## 3. Sequencing

1. **Add `commitMapping()` alongside the existing code**, unused. Unit-test it directly against
   the `CommitResult` matrix — every blocker, both save modes, deployment success and failure,
   validation rejection. This is the step that makes the rest safe.
2. **Move the unified editor onto it.** It has the richer failure handling, so it exercises the
   whole contract. Its spec is green and covers `onCommitButton` today.
3. **Move the stepper parent onto it**, deleting the `mappingPersisted` / `saveFailed` /
   `validationError` flags in favour of the result status.
4. **Delete the two inline sequences.**

Stop after any step if behaviour diverges; each leaves the tree green.

---

## 4. Risks

- **The save path has never had an executed regression run.** §4 of the dedup plan is still marked
  *not yet executed*. Step 1 above (unit tests before any call-site change) is the mitigation, but
  a manual pass over draft-save, create, copy, connector-only change, and a rejected save is
  warranted before merging.
- **`lastUpdate` must not be stamped.** Both paths carry a comment saying so — it is the
  optimistic-concurrency token echoed back unchanged on a draft save. The shared path must keep
  that property, and a test should pin it.
- **Deployment is only persisted when the mapping exists.** The parent guards on
  `mappingPersisted`; the editor guards on `deploymentChanged || mode !== UPDATE`. These are not
  the same condition — reconcile deliberately rather than picking one, and write the chosen rule
  down here when it is decided.

---

## 5. Non-goals

- Reconciling the controlled-child vs routed-page boundary — still out of scope, as in the parent plan.
- Any change to `MappingService` / backend APIs.
- Merging the two editor components themselves.
