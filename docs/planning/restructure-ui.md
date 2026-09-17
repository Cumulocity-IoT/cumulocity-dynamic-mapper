# UI Cleanup — dynamic-mapper-ui

**Status:** Step 1 done — the suite is green (447 SUCCESS, 0 FAILED). Step 2 in progress; steps 3–5 not started.
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

## 3. Folder and file naming

| Problem | Examples |
|---------|----------|
| Folder name disagrees with the files inside it | `mapping-tree/` holds `tree.component.ts`, `tree.service.ts`; `stepper-mapping/` holds `mapping-stepper.component.ts`; `mapping-create/` holds `mapping-type-drawer.component.ts` |
| `util.ts` vs `utils.ts` | `util.ts` ×3, `utils.ts` ×2 (`mapping-tree/`, `shared/component/json-editor/`) |
| Singular vs plural siblings | `versions/` among `subscription/`, `substitution/`, `validation/`, `renderer/` |
| Placement | `mapping-tree/` is top-level while every other mapping concern is under `mapping/` — and only `dynamic-mapper.module.ts` registers it, so it does not appear to be shared outside mapping |

Worth keeping: the `step-*` prefix (`step-connector`, `step-property`, `step-template`,
`step-testing`, `step-transformation`) is a good, consistent convention. `stepper-mapping/` is the
one that breaks it.

---

## 4. Test coverage

| | With spec | Total | |
|---|---|---|---|
| Components | 17 | 84 | 20% |
| Services | 4 | 19 | 21% |

Not a defect to fix in one pass — a standing practice to adopt once §1 gives a green baseline.

---

## 5. Already planned elsewhere — do not re-plan

The stepper / unified-editor duplication has its own document:
[IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md](IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md).
Phases 1–5 are implemented; **Phase 6 is outstanding** — folding the commit sequence into one shared
`commitMapping(...)` entry point, which is the phase that makes that plan's §0 class of bug
structurally impossible rather than prevented by review.

That plan's §4 manual regression matrix is still marked **not yet executed**.

Note the relationship to §1: the 26 failing tests are the spec for the component Phases 1–5 just
refactored.

---

## 6. Sequence

1. **Fix the 26-failure spec.** Highest value, lowest risk, establishes a green baseline.
2. **Resolve `shared/mapping` ÷ `mapping/shared`** and rename the constants file. Compiler-verified.
3. **Normalise naming** — `utils.ts` → `util.ts`, align folder names with file prefixes, move
   `mapping-tree/` under `mapping/tree/`. Mechanical.
4. **Phase 6 of the dedup plan.** Needs its own design for the tab-validation-redirect contract.
5. **Coverage**, as practice rather than a sprint.

## 7. Follow-ups found while fixing the tests

Two production asymmetries surfaced during §1. Neither is reachable today, so neither was changed
as part of the test fixes — but both are latent and should be fixed deliberately.

**`onSelectCodeTemplate` does not guard `this.mapping`.**
`mapping/unified-editor/mapping-unified-editor.component.ts` reads
`this.mapping.transformationType` unguarded, while its sibling `updateCodeTemplateEntries` — three
lines away, same class — uses `this.mapping?.transformationType`. One of the two is wrong. Until
this is settled the tests have to construct a mapping to avoid the path rather than the component
defending itself.

**`onCreateCodeTemplate` can throw for non-Smart-Function mappings.**
It calls `toTemplateType(direction, transformationType)`, which has entries only for
`INBOUND_SMART_FUNCTION` / `OUTBOUND_SMART_FUNCTION` and throws otherwise. It is unreachable today
only because the "Create new code template" button renders inside the `showCodeEditorSection`
block. That is an incidental guarantee from a template condition, not an enforced one: any change
that surfaces the button elsewhere turns a mis-selected transformation type into an uncaught
throw. Either guard the call or make the precondition explicit.

---

## 8. Non-goals

- Any change to `Mapping` / `ConnectorConfiguration` models or backend APIs.
- Re-planning the stepper/unified-editor dedup (§5).
- Framework or dependency upgrades.
