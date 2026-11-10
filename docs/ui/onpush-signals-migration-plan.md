# Plan: OnPush and signals for the UI

Status: paused, to be continued later. The editor and everything below it is on `OnPush` and signals, the
`detectChanges()` calls are removed except two on purpose, the lint rules are in, and the first tranche of the
`any` typing is done (commit `d1ff21b24`). See "Status at a glance" for the numbers and "Progress" for each
step. The rest of the plan (the remaining screens, the schematics, the remaining `any`, and promoting more
directories to lint errors) has not been started and will be picked up in a later session.

Open at the pause:

- Not yet checked in a browser: the connector grid and connector step, the older stepper's AI drawer, and the
  validation drawer's "Go to problem" (needs a JSONata mapping). The read-only case of the Subscription tab
  (no Mapping Admin or Create role) was also not tested.
- Still on default change detection: the two AI prompt components, the mapping grid, the message explorer, the
  older stepper, the cell renderers, the connector details page and drawers, and the rest of the 73 components
  the lint rule lists.
- Two `detectChanges()` calls stay on purpose (the AI prompt's `ngOnInit` and the connector drawer's direction
  filter). Do not replace them with a render wait without a browser check; see "Regression: AI conversation not
  restored".

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

Measured on 2026-11-10 against the starting numbers in the review.

| Measure | Start | Now |
|---|---|---|
| Components on `OnPush` (lint count) | 5 of 91 by grep | 20 of 93 (the lint rule finds 93 components; my grep missed two) |
| `detectChanges()` calls | 29 | 2 (two justified synchronous renders, see below) |
| `ngDoCheck` | 1 | 0 |
| Explicit `any` (lint count, non-spec) | 277 (235 by the first grep, which counted fewer patterns) | 188 |
| Unit tests | 978 | 1012 |

| Phase | State |
|---|---|
| 0. Safety net | Lint rules added as warnings (`prefer-on-push`, `prefer-signals`, `no-explicit-any`), and promoted to errors in the directories that are done. Starting counts: 73 not `OnPush`, 198 `prefer-signals`, 277 `any`. |
| 1. Leaf components | Partly done through phase 4 work (code section, grid, extension selector, subscription selectors). Doc components, grid renderers, `custom-select`, and the Formly wrappers are untouched. The migration schematics have not been run. |
| 2. `MappingStepPropertiesComponent` | Done: `ngDoCheck` removed and the step is `OnPush`. |
| 3. `MappingUnifiedEditorComponent` | Done: state in signals, 3 forced renders replaced, and the editor is `OnPush`. The older `MappingStepperComponent` is still on default change detection. |
| 4. Step components | Done: every step and every component below the editor, including the connector step and its grid. |
| 5. AI prompt components | `detectChanges()` calls removed except one that stays (10 of 11). The two prompt components are still on default change detection. |
| 6. Grids, lists, rest | `detectChanges()` calls removed in the connector grid, renderers, details page, and drawers. The connector grid is `OnPush`; the renderers, details page, drawers, mapping grid, message explorer, and the rest are not. |
| 7. Enforce | Not started. |

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
