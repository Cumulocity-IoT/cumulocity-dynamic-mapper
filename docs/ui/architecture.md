# UI Architecture

Angular app under `dynamic-mapper-ui/src/`, built as a Cumulocity web plugin with `@c8y/ngx-components`.

## Folder Structure

| Folder | Purpose |
|--------|---------|
| `mapping/` | Mapping feature: stepper wizard, substitution editor, testing, grid view |
| `connector/` | Connector configuration UI |
| `monitoring/` | Real-time mapper and connector status |
| `configuration/` | Service/tenant configuration |
| `shared/` | Common services, models, API path constants |

## Key Services

- `mapping/service/` — API calls for mapping CRUD and testing
- `connector/` — connector configuration API
- `TestingService` — executes mapping tests via the backend endpoint (`PATH_TESTING_ENDPOINT`); all testing is done server-side

## Mapping Direction

- **Inbound** = Broker → C8Y
- **Outbound** = C8Y → Broker. `filterMapping` is a JSONata expression required on all outbound mappings (default `'true'`).

## State and change detection

Every component uses `ChangeDetectionStrategy.OnPush`. The lint rules enforce it; the history and reasons are in
[onpush-signals-migration-plan.md](onpush-signals-migration-plan.md).

1. **One owner per piece of state.** The mapping editor holds the mapping in one signal and changes it only through
   `patchMapping()` or `updateMapping()` in `MappingEditorBase`. Children get the mapping as an input and report edits
   as events. They never assign to an input or change its object in place.
2. **A template reads signals, observables through the `async` pipe, or inputs.** A plain field that changes after an
   `await`, a timer, a router event, or a subscription is not seen by an `OnPush` view. Use a signal, or the
   signal-backed accessor pattern (a private `xState = signal(...)` with a getter and setter of the old name) when
   other code already assigns the field.
3. **Replace, do not mutate.** Assign a new array or object (`this.items = [...this.items, item]`) instead of
   `push()` or `splice()` on a value a template reads. This includes arrays assigned after an `await` in `ngOnInit`.
4. **`markForCheck()` is the fallback**, for state that cannot be a signal, such as a value from a library callback.
5. **No forced renders and no `ngDoCheck`.** `cdr.detectChanges()` and `ngDoCheck` are lint errors. Two exceptions
   exist, each with an `eslint-disable` and a reason: the AI prompt's `ngOnInit` and the connector drawer's direction
   filter. Do not replace them without a browser check.
6. **Wait for a render with `whenRendered()`** (from `MappingEditorBase`) or `afterNextRenderInZone()`
   (`shared/misc/after-render.ts`), not with `setTimeout`.
7. **Types, not `any`.** Use `Template`, `JsonSchema`, `JsonObject` and `isJsonObject` from `shared/mapping/json.model.ts`
   for template and schema data.
8. **Size.** A component class over 500 lines gets a warning. Split it along the seams of its state, one pull request
   at a time.

`ai-prompt` is the one component left on default change detection, on purpose. See the plan before changing it.

### Lint rules

`eslint.config.cjs`: `prefer-on-push-component-change-detection`, `prefer-signals`, and `no-explicit-any` are errors
for all app code except specs. `no-explicit-any` has no exceptions left. The directories that still have
`prefer-signals` findings are listed in the "Known debt" block, as warnings. When a directory reaches zero, delete its
line so that it stays at zero.

## Translations

Every text the user sees must be marked, so that `extract-i18n` finds it. English is the source language and the message ID.

| Case | How to mark it |
|------|----------------|
| Static text in a template | `<span translate>Text</span>` |
| Attribute | `[title]="'Text' \| translate"` |
| Text in TypeScript, for example in a constant | `gettext('Text')` where it is defined; translate it where it is shown |
| Text with values | `translateService.instant(gettext('Version {{version}} of {{name}}'), { version, name })`. Never use `+` or `${}` inside `gettext()`. |
| Form field label, placeholder, description, tooltip | `gettext('Text')`; a Formly extension translates it |

Do not split a sentence across messages. Details, tooling, and the process for new languages: [ui-text-translation plan](../improvements/ui-text-translation.md).

Checks: `npm run i18n:check` (also in CI) fails on an unmarked text, an outdated `locales/locales.pot`, or an outdated `src/locales/*.po`. After changing a text, run `npm run i18n:extract` and commit both files.
