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
