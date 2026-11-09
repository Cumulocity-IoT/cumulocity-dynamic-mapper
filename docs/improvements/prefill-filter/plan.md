# Plan: prefill and structured editing of the execution filter

Status: implemented (uncommitted). Deviation: the Explorer hand-off is unchanged; `buildNewMapping` reads `type` from the captured payload itself.
Source of the request: annotated screenshot in this folder (`SpecifyFilter_annotated.png`).

## Goal

1. Add a three-option filter control to the General Settings step/tab of the mapping stepper and the unified editor:
   1. **Filter by type**: free text, the user enters a type. Result: `type = '<VALUE>'`, written to **Filter execution mapping** (`mapping.filterMapping`) in the Templates step.
   2. **Filter by fragment**: free text, the user enters a fragment name. Result: `$exists(<VALUE>)`, written to `filterMapping`.
   3. **Custom filter**: no helper text field. Sets `filterMapping` to an empty value, so the user types an expression in the Templates step.
2. When the stepper is opened from the Message Explorer ("Create mapping"), the control is prefilled with option 1 and a type taken from the captured message.
3. When an existing mapping opens in the unified editor, `filterMapping` is translated back to the matching option and value.

## What exists today

| Piece | Location |
|-------|----------|
| General Settings form (`filterInventory` field, `Source API`, external id) | `src/mapping/step-properties/mapping-step-properties.component.ts` (field at ~l.305) |
| `filterMapping` Formly field and its validation | `src/mapping/service/editor-session.forms.ts` (`buildFilterFormlyFields`), `src/mapping/step-template/mapping-template-step.component.ts` |
| `Mapping.filterMapping` / `filterInventory` | `src/shared/mapping/mapping.model.ts` l.133-136 |
| Explorer hand-off, single-shot | `src/mapping/core/explorer-mapping-handoff.service.ts`, built in `message-explorer.component.ts` (~l.700), consumed in `src/mapping/grid/mapping.component.ts` (~l.236) |
| Device type and groups of the source device (outbound only) | `SubscriptionSuggestions` in `outbound-subscription-selection.model.ts`, passed via `stepperConfiguration.subscriptionSuggestions` |
| New-mapping defaults (`filterMapping: 'true'` for outbound) | `src/mapping/grid/new-mapping.factory.ts` l.162, 191, 330 |
| Unified editor hosts the same steps | `src/mapping/unified-editor/mapping-unified-editor.component.ts` / `.html` |

## Design

### 1. Pure translation helper (new, unit-tested first)

New file `src/mapping/step-properties/execution-filter.model.ts`, no Angular dependencies:

```ts
export type ExecutionFilterMode = 'type' | 'fragment' | 'custom';
export interface ExecutionFilterChoice { mode: ExecutionFilterMode; value?: string }

export function toFilterMapping(choice: ExecutionFilterChoice): string;       // choice -> expression
export function fromFilterMapping(expr: string | undefined): ExecutionFilterChoice; // expression -> choice
```

Forward rules:
- `type`, value `lora` gives `type = 'lora'`. Single quotes inside the value are escaped (`\'`), and the value is trimmed.
- `fragment`, value `c8y_Temperature` gives `$exists(c8y_Temperature)`. Names that are not plain JSONata identifiers (for example containing `-` or `.`) are wrapped in backticks.
- `custom` gives `''` (empty), as specified.
- Empty value in mode `type` or `fragment` gives `''` and a validation error on the control (required for outbound).

Reverse rules, anchored regexes on the trimmed expression:
- `^type\s*=\s*'([^']*)'$` (also accept `"` quotes) gives mode `type`.
- `^\$exists\(\s*([A-Za-z0-9_`.-]+)\s*\)$` gives mode `fragment`.
- Anything else, including empty, `true`, and compound expressions such as `type='a' and $exists(b)`, gives mode `custom`, and the raw expression stays untouched in `filterMapping`.

Round-trip property test: `fromFilterMapping(toFilterMapping(c))` equals `c` for modes `type` and `fragment`; unknown expressions are never rewritten.

### 2. UI control in General Settings

- Add a radio group or select (three options) plus one conditional text input, as a second column behind the Source API dropdown, in `mapping-step-properties.component.ts`. Hide the text input for option 3.
- The control is a view over `mapping.filterMapping`: on change it calls `toFilterMapping` and emits through the existing `filterMappingChange` path (`MappingEditorBase.onFilterMappingChange`), so the Templates step shows the generated expression and keeps its own evaluation and validation (`updateFilterExpressionResult`).
- The control is not persisted: no new field on `Mapping`. The mode is always derived from `filterMapping`, so saved mappings need no migration and the backend is untouched.
- The Templates step stays the source of truth: if the user edits `filterMapping` there, the control re-derives its state via `fromFilterMapping` (a manual edit that no longer matches shows "Custom filter").
- Respect `isFieldDisabled` and the existing role checks, as `buildFilterFormlyFields` does.
- Add the i18n keys by hand to `locales/locales.pot` and `src/locales/de.po` (do not run `i18n:extract`; it rewrites about 6.9k platform lines, see project memory).

### 3. Explorer prefill

- Extend `ExplorerMappingHandoff` with `executionFilter?: ExecutionFilterChoice`, set in `message-explorer.component.ts` next to `subscriptionSuggestions`.
- `mapping.component.ts` forwards it (like `explorerPreFill`) into `buildNewMapping`, which sets `mapping.filterMapping = toFilterMapping({ mode: 'type', value })`. Because the control derives from `filterMapping`, no extra stepper wiring is needed.
- If no type can be determined, keep today's default (`'true'` for outbound) and show option 3.
- The hand-off stays single-shot (`consume()`), so back-navigation does not replay it.

### 4. Reverse translation in the unified editor

- On load, `mapping-unified-editor.component.ts` already evaluates `mapping.filterMapping` (~l.306). The control initializes itself from `fromFilterMapping(mapping.filterMapping)` at the same point. The stepper does the same, so both hosts behave alike.
- Reverse translation runs only on open and on external changes to `filterMapping`, not on every keystroke inside the control (avoids a feedback loop while the user types a half-finished value).

### 5. Tests

- `execution-filter.model.spec.ts`: forward, reverse, quoting, round trip, non-matching expressions.
- `mapping-step-properties.component.spec.ts`: option switch writes `filterMapping`; option 3 clears it; reverse init for an existing mapping.
- `new-mapping.factory.spec.ts`: explorer prefill sets `type = '<value>'`.
- `mapping-unified-editor.component.spec.ts`: a stored `$exists(x)` opens as option 2 with value `x`.
- Remember the `shared/mapping` import-cycle gotcha: import the `../index` barrel first in new specs.

### 6. Docs

- Update `public/docs/define-mapping.md` (General Settings) and the filter documentation page, with the partial-delta caveat below.

## Risks

- **Partial Notification 2.0 delta.** `filterMapping` is evaluated against the raw notification payload. For INVENTORY updates only changed fragments arrive, so `type = 'x'` or `$exists(x)` evaluates to `false` for deltas that do not include it, and the mapping is skipped without an error (WIKA case). For measurement, event, and alarm creation the payload is complete and the filter is safe. The existing `filterInventory` is evaluated against the full cached managed object and does not have this problem. Accepted under decision 2.
- **Required filter on outbound.** The `filterMapping` field is `required` for OUTBOUND (`editor-session.forms.ts`), and new outbound mappings default to `'true'`. Option 3 setting an empty value would leave the mapping invalid until the user types something. Accepted under decision 3.
- **Hidden state.** Because the mode is derived, hand-written expressions that happen to match a pattern switch to option 1 or 2 on reopen. This is intended, but worth a line in the docs.

## Decisions (answered 2026-11-09)

1. **Prefill type**: the `type` field of the captured message payload (not the source device type).
2. **Target field**: `filterMapping` only, for all Source APIs. The partial-delta risk above stays documented; no `filterInventory` routing.
3. **Option 3**: sets `filterMapping` to an empty value. For outbound the mapping stays invalid until the user enters an expression in the Templates step.
4. **Placement**: the new control is a second column behind the Source API dropdown. The existing Filter inventory field is unchanged.

## Confirmed

- **Direction**: outbound only. Inbound Explorer sessions are not prefilled.
- **Placement**: the control is a second column in the Source API row, right of the Source API dropdown (`col-lg-6` next to the existing `col-lg-6`). It is hidden for inbound mappings. The "Use external id" row below stays as it is.
- **No `type` in the payload**: fall back to option 3 with the existing default (`'true'` for new outbound mappings), and show no error.

## Implementation order

1. Helper + unit tests .
2. Control in General Settings, wired to `filterMapping`.
3. Reverse initialization in stepper and unified editor.
4. Explorer hand-off and `buildNewMapping`.
5. i18n, docs, full UI test run (`npm test`; the suite was 725/725 green on 2026-10-26).

## Update: the control is required

- The execution filter is mandatory on the General Settings step. A new outbound mapping starts with nothing selected and an empty `filterMapping` (the former `'true'` default is gone for outbound). The user picks an option and, for type or fragment, enters the value; the step cannot be left until then.
- Explorer prefill without a payload `type` leaves the control unselected (this replaces the earlier fallback to option 3 with `'true'`).
- Custom filter shows the current expression read-only next to the dropdown; it is edited in the Templates step.
