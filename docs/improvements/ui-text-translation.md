# Translate all UI texts through .po files

Status (2026-11-08): Phases 0, 1, and 3 done. Phase 2 tooling done; the German catalog is empty and waits for a translator. Phase 4 not started. Affects `dynamic-mapper-ui` only.

## Summary

Most UI texts are already marked for translation, but the app has no translation catalog of its own.
Every marked text shows its English source, and about 350 texts are not marked at all. This plan marks the
remaining texts, extracts them into `.po` files with the tooling that is already installed, loads the
catalogs, and adds a check that stops new unmarked texts from entering the code.

English stays the source language. The English text is the message ID, as it is in the Cumulocity platform.

## Current state

| Topic | State |
|---|---|
| Catalogs | None. `src/i18n.ts` imports only the platform files from `@c8y/ngx-components/locales`. |
| Tooling | `extract-i18n` and `compile-i18n` (`@c8y/devkit`) are configured in `angular.json`. `extract-i18n` writes `locales/locales.pot` and merges existing language files. |
| What extraction finds | Only texts marked with the `translate` pipe, the `translate` directive, or `gettext()`. |
| Marked texts | About 875 in templates and 293 `gettext()` calls in 26 files. |
| Dynamic texts with values | One pattern exists in `testing.service.ts`: `translateService.instant(gettext('... {{status}}'), { status })`. |

Numbers of unmarked texts, from a scan on 2026-11-08. The scan is a heuristic, so Phase 0 replaces it with an exact list.

| Kind | Count | Notes |
|---|---|---|
| Static text nodes in templates | about 100 in 24 files | For example the client-relation stepper headings and the "Add substitution" button. |
| Literal `label`, `title`, `placeholder`, `aria-label` attributes | about 50 | |
| Alert messages without `gettext()` | 94 in 29 files | 31 of them contain `${...}` and need placeholders. 56 alerts already use `gettext()`. |
| Label, description, and placeholder literals in Formly and similar configs | about 110 | The custom form wrapper translates `description` and `tooltip`. Whether it translates `label` is not verified. |
| Texts in constants | not counted | `TransformationTypeLabels`, `MappingTypeLabels`, the QoS descriptions, `ValidationFormlyError`, and the mapping type configuration. |

## Scope

In scope:

- All texts that a user sees in the Dynamic Mapper UI: templates, alerts, dialogs, grid headers, form labels, help texts, validation messages, and tooltips.
- Error codes from the service that the UI turns into messages (`translateValidationErrorCode`).

Out of scope:

- Messages that the Java service sends as text. The UI translates its error codes only.
- The Markdown pages in `public/docs`. They are whole documents and need one file per language, not `.po` entries.
- Mapping content written by users, such as names, topics, and code.

## Decisions needed before Phase 2

1. Which languages ship first? Phase 1 does not depend on the answer.
2. Who translates, and who reviews? The first catalog is a machine draft unless a translator is assigned.
3. Does a missing translation fall back to English? The platform does, and this plan assumes the same.

## Conventions

| Case | How to mark it |
|---|---|
| Static text in a template | `<span translate>Text</span>` or `{{ 'Text' \| translate }}` |
| Attribute in a template | `[title]="'Text' \| translate"` |
| Text in TypeScript that is shown later, for example in a constant | `gettext('Text')` where it is defined, and `\| translate` or `translateService.instant()` where it is shown |
| Text with values | `translateService.instant(gettext('Version {{version}} of {{name}}'), { version, name })`. Never build it with `+` or `${}`. |
| Alert | Pass a `gettext()` key. Check in Phase 0 how `AlertService` accepts parameters, and use that. |
| HTML in a help text | Keep the markup in the message and tell translators to keep the tags. |
| Plural | Use the `.po` plural forms. Add them only where a text changes with a count. |

Rules:

- A message ID is the English text. Changing the wording changes the ID and drops its translations, so change wording on purpose and re-run the extraction.
- Do not split a sentence across several messages. Translators need the whole sentence.
- One placeholder name per value, written the same way in every language.

## Plan

### Phase 0: baseline and checks (half a day)

1. Run `ng run dynamic-mapper:extract-i18n` and record the number of message IDs. This is the baseline.
2. Write a script that lists every unmarked text by file and line, so Phase 1 works from an exact list. Keep the script, because Phase 3 reuses it.
3. Verify that the `d11r-wrapper-form-field` wrapper and the `d11r-input` and `d11r-textarea` types translate `label` and `placeholder`. If they do not, fix the wrapper once. That removes most of the 110 Formly literals.
4. Verify how `src/i18n.ts` loads a local `.po` file in this build, and how `AlertService` accepts parameters.

Done when: the baseline count, the exact list, and answers to items 3 and 4 are in this document.

#### Phase 0 results

- **Baseline:** extraction finds 646 message IDs. The app has no `.po` files of its own.
- **Exact list:** `node scripts/find-unmarked-texts.js` finds 579 texts. Smart Function editor help text, instructions for the AI agent, product names, and code-like examples are allowed in `scripts/unmarked-texts.allowlist.json`, each with a reason. That leaves 510 in 99 files. The script reads templates with the Angular parser and TypeScript with the compiler API, and `--check` exits with code 1 when texts are left, for Phase 3.
- **Form labels were not translated.** The form wrapper passed `label` through `formatStringAsWords` only, and placeholders were not translated. A Formly extension (`translate-props.extension.ts`, registered in the shared module) now translates `label`, `placeholder`, `description`, and `tooltip` for every field. A field configuration only needs `gettext('...')`.
- **Loading:** the build has a loader for `.po` imports. Its aggregation plugin groups the files by the `Language` header and writes one `<language>.json` per language, so every `.po` file needs that header. Importing a file in `src/i18n.ts` is the mechanism.
- **Alerts:** `AlertService.success(text, detailedData?)` takes no parameters. Fill the values first with `translateService.instant(gettext('... {{x}}'), { x })`. The app's missing-translation handler fills the placeholders when a text has no translation.
- **What the extractor finds** (tested): the `translate` directive on a leaf element, `[attr]="'text' | translate"`, `[textContent]="'text {{x}}' | translate: {...}"`, and `gettext('...')` in TypeScript. **What it misses:** a pipe inside text interpolation that contains `{{`, `[translate]="'...'"`, and string literals inside conditionals or object literals. Use the forms it finds.
- **Template check:** `npx ngc -p tsconfig.app.json --noEmit` type-checks templates in about 6 seconds. Eight errors existed before this work.
- **Specs:** `provideTranslateStub()` in `src/shared/testing/translate-stub.ts` gives a spec a `TranslateService` that returns each message with its parameters filled in.

### Phase 1: mark the remaining texts (one to two days)

Work in this order, one commit per area, so each change is small to review:

1. Shared components and the form wrapper.
2. Mapping editors: stepper, unified editor, and all step components.
3. Mapping grid, drawers, and the message explorer.
4. Configuration, connectors, extensions, monitoring, and test devices.
5. Constants and validation messages.
6. Alerts that contain values: replace `${...}` with placeholders.

Done when: the Phase 0 script reports no unmarked text, and the existing UI tests pass without changes, because English remains the source.

#### Phase 1 results

- The finder reports no unmarked text. It now also flags `gettext(`...${x}...`)` and assignments such as `this.errorMessage = `...``. Allowlisted: log level names, date formats, JSON field names, CSS, and messages sent to the AI agent.
- Texts with values use `translateService.instant(gettext('... {{x}}'), { x })`. Pure helper functions take a translate function or `TranslateService` as a parameter: `translateValidationErrorCode`, `buildBackendErrorMessage`, `commitSuccessMessage`.
- Display sites now translate: validation messages, QoS labels, mapping type labels and descriptions, polling intervals.
- Known gap: validation errors set by the Formly validators in `validation.util.ts` carry an English `message` and are not translated at the field. Check in Phase 4 whether the form field shows them.
- Error details are still appended to a translated prefix, for example `instant(gettext('Failed to delete relation:')) + error`. That is one message plus a technical detail.

### Phase 2: extract and add catalogs (half a day plus translation time)

1. Run the extraction and commit `locales/locales.pot`.
2. For each language, copy the template to `locales/<language>.po`, set the `Language` header, and translate the `msgstr` entries.
3. Import each file in `src/i18n.ts`, next to the platform files.
4. Check the size of the bundle before and after. The catalogs add to the download of the app.

Done when: switching the user language in Cumulocity shows translated texts on every page, and an untranslated text shows its English source.

#### Phase 2 results

- `extract-i18n` finds 1,072 messages when `dist/` does not exist. It also reads `dist/`, so a local build adds platform messages; delete `dist/` before extracting (CI does not have it). It also writes `locales/<language>.po` for 12 languages with the platform translations merged in, so those files are ignored by git (`locales/.gitignore`). Only `locales/locales.pot` is committed.
- The app must not ship the platform translations again. `scripts/sync-app-catalogs.js` writes `src/locales/<language>.po` with the 955 messages the platform catalog does not contain, and keeps translations that already exist. `--check` exits with code 1 when a catalog is out of date, for Phase 3.
- `src/i18n.ts` imports `src/locales/de.po`. The build produces the app's messages inside `de.json`. An empty `msgstr` is left out, so that text shows English.
- To add a language: add it to `LANGUAGES` in the script, run it, import the file in `src/i18n.ts`.
- Open: the languages, the translator, and the review. German is the only catalog, and it has no translations yet.

### Phase 3: guardrails (half a day)

1. Add a CI step that runs the extraction and fails if `locales.pot` differs from the committed file.
2. Add the Phase 0 script to CI, so a new unmarked text fails the build.
3. Add a short section to `docs/ui/architecture.md` with the conventions above.

Done when: a pull request with a new unmarked text, or a changed `.pot`, fails the check.

#### Phase 3 results

- `npm run i18n:check` runs three checks: the finder (`--check`), the extraction followed by `git diff --exit-code locales/locales.pot`, and `sync-app-catalogs.js --check`. `npm run i18n:extract` regenerates `locales/locales.pot` and `src/locales/*.po`.
- CI runs `i18n:check` in the "UI Lint, Build and Test" job, after the lint and before the build, because the extraction reads `dist/`.
- The check was run locally without `dist/`. It passes on the committed files.
- `docs/ui/architecture.md` has a "Translations" section with the conventions.
- The CI step has not run on GitHub yet. Look at the first pull request that triggers it.

### Phase 4: verify in the app (half a day)

1. Generate a test language `xx.po` that wraps every message in brackets and accents, for example `[Ámóúnt]`. Load it in a development build.
2. Walk through every page. Any text without brackets is unmarked, and any bracketed text that looks broken is a placeholder or markup problem.
3. Repeat the walk-through for one real language with long words, such as German, to find layout problems.

Done when: no unbracketed text remains in the test language, and no layout breaks in the real one.

## Where things stand and what is left

Done and committed on `develop`: `2d9aea2af` (tooling), `d96d1f9ed` (all texts marked), `a4a282583` (display gaps, catalog scaffold), plus the Phase 3 commit.

| Item | Owner | State |
|---|---|---|
| Languages that ship, translator, reviewer | Product | Open. Decision 1 and 2 above. |
| Fill `src/locales/de.po` (954 empty entries) and add other languages | Translator | Open. A machine draft is possible. |
| Phase 4 walk-through with a bracketed test language | Developer | Not started. Do not commit the test language. |
| Formly validator messages (`validation.util.ts`) are English at the field | Developer | Unverified. Check in Phase 4. |
| Sentences split into pieces, for example the `, which provides ...` message and the overview sentence with links | Developer | Known. Rewrite with placeholders if translators need the whole sentence. |
| Texts built as translated prefix plus error detail (`instant(gettext('Failed to ...:')) + error`) | Developer | Accepted: one message plus a technical detail. |
| The doc pages in `public/docs` | Product | Out of scope. One file per language is needed. |
| First CI run of `i18n:check` | Developer | Pending. |

## Risks

| Risk | Mitigation |
|---|---|
| Concatenated texts cannot be translated | Rewrite them with placeholders in Phase 1. The script flags `+` and `${}` next to message text. |
| Reworded English texts orphan translations | Extraction merges existing files. Review the `.po` diff in every pull request that changes a message. |
| Longer translations break layouts, for example in buttons, tabs, and grid headers | Phase 4 step 3. |
| Machine translations contain mistakes | Assign a reviewer per language before release. |
| Tests that assert English texts break | They keep working while English is the source. Only the test language `xx` changes them, so do not load it in tests. |

## Effort

About three to four days of development for Phases 0 to 4, excluding translation and review. Phase 1 is the largest part. It touches many files but changes little in each.

## Definition of done

- The Phase 0 script reports no unmarked text.
- `locales/locales.pot` is committed, and CI keeps it current.
- Every language that ships has a `.po` file that the app loads.
- The test-language walk-through finds no unbracketed text.
- The conventions are documented in `docs/ui/architecture.md`.

## Commands

```bash
# Extract all marked texts into locales/locales.pot (run from dynamic-mapper-ui)
npx ng run dynamic-mapper:extract-i18n

# Compile .po files to JSON, only if a build step needs them
npx ng run dynamic-mapper:compile-i18n -p ./locales/de.po -o ./compiled-locales
```
