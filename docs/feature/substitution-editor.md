# Substitution editor

The part of the Transformation step where a user defines substitutions: which source value
is written to which target node. This page records the redesign from three stacked areas
(buttons, collapsed "Expert Mode" panel, table) to one master-detail panel built on the Codex split view (`c8y-sv`).

## Requirements

1. **One working area.** Below the two template trees, a single "Substitutions" panel shows the
   list of substitutions and an editor side by side, in the platform split view. The divider can be
   dragged. When the panel gets narrow, the panes stack and a back button returns to the list.
2. **The editor is always visible.** No "Expert Mode" toggle hides the source and target expression
   fields. A beginner who only clicks tree nodes never has to write an expression.
3. **The mode is detected, not chosen.** A label beside the source preview reads "Path" or
   "JSONata expression" depending on the text. The user cannot set it by hand and it is never stored.
4. **One primary action.** With no row selected the button reads "Add substitution". With a row
   selected it reads "Save change". "New substitution" and "Cancel" leave edit mode.
5. **Inline previews.** Under each expression field one line shows the evaluated type and value.
   Long results scroll inside the line instead of growing the page.
6. **Expand as array sits next to the source.** When the source returns an array and the option is
   off, the editor asks whether to create one request per element, right below the source field.
   Outbound mappings cannot expand, so the option stays disabled for them.
7. **Expand as array and repair strategy are edited in the editor**, saved together with the
   paths. Rows in the list show them as labels. They are no longer edited inline in the list.
8. **Row labels.** Each row shows source and target, plus labels for "Device identifier"
   (substitution defines the identity), "JSONata expression", "Expand as array", and a repair
   strategy other than the default.
9. **Selecting a row highlights its source and target nodes in both trees** (unchanged behavior).
10. **Deleting a row still asks for confirmation** (unchanged behavior).

Deliberate non-goals:

- No "Duplicate" action. Two substitutions with the same target are collapsed by the service,
  so a duplicate would replace its own original.
- No per-row health icon. Telling whether a row resolves needs an evaluation of every row against
  both templates on every change. Revisit if users ask for it.
- No change to what is persisted: a substitution still holds `pathSource`, `pathTarget`,
  `repairStrategy`, `expandArray`.
- Mappings that use Smart Functions or code-based substitutions keep their own editor.

## Implementation

| Concern | Where |
|---|---|
| Layout, editor, previews, mode label | `dynamic-mapper-ui/src/mapping/step-transformation/mapping-substitution-step.component.{html,ts}` and `substitution-editor.style.css` |
| Split view | `c8y-sv` with `c8y-sv-list` (list, New and AI buttons) and `c8y-sv-details` (editor, Save and Cancel in `c8y-sv-footer`) |
| Row list | `dynamic-mapper-ui/src/mapping/substitution/substitution-grid.component.{html,ts}` (selector unchanged) |
| Repair strategy descriptions, array-only set | `dynamic-mapper-ui/src/mapping/substitution/repair-strategy.ts` (moved out of the grid, shared with the editor) |
| Update keeps `expandArray` / `repairStrategy` from the form | `substitution-management.service.ts`, `updateSubstitution` |

Choices and traps:

- The split view shows the details only while something is selected, and the editor must also show for a
  new substitution. So "new" is the selection `-1`: `selectedSubstitution` stays the source of truth and
  `syncSplitSelection()` mirrors it into the split view's selection service. The back button of the
  stacked layout clears that selection and shows the list.
- The panel has a fixed height (`.d11r-sub-panel`): the split view fills its container.
- The step keeps its Formly fields, validators, and debounced evaluation. Only the arrangement changed.
- `showExpandArrayHint` is kept: it now decides whether the array question is shown as a prompt.
- `isExpression()` decides the mode label, for the editor and for the row labels.
- Add used to force `expandArray = false` and `DEFAULT`. It now takes both from the form and
  resets them after the add, so the next new substitution starts clean.
- `updateSubstitution` used to keep the stored `expandArray` and `repairStrategy`. With the
  inline controls gone, it takes them from the form, and falls back to the stored value when the
  form holds none.
- Choosing an array-only repair strategy and enabling Expand as array together is invalid, so
  enabling Expand as array resets such a strategy to the default (same rule the grid had).
- Removed: `toggleExpertMode`, `expertMode`, `onGridSubstitutionPatch`, `patchSubstitution`, the
  grid's inline controls, and the `dm-mapping-transform-expert-toggle`, `dm-mapping-transform-add-substitution`,
  `dm-mapping-transform-update-substitution` test ids (replaced by `dm-mapping-transform-save-substitution`
  and `dm-mapping-transform-new-substitution`).
- New texts are added to `locales/locales.pot` by hand: `i18n:extract` rewrites the platform lines.
