# Integrate outbound subscription management into the stepper and unified editor

Status: implemented 2026-11-09, reviewed 2026-11-10 (see [Review fixes](#review-fixes-2026-11-10)). See [Implementation notes](#implementation-notes) for where the code differs from this plan.

## Goal

Today a user can only manage outbound subscriptions in two places:

- The **Subscription outbound** page, with the tabs *static* and *dynamic* (`OutboundSubscription_static.png`, `OutboundSubscription_dynamic.png`).
- A small choice drawer that opens only from the **Message Explorer** when a mapping is created from an outbound message (`OutboundSubscription_drawer.png`, `SubscriptionChoiceDrawerComponent`).

Both are far from where the user defines an outbound mapping. This plan adds a **Subscription** step to the stepper and a **Subscription** tab to the unified editor, directly after *General settings* and before *Templates*, and removes the Message Explorer drawer.

## Decisions (confirmed 2026-11-09)

| Topic | Decision |
|-------|----------|
| When the subscription is written | On mapping save. The step only collects the selection. Cancel leaves no side effects. |
| Options in the step | **Skip for now**, **Dynamic**, **Static**. |
| Visibility | Every outbound mapping, new or existing. Inbound mappings never see the step. |
| Old UI | Delete `SubscriptionChoiceDrawerComponent` and its call in the Message Explorer. The Subscription outbound page stays (overview, resync, bulk management). |
| Existing devices of a type | The Dynamic mode has a checkbox per new type in the type list ("Also subscribe existing devices"), default off. On save it calls the resync endpoint for the ticked types. The standalone *Resync existing devices* action stays on the page only. |
| Removing subscriptions | Allowed in the step, as on the page. Removals show in the pending summary and are applied on save. |
| Explorer device context | Included in this work. The Message Explorer passes the sampled device's type and groups to the step as one-click suggestions. |
| Untouched selection | Nothing is written and no alert is shown. |

## Target UX

New step label: **Subscription** (tab icon `subscription`). Position: index 2 for outbound.

Stepper order (outbound): Connector, General settings, **Subscription**, Templates, Transformation, Testing.
Inbound is unchanged.

Content of the step:

1. Intro text, reused from the drawer: "Outbound mappings require a device subscription so Cumulocity pushes events to the broker."
2. Radio list with three options.

   | Option | Behavior |
   |--------|----------|
   | Skip for now (default) | No change on save. Text: "Continue without a subscription. Add one later from the Subscription outbound page." |
   | Dynamic | Two nested radio options, as on the **dynamic** page: *Manage subscriptions (by device type)* shows `d11r-type-selector` inline, *Manage subscriptions (by device groups)* shows `d11r-group-selector` inline. Only the selected option is expanded; choosing Dynamic selects the first. Each has the help popover of the page. |
   | Static | Two options, as on the **static** page: *Manage subscriptions (device selection)* is a radio option that shows `d11r-device-selector-tree` inline. *Add subscriptions (device table)* is a radio option too, but the table needs too much room for the page: selecting it opens `d11r-device-selector-table` in a drawer. The option stays selected after the drawer closes (Save or Cancel) and shows an **Open device table** link to reopen it. |

3. Under the *by device type* option, the type list has a second column, **Also subscribe existing devices**: a checkbox in the row of each type that the server does not have yet (existing types show "subscribed already"). A short hint explains that a type rule only subscribes devices created afterward. The ticked types are kept in `OutboundSubscriptionSelection.resyncTypes`; on save, only added types that are ticked are resynced (`resyncTypeSubscription`), one after another.
4. Each option shows a one-line summary ("n selected" or "None") and a "not saved yet" label while its list differs from the loaded one. The lists themselves are the inline editors: they show what is subscribed on the tenant, not something specific to this mapping or device.
5. The info text from the page: "Subscriptions are processed asynchronously. Verify the result on the Subscription outbound page or in Service Events."

The mode radio only chooses which actions and summary are shown. It never deletes subscriptions of the other mode, because both kinds can coexist on a tenant (`validateSubscriptionOutbound` already treats them as additive).

The four selectors are existing bottom-drawer components with the inputs `deviceList` or `typeList` and the outputs `commit` and `cancel`. The step reuses them. Three get an `inline` input (see "Selectors in the step" below); the device table is still used as a drawer.

## Design

### 1. New step component

New standalone component `d11r-mapping-step-subscription` in `src/mapping/step-subscription/`, following the naming of the other steps (`step-connector`, `step-properties`, `step-template`).

- Input: `selection: OutboundSubscriptionSelection` (see model below).
- Output: `selectionChange`.
- Hosts one drawer, for the device table, with the same `drawerOpen` / `bottom-drawer` markup as `mapping-subscription.component.html`. The other three selectors are embedded inline.
- Loads the current subscriptions when it first renders (see "Shared loading" below).
- Respects roles: without the Mapping Admin or Mapping Create role the buttons are disabled and the inline selectors are dimmed and not clickable (`.selector-readonly`), same `feature?.userHasMapping...` check as the page.

### 2. Selection model

New file `src/mapping/step-subscription/outbound-subscription-selection.model.ts`:

```ts
export type SubscriptionMode = 'skip' | 'dynamic' | 'static';

export interface OutboundSubscriptionSelection {
  mode: SubscriptionMode;
  // null = untouched, so nothing is written for that list on save
  types: string[] | null;          // dynamic, by device type
  groups: Device[] | null;         // dynamic, by device group
  devices: Device[] | null;        // static, tree or table
  resyncTypes: string[];           // dynamic only, added types to resync, default []
}
```

The state lives on the host (stepper or unified editor), not in the step. The unified editor destroys tab components on every tab switch, so the step must be able to rebuild itself from the host state. The stepper keeps the step alive, but uses the same host-owned state so both editors behave the same.

### 3. Commit flow

Selector `commit` events only update the pending selection. Nothing is sent to the backend at that point.

On save, in `MappingCommitService.commitMapping` (shared by both editors), after the mapping itself was saved successfully:

1. If `direction !== OUTBOUND` or `mode === 'skip'`, do nothing.
2. For each non-null list, call the same service methods the page uses:
   - `updateSubscriptionByDeviceType({ api: API.ALL.name, types })`
   - `updateSubscriptionByDeviceGroup({ api: API.ALL.name, devices: groups })`
   - `updateSubscriptionDevice({ api: API.ALL.name, devices }, STATIC_DEVICE_SUBSCRIPTION)`
3. If a subscription call fails, the mapping stays saved. Show a warning ("The mapping was saved, but updating the subscriptions failed: ...") and keep the editor open so the user can retry. This mirrors the existing post-save handling in `mapping-unified-editor.component.ts` around line 402.
4. For each type that is in `resyncTypes`, is in the saved list, and was not in the loaded list, call `resyncTypeSubscription(type)`. Run it after the type subscription was written, and run the types one after another, because each call rescans the inventory in a background job. The confirmation dialog from the page is not repeated, because the ticked checkbox is the explicit opt-in and its hint names the cost. A failure shows a warning and does not undo the saved subscriptions.
5. Show the existing info alert "Subscription request submitted. Subscriptions are processed asynchronously."

Order matters: the mapping is saved first, so a rejected mapping never creates subscriptions. The selector components submit complete lists (they receive the current list as input and return the edited list), so the calls are replace-style, same as on the Subscription outbound page. The drawer being removed used merge-style writes only because it started from a single device.

Keep the existing `validateSubscriptionOutbound` check after save. It still catches "no subscription at all" and now stays silent in the common case, because users have just been through the step.

### 4. Shared loading

The page loads subscribed devices, types, and groups in `MappingSubscriptionComponent` (`loadAllSubscribedDevicesForDrawer`, `loadSubscriptionByDeviceType`, `loadSubscriptionByDeviceGroup`). The step needs the same data. Extract these loaders into `SubscriptionService` (or a small `SubscriptionSelectionLoader` next to it) and make both the page and the step call them. Do not copy the code. Check that the page's behavior and its spec (`mapping-subscription.component.spec.ts`) are unchanged.

### 5. Step indices

`stepper.model.ts` defines fixed indices (`STEP_SELECT_CONNECTOR = 0` ... `STEP_TEST_MAPPING = 4`). A step that exists only for outbound breaks fixed indices, because a conditional `cdk-step` shifts every later index for outbound but not for inbound.

Uses that depend on indices:

- `mapping-stepper.component.ts` (`handleStepChange` switch, `onNextStep`, `onBackStep`, `STEP_DEFINE_SUBSTITUTIONS`, `isBeforeSubstitutionStep`)
- `mapping-unified-editor.component.ts` (`TAB_*` constants, `TAB_FOR_BLOCKER`, `isTabVisible`)
- `stepper-configuration.strategy.ts`: `JAVA_EXTENSION_PROPERTIES.advanceFromStepToEndStep: 2`, which means "advance from Templates to the end"
- `mapping-stepper.service.ts` (comments say it coordinates two numbering schemes)
- specs: `mapping-stepper.component.spec.ts`, `mapping-unified-editor.component.spec.ts`

Recommended approach: replace the constants with a direction-aware layout.

```ts
export type StepId = 'connector' | 'general' | 'subscription' | 'templates' | 'transformation' | 'testing';

export function stepLayout(direction: Direction): readonly StepId[] {
  return direction === Direction.OUTBOUND
    ? ['connector', 'general', 'subscription', 'templates', 'transformation', 'testing']
    : ['connector', 'general', 'templates', 'transformation', 'testing'];
}
```

Each editor computes the layout once from `stepperConfiguration.direction` and converts between step ids and indices in one place. `advanceFromStepToEndStep` becomes an id (`advanceFromStep?: StepId`, set to `'templates'` for Java extensions). Do this as the first implementation step, as a behavior-neutral refactor with the existing specs green, before adding the new step. A smaller alternative is to keep numeric constants and bump `advanceFromStepToEndStep` to 3 for outbound, but that leaves two numbering schemes that can drift apart, which the `mapping-stepper.service.ts` comments already warn about.

### 6. Unified editor

- Add a tab `<c8y-tab [icon]="'subscription'" [label]="'Subscription' | translate" [priority]="35" ...>` between *General settings* (priority 40) and *Templates* (priority 30), with `data-cy="dm-unified-editor-tab-subscription"`.
- `isTabVisible('subscription')` returns false for inbound.
- Add the content block with `@if (currentStepIndex === ... )` like the other tabs.
- Add `CommitBlocker`-independent behavior only: this step never blocks Save, because skipping is valid.

### 7. Stepper

- Add `<cdk-step [label]="'Subscription' | translate">` wrapped in `@if (stepperConfiguration.direction === Direction.OUTBOUND)`, between *General settings* and *Select templates*, with `c8y-stepper-buttons` (`data-cy="dm-mapping-stepper-buttons-subscription"`, Next and Back).
- Confirm the CDK stepper handles a conditionally rendered step in `linear` mode. If it does not, render the step for both directions and mark it `[completed]="true"` and `[optional]` for inbound, or build the steps with `@for` over the layout. Verify this first with a small spike, because it decides the structure of the template.

### 8. Message Explorer cleanup

Delete:

- `src/mapping/message-explorer/subscription-choice-drawer.component.ts`
- the import and the "Step 2 (OUTBOUND only)" block in `message-explorer.component.ts` (about lines 31 and 619 to 659 of the current file)
- the `locales.pot` and `de.po` entries that only the drawer used (the strings "Skip for now" and "By device type" move to the new step, so reuse the same msgids to keep the German translation)

Keep the convenience the drawer gave: it knew the sampled device's type and groups. Pass this to the new step through the existing `ExplorerMappingHandoffService` (new optional fields `sourceDeviceId`, `sourceDeviceType`, `sourceDeviceGroups`). The step shows them as suggested options: "Add type c8y_SensorPhone" and "Add group Phones", one click each, which fill the pending selection and switch the mode to Dynamic. The sampled device itself is offered as a Static suggestion. Suggestions disappear once they are part of the pending selection or the current subscriptions. This lookup uses the same `inventoryService.detail(..., { withParents: true })` code that exists in the drawer block, so move it into the handoff instead of deleting it.

### 9. Documentation and translations

- `docs/feature/outbound-subscriptions.md` and `docs/feature/roles-and-permissions.md` mention the drawer. Update them to describe the step.
- User guide pages in `public/docs` that describe creating an outbound mapping: add the Subscription step, and replace the screenshots.
- All new strings use `gettext` and go into `locales.pot` and `de.po` (see `docs/improvements/ui-text-translation.md`).

## Implementation order

1. **Spike** (small): confirm conditional `cdk-step` behavior in the linear c8y stepper (section 7).
2. **Refactor**: introduce `StepId` and `stepLayout`, convert stepper, unified editor, strategy, and service. No visible change. Existing specs stay green.
3. **Shared loading**: extract the subscription loaders from the page into `SubscriptionService`. The page spec stays green.
4. **Step component** with model, drawers, summary, and its spec.
5. **Wire into unified editor**, then **stepper**.
6. **Commit flow** in `MappingCommitService` plus its spec (skip, each list, partial failure).
7. **Message Explorer**: handoff of device context, remove the drawer.
8. **Docs, translations, screenshots**.

Steps 2 and 3 are independent. Steps 5 to 7 depend on 4.

## Tests

- New: `mapping-step-subscription.component.spec.ts` (mode switch shows the right actions, selector `commit` updates the pending selection only, role gating, summary). Use the `overrideComponent` pattern from the memory note on c8y standalone components.
- Update: `mapping-stepper.component.spec.ts` and `mapping-unified-editor.component.spec.ts` for the new indices and the outbound-only step.
- Update: `mapping-commit.service.spec.ts` for the three save paths, removal of entries, the resync of only the newly added types, and the failure path. `mapping-publish.service` and the grid call `validateSubscriptionOutbound` directly, so check that their specs still pass.
- Message Explorer: the spec for `onCreateMappingFromMessage` must no longer expect a drawer for outbound.
- Cypress: no e2e file references the `dm-subscription-choice-*` ids today (searched the repo). Add `dm-unified-editor-tab-subscription` and `dm-mapping-stepper-buttons-subscription` for later e2e tests.
- Remember that a UI test failure is probably real: the suite was green on 2026-10-26.

## Risks

| Risk | Mitigation |
|------|-----------|
| Index shift breaks Java extension mappings that skip to the end | Symbolic `advanceFromStep`, plus a spec that covers an outbound Java extension mapping. |
| Partial failure on save: mapping saved, subscription not | Warning with retry path, mapping is not rolled back. Documented in the step text. |
| A type rule only affects devices created later | The resync checkbox in the Dynamic mode, default off, with the hint text. |
| Two screens edit the same tenant-wide data | The step loads fresh data each time it opens. Pending changes are replace-style, so a concurrent change on the page is overwritten. Compare the loaded list with the current list just before writing and show a warning if they differ. |
| Duplicated drawer markup in page and step | Extract the loaders (section 4). Consider a shared `d11r-subscription-drawers` component if the markup keeps growing. |

## Open questions

None. The questions raised in the first draft were answered on 2026-11-09 and are recorded in the Decisions table.

## Implementation notes

Implemented with these differences (the Explorer context mode is one of them, see Review fixes):

- **Step layout.** `StepLayout` in `stepper.model.ts` replaces the fixed positions. The `STEP_*` constants stay as the base numbering that `advanceFromStepToEndStep` uses, and `StepLayout.fromBase` maps it to the real position. This avoided changing the configuration strategy and its specs.
- **Stepper keeps no failed save open.** The stepper creates a new mapping, so a second Save would create a duplicate. A failed subscription write there shows the warning and closes as before. The unified editor stays open, because saving again only retries.
- **Baseline.** The step stores the loaded subscriptions in the selection (`baseline`). Editing is disabled until they are loaded, because the writes replace the whole list and a failed load must not wipe it. A baseline with `available: false` means outbound mappings are disabled on the service.
- **Concurrent change.** Before writing, `SubscriptionSelectionService.apply` reloads the current lists and warns if they differ from the baseline. The selection is still written.
- **Message Explorer.** The device context travels in `ExplorerMappingHandoff.subscriptionSuggestions` and `StepperConfiguration.subscriptionSuggestions`. The drawer `SubscriptionChoiceDrawerComponent` is deleted.
- **Translations.** The catalogs were edited by hand. Running `npm run i18n:extract` on this machine rewrites thousands of platform lines in `locales/locales.pot`, so its output was discarded.
- **Not done.** Screenshots in the user guide are not replaced. Cypress tests for the new step are not written (the test ids exist).

## Selectors in the step (2026-11-09)

- **Inline mode.** `d11r-type-selector`, `d11r-group-selector`, and `d11r-device-selector-tree` have an `inline` input. Inline, they drop the card header and the Save and Cancel buttons and report every edit (`typesChange` / `selectionChange`). The step writes each edit into its selection, which is saved with the mapping. The Subscription outbound page keeps using all four as drawers.
- **Type list.** Inline, the type selector owns its rows after the first value, so a row the user has just added and not yet typed is not wiped when the host echoes back only the non-blank types. Blank rows are never emitted.
- **Device table stays a drawer.** `d11r-device-selector-table` showed a full device grid, which pushed the page content out of view. It opens when its radio option under Static is selected, with its own Save and Cancel; the option then shows an **Open device table** link to open it again. Its `inline` input remains in the component but the step does not use it.
- **Only the selected option is expanded.** The options of the chosen mode are radio options (`expanded` in the step component); selecting one collapses the other. Switching only changes the view: an edit made in the option left is kept and saved.
- **Menu icon.** The Subscription outbound menu entry uses the `subscription` icon, the same as the step and tab (it was `mail`).
- **Not done.** The Cypress tests that used the former button test ids of the step are not updated (none existed for the step); the user guide screenshots still show the old layout.

## Review fixes (2026-11-10)

- **Explorer context mode differs from the plan.** From the Message Explorer the step shows only a radio list for the sampled device (skip, its type, one of its groups, this device) and hides Dynamic and Static. The earlier "suggestions on top of the full options" design was not built. Context mode now also offers "Also subscribe existing devices" for a new type, because a type rule alone never subscribes the sampled device.
- **Mode no longer gates writes.** Every list the user edited is written, except in Skip mode. Switching between Dynamic and Static keeps edits.
- **Retry after partial failure.** `SubscriptionSelectionService.apply` records each written list in the selection's `baseline`, so a retry writes and resyncs only what is left and raises no false "changed elsewhere" warning.
- **Fresh baseline.** The step reloads the subscriptions each time it opens, unless the user already edited a list (the edits stay diffed against their original baseline).
- **Removals** show as "n removed" next to each list; the type, group and device lists all do this.
- **Role gating** adds `inert` to the inline selectors, so keyboard input is blocked as well.
- **Deployment failure** now warns that the subscriptions were not written.
- **Open.** The page and the step still have separate loader code (plan step 3 was not done). The concurrency check still warns after the fact instead of merging. User guide screenshots are still missing. Cypress: `subscription-step.cy.ts` covers skip, type plus resync, and the editor tab for both directions. Right after a save the "no subscription" check cannot fire: the writes are synchronous and the check only runs on activation.
