# Plan: OnPush and signals for the UI

Status: stopped on 2026-11-10, to be continued later. 92 of 93 components are on `OnPush`. `ai-prompt` stays on default change detection (see below). The other six of
the last seven (listed below) were converted without a browser check and are the first to test. Nothing from the later rounds has
been checked in a browser yet: only the editor rounds up to the "Code template: Replace code" entry were
checked, see "Progress". The code is committed on `develop` and is not pushed.

Results at the stop (unit tests 1012 of 1012; lint 0 errors and 395 warnings; AOT check: 8 existing errors,
none from this work):

| Measure | Start | At the stop |
|---|---|---|
| Components on `OnPush` | 5 of 91 | 92 of 93 (`ai-prompt` stays on default) |
| `detectChanges()` calls | 29 | 2 on purpose (AI prompt `ngOnInit`, connector drawer direction filter) |
| `ngDoCheck` | 1 | 0 |
| `prefer-on-push` warnings | 73 | 1 (`ai-prompt`, on purpose) |
| `prefer-signals` warnings | 198 | 198 (not started) |
| Explicit `any` (non-spec) | 277 | 190 (second round added 2 casts) |

Converted last, with no browser check yet (6): `shared-code-ai-prompt`,
`connector-configuration-drawer`, `custom-form-field-wrapper`, `input-list-formly`, `input-list`, and
`custom-select`. See the entry "Last components on `OnPush`" for what changed and how to test each.

Open browser tests (nothing below has been run; each entry under "Progress" has its own steps):

1. Earlier rounds, still unchecked: connector grid and connector step; the older stepper's AI drawer (create
   a mapping with "Generate with AI" and confirm the drawer opens); the validation drawer's "Go to problem"
   (needs a JSONata mapping); the read-only Subscription tab (no Mapping Admin or Create role).
2. Cell renderers (19): toggle a mapping's activation and debug flag and a connector's enabled switch; confirm
   the version, draft, deployment, and status cells refresh.
3. Mapping grid and older stepper: spinner on reload, import, and bulk delete; add, edit, copy; save and cancel.
4. Mapping type drawer and message explorer: add a mapping in both directions; start, pause, resume, edit and stop
   a session; reload to resume the stored session; create a mapping from one and several messages.
5. Monitoring, extension, configuration: cache page, statistics and chart, events page, extension upload and
   delete, service configuration (every tab by direct URL, Expert Mode, Save enables and disables, import), code
   template (select, edit, save, rename, copy, delete).
6. Service logs, connector details, explorer drawer, versions drawer and count, publish dialog.
7. Modals and import dialogs, subscription page (all drawers, resync), device-to-client map, test-device grid,
   documentation pages (search, code blocks, diagrams), copy-source dialog, validation drawer, deprecation notice.

Two things to watch during all of these: a screen that stops updating after an `await` or a timer (the failure
mode in "The two ways OnPush breaks a screen"), and the first render of a drawer or dialog opened through
`initialState`.

Next steps, in this order:

1. Run the browser tests above and fix what they show. A failed test points at a field that needs a signal or
   a `markForCheck()`.
2. Test the six converted last first (checklist in their entry). Keep `ai-prompt` on default change detection. Do not replace the `detectChanges()` in `ai-prompt`
   `ngOnInit` with a render wait; see "Regression: AI conversation not restored".
3. Pay down the debt (198 `prefer-signals`, 89 `any`, 4 components over 500 lines). Delete a directory's line from
   the "Known debt" blocks in `eslint.config.cjs` when it reaches zero.

## Why

A review of `dynamic-mapper-ui` found one root cause behind several symptoms:

- 5 of 91 components use `ChangeDetectionStrategy.OnPush`.
- `signal()`, `computed()`, and `input()` appear in the code once between them.
- 31 `cdr.detectChanges()` calls and one `ngDoCheck` exist to patch over the missing reactivity.
- About 69 template expressions call methods, so they run on every change-detection pass.

The mapping editor is the center of the problem. `Mapping` is one mutable object that the parent
(`MappingUnifiedEditorComponent`) passes down as `@Input() mapping` to every step. Children report
edits as events (`propertiesChange`, `filterMappingChange`, `substitutionsChange`), and the parent
or the child then mutates the shared object. Three of the four `detectChanges()` hot spots, and the
`ngDoCheck` in `MappingStepPropertiesComponent`, exist because a child does not notice that another
component changed the object.

## Goal and non-goals

Goal: every component that holds mapping state reads it from signals, runs `OnPush`, and needs no
manual `detectChanges()` or `ngDoCheck`. Each component migrated also loses its `any` types and, where
it is oversized, gets split along the seams the migration exposes.

Non-goals:

- Going zoneless. This plan keeps zone.js. It can follow later and is easier once this is done.
- Replacing Formly or the c8y component library.
- Changing behavior, labels, or layout. Every step is a refactor with no visible change.
- A separate sweep for the 235 `any` uses or the oversized components. Both are handled inside the
  phases below (see "Typing and splitting alongside the migration"), never as a standalone pull request.

## The two ways OnPush breaks a screen

Every review and test in this plan targets these two failure modes. Both produce a view that
silently stops updating, with no error in the console.

1. **Mutation inside an input.** An `OnPush` child only re-renders when the input reference changes
   or an event fires inside it. `this.mapping.filterMapping = x` in a sibling or parent is invisible.
2. **Assignment after `await` or a subscription.** `await this.service.load(); this.flag = true;`
   does not mark the component dirty. Today `detectChanges()` hides this. With `OnPush` the flag
   must be a signal, or the call must be replaced.

Also watch for lazy tab content. The editor renders each step with `@if (currentStepIndex === …)`,
so `@ViewChild` references (`transformationStepRef`, `templateStep`) exist only after a render. The
`detectChanges()` calls at `mapping-unified-editor.component.ts` lines 564 and 566 force that render.
The repo already has `afterNextRenderInZone` in `src/shared/misc/after-render.ts` for this case.

## State model

Decided: keep `Mapping` as a plain object, but make the editor hold it
in one `signal<Mapping>` and replace it through a single `patchMapping(patch)` method that creates a
new object. Children get `input.required<Mapping>()` and never assign to it.

Consequences to check in phase 2:

- `this.mapping.lastUpdate = persisted.lastUpdate` (lines 452 to 457) becomes a `patchMapping` call.
- `MappingStepperService`, `captureMappingContentSnapshot`, and the testing step's
  `updateTestingTemplate: ReplaySubject<Mapping>` hold references to the object. Each must accept
  "a new object arrives" instead of "the same object changed".
- Anything comparing `mapping` by identity (snapshots, dirty checks) must be reviewed. Identity now
  changes on every edit, so a dirty check that relies on identity would report "dirty" too often.

## Typing and splitting alongside the migration

The review found 235 `any` uses and several oversized classes. They are part of this work for one
reason: both make the migration harder, and doing them in the same pass costs less than a second
visit to the same file. A separate sweep would touch every file twice, produce large diffs with no
behavior to check, and conflict with the migration pull requests.

### The `any` uses

The 235 uses are concentrated. Ten inputs are typed `@Input() x: any`, and the top two files hold 43:

| File | `any` uses | Handled in |
|---|---|---|
| `mapping/service/mapping-stepper.service.ts` | 22 | Phase 3 |
| `mapping/step-template/mapping-template-step.component.ts` | 21 | Phase 4 |
| `mapping/prompt/ai-prompt.component.ts` | 10 | Phase 5 |
| `mapping/client-relation/client-relation-stepper.component.ts` | 10 | Phase 4 |
| `mapping/step-testing/mapping-step-testing.component.ts` | 9 | Phase 4 |
| `shared/mapping/editor-session.util.ts`, `json-editor.component.ts` | 8 each | Phase 3 and Phase 1 |
| everything else | under 7 each | with its phase |

Rules:

- **Type what the migration touches.** A signal input needs a real type, so `input<any>()` is not
  accepted. The template and schema inputs on `step-template` (`sourceTemplate`, `targetTemplate`,
  `schemaSource`, `schemaTarget`, the editor options) get named interfaces in `shared/mapping`.
- **Prefer `unknown` plus a narrowing guard** for payloads that really are arbitrary JSON (source and
  target templates). Reserve `any` for the one boundary with a third-party type that cannot be typed,
  and mark it with a comment that names the reason.
- **No new `any`.** Phase 0 turns on `@typescript-eslint/no-explicit-any` as a warning. A file moved to
  `OnPush` must have zero warnings from that rule before its pull request merges.
- **Do not widen the scope.** A file with `any` that no phase touches stays as it is until a later
  phase reaches it. The warning count is the progress measure, and the goal is a falling number, not
  zero in one release.

### Oversized components

The largest classes are `mapping-step-properties` (903 lines), `mapping.component` (896),
`ai-prompt` (877), `mapping-type-drawer` (822), `message-explorer` (761), and `mapping-step-testing`
(712). They mix data loading, form wiring, and state. That mix is also what forces the
`detectChanges()` calls: state set after an `await` inside a 900-line class has no clear owner.

Rules:

- **Split along state ownership, not line count.** After a component's state is in signals, the seams
  are visible: pieces that only read some signals become child components or `computed()` helpers, and
  pieces that load data move to a service that exposes signals. Do not split before that point, because
  the split would copy the same mutation problem across two files.
- **One split per pull request, after the migration of that component has merged and been checked in
  the browser.** The migration and the split never share a pull request, so a regression has one cause.
- **Extract only what has a name.** Candidates already visible in the code: the execution-filter
  control in `mapping-step-properties`, the AI settings and availability check in `ai-prompt` and
  `shared-code-ai-prompt` (the two duplicate it), the connector filtering in `message-explorer-drawer`,
  and the test-run state in `mapping-step-testing`.
- **Target, not a quota.** Aim for no component class above about 500 lines and no method that mixes
  loading and rendering state. Where a class stays larger for a good reason, leave a one-line comment.
- **Moved logic keeps its tests.** Extracted code moves with its existing spec. A split that leaves
  logic without a unit test is not done.

## Status at a glance

Measured on 2026-11-10 (at the stop) against the starting numbers in the review.

| Measure | Start | Now |
|---|---|---|
| Components on `OnPush` (lint count) | 5 of 91 by grep | 92 of 93 (see the status block at the top) |
| `detectChanges()` calls | 29 | 2 (two justified synchronous renders, see below) |
| `ngDoCheck` | 1 | 0 |
| Explicit `any` (lint count, non-spec) | 277 (235 by the first grep, which counted fewer patterns) | 89 |
| Unit tests | 978 | 1012 |

| Phase | State |
|---|---|
| 0. Safety net | Lint rules added as warnings (`prefer-on-push`, `prefer-signals`, `no-explicit-any`), and promoted to errors in the directories that are done. Starting counts: 73 not `OnPush`, 198 `prefer-signals`, 277 `any`. |
| 1. Leaf components | Done, including the 19 cell renderers and the documentation pages. `custom-select` and the Formly wrappers are left (see the status block). The migration schematics have not been run. |
| 2. `MappingStepPropertiesComponent` | Done: `ngDoCheck` removed and the step is `OnPush`. |
| 3. `MappingUnifiedEditorComponent` | Done: state in signals, 3 forced renders replaced, and the editor is `OnPush`. The older `MappingStepperComponent` is `OnPush` too. |
| 4. Step components | Done: every step and every component below the editor, including the connector step and its grid. |
| 5. AI prompt components | `detectChanges()` calls removed except one that stays (10 of 11). The two prompt components are still on default change detection, on purpose. |
| 6. Grids, lists, rest | Done: mapping grid, message explorer, monitoring, extension, configuration, connector details, versions, and the dialogs are on `OnPush`. |
| 7. Enforce | Done. `OnPush`, `prefer-signals`, and `no-explicit-any` are errors for the whole tree; 59 directories with remaining findings are listed as warnings ("Known debt" blocks). New `cdr.detectChanges()` and `ngDoCheck` are errors, components over 500 lines warn, and `docs/ui/architecture.md` has the state rule. The debt itself (198 `prefer-signals`, 190 `any`) is not paid. |

## Progress

### Phase 3, step 1: mapping state in a signal (done, not yet browser-checked)

`MappingEditorBase` (shared by `MappingUnifiedEditorComponent` and the older
`MappingStepperComponent`) now holds the mapping in one signal. `mapping` is an accessor over it, so
existing reads such as `this.mapping.name` are unchanged. The `mapping` input of the stepper is
declared through `inputs: [...]`, as `deploymentMapEntry` already was.

| Method | Use |
|---|---|
| `patchMapping(patch)` | Replaces top-level properties and creates a new mapping object |
| `updateMapping(recipe)` | Runs in-place code (stepper service extension and target API handlers) on a copy |
| `withMappingDraft(run)` | Async form for commit, publish, and activate. Patches only what the callback changed, in `finally`, so an edit made meanwhile is kept |
| `mappingSignal` | Read-only signal for `computed()` slices |

All write paths in the editor now go through these: the four child-event handlers in the base, the
three extension and target API handlers, the post-save rebaseline, commit, publish, and activate.
Children were checked and do not mutate the mapping they receive.

The unit tests for the new behavior are in `mapping-editor.base.spec.ts`. Children still use default
change detection, so nothing is `OnPush` yet and no `detectChanges()` call has been removed.

To verify in the browser: Save and Save and Activate (version, last update, and the unsaved marker
after save), extension selection on a Java extension mapping, and the target API change on an inbound
and an outbound mapping.

Browser check of the three flows above: done, no problems found.

### Phase 2, step 1: `ngDoCheck` removed from `MappingStepPropertiesComponent` (done, not yet browser-checked)

The component followed an edit made in the Templates step by comparing `mapping.filterMapping` on
every change-detection pass. Because the host now replaces its mapping object on each edit, the
component uses `ngOnChanges` on its `mapping` input instead and re-derives the execution-filter control
only when the expression differs from the one it last wrote or read. This differs from the plan text,
which proposed a `computed()` slice: the component still uses an `@Input()` field, and converting it
to a signal input would touch its template and about 20 spec lines for no behavior gain. Revisit when
the component moves to `OnPush`.

The component is still on default change detection. It has an async `ngOnInit`, Formly forms, and
`cdr` calls, so moving it to `OnPush` needs a browser pass and belongs in its own pull request.

To verify in the browser: on an outbound mapping, edit the execution filter in the Templates step and
confirm the Properties step shows the same expression, and the reverse. Typing in the control must not
reset the chosen option.

Browser check: done, no problems found.

### Phase 3, step 2: editor `detectChanges()` calls replaced (done, not yet browser-checked)

The three forced renders in `MappingUnifiedEditorComponent` are gone (26 `detectChanges()` calls remain in
the code base). The base class has a new `whenRendered()`, a promise that resolves after Angular's
next render. It replaces `cdr.detectChanges()` where the code needs a lazily rendered child to exist:

- After `templatesInitialized = true` in the initial template setup, before the extension form is patched.
- In `showValidationIssues`, between switching to the transformation tab and selecting the substitution.
- The render after `onSelectSubstitution` is now `cdr.markForCheck()`, which also works on `OnPush`.

Specs call `ngOnInit()` directly with no render, so `initEditor()` in the editor spec stubs
`whenRendered`. The base spec has a test that it stays pending until a render and then resolves.

To verify in the browser: open an existing mapping with an extension (the extension name, event, and
parameter fields must be filled in), and trigger a save that fails validation, choose "go to" on a
substitution problem, and confirm the Transformation tab opens with that substitution selected.

Not done: `currentStepIndex` and `templatesInitialized` are still plain fields. They must become signals
before the editor can move to `OnPush`, because the template reads them.

Browser check: the extension check passed. The validation drawer check ("Go to problem") is still open.
It needs a JSONata mapping, because the backend skips substitution validation for Smart Function and
Java extension mappings (see `MappingValidator.validateSubstitutionExpressions`).

### Phase 3, step 3: `currentStepIndex` and `templatesInitialized` as signals (done, not yet browser-checked)

Both are now accessors over signals in `MappingEditorBase`. Reads and writes elsewhere are unchanged,
including `templatesInitialized ||= …`. The hosts and the base spec no longer declare the fields.

### Phase 3, step 4: template-bound editor fields as signals (done, not yet browser-checked)

The fields that blocked `OnPush` are now signal-backed accessors in `MappingEditorBase`, with the same
names, so no call site changed: `stepperConfiguration`, `stepperViewModel`, `feature`, `sourceSystem`,
`targetSystem`, `sourceTemplate`, `targetTemplate`, `mappingCode`, `schemaSource`, `schemaTarget`,
`templateForm`, `filterFormlyFields`, `editorOptions`, `extensionItems`, `codeTemplateItems`,
`codeEditorLabel`, `codeEditorHelp`, `aiAgent`, `aiAgentDeployed`, `aiCapabilitiesDisabled`,
`hasExtensionParameter`, `isLoadingExtensions`, and `subscriptionSelection`. The stepper takes
`stepperConfiguration` through `inputs: [...]`, as it does `mapping`.

The editor and the stepper still use default change detection, so this step changes no behavior. It was
kept separate from the `OnPush` switch on purpose: if a screen goes stale after the switch, this step is
ruled out as the cause.

Checks run: 985 unit tests, lint, and `tsc`. `ngc -p tsconfig.app.json --noEmit` reports 8 errors, all
present before this work (echarts typings, Formly `AbstractControl` typing, a keyboard event type, and
two templates); none are in the editor files.

To verify in the browser: open an inbound and an outbound mapping, switch through all tabs, edit the
source and target templates, change the code in the Smart Function editor, and save. Everything should
look and behave as before.

Still plain and bound in the template, to review in the `OnPush` step: `layout`, `isGenerateSubstitutionOpen`,
`extensions`, and anything the editor's own template reads that changes after init (`propertyFormly`
and `filterFormly` are objects whose contents change, not references).

Next: see "Ordering correction" below. The editor cannot be switched to `OnPush` yet.

### Ordering correction: `OnPush` must go bottom-up

An earlier version of this plan switched the editor to `OnPush` first and let its children keep default
change detection "until their own phase". That is wrong. An `OnPush` component that is not dirty is
skipped together with its whole subtree. A default-strategy child that updates itself after an `await`,
with no signal and no `markForCheck()`, then stops updating on screen, even though the child's own code
is unchanged. A descendant's own event or `markForCheck()` still reaches the ancestors, so the problem
is limited to updates that arrive without either.

Consequences:

- A component moves to `OnPush` only after every component below it is safe: it uses signals or
  `markForCheck()` for state that changes asynchronously, or it is `OnPush` itself.
- The `OnPush` switch for the editor host (phase 3) therefore comes after the steps (phase 4). The state
  work already done in phase 3 (signals in the base class) stays; it is the prerequisite.
- The same applies to `MappingStepperComponent` and every grid.

Inventory of what is below `MappingUnifiedEditorComponent`:

| Component | Size | Async state | Status |
|---|---|---|---|
| `mapping-code-editor-section` | 62 lines | none | `OnPush` (done) |
| `mapping-connector` | 62 | none, but hosts `ConnectorGridComponent`, which loads data | blocked on the connector grid |
| `mapping-extension-selector` | 58 | form values patched by the host through the host's `cdr` | needs its own `valueChanges` to mark the view |
| `mapping-substitution-grid` | 218 | none | check whether its parent mutates `settings` in place |
| `mapping-step-subscription` | 305 | 1 await | next candidate |
| `mapping-template-step` | 462 | 1 await, 1 subscription | after the extension selector |
| `mapping-substitution-step` | 523 | 6 awaits, 2 subscriptions | after the substitution grid |
| `mapping-step-testing` | 712 | 10 awaits, 1 subscription | signals for the test result state |
| `mapping-step-properties` | 905 | 6 awaits, Formly | last of the steps |

Order: the three small leaves first, then the steps from smallest to largest, then the editor. Each
component is its own pull request with a browser check of its tab.

### Phase 4, first leaf: `mapping-code-editor-section` on `OnPush` (done, not yet browser-checked)

The code editor block of the Transformation tab is stateless, with inputs and outputs only, so it is the
first component moved. Its spec now sets inputs through `setInput`, which is how a parent binds them,
and a new test pins that a bare property assignment does not re-render it.

To verify in the browser: open a mapping on the Transformation tab. The code editor must appear once the
editor has loaded (the `showEditor` input flips after init), the code template select must list the
templates, and selecting one must put its code into the editor. The AI button must be enabled or
disabled as before, and its help button must show only when no agent is deployed.

### Phase 4: Subscription tab subtree on `OnPush` (done, not yet browser-checked)

`mapping-step-subscription` and the four selectors it hosts are now `OnPush`: `device-selector-tree`,
`device-selector-table`, `group-selector`, and `type-selector`. The selectors only take inputs and report
through outputs, with no async code of their own. The step sets `loading`, `loadFailed`, and the baseline
after an `await`, outside any event, so it calls `markForCheck()` once the load has finished. Every other
state change reaches it through a template output or click binding, which marks it dirty.

The step's spec gained an `act()` helper that runs a handler and marks the component dirty, as a template
binding does, and a test for the failed-load warning. The test fails when the `markForCheck()` is removed,
so it guards the fix.

Not covered by unit tests: the c8y library components inside the selectors (`c8y-asset-selector-miller`,
`c8y-device-grid`, `c8y-input-group-list`). They load their own data. If one of them updates without
notifying its parent, its list will look stale under `OnPush`; only the browser can show that.

To verify in the browser: open an outbound mapping and go to the Subscription tab. Each option (dynamic by
type, by group, static by device tree, by device table) must expand and show its list once loaded. Edit a
list and check that the "not saved yet" label appears. Open the device table drawer, tick devices, and
confirm. With no Mapping Admin or Create role the selectors must be read-only. Also check a mapping started
from the Message Explorer (device context): the radio list must show the device, its type, and its groups.

Browser check: all items passed except the read-only case (no Mapping Admin or Create role), which has
not been tested yet.

Inventory update: `mapping-step-subscription` is done. Remaining below the editor: `mapping-connector`
(blocked on the connector grid), `mapping-extension-selector`, `mapping-substitution-grid`,
`mapping-template-step`, `mapping-substitution-step`, `mapping-step-testing`, `mapping-step-properties`.

### Phase 4: extension selector and substitution grid on `OnPush` (done, not yet browser-checked)

`mapping-extension-selector` and `mapping-substitution-grid` are now `OnPush`.

- **Extension selector:** it shows form validity and the description of the selected event. The host fills
  its form programmatically, which raises no event in the selector's view, so the selector now subscribes
  to the form's value and status changes and marks itself dirty. The new spec renders the real template.
  Only one of its three tests fails when the subscription is removed (the one that swaps in a new form);
  the other two pass either way, because Angular's forms already mark the view in that setup. The
  subscription is kept as cheap insurance, since the real `c8y-select` value accessor was not exercised.
- **Substitution grid:** its `[settings]` input is an object literal in the parent's template, which Angular
  recreates only when its values change, so `OnPush` sees every change. Its inputs are the replaced
  `mapping` and that literal. Its spec replaces the template with an empty `<div>`, so rendering under
  `OnPush` is not covered by a unit test; only the browser can show it. One known gap: the repair strategy
  labels use `translateService.instant()` in method calls, so a language switch without a page reload
  would leave them in the old language until another event.

To verify in the browser: on a mapping with a Java extension, the Templates tab must show the extension
name and event once loaded, the event description under the select, and the "required" message after
touching an empty select. On the Transformation tab of a JSONata mapping, the substitution grid must list
the substitutions, highlight the selected row, update when a substitution is added, edited, or deleted,
and lock "Expand as array" for outbound mappings and in read-only mode.

Inventory update: remaining below the editor: `mapping-connector` (blocked on the connector grid),
`mapping-template-step`, `mapping-substitution-step`, `mapping-step-testing`, `mapping-step-properties`.

### Phase 4: template step on `OnPush` (done, not yet browser-checked)

`mapping-template-step` is now `OnPush`. Its async state comes from the filter expression evaluation: the
debounced filter input, `ngOnChanges`, `afterNextRender`, and the host calling `updateFilterExpressionResult`
directly all end in code that runs after an `await` with no event of the step's own view. That method now
ends with `markForCheck()`. The step already re-assigned `filterModel` there to make Formly notice, which
still works. `stepperViewModel` is never mutated in place, and `contentChangeValid` is not read by any host,
so neither needed a change. The projected extension selectors belong to the host's view and are checked with it.

Both of its specs replace the template with an empty `<div>`, so rendering under `OnPush` is not unit
tested. Two new tests pin that both outcomes of an evaluation (success and failure) mark the step for
checking; both fail when the call is removed.

To verify in the browser: open a mapping on the Templates tab. On an outbound mapping, enter a filter
expression: the "Filter Result" box and the validation message must appear after the input pauses, and
show the right result type for a valid and for an invalid expression. Open an existing outbound mapping
that already has a filter: the result box must be filled once the tab has loaded. The source and target
template editors must show their content, the metadata toggle must show and hide the `_IDENTITY_` keys,
"Reset to default" must restore the target template, and a template edit that changes protected fields
must show its warning.

Inventory update: remaining below the editor: `mapping-connector` (blocked on the connector grid),
`mapping-substitution-step`, `mapping-step-testing`, `mapping-step-properties`.

### Phase 4 and 3: remaining steps and the editor on `OnPush` (done, not yet browser-checked)

Four components moved in one round, in this order: `mapping-substitution-step`, `mapping-step-testing`,
`mapping-step-properties`, and then `MappingUnifiedEditorComponent` itself. Each one's async state
changes after an `await`, from a debounced input, or when the host pushes a value, with no event of its
own view, so each now calls `markForCheck()` where that state lands:

- Substitution step: after each source and target expression evaluation, after a row is selected by the
  host, and after the AI drawer closes.
- Testing step: when a test run finishes (`finally`) and when the host pushes a new test mapping.
- Properties step: at the end of `ngOnInit` (the form is built after awaits), after the inventory filter
  evaluation, and after the inventory cache warning is refreshed.
- Editor: no new `markForCheck()`. Everything its template reads is a signal, a constant, or changes only
  through a template event. `initialDeploymentConnectors` became signal-backed because it is re-baselined
  after a save and feeds the testing step. An audit of the template found no plain field that code
  assigns (`templateId` is only set by a template event).

Seven new unit tests pin the `markForCheck()` calls (4 substitution step, 2 testing step, 1 properties
step). All seven fail when the calls are removed. The specs for the substitution step, testing step,
and template step replace or avoid the real template, so rendering under `OnPush` is not unit tested.

Deliberately left on default change detection: the connector step and `ConnectorGridComponent`. The
grid has polling timers, an `IntersectionObserver`, a header template declared in one view and rendered
inside `c8y-data-grid`, and three cell renderers with their own `detectChanges()` calls. It needs its own
step with its own browser pass. It works under the `OnPush` editor because it updates through the `async`
pipe, `detectChanges()`, and events, all of which reach the ancestors. The older `MappingStepperComponent`
also stays as it is; it has far more plain state.

To verify in the browser, tab by tab, on an inbound and an outbound mapping:

- General Settings: the form must appear once loaded (it is built after awaits), with the QoS hint. Edit
  the inventory filter in Expert Mode: the result box and the "not in the inventory cache" warning must
  update after you stop typing. Change the execution filter in Templates and see it in General Settings.
- Transformation (JSONata mapping): select a row, then edit its source and target expressions; the result
  box and its type label must update after a pause. Add, edit, and delete a substitution. Trigger the
  validation drawer and use "Go to problem": the row must be selected.
- Testing: run Test transformation (dry run) and Send test: the loading state must show and clear, the
  results, console, and response must fill in, and the "Next result" button must step through them. Change
  a template or the code, return to Testing, and check the payload follows.
- Editor frame: the title must show the mapping name; tab switching, the footer version, debug and draft
  labels, Save and Save and Activate, and a failed save (stay in the editor) must all update the screen.
  After a save, the Testing tab's "connectors not saved" hint must clear.
- Connector tab: the connector list must load, status and countdown must keep updating, and selecting a
  connector must update "Selected connectors for mapping".

### Phase 5 and 6: `detectChanges()` removed everywhere but one place (done, not yet browser-checked)

Of the 26 remaining calls, one was only a comment and 25 were real. All but two are gone, in the ways below.
(The first version of this entry removed all but one. A reported regression in the AI prompt put one back; see
"Regression: AI conversation not restored".)

- **State published after an await or a callback (20 calls):** replaced by `markForCheck()`. This covers the
  two AI prompts (all but one call), the connector status and mapping activation renderers, the connector
  details page, the connector drawer (form creation), the mapping type drawer, and the client-relation
  stepper. These components stay on default change detection; `markForCheck()` also marks their `OnPush`
  ancestors, which is why it is the right call under the `OnPush` editor.
- **Render before use (1 call):** the older stepper needs the transformation step to receive updated
  templates before it opens the AI drawer. It now waits with `whenRendered()`, a promise that resolves after
  the next render, which the stepper has from `MappingEditorBase`. (`AiPromptComponent` had a second such
  call; it was put back, see the regression note.)
- **Connector grid (1 call):** the header controls were set in `ngAfterViewInit` and a render was forced to
  apply them. The `autoRefreshTemplate` view child is now `static: true` (the template is at the top level
  of the grid's template), so the controls are set in `ngOnInit` before the first render.
- **Kept (2 calls):** `AiPromptComponent.ngOnInit` keeps its synchronous render (see the regression note).
  `ConnectorConfigurationDrawerComponent.onDirectionFilterChange` clears the form fields,
  renders the empty form so Formly tears the old fields down, and then rebuilds them. With
  `markForCheck()` the render would come after the rebuild and Formly would reuse the old field state. The
  call has a comment saying so.

Unit tests: the drawer spec now asserts `markForCheck()` after creating the form.

To verify in the browser:

- AI assistant in the mapping editor, on a Smart Function mapping and on a JSONata mapping: open it on a
  new mapping (it must start generating by itself and show the chat), on an existing mapping (the two
  suggestion chips must appear), and again after closing it (the earlier transcript must be restored).
  Also with the AI kill switch on and with the agent unavailable: the matching message must show.
- AI assistant for shared code on the Code Templates screen: the suggestion chips, the generated code, and
  the "valid" state must appear.
- Older stepper (the create flow that opens the AI assistant at the end): the AI drawer must open with the
  templates the user last edited.
- Connector tab and the Connector Configuration page: the grid must show the "Auto refresh" control in its
  header with the countdown, the list, and the selection. Toggle a connector on and off: the spinner must
  show and clear. Open the connector details page: it must follow edits made in the drawer. In the
  connector drawer, create and edit a connector, and change the direction filter: the broker fields must
  reset and rebuild.
- Mapping grid: toggle a mapping's activation: the spinner must show and clear.
- Mapping type drawer (Add mapping): the loading state must clear and the form appear.

### Regression: AI conversation not restored (fixed by the rollback; mechanism not established)

After the round above, the AI assistant drawer was reported to show no earlier conversation when reopened.
The first version of that round had replaced the synchronous `detectChanges()` in `AiPromptComponent.ngOnInit`
(after the stored session is read, before the first message is sent) with a wait for the next application
tick. Reading the code did not show a defect in that wait. The drawer's persistence (`persistSession`, called
on every message change and on save and cancel) and the history service (in memory, per tab, lost on a page
reload) are unchanged. What the chat library does is documented here because it is easy to misread:

- It restores messages from `initialChatHistory` when it is created, and again whenever that input changes
  (a change also clears the messages first).
- It shows the suggestion chips it restored from the history whenever the host's `suggestions` is
  `undefined`. Chips on screen therefore do not mean that no session was found.
- A stored session that holds only the user's message (the agent had not answered when the drawer was
  closed) restores as one user message with the chips and "Tokens used: 0".

The synchronous render was put back, and a retest in the browser confirmed that the conversation is
restored again. So the wait for the next application tick was the cause. Why is still not established; the
code reading above found no defect, and the drawer is created outside the router outlet, which is the
likeliest difference. A comment at the call says so.

Lesson for the rest of the plan: replacing `cdr.detectChanges()` with `whenRendered()` is not equivalent
when the code after it depends on the view children and inputs existing in that very task. It was
browser-checked and fine in the editor (extension selection). Two places still use it and have not been
checked: the older stepper's `launchAIGenerationOnStart` (the AI drawer after "create"), and the editor's
"Go to problem" jump from the validation drawer. If either misbehaves, put the synchronous render back.

Three tests now cover opening the drawer for the first time (the two chips appear), resuming a stored
conversation (its history reaches the chat, and no new chips are set), and a conversation stored by one run
being resumed by the next. The spec never ran `ngOnInit` before.

### Connector grid and connector step on `OnPush` (done, not yet browser-checked)

`ConnectorGridComponent` and `mapping-connector`, the step that wraps it, are `OnPush`. Everything the grid's
template shows arrives through `async` pipes (`configurations$`, `nextTriggerCountdown$`) or template events
(selection, direction filter, refresh interval, and the data grid's own actions), all of which mark the view.
One plain field is read indirectly: `configurations`, which `findNameByIdent` uses for the "Selected
connectors" labels. It is set from a subscription, so that subscription now calls `markForCheck()`. The
polling timers, the `IntersectionObserver`, and the visibility listener only drive the service and the
countdown component, not the grid's own state.

Two new unit tests pin the `markForCheck()` and the name lookup. The grid's cell renderers, details page, and
drawers stay on default change detection; they use `markForCheck()` and `async` pipes, which also mark an
`OnPush` ancestor.

To verify in the browser, on the Connector tab of a mapping and on the Connector Configuration page: the list
loads, the status and the polling countdown keep updating, and "Auto refresh" shows in the grid header with
its interval select and toggle. Pause and resume the toggle and change the interval. Select and deselect a
connector: "Selected connectors for mapping" must follow, with the right names. On the Configuration page,
filter by direction, add, edit, copy, delete, import, and export a connector: the list must refresh after each.
Scroll the grid out of view and back, and switch browser tabs: polling must pause and resume.

### Stateless cell renderers on `OnPush` (done, not yet browser-checked)

13 renderers that only read `CellRendererContext` moved to `OnPush`: draft badge, deployment, mapping id, QoS,
status, template, version badge, label, tagged label, name, number, monitoring direction, and monitoring id.
Safe because `c8y-cell-renderer` is itself `OnPush` and builds a new injector (and so a new renderer) when
the cell spec changes. Not migrated: `mapping-status-activation` and the other renderers that hold state.
Browser check: open the mapping grid, toggle a mapping's activation and debug flag, and confirm the version,
draft, and deployment cells refresh after the grid reloads. Unit tests: 1012 of 1012 pass.

A second batch of 6 followed: activation toggle, connector enabled toggle, connector name, connector status, version note, and version state. These had their own state, but each already calls `markForCheck()` after its `await`, so nothing else was needed.

### Mapping grid and older stepper on `OnPush` (done, not yet browser-checked)

`MappingStepperComponent` needed only the strategy change, because all its template-bound state lives in the
base class signals. `MappingComponent` (the grid) got signal-backed accessors for the fields that change after
an `await` or a callback: `isLoading`, `showConfigMapping`, `mappingToUpdate`, `deploymentMapEntry`,
`stepperConfiguration`, and `feature`. The row data already comes through `mappingsEnriched$ | async`, and
`realtimeActive` changes only from a click in the template.
Browser check: open the grid for both directions and confirm the spinner shows and hides on reload, import, and
bulk delete; add a mapping and edit one (the stepper opens and shows its data); copy a mapping; cancel and save
closes the stepper; "Go to problem" after a rejected save (same item as the editor check); the AI drawer opens
when a mapping is created with "Generate with AI". Unit tests: 1012 of 1012 pass.

### Mapping type drawer and message explorer on `OnPush` (done, not yet browser-checked)

Both use signal-backed accessors for the fields their templates read and that change after an `await`,
a timer, or a subscription. Drawer: the form group, the type, transformation, code template and extension
option lists, `hasExtensionParameter`, and the three loading flags. Explorer: the session fields
(`sessionId`, connector name, topic, direction, source id, device name and type filter, subscription warning),
`messages`, `paused`, and `columns`. The countdown already runs through `nextTriggerCountdown$ | async`.
Browser check, drawer: open "Add mapping" for both directions, change the mapping type, the transformation type
(Expert Mode on), the code template, and the extension and event; confirm each list and the loading spinners
update and "Continue" enables when the form is valid. Explorer: start a session, confirm messages arrive on the
polling interval, pause and resume, edit the session, stop it, reload the page to resume the stored session, and
create a mapping from one message and from several. Unit tests: 1012 of 1012 pass.

### Monitoring, extension, and configuration screens on `OnPush` (done, not yet browser-checked)

Moved: the cache statistics page and its three KPI components, the monitoring chart, the mapping statistics
page, the service events page, the extension grid, card, properties page, and upload dialog, the service
configuration page, its import dialog, and the code template page. Left on default detection:
`shared-code-ai-prompt` (goes with the AI prompts) and the service logs page (499 lines, 18 `await`s, timers).
Signals were needed where a template reads a field that changes after an `await`, a timer, or a subscription:
- Cache statistics: `kpis`, `loading`. Monitoring chart: the two update options and `hasLatencyData`.
- Extension grid: its state is now one signal instead of a `BehaviorSubject` read through getters. Extension
  card: `feature`. Upload dialog: `isLoading`, `isAppCreated`, `app` (now `null` when unset), `errorMessage`.
- Service configuration: `section`, `expertMode`, the two AI flags, the fragment list, and the saved snapshot
  behind `isDirty`. Import dialog: loading, imported, error, the pending file and its counts, `fileName`.
- Code template: `template`, `templateType`, the decoded template, `isLoading`, the editor options, the AI
  fields, and the saved code behind `isDirty`.
The mapping statistics and service events pages needed no field change: they read through the `async` pipe.
`service-configuration.component.spec.ts` builds the component with `Object.create`, so its helper now
creates the two signals by hand.
Browser check: cache page (reload and clear a cache); monitoring chart and statistics (values change as
mappings run, reset statistics); events page (filters, load more); extensions (upload a jar, delete one,
reload, open properties and switch the direction filter); service configuration (open each tab on a direct
URL, toggle Expert Mode, edit a value and confirm Save enables, save and confirm it disables, add and remove
a fragment, import a file); code template (select, edit, save, rename, copy, delete). Unit tests: 1012 of 1012.

### Service logs page and the small screens on `OnPush` (done, not yet browser-checked)

Moved: the service logs page (17 fields as signals, because it reads them after `await`s and from a
`setInterval`), the connector details page (`configuration`, `feature`, `serviceConfiguration`,
`isTogglingConnection`; the optimistic `enabled` flip now replaces the object instead of mutating it), the
connector status history, the message explorer drawer (11 form fields) and its three cell renderers, the
versions drawer (`loading`, `busy`), the versions count page, and the publish-version dialog.
Browser check: service logs (page loads, switch source, pick a mapping, auto refresh ticks, paging, theme
toggle, download); connector details (open from the grid, toggle the connection, edit, status history shows
new entries); message explorer drawer (change direction, pick a connector, pick a device, start; edit an
existing session); versions drawer (open from the grid, edit a note, activate, publish, delete); versions
count page; publish dialog (suggestion buttons set the version, OK enables for a valid version).
Unit tests: 1012 of 1012.

### Modals, drawers, doc pages, and import dialogs on `OnPush` (done, not yet browser-checked)

19 more components: validation drawer, code editor drawer, manage-template dialog, copy-source dialog,
confirmation modal, deprecation notice, mapping tree, device-id cell renderer, connector configuration page,
the four documentation pages (page, main, overview, code templates), the mapping and connector import dialogs,
the device-to-client map, the test-device grid, the subscription page, and the type resync drawer.
Signals were added where a template reads something set after an `await`, a timer, or a router event:
doc pages (`title`, `html`, `codeTemplates`, search state in `doc-main`), import dialogs (loading, result, error
fields), the subscription page (five drawer flags, the three subscribed lists, the two responses, `feature`, and the
action controls, which are now replaced instead of pushed to after the `await`), the device-to-client map and
the test grid (same `await` then in-place `push` pattern), and the resync drawer's `rows`.
Not migrated on purpose, because each needs a browser check first:
- `connector-configuration-drawer`: Formly forms, field arrays mutated in place, state set after an `await` in
  `ngOnInit`, and a kept `detectChanges()`.
- The three Formly components (`custom-form-field-wrapper`, `input-list-formly`, `input-list`) and
  `custom-select` (a value accessor with its own open and search state). Formly drives its own checks.
- The two AI prompts (`ai-prompt`, `shared-code-ai-prompt`), see "Regression: AI conversation not restored".
Browser check: open each import dialog and import a file; subscription page (static, dynamic, group and type
tabs: open each drawer, commit, delete a subscription, resync a type); device-to-client map (add, delete);
test-device grid; each documentation page (search in `doc-main`, code blocks and diagrams render); copy a mapping
that has a draft (the copy-source dialog); the validation drawer; the deprecation notice. Unit tests: 1012 of 1012.

### Last components on `OnPush` (six done, `ai-prompt` reverted; not yet browser-checked)

These seven were the ones left on purpose. Six are converted and need browser checks before anything else is
built on them; `ai-prompt` was converted and then reverted.
- `connector-configuration-drawer`: nine fields became signals (the three form field lists and `dynamicForm`,
  `description`, `readOnly`, `feature`, `mode`, `testing`, `testResult`). The synchronous `detectChanges()` in
  `onDirectionFilterChange` stays; it still renders an `OnPush` view.
- `custom-form-field-wrapper`, `input-list-formly`, `input-list`: strategy change only. The c8y form field
  wrapper that `custom-form-field-wrapper` copies is `OnPush` as well, which is the precedent.
- `custom-select`: `OnPush` plus `markForCheck()` wherever state changes outside a template event: `writeValue`,
  `setDisabledState`, the keyboard service subscription, the focus microtask, `select`, `deselect`,
  `deselectAll`, and `onHidden`.
- `shared-code-ai-prompt`: strategy change only; it already called `markForCheck()` after each async step.
- `ai-prompt`: converted, then reverted to default change detection after a browser report that the reopened
  drawer showed no earlier conversation. After the revert the history was missing once more, then worked again
  and could not be reproduced. With the drawer closed by "Cancel" after a finished answer the history is
  restored; closing mid-reply logs "AI response cancelled" (expected, the chat aborts the stream) and was not
  shown to lose anything. So it is not established that `OnPush` caused the loss; it was the first change in
  the area, and so the revert was the safe move. The component stays on default change detection. If `OnPush`
  is tried again, first write a unit test with the real `c8y-agent-chat` that opens, converses, closes, and
  reopens, and test closing mid-reply and by Esc and backdrop in the browser.
Browser check, in this order: (1) the AI prompt must still show its history after reopening (it is back on the
default strategy, so this confirms the revert); (2) code template AI prompt (shared code: open, chat, accept the result); (3) connector
drawer: add a connector, change the type, change the direction filter, view an enabled connector (read only),
run "Test connection"; (4) every Formly field type in that drawer, including the map field (add and remove
rows) and the validation messages when a required field is left empty; (5) every `d11r-select` in the
application: open, search, pick with mouse and keyboard, multi-select, disabled state, preselected value.
Unit tests: 1012 of 1012.

### Lint rules promoted (done)

In `eslint.config.cjs`: `prefer-on-push-component-change-detection` is an error for all app code (specs are
exempt), and a `no-restricted-syntax` rule makes `cdr.detectChanges()` and `ngDoCheck` errors. Three places carry an
`eslint-disable` with a reason: the `ai-prompt` component (default change detection on purpose), its
`ngOnInit` render, and the connector drawer's direction filter render. I checked the rules with a throwaway
component that broke all three, and they reported four errors. `prefer-signals` (198) and `no-explicit-any`
(190) stay warnings outside the directories already promoted. Lint now reports 0 errors and 388 warnings.

### Phase 7: enforce (done)

`eslint.config.cjs` now enforces by default and lists the exceptions, which is the reverse of the earlier setup
(warnings everywhere, errors for finished directories). `prefer-signals` and `no-explicit-any` are errors for all
app code; two "Known debt" blocks downgrade them to warnings for the directories that still have findings, so a
new file or directory is strict from the first commit, and each cleaned directory is one deleted line. A
`max-lines` warning (500, blank lines and comments skipped) covers components. I checked it with a throwaway
component in a new directory: it failed on the `@Input()` decorator, the `any`, and the missing `OnPush`.
`docs/ui/architecture.md` has a new section "State and change detection" with the eight rules. Lint: 0 errors and
394 warnings (198 `prefer-signals`, 190 `any`, 6 over 500 lines). The old per-directory error blocks are gone,
because the global errors replace them.

### Oversized components: message explorer and properties step split (done, not yet browser-checked)

Two of the six components over 500 lines were split. Neither split changes behavior.
- **Message explorer (645 lines by the rule's count, now under 500):**
  - `explorer-mapping-context.service.ts`: the device type and group lookup and the publish-topic derivation that
    "Create mapping from message" used. It was one 147-line method. 12 unit tests were added.
  - `explorer-session-store.ts`: reading, writing, and clearing the stored session in `localStorage`, with the
    defaults for older stored sessions. 4 unit tests were added.
  - `message-explorer.columns.ts`: the column definitions.
  - `onOpenDrawer` and `onEditSession` repeated about 45 lines of session start code. Both now call
    `replaceSession(result, 'start' | 'update')`. The two modes differ only in the texts shown to the user.
- **Properties step (767 lines by the rule's count, now under 500):** `ngOnInit` was 470 lines of Formly field configuration. It moved to
  `property-form-fields.ts` as `buildPropertyFields(host)`. The host object exposes the model, the form, and the
  mapping as getters, so every handler still reads the current value when it runs. The `PropertyFormModel` type
  now lives next to the fields. The new file is 513 lines. It is not a component, so the size rule does not
  apply, but it is the next candidate if the field list grows.
Four components are still over 500 lines: the grid (743), the type drawer (640), `ai-prompt` (595), and the
testing step (537). Unit tests: 1027 of 1027 (15 new).
Browser check: message explorer (start a session, edit it, create a mapping from one message and from several,
outbound with a device and with a group as the source, reload to resume); Properties step (open it for an
inbound and an outbound mapping; change the topic, the target API, the execution filter, Expert Mode and the
inventory filter; toggle external id; change QoS and confirm its description updates).

### Lint rules (done)

Three rules are on for application code as warnings: `@angular-eslint/prefer-on-push-component-change-detection`,
`@angular-eslint/prefer-signals`, and `@typescript-eslint/no-explicit-any`. They are off for specs and
generated files. `npm run lint` still reports 0 errors. Starting counts: 73 components not `OnPush`, 198
`prefer-signals`, 277 `any`.

Two error-level blocks keep migrated directories from regressing:

- `prefer-on-push-component-change-detection` is an error in the step directories, `substitution`,
  `unified-editor`, `editor-base`, and the three subscription selector directories.
- `no-explicit-any` is an error in `step-subscription`, `step-connector`, `step-template`, `substitution`,
  and `unified-editor`.

A probe file with an `any` in a protected directory failed the lint run, as intended. Promote a directory by
adding it to those lists once it reaches zero.

### `any` typing, first tranche (done)

The explicit `any` count went from 277 to 188 with no behavior change. The work was one cluster, the
"template" values, plus the places that touch them:

- New `shared/mapping/json.model.ts`: `JsonValue`, `JsonObject`, `Template` (a parsed source or target
  template), `JsonSchema`, `JsonEditorOptions`, and the guard `isJsonObject`.
- Typed with them: the editor base's template and schema fields, the template step, the substitution step,
  the testing step, `MappingStepperService` (the evaluate methods, template expansion, `applyTargetAPIChange`,
  the sample target template), `buildTestMapping`, `updateTemplatesInEditors`, the template expansion helpers,
  and `MappingService.evaluateExpression`, which had used the global `JSON` type for parsed data.
- `MappingForAiDrawer` replaces an `any` for the mapping copy handed to the AI drawer.
- Casts remain only at trust boundaries, each with a comment: the editor's parsed content and the backend's
  untyped request and response in the testing step.
- Spec side effect: Jasmine's matchers recurse without end on `JsonValue`, so specs that compared a
  `Template` with `toEqual` cast to `unknown` first. That is a test-typing limit, not a defect in the types.

Remaining by area: `mapping/core` (14), `mapping/service` (some), `client-relation` (12), `message-explorer`
(13), `prompt` (10), `json-editor` (8), `mapping-create` (7), `mapping-tree` (6), `introduction` (8), and a
long tail. `step-testing` and `step-transformation` are close to zero.

### Code template: "Replace code" button (done, not yet browser-checked)

Selecting a code template used to put its code into the editor at once, which overwrote in-progress code
with no way to confirm. A report from the browser also showed the code not being replaced after a
selection. The cause was not found by reading the code: the replace logic and the section's binding both
pass their unit tests, including a test that binds a parent to the `OnPush` section. The behavior is now
explicit: selecting a template only selects it, and a new **Replace code** button next to **Create new
code template** puts the code into the editor. The button is disabled until a template is selected.
Both hosts (editor and stepper) use it.

Root cause of "selecting a template does not replace the code": `c8y-select` writes the whole selected
item (`{ label, value }`) to `ngModel`, not the item's `value`. The host stored that object as
`templateId` and looked the template up by key, so `codeTemplatesDecoded.get(templateId)` never found
anything and the replace did nothing, silently. The `OnPush` change to the code section was not the
cause, which a test that binds a parent to that section confirms. The section now reduces the selection
to its key before it emits `templateSelected`.

Two related defects were fixed on the way:

- `codeEditor` in both hosts was a `@ViewChild('codeEditor')`, but no `#codeEditor` reference exists in any
  template (it was lost when the code block moved into its own component). It was always `undefined`, so
  the explicit editor write after AI generation never ran either. The section now exposes its editor, the
  base class reads it through the section, and replacing code calls `writeCode()` on it, in addition to the
  `code` binding.
- A selected template that could not be found made the button do nothing, with no message. It now raises
  a warning. This is how the root cause showed up.

The button was also renamed to **Create code template** (the "new" is dropped).

To verify in the browser: select a template (the code must stay as it is), then press Replace code (the
editor must show the template code). Also generate code with the AI assistant on a Smart Function
mapping: the generated code must appear in the editor, which also depends on the second fix. If Replace
code still does nothing and no warning appears, the template lookup succeeded and the fault is between
the editor component and Monaco; then the browser console is needed. A warning "The selected code
template was not found" means the lookup failed again.

## Phases

One pull request per component or small group. Each pull request can be reverted alone.

### Phase 0: safety net (before any change)

- Record the baseline: 978 unit tests green, and a green run of the Cypress specs in `cypress/e2e`
  that cover the mapping editor, the grids, and the AI prompt.
- Write a manual check list for the editor (see "Browser check list") and run it once on the current
  build, so a regression can be told apart from existing behavior.
- Add lint rules as warnings: `@angular-eslint/prefer-on-push-component-change-detection`,
  `@angular-eslint/prefer-signals`, and `@typescript-eslint/no-explicit-any`. Warnings give a progress
  counter and block nothing. Record the starting counts (235 `any`, 5 of 91 on `OnPush`).
- Add one test helper that mounts a component with `OnPush` forced through `overrideComponent`.
  Existing specs run in Default mode, so they cannot catch failure mode 1 or 2 by themselves.

### Phase 1: leaf components (low risk)

Components that only display data: `substitution-grid`, `custom-select`, the grid cell renderers
(`mapping-status-activation.renderer`, `connector-status.renderer`), `extension-card`, the doc
components in `src/introduction`, and the shared Formly wrappers not yet on `OnPush`.

Method: run Angular's migration schematics on one directory at a time, then review by hand.

```bash
cd dynamic-mapper-ui
npx ng generate @angular/core:signal-input-migration --path=src/mapping/substitution
npx ng generate @angular/core:output-migration      --path=src/mapping/substitution
npx ng generate @angular/core:signal-queries-migration --path=src/mapping/substitution
```

Replace template method calls in these files with `computed()` values or pre-computed fields.
This phase also proves the pattern before it touches mapping state.

### Phase 2: `MappingStepPropertiesComponent` (first stateful component)

- Make `mapping` a signal input. Derive the execution-filter control with `computed()` from
  `mapping().filterMapping` and drop `knownFilterMapping` and `ngDoCheck` (line 851).
- Keep the control's writes as one event, `propertiesChange`, as today.
- Done when: editing the filter in the Templates step shows up in the Properties step without a tab
  change, and the reverse, with `OnPush` on.

### Phase 3: `MappingUnifiedEditorComponent` (the state owner)

The state work in this phase is done (see Progress). The `OnPush` switch for the editor itself comes after
phase 4; see "Ordering correction".

- Introduce the `mapping` signal and `patchMapping` (see State model).
- Turn `templatesInitialized`, `currentStepIndex`, `mappingCode`, `sourceTemplate`, and
  `targetTemplate` into signals. These are the values the `detectChanges()` calls at lines 215, 564,
  and 566 exist to publish.
- Replace the line 564 and 566 calls with `afterNextRenderInZone`, or with signal-driven `@if` plus
  an `effect` that runs after the step exists.
- Move the `onPropertiesChange`, `onFilterMappingChange`, and `onSubstitutionsChange` handlers onto
  `patchMapping`.
- Type `mapping-stepper.service.ts` (22 `any` uses) and `editor-session.util.ts` here. They define the
  shapes every step receives, so typing them first makes phase 4 mechanical.
- Highest-risk step. Do it alone, with the full browser check list.

### Phase 4: the step components

In this order: `step-template` (15 inputs, many typed `any`), `step-transformation`, `step-subscription`,
`step-testing`, then the older `stepper` and `client-relation-stepper` (already `OnPush`, 3 calls).
For each: signal inputs, `OnPush`, remove its `detectChanges()` calls, and replace `any` inputs with
real types while the file is open. `step-template` and `client-relation-stepper` hold 31 of the `any`
uses between them.

`step-testing` needs extra care. Its `updateTestingTemplate` is a `ReplaySubject<Mapping>`. Replace it
with an input that changes when the mapping changes.

### Phase 5: AI prompt components

`ai-prompt.component.ts` (8 calls) and `shared-code-ai-prompt.component.ts` (4 calls) use
`detectChanges()` after `await` to publish `aiCapabilitiesDisabled`, `aiAvailabilityCheckFailed`,
progress, and results. Make each of those a signal. Most of the 12 calls then disappear with no
replacement.

### Phase 6: grids, lists, and the rest

`mapping.component` (896 lines), `connector-grid`, `mapping-type-drawer`, `message-explorer`,
monitoring, extension, and configuration screens. For these, the bigger win is to read the grid data
through `toSignal()` or the async pipe and stop assigning arrays inside subscriptions. Splits of the largest
classes (`mapping.component`, `ai-prompt`, `mapping-type-drawer`, `message-explorer`) follow the
rules in "Typing and splitting alongside the migration", each in its own pull request.

### Phase 7: enforce

Promote the lint rules to errors for every migrated directory, and for the whole tree once the warning
counts reach zero. Add a size check that warns above 500 lines per component class. Add a rule or a CI grep that rejects new `cdr.detectChanges()`
and `ngDoCheck` outside an allow-list. Update `docs/ui/architecture.md` with the state rule: one
owner, signals, no mutation of inputs.

## Definition of done for each component

1. `changeDetection: ChangeDetectionStrategy.OnPush`.
2. No `cdr.detectChanges()`, no `ngDoCheck`, no assignment to an input object.
2a. No `any` in the file, or each remaining one carries a comment that names the reason.
2b. If the class was over about 500 lines, the split is planned or done in a follow-up pull request.
3. State that changes after `await` or in a subscription is a signal, or arrives through `toSignal()`.
4. Unit spec mounts the component with `OnPush` forced and asserts a re-render after (a) an input
   reference change and (b) an async state change.
5. The component's section of the browser check list passes on a real tenant.
6. `npm test`, `npm run lint`, and `tsc --noEmit` are green. The relevant Cypress spec passes.

## Browser check list (editor)

Run against a tenant with at least one inbound and one outbound mapping:

- Open an existing mapping and a new mapping. The title, connector tab, and settings tab fill in.
- Change the execution filter in Properties, open Templates, and confirm the same expression shows.
  Change it in Templates, return to Properties, and confirm the control follows.
- Edit source and target templates. Add, edit, and remove a substitution. Confirm the grid updates.
- Switch Substitution and Smart Function editors. Confirm the code editor keeps its content.
- Run a test (dry run and live). Confirm the result panel updates, including after a failure.
- Save, then confirm version, last update, and the unsaved marker update without a reload.
- Use the AI prompt with the AI agent on, off, and with the kill switch set.
- Change the app language and confirm labels, including the repair strategy options, follow.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| A screen stops updating with no error | The two failure modes above, an `OnPush` test helper, and the browser check list on each pull request |
| Identity-based dirty checks break once `mapping` is replaced on each edit | Review `captureMappingContentSnapshot` and the stepper service in phase 3 before any other change |
| Formly mutates its model in place | Keep Formly models as plain objects inside the owning component and publish changes through events, as the `OnPush` Formly wrappers already do |
| c8y library components are `Default` strategy | They work inside an `OnPush` host. Test projected content explicitly (tabs, stepper) |
| Typing inputs exposes real mismatches that `any` was hiding | Fix them in the same pull request when small. If one is large, keep the narrow type at the boundary, record it in the pull request, and do not widen it back to `any` |
| A split moves logic and loses its test or its tenant-aware behavior | Split only after the migration of that component has merged, move the spec with the code, and run the browser check list again |
| The migration schematics change more than intended | Run them per directory and review the diff before committing |

## Open questions

1. Which tenant and which Cypress specs count as the gate for phases 3 to 5?
2. Is zoneless a stated goal for a later release? If so, phase 7 should also ban `setTimeout` for
   rendering, and the 12 existing uses would join the list.

Note on counts: the review's "31" counted `detectChanges()` and `markForCheck()` together. Counting
`detectChanges()` alone, there were 29 before phase 3 step 2 and 26 after it.

### `any` conversion, first batch (done, not yet browser-checked)

The 188 `any` warnings are down to 153, and 21 directories are at zero and now fail on `any`: the root `src/*.ts`,
`configuration`, `configuration/code-template`, `extension/{add,grid,properties,share}`,
`mapping/{deprecation-notice,grid,renderer,step-properties,stepper}`, the four `mapping/subscription/*` folders,
`monitoring`, `monitoring/logs`, `shared/component/{formly,renderer}`, and `shared/configuration`. Their lines are
gone from the second "Known debt" block. Tests pass (1027 of 1027), lint reports 0 errors and 354 warnings, and the
AOT check shows the same 8 baseline errors.

What replaced the `any`:
- `cancel` outputs are `EventEmitter<void>` (they never carried a value).
- Track-by functions take `number, unknown`. Renderer `isArray` takes `unknown`.
- `ServiceConfiguration.codeTemplates` is `CodeTemplateMap`. Grid rows are `MappingEnriched`.
- The monitoring navigation factory returns `Promise<NavigatorNode[]>`.
- Upload errors use a local `UploadError` union, the log service reads `res.status` through a typed cast, and the
  JSONata AST walker has a small `JsonataNode` interface.
- `polyfills.ts` writes the zone flags through a `Record<string, unknown>` view of `window`.

Left: 153 `any` in 19 directories, mostly `shared/mapping` (24), `mapping/client-relation` (14), `mapping/core` (14),
`mapping/message-explorer` (11), `mapping/prompt` (10), and `mapping/service` (10).

### `any` conversion, second batch (done, not yet browser-checked)

`any` is down from 153 to 89, and 11 more directories are at zero and now fail on `any`: `introduction`, `mapping-tree`,
`mapping/mapping-create`, `mapping/versions`, `shared/component/json-editor`, `shared/connector-configuration`,
`shared/connector-details`, `shared/misc`, `shared/service`, `test-device`, and `test-device/grid`. The `any` block in
`eslint.config.cjs` now lists only the seven directories that still have findings. Tests pass (1027 of 1027), lint
reports 0 errors and 291 warnings, and the AOT check shows the same 8 baseline errors.

Choices worth knowing:
- `JsonEditorComponent`: `options` is `JsonEditorInputOptions` (a record plus `removeModes`), `schema` is the library's
  `JSONSchema`, and `get<T = unknown>()` replaces `get(): any`.
- Raw events use `IEvent` from `@c8y/client` (`ConnectorStatusEvent.raw`, the log service, `getEventMetadata`).
  Realtime events are narrowed to `IEvent`, because the realtime payload is `IEvent | number`.
- `ConnectorConfiguration.properties`, `status`, and `defaultValue` are `unknown`. Nothing else needed a change.
- `testing-device.service.ts`: the column query has a `ColumnQuery` interface. `getQueryString` was removed: it was
  typed `string` but returned the query object. The reassigned `queryx` after an external filter still discards the
  new object, as before (a column with an external filter loses its own sort; not changed here).
- The four navigation factories return `Promise<NavigatorNode[]>`.

Left: 89 `any` in seven directories: `shared/mapping` (24), `mapping/client-relation` (14), `mapping/core` (14),
`mapping/message-explorer` (11), `mapping/prompt` (10), `mapping/service` (10), and `shared/mapping/processor` (6).
