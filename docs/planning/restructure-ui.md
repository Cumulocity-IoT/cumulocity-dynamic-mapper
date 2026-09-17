# UI Cleanup — dynamic-mapper-ui

**Status:** All steps done; 489 SUCCESS, 0 FAILED.
**Scope:** `dynamic-mapper-ui` only. No backend or API changes.
**Companion to:** [restructure-java-package.md](restructure-java-package.md) (the backend equivalent).

The codebase is 193 TypeScript files / 44k lines across `src/`. This plan is ordered by
value-to-risk, not by size.

---

## 0. What is already healthy

Worth stating, because it rules out whole categories of cleanup and narrows where to spend effort:

- **No orphaned components.** All 84 `*.component.ts` classes are referenced somewhere.
- **No dead exports** in the four largest shared files — 47, 46, 46 and 8 exports, every one used.
- **File suffixes are consistent**: `.component.ts` (84), `.service.ts` (19), `.model.ts` (11),
  `.module.ts` (10), `.factory.ts` (10), plus `.pipe.ts`, `.guard.ts`, `.constants.ts`.

This is not a decaying codebase. The problems are structural naming and test health.

---

## 1. The 31 test failures were six problems, all in tests ✅ Done

`npm test` reported **31 FAILED / 416 SUCCESS**; it now reports **447 SUCCESS, 0 FAILED**.
Production reachability was checked before each fix; none was a product bug.

| Count | Problem | Fix |
|-------|---------|-----|
| 26 | `MappingUnifiedEditorComponent` injects `SubscriptionService`, whose constructor takes a `FetchClient`. The TestBed mocked eleven dependencies but not that one, so every test failed at construction (`NG0201`). | Added the missing spy. |
| 2 | The same spec's `onSelectCodeTemplate` tests never set `component.mapping` while asserting on `component.mapping.transformationType`. Masked until construction worked. | Used the existing `buildMapping()` fixture. |
| 2 | `createCodeTemplateAndRefresh` passed `TransformationType.DEFAULT`. Code templates exist only for Smart Functions — `toTemplateType` throws otherwise, correctly. | Fixture switched to `SMART_FUNCTION`. |
| 1 | The compaction fixture nested `_TOPIC_LEVEL_` inside a child object; `reduceSourceTemplate` strips those tokens at the **top level** by design. | Tokens moved to the top level, assertion made positive. |
| 1 | The protected-field test never set `showSourceMetadata`, which the check is deliberately gated on (hidden metadata is absent from the editor, so its absence is not an edit). | Test now enables it. |
| 1 | The `isSubstitutionValid$` -> `templateForm.setErrors` test lived on the component, but that subscription **moved into `MappingStepperService.buildTemplateForm()`** during dedup Phases 3-4. The component spec mocks that service, so it could never pass again. | Moved to `mapping-stepper.service.spec.ts`, where `initializeEditorSession` returns the real form. |

The initial diagnosis — that this was the known `CoreModule` / `overrideComponent` issue — was
wrong: that workaround was already present in the spec.

> **Lesson for Phase 6** (§5): the last row is refactoring fallout. Behaviour moved from component
> to service without its test, and the failure then sat unnoticed among thirty others. Phase 6
> relocates more behaviour the same way — move the tests with it.

---

## 2. `shared/mapping/` vs `mapping/shared/`

Two mirror-image directories, **both containing `mapping.model.ts` and `util.ts`**:

| File | `shared/mapping/` | `mapping/shared/` |
|------|-------------------|-------------------|
| `mapping.model.ts` | 927 lines, 46 exports | 202 lines, 8 exports |
| `util.ts` | 445 lines, 47 exports | 998 lines, 46 exports |
| imported by | 20 files | 7 files |

The exports do **not** clash, so nothing is broken. The cost is navigational: two identical
filenames in near-identical paths, and an import line that looks right either way.

The names are also wrong. `shared/mapping/util.ts` holds **constants** — `BASE_URL`, `AGENT_ID`,
`MAPPING_FRAGMENT`, `COLOR_HIGHLIGHTED`. `mapping/shared/util.ts` holds **helpers** —
`base64ToBytes`, `buildTestMapping`, `buildBackendErrorMessage`.

**Resolved:** the split is accidental, not an intentional cross-feature / within-feature
distinction. The two directories are therefore **merged**, and the constants file is renamed to say
what it holds — the `.constants.ts` suffix is already used elsewhere in the codebase.

---

## 3. Folder and file naming ✅ Done

| Fixed | |
|-------|---|
| `utils.ts` -> `util.ts` | `mapping-tree/`, `shared/component/json-editor/` — the repo already used `util.ts` in three other places |
| `mapping-tree/mapping-tree.factory.ts` -> `tree.factory.ts` | its five siblings are all `tree.*`; this one file used a different prefix |
| `mapping/stepper-mapping/` -> `mapping/stepper/` | the folder reversed its own file's words (`stepper-mapping` vs `mapping-stepper.component.ts`) and carried a redundant `-mapping` suffix inside `mapping/` |

### Corrected: `mapping-tree/` stays top-level

An earlier draft of this plan called `mapping-tree/` misplaced and proposed moving it under
`mapping/`. **That was wrong.** Every top-level directory is an independently-registered Angular
feature module, each with its own `*.module.ts` wired into `dynamic-mapper.module.ts`:

```
configuration/  connector/  extension/  introduction/
mapping/  mapping-tree/  monitoring/  test-device/
```

`mapping-tree/tree.module.ts` is registered alongside the other seven. Moving it under `mapping/`
would break that invariant, not restore one. Only its internal file prefix was inconsistent.

### Deliberately not changed

- **`mapping/mapping-create/`** — carries a redundant `mapping-` prefix inside `mapping/`, so by the
  dominant convention it would be `create/`. Left alone: the folder names the *flow* (creating a
  mapping) while its component names the *widget* (`mapping-type-drawer`), and `create/` on its own
  is vaguer than what it replaces. A judgment call, not an oversight.
- **`versions/` being plural** among `validation/`, `subscription/`, `substitution/` — it genuinely
  holds several version-related components. Renaming would be churn.
- **Module filenames not matching their folders** (`configuration/service-configuration.module.ts`,
  `connector/connector-configuration.module.ts`, `introduction/doc.module.ts`,
  `test-device/testing.module.ts`). A real inconsistency, but renaming registered NgModules for
  cosmetics is a poor trade.

Worth keeping as-is: the `step-*` prefix (`step-connector`, `step-property`, `step-template`,
`step-testing`, `step-transformation`) is a good, consistent convention.

---

## 4. Test coverage — targeted, not bulk

| | With spec | Total | |
|---|---|---|---|
| Components | 17 | 84 | 20% |
| Services | 4 | 19 | 21% |

Writing specs for the other 67 components would be bulk with little return. The targeted version
is `MappingStepperService`: 939 lines, and now the single home for logic extracted from **both**
editors by dedup Phases 1-5 — so a defect there shows up in two places at once, and Phase 6 will
move the save path there too.

It had 13 of its 27 public methods under a `describe`. Added coverage for the three untested ones
carrying real branching (447 -> 461 tests):

- **`updateSubstitutionValidity`** (a Phase 2 extraction) — drives `isSubstitutionValid$`, which
  `buildTemplateForm()` mirrors into `templateForm.setErrors`, so a wrong answer silently blocks or
  unblocks saving in both editors. Each of the five OR-clauses is now pinned: INBOUND needs exactly
  one device identifier, OUTBOUND one or more, and `showCodeEditor` / `allowNoDefinedIdentifier` /
  `isBeforeSubstitutionStep` each short-circuit the count.
- **`selectExtensionName`** — chooses which extension entries the event dropdown offers, from
  `transformationType` first and the mapping's own `extensionType` as fallback. Getting it wrong
  offers events that cannot work.
- **`loadCodeTemplates`** — in particular that one template with an undecodable body does not cost
  the user the rest of the list.

Still untested and deliberately left: thin delegating wrappers (`refreshSubstitutionValidity`,
`notifyMappingPropertyChanged`, `expandTemplates`), and `registerCompletionProvider` /
`cleanup`, which are Monaco and teardown plumbing better covered by the editor's own specs.

---

## 5. Already planned elsewhere — do not re-plan

The stepper / unified-editor duplication has its own document:
[IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md](IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md).
Phases 1–6 are implemented. Phase 6 folded the commit sequence into one shared
`commitMapping(...)` entry point, which is what makes that plan's §0 class of bug structurally
impossible rather than prevented by review.

That plan's §4 manual regression matrix is still marked **not yet executed** — it is the
outstanding risk on this work, not a missing code change.

Note the relationship to §1: the 26 failing tests are the spec for the component Phases 1–5 just
refactored.

---

## 6. Sequence

1. ~~**Fix the failing specs.**~~ Done — see §1.
2. ~~**Resolve `shared/mapping` ÷ `mapping/shared`.**~~ Done — merged, see §2.
3. ~~**Normalise naming**~~ — done, see §3. `mapping-tree/` was *not* moved; see the correction there.
4. ~~**Phase 6 of the dedup plan.**~~ Done — see
   [IMPLEMENTATION-PLAN-COMMIT-MAPPING.md](IMPLEMENTATION-PLAN-COMMIT-MAPPING.md). One
   `MappingStepperService.commitMapping()` now owns the save sequence for both editors; the one
   behavioural change is that an UPDATE which touched no connectors no longer rewrites the
   deployment. That plan's §4 manual regression matrix is **still not executed**.
5. ~~**Coverage.**~~ Targeted pass done — see §4.

## 7. Follow-ups found while fixing the tests ✅ Done

Two production asymmetries surfaced during §1. Investigating each changed what the right fix was.

**`this.mapping?.` was dead syntax, not a missing guard.**
The apparent asymmetry — `onSelectCodeTemplate` reading `this.mapping.transformationType`
unguarded while `updateCodeTemplateEntries` used `this.mapping?.` — resolves the other way round
from how it reads. `mapping` is declared `mapping!: Mapping` (non-nullable) in both editors, the
template dereferences `{{mapping.name}}` on its first line, and both `computeCodeFromTemplate` and
`computeCodeTemplateEntries` declare `transformationType: TransformationType` as required. So the
`?.` can never short-circuit under the declared types: it implied a nullability the code rules out.
Removed in both editors so the precondition is stated once and consistently.

Left alone: `this.mapping?.extension?.extensionName`, where the meaningful guard is the second
`?.` — `extension` genuinely is optional.

**`onCreateCodeTemplate` no longer throws out of an event handler.**
It called `toTemplateType(...)`, which throws for every transformation type except Smart Function.
That was unreachable only because the "Create new code template" button renders inside
`showCodeEditorSection` — and that flag comes from `StepperConfiguration.showCodeEditor`, two hops
from the transformation type. An incidental guarantee, not an enforced one.

Added `tryToTemplateType()` in `configuration/shared/configuration.model.ts` — a non-throwing
companion returning `TemplateType | undefined`, with `toTemplateType()` now implemented on top of
it and kept for call sites where an unsupported combination really is a programming error. Both
editors' `onCreateCodeTemplate` use the safe variant and warn the user instead of crashing.

---

## 8. Non-goals

- Any change to `Mapping` / `ConnectorConfiguration` models or backend APIs.
- Re-planning the stepper/unified-editor dedup (§5).
- Framework or dependency upgrades.
