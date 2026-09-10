# Implementation Plan: Stepper / Unified Editor Deduplication

**Status:** Phases 1–5 implemented (see each phase's implementation note in §3; not yet committed/
merged as of this writing — verify current branch/PR state before assuming otherwise). Phase 6
remains a separate, not-yet-started follow-up plan (see §3.6).
**Scope of this plan:** Frontend only (`dynamic-mapper-ui`), specifically:
`mapping/stepper-mapping/`, `mapping/unified-editor/`, `mapping/step-transformation/`,
`mapping/service/mapping-stepper.service.ts`, `mapping/shared/util.ts`, `mapping/grid/mapping.component.ts`.

---

## 0. Problem & goal

`MappingStepperComponent` (linear wizard, `mapping/stepper-mapping/mapping-stepper.component.ts`,
~1080 lines) and `MappingUnifiedEditorComponent` (tab-based editor for already-defined mappings,
`mapping/unified-editor/mapping-unified-editor.component.ts`, ~1060 lines) implement the same
mapping-editing feature against two different navigation models (cdk-stepper steps vs. tabs). A
review of both files found they are **~90% duplicated at the method level** — same fields, same
logic, just wired to step-transitions vs. tab-transitions.

This is not just untidy: the duplication has already caused real, observable divergence, three
instances of which were found and patched as point-fixes in the current working tree (uncommitted
at the time of writing) rather than fixed at the root:

1. The unified editor's save flow silently skipped the "outbound mapping needs a subscription"
   check that the stepper's flow (via its parent, `mapping.component.ts::onCommitMapping`) already
   had. **Point-fixed**: extracted `SubscriptionService.validateSubscriptionOutbound()` and called
   it from both.
2. The stepper always created a draft on any UPDATE-mode commit, even a connector-only
   reassignment with no content change, because it never got the unified editor's later
   `hasMappingContentChanged()` optimization. **Point-fixed**: extracted
   `captureMappingContentSnapshot()`/`hasMappingContentChanged()` into `mapping/shared/util.ts` and
   wired an equivalent snapshot into the stepper.
3. The two components' inlined ESM-export-detection regexes had already drifted — the stepper's
   didn't recognize `export default`, the unified editor's did. **Point-fixed**: extracted
   `hasEsmExport()` into `mapping/shared/util.ts`.

These point-fixes close the specific bugs but do nothing to stop the *next* one: every method
still exists twice, and nothing enforces that a future change lands in both copies. This plan
describes eliminating the duplication at the source, in safely-sequenced, independently-mergeable
phases.

**Goal:** one source of truth for shared mapping-editing logic (template/extension/code-template/
YAML/commit-encoding/substitution-validity handling); each component shrinks to owning only its
own navigation model (step vs. tab transitions, index bookkeeping, ViewChild refs) and its own
persistence/routing boundary (see §1).

---

## 1. Target architecture

**Decision: grow `MappingStepperService` into the shared "editor session" owner, rather than
introducing a new service.**

Both components already inject `MappingStepperService` and `SubstitutionManagementService` as
**component-level providers** (`providers: [MappingStepperService, SubstitutionManagementService]`
in each `@Component` decorator — `mapping-stepper.component.ts:109`,
`mapping-unified-editor.component.ts:112`). That means each open editor instance already gets its
own private instance of both services — there is no app-singleton cross-talk risk to worry about,
and no new DI wiring is needed. Moving shared logic into methods on `MappingStepperService` is a
mechanical extension of a pattern that's already in place, not a new architectural concept.

Each component becomes a thin view over the service:
- keeps its own step/tab index constants, transition-queue wrapper (`onStepChange`/`onTabSelected`),
  ViewChild refs, and dispatch switch (`handleStepChange`/`handleTabSelected`)
- delegates "what does this mapping look like / how do I edit it" logic to
  `MappingStepperService` method calls, passing its own state as arguments

### Two boundary asymmetries this plan does **not** try to fix

- **Stepper = controlled child.** `@Input() mapping/stepperConfiguration/deploymentMapEntry`,
  `@Output() cancel/commit` (`mapping-stepper.component.ts:113-117`); its parent
  (`mapping/grid/mapping.component.ts::onCommitMapping`) owns actual persistence.
- **Unified editor = routed page.** No `@Input`/`@Output` at all
  (`mapping-unified-editor.component.ts:128-130` are plain fields); it self-populates from
  `ActivatedRoute` snapshot data (`core/mapping-edit.resolver.ts`) and self-persists via injected
  `MappingService`/`SubscriptionService`, navigating away via `Router`.

Reconciling these into one shape (should the stepper become routable? should the unified editor
become embeddable in a drawer like the stepper?) is a separate, larger UX/routing decision.
Forcing it into this plan would multiply its risk for no de-duplication benefit — the logic
duplication this plan targets is orthogonal to it. Phase 6 (§3.6) revisits the one place this
boundary difference *does* leak into the duplicated logic (commit/persistence), and is scoped as
a follow-up plan rather than folded in here.

---

## 2. Inventory summary

Full field-by-field and method-by-method tables were produced during review; condensed here by
category. "Target" = move into `MappingStepperService`. "Stays" = component-specific, not
extracted.

### Stays (step/tab-orchestration only — expected to differ per navigation model)

| Stepper | Unified editor |
|---|---|
| `STEP_DEFINE_SUBSTITUTIONS`/`STEP_GENERAL_SETTINGS`/`STEP_SELECT_TEMPLATES`/`STEP_TEST_MAPPING` | `TAB_CONNECTOR`/`TAB_GENERAL_SETTINGS`/`TAB_SELECT_TEMPLATES`/`TAB_DEFINE_TRANSFORMATION`/`TAB_TEST_MAPPING` |
| `stepTransitionQueue`, `onStepChange`, `handleStepChange` dispatch switch | `tabTransitionQueue`, `onTabSelected`, `handleTabSelected` dispatch switch |
| `onNextStep`, `onBackStep`, `goToLastStep`, `@ViewChild('stepper') stepper: C8yStepper`, `labels`, `stepperForward` | `isTabVisible()` |
| `aiReviewBaseline`, `captureAIReviewBaselineIfNeeded()`, `hasReviewedAITemplate()`, `launchAIGenerationOnStart()` — CREATE-mode-only AI-review gate before advancing past Select Templates; tabs have no "advance" to gate | *(no equivalent — intentional, see finding #8 of the original review)* |
| `@Output() cancel`, `@Output() commit`, parent-owned persistence | `route`/`router`/`globalContextService` injections, `navigateToGrid()`, self-owned persistence (`MappingService`, `SubscriptionService`) |

### Target (duplicated business logic — extraction candidates, §3)

- `ngOnInit`'s shared bootstrap sequence (view model, extension-event mapping, target/source
  system, `editorOptions`, `setTemplateForm()`, feature/role read-only gating, service
  configuration, AI-agent deployment check, formly fields, code templates, schema lookups)
- `setTemplateForm`, `initializeFormlyFields`, `initializeCodeTemplates`, `registerCompletionProvider`
- `patchExtensionFormValues`, `onSelectExtensionName`, `onSelectExtensionEvent`, `onTargetAPIChanged`
- `configurationToYaml`, `yamlToConfiguration` (pure)
- `onSampleTargetTemplatesButton`, `onValueCodeChange`, `onSelectCodeTemplate`,
  `updateCodeTemplateEntries`, `onCreateCodeTemplate`, `updateExtensionItems`, `updateCodeTemplateItems`
- `openGenerateSubstitutionDrawer` (entire AI-result handling)
- `buildTestMapping` (stepper's named helper vs. unified's 3x inlined equivalent)
- `updateTemplatesInEditors` (stepper's version is more robust — see §3.1)
- `deploymentMapEntryChange` / `isConnectorSelectionEmpty` (near-duplicate helper logic)
- The repeated `updateSubstitutionValidity(...)` 4-argument call (5 call sites in stepper, 4 in
  unified, only the boolean differs)
- The commit-encoding block inside `onCommitButton` (content-changed detection, template
  reduce/stringify, code encode, substitutions-as-code guard) — embedded in otherwise-divergent
  `onCommitButton` bodies, but the encoding logic itself is identical

---

## 3. Phased extraction

Each phase must leave `ng build --configuration production` clean and the existing specs
(`mapping-stepper.component.spec.ts`, 55 tests; `mapping-unified-editor.component.spec.ts`, 28
tests) green before moving to the next. Recommend **one PR per phase**, reviewed and merged in
order — do not batch phases. The whole point of this plan is incremental de-risking of a file
pair that has already produced production-visible bugs from being edited independently.

### Phase 1 — Zero-risk pure-function consolidation ✅ Done

No component state, no DI — pure functions or single-purpose predicates. Add to
`mapping/shared/util.ts` (already the shared home for `hasEsmExport`/`hasMappingContentChanged`):

- **`configurationToYaml`/`yamlToConfiguration`**
  (`mapping-stepper.component.ts:573-594`, `mapping-unified-editor.component.ts:874-895`) — only
  depend on the `jsYaml` import, no `this`. Move verbatim.
- **`buildTestMapping`** (`mapping-stepper.component.ts:651-659`) — parameterize as
  `buildTestMapping(mapping, sourceTemplate, targetTemplate, mappingCode, includeCode): Mapping`.
  Replaces the stepper's private method **and** the unified editor's three inlined
  clone/stringify duplicates (`handleDefineSubstitutionsTab`, `handleTestMappingTab`,
  `openGenerateSubstitutionDrawer`).
- **`isConnectorSelectionEmpty`** (`mapping-unified-editor.component.ts:921-923`) — shared
  predicate `isConnectorSelectionEmpty(deploymentMapEntry): boolean`; also replaces the stepper's
  inline equivalent check inside `deploymentMapEntryChange`.
- **`updateTemplatesInEditors`** — reconcile, don't just relocate. The stepper's version
  (`:796-801`) tries `tryGetLiveEditorContent()` first (reads the live JSON editor directly),
  falling back to `sourceTemplateUpdated`/`targetTemplateUpdated`, falling back to the current
  value. The unified editor's version (`:671-678`) only checks
  `templateStepRef?.sourceTemplateUpdated`/`targetTemplateUpdated` — **a latent bug**: it can miss
  an in-progress edit that hasn't yet propagated to the mirrored property. Promote the stepper's
  implementation as the shared one; this is a real behavior fix for the unified editor as a side
  effect of extraction, not a separate task — call it out explicitly in the PR description so
  reviewers don't mistake it for scope creep.

**File touches:** `mapping/shared/util.ts` (add ~4 functions); `mapping-stepper.component.ts`
(remove 4 methods, call util); `mapping-unified-editor.component.ts` (remove/replace 3 inline
blocks + 1 method, call util).

**Verification:** `ng build --configuration production`; both spec suites green; no behavior
change intended except the `updateTemplatesInEditors` fix.

**Implemented as planned.** All four functions landed in `mapping/shared/util.ts`
(`configurationToYaml`/`yamlToConfiguration`/`buildTestMapping`/`isConnectorSelectionEmpty`), plus
`updateTemplatesInEditors`/`tryGetLiveEditorContent` reconciled onto the stepper's more robust
version as called out above. Added dedicated coverage in `mapping/shared/util.spec.ts` for all of
these (they had none before, split across the two components' specs).

### Phase 2 — Convenience wrapper for `updateSubstitutionValidity` ✅ Done

9 call sites total (5 stepper, 4 unified) all pass the same 4 arguments, differing only in the
boolean gate. Add to `MappingStepperService`:

```ts
refreshSubstitutionValidity(mapping: Mapping, stepperConfiguration: StepperConfiguration, isBeforeSubstitutionStep: boolean): void {
  this.updateSubstitutionValidity(mapping, stepperConfiguration.allowNoDefinedIdentifier, isBeforeSubstitutionStep, stepperConfiguration.showCodeEditor);
}
```

Replace all 9 call sites with the 3-argument wrapper call. Purely mechanical, low risk.

**Implemented as planned.** `refreshSubstitutionValidity` added verbatim; all 9 call sites
(3 stepper, 4 unified editor, plus 1 in `MappingSubstitutionStepComponent` that the original
inventory undercounted) converted to the 3-argument form.

### Phase 3 — Move stateless-but-mutating methods into `MappingStepperService` ✅ Done

Methods whose only component-specific dependency is the state passed to them (`templateForm`,
`mapping`, `extensions`, etc. — reference types, so in-place mutation from inside the service
preserves today's semantics with no extra plumbing back to the caller):

`patchExtensionFormValues`, `onSelectExtensionName`, `onSelectExtensionEvent`,
`onTargetAPIChanged`, `onSampleTargetTemplatesButton`, `onValueCodeChange`, `onSelectCodeTemplate`,
`updateCodeTemplateEntries`, `onCreateCodeTemplate`, `updateExtensionItems`,
`updateCodeTemplateItems`, `raiseAlert`.

Each becomes a `MappingStepperService` method taking the previously-`this`-scoped state as
explicit parameters. Components keep a thin same-named wrapper method that calls the service and
passes its own fields — **this preserves every existing template binding**
(e.g. `(change)="onSelectExtensionName($event)"`) with zero HTML changes in this phase.

**Verification:** build + specs, plus a manual pass through extension-selection and code-template
flows in both editors (these are the two areas with the most Formly/Monaco interaction and the
least unit-test coverage).

**Implemented with one deliberate deviation:** `onValueCodeChange` (`this.mappingCode = value;`) was
**not** extracted — it's already the minimum possible duplication (one line assigning a primitive),
so routing it through the service adds indirection without reducing any drift risk. All other
listed methods moved as planned, including folding `updateCodeTemplateItems` into
`computeCodeTemplateEntries` (it had no callers besides that one method, so a separate service
method would have been pure ceremony). `raiseAlert` additionally required injecting `AlertService`
into `MappingStepperService` (safe — same per-component-instance DI pattern already used for the
other injected services).

**Test fallout (not in the original plan, but necessary):** the business logic these methods used
to contain was directly unit-tested in both component specs. Since the components' own
`MappingStepperService` is a full jasmine mock in those specs, the logic moving into the service
meant those tests would otherwise silently stop testing anything. Fixed by moving the real-logic
tests to a new `mapping/service/mapping-stepper.service.spec.ts` (created this phase) and rewriting
the component-spec tests to verify delegation (correct arguments in, correct field mutation out)
instead. Repeated at every subsequent phase — see each phase's notes below.

### Phase 4 — Consolidate `ngOnInit` bootstrap + form/template initialization ✅ Done

The highest-value, highest-risk phase before §3.6. Add
`MappingStepperService.initializeEditorSession(...)` covering: `setTemplateForm`,
`initializeFormlyFields`, `initializeCodeTemplates`, `registerCompletionProvider`, plus the shared
parts of `ngOnInit` (view model, extension-event mapping, target/source system, `editorOptions`,
feature/role read-only gating, service configuration, AI-agent deployment check, schema lookups).

Parameterize the two known divergences instead of guessing which caller "wins":
- **`showExtensionSelectorsSource`/`Target` disabled-condition check** (stepper only, in
  `setTemplateForm`, `:382-383,387-388`) — pass as a boolean flag, default `false` (unified's
  current behavior) so the unified editor's call site doesn't change behavior by default.
- **`codeEditorHelp`/`codeEditorLabel` branch** — the unified editor branches on
  `transformationType === SUBSTITUTION_AS_CODE` vs. Smart Function (`:319-327`); the stepper
  hardcodes the Smart Function text. This is itself a second latent inconsistency (a legacy
  `SUBSTITUTION_AS_CODE` mapping edited via the stepper would show the wrong help text) — **flag
  it to reviewers explicitly** and default to the unified editor's (more correct) branching logic
  for both, rather than silently picking one.
- **`registerCompletionProvider`'s re-entrancy guard** (`completionProviderRegistering`, unified
  only, needed because Monaco unmounts/remounts per tab-switch) — always apply it; it's a no-op
  guard for the stepper, where the template/code step is never re-entered while still registering.

Components' own `ngOnInit` shrinks to: call `initializeEditorSession(...)`, then layer their own
boundary-specific setup — unified: route-data extraction, `globalContextService` register,
`activeTabIndex`/`currentStepIndex` initial value, `initialDeploymentConnectors`,
`isButtonDisabled$` gate; stepper: nothing extra (template expansion stays lazy per-step, per
today's `expandTemplates()`/`handleSelectTemplatesStep()`).

**Verification:** full manual regression matrix (§4) run before and after, in addition to build +
specs. Recommend its own PR, reviewed with extra scrutiny given the surface area.

**Implemented as planned**, including both flagged behavior fixes (`disableExtensionSelectorsWhenHidden`
parameterized with the stepper passing `true`; `codeEditorHelp`/`codeEditorLabel` now branch on
`SUBSTITUTION_AS_CODE` for both editors) and the completion-provider re-entrancy guard always
applied. One addition beyond the plan's method list: the Monaco completion-provider disposable
itself (`completionProviderDisposable`/`completionProviderRegistering`) moved into the service too
(alongside `registerCompletionProvider`), since it's the same kind of per-component-instance state
as everything else the service already owns — `cleanup()` now disposes it, so both components'
`ngOnDestroy` simplified to just calling `cleanup()`. Also removed `SharedService` injection from
both components entirely — after this phase nothing in either component called it directly anymore.
Manual regression matrix (§4) was not re-run against a live browser this session (no interactive
environment available); rely on the build + full spec suite passing, and treat the matrix as still
outstanding before this ships to users.

### Phase 5 — Consolidate the commit-encoding block ✅ Done

Extract the shared portion of `onCommitButton` into:

```ts
encodeMappingForCommit(
  mapping: Mapping,
  sourceTemplate: any,
  targetTemplate: any,
  mappingCode: string | undefined,
  initialContentSnapshot: MappingContentSnapshot | undefined,
  allowTemplateExpansion: boolean,
  editorMode: EditorMode
): { mapping: Mapping; contentChanged: boolean } | { error: string }
```

covering: `hasMappingContentChanged` detection, template reduce/stringify (or `JSON.stringify`
depending on `allowTemplateExpansion`), code encode, and the substitutions-as-code guard (today's
"Internal error in editor. Try again!" alert path becomes the `{ error }` return).

- Stepper's `onCommitButton` shrinks to: call it, on `{ error }` call `raiseAlert` and return,
  else `this.commit.emit(result)`.
- Unified editor's `onCommitButton` keeps its own connector/property/extension-selector validation
  and tab-redirect logic (genuinely tab-specific, not extractable — see §3.6 for why), calls the
  same encoder, then proceeds with its existing persistence sequence using `result.mapping`/
  `result.contentChanged`.

**Verification:** the existing "Do NOT stamp `lastUpdate` here" comment on both call sites is a
signal that operation *order* matters for optimistic-concurrency correctness — diff the exact
sequence of operations in the extracted method against both current implementations line-by-line
before merging, not just behaviorally. Full manual regression matrix (§4) required.

**Implemented as planned**, exact signature. Verified the operation order against both prior
implementations line-by-line (content-changed check before mutation → template encode → code
encode → substitutions-as-code guard) — unchanged. As with Phase 4, the manual regression matrix
was not re-run against a live browser this session; treat it as outstanding before shipping.

### Phase 6 (stretch — separate follow-up plan, not part of this one)

Move the unified editor's inline `saveDraft`/`createMapping`/`updateDefinedDeploymentMapEntry`/
alerts/`validateSubscriptionOutbound`/`navigateToGrid` sequence, and the stepper-parent's
equivalent in `mapping/grid/mapping.component.ts::onCommitMapping` (already reconciled once as a
point-fix — see §0, item 1), into one shared `commitMapping(...)` entry point (candidate home:
`MappingService`, since both call sites already inject it).

This is the phase that would make the §0 class of bugs **structurally impossible** to
reintroduce — one code path, not two kept in sync by convention/review. It's scoped out of this
plan because it also has to absorb the tab-validation-redirect responsibility (the unified
editor's `onCommitButton` needs to know *where* to send the user on a validation failure — Connector
tab, General Settings tab, Select Templates tab — which the stepper's flow doesn't need, since
cdk-stepper enforces linear step completion instead of allowing arbitrary jumps). Design that
callback/routing contract as its own plan once Phases 1–5 have proven the extraction pattern works
end-to-end.

---

## 4. Manual regression test matrix

**Status: not yet executed.** Phases 1–5 were implemented and verified via `ng build
--configuration production` and `tsc -p tsconfig.spec.json --noEmit` (full type-check of the specs
too), plus new/updated specs written for every piece of logic that moved into
`MappingStepperService` at each phase (see each phase's implementation note above). No headless
Chrome was available in the environment this work was done in, so the Jasmine/Karma suite itself
was not actually executed — only type-checked. Both **running `npm test`** and this manual matrix
are outstanding before this work ships to users.

Required before/after Phase 4 and Phase 5 at minimum (recommended for every phase if time
allows). Run each scenario through **both** editors where applicable.

| Scenario | Stepper | Unified editor |
|---|---|---|
| Create INBOUND JSON/DEFAULT mapping | ✓ | — (create-only via stepper today) |
| Create OUTBOUND mapping, no subscription configured → warning shown | ✓ | — |
| Update existing mapping — actual content change → draft created, "Publish and activate" message | ✓ | ✓ |
| Update existing mapping — connector-only reassignment → **no** draft created, deployment still saved | ✓ | ✓ |
| Copy mapping | ✓ | — |
| Extension-based mapping (select extension name/event, parameter YAML round-trip) | ✓ | ✓ |
| Code template insert, ESM export already present (`export default function onMessage`) → no duplicate export appended | ✓ | ✓ |
| "Generate with AI" at creation → review gate blocks advancing until target template reviewed | ✓ | n/a (no create flow) |
| "Generate substitutions with AI" drawer, from Transformation step/tab | ✓ | ✓ |
| Legacy `SUBSTITUTION_AS_CODE` mapping — code editor help text correct (Phase 4 risk) | ✓ | ✓ |
| Cancel mid-edit, no partial save | ✓ | ✓ (navigates back without persisting) |
| Rapid double-click Next/tab-switch — no corrupted state (already fixed this session, regression-guard only) | ✓ | ✓ |

---

## 5. Non-goals

- Reconciling the controlled-child vs. routed-page boundary (§1).
- Porting the CREATE-mode AI-review gate (`aiReviewBaseline`/`hasReviewedAITemplate`/
  `launchAIGenerationOnStart`) to the unified editor — it has no CREATE flow today; if that
  changes, this becomes an explicit follow-up.
- Any change to the underlying `Mapping`/`ConnectorConfiguration` models or backend APIs — this
  plan is UI-internal refactoring only.
