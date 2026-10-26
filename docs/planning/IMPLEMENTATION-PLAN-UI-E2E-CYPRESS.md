# Implementation Plan: UI end-to-end tests (Cypress)

Status: in progress (steps 1–2 started 2026-10-26)

## Goal

A small, reliable Cypress smoke suite that exercises the key UI journeys of the
Dynamic Mapper against a real tenant, runnable locally and in CI. Cypress is
**not** the place for exhaustive form or logic coverage; that belongs in the
Karma unit specs and the backend integration tests in `resources/testing`.

## Starting point (audited 2026-10-26)

- Cypress 14.5.4 + `cumulocity-cypress` already set up in `dynamic-mapper-ui/`
  (`cypress.config.ts`, `cypress/support`, `cypress/e2e`).
- 9 specs, ~1,350 lines: connector add/edit/delete/toggle/status, mapping
  create (UI stepper and inbound JSONata), configuration.
- Tests run against `ng serve` on `localhost:4200`, which proxies to a real
  tenant configured in `.env` (`C8Y_BASEURL`, `C8Y_USERNAME`, ...).
- Test data cleanup: `after()` hook in `support/e2e.ts` sweeps `Cypress*`
  connectors through the REST API.
- `data-cy` hooks already exist (naming scheme `dm-<area>-<element>[-<id>]`,
  213 occurrences), concentrated in mapping (36 files), shared (8),
  configuration (4), monitoring (3), connector (1).
- Components with **no** hook yet: extension (4), mapping-tree, test-device,
  mapping versions modals, message-explorer drawer, step-connector,
  connector-details, monitoring (5), formly input types, confirmation modal,
  JSON editor, code-editor drawer, manage-template.
- CI (`.github/workflows/ci.yml`) runs backend tests only; no UI or Cypress job.
- Karma baseline: 31 of 443 unit specs fail on clean `develop` (separate work).

## Strategy

| Layer | Tool | Scope |
|---|---|---|
| Logic, pure functions, small components | Karma/Jasmine | Fix the 31 red specs first; a red baseline hides regressions |
| UI journeys | Cypress (10–15 tests) | Connector lifecycle, create inbound and outbound mapping, test/debug step, deploy, role visibility |
| Does the mapping really transform? | Backend integration tests / REST via `c8yclient` | Assert on Cumulocity data, not UI toasts |

Principles:

1. **Setup and teardown through the REST API**, UI only for the flow under test.
2. **Selectors only via `data-cy`** (`cy.getByData`). No text, class or id
   selectors in new specs; migrate the existing `#name`, `#mqttHost`, ...
   selectors as the forms get hooks.
3. **One generic MQTT connector** is enough for UI tests; do not enumerate every
   connector type.
4. **Monaco/JSON editors** are filled through the editor API, not keystrokes.
5. **No fixed waits**: `cy.intercept` aliases; drop `commandDelay` in CI and use
   `retries: { runMode: 2 }`.
6. CI runs Cypress nightly and on release branches, not on every push.

## Work items

| # | Item | Effort | Status |
|---|---|---|---|
| 1 | Audit the existing 9 specs, run them green locally | 1–2 d | done (2026-10-26) |
| 2 | Fill the `data-cy` gaps in the main components | 1–2 d | in progress |
| 3 | Page objects and API helpers for setup/teardown | 2 d | done as helper modules (2026-10-26); full page objects deferred until a spec needs them |
| 4 | New journey specs (outbound, Smart Function editor, deployment, subscriptions, test/debug) | 4–6 d | open |
| 5 | CI job: dedicated tenant, secrets, MQTT broker container, artifacts on failure | 2–3 d | open |
| 6 | Stabilisation and flake hunting | 2–3 d | open |

Total about 2.5–3.5 weeks for one person. Risk is concentrated in item 5
(tenant provisioning and secrets), not in writing specs. Without a dedicated
tenant, the suite stays local-only.

## Item 1: audit and green baseline

Steps:

1. Run `npm run e2e:run` against the local `ng serve` and record pass/fail per spec.
2. Classify each failure: stale selector, timing, environment (tenant/backend
   state), genuine regression.
3. Fix stale and timing issues; document environment prerequisites in
   `dynamic-mapper-ui/cypress/README.md`.

Result log: see "Progress log" below.

## Item 2: `data-cy` gaps

Naming: `dm-<area>-<element>` with a `-<id>` suffix for rows. Prefer
`[attr.data-cy]` for dynamic values. Add hooks only where a planned journey
needs them (priority order):

1. Connector form fields (replace `#name`, `#mqttHost`, `#mqttPort`, `#user`,
   `#password`, `#clientId`, `#connectorType`).
2. Mapping stepper: connector step, JSON editor, code editor drawer, test step.
3. Deployment and subscription (message explorer drawer).
4. Version modals (publish version, versions count).
5. Confirmation modal (generic confirm/cancel buttons).
6. Extension and monitoring pages (only when a journey covers them).

Each new hook gets used by a spec in the same change, or it is not added.

## Progress log

- 2026-10-26: plan written; baseline run started.
- 2026-10-26: baseline run 1: all 9 specs failed at config load. `import dotenv from 'dotenv'`
  is `undefined` under Cypress' ts-node (CommonJS); fixed with a named import.
- 2026-10-26: baseline run 2: all 9 specs failed with `Cypress.expose is not a function`.
  `cypress-slow-down` 1.4.0 / `cypress-plugin-config` 2.0.0 need Cypress 15, the repo has 14.5.4.
  Removed the dependency, the `slowCypressDown()` calls and `commandDelay` (debug aid only; the
  plan drops it for CI anyway).
- 2026-10-26: baseline run 3: specs now execute, but all 9 fail in `beforeEach` with
  "redirected to /apps/public/login/index.html more than 20 times". Same symptom as the known
  local-dev app-key 401 loop (memory: local-dev-serve-app-key-401-gotcha). Not yet resolved;
  next step is to run against an `ng serve` started with the throwaway `-dev` contextPath/key,
  or to confirm whether the per-app-key lockout has expired. Stop retrying against the tenant
  until then, since each failed run adds more 401s on that key.
- 2026-10-26: tenant check (ck4): 49 applications, none named "Dynamic Mapper"; the only match is
  the microservice `dynamic-mapper-service`. So `createUser(..., ['Dynamic Mapper'])` in
  `connector.helpers.ts` grants access to an app that does not exist. This alone does not explain
  the login loop (the `-dev` workaround also uses a non-existent app and works), so the
  per-app-key lockout theory is still open. Next: apply the `-dev` contextPath/key workaround
  and make the specs read the context path from an env var.
  Note: `.env` password contains `$`; do not `source` it in a shell, parse it (dotenv is fine).
- 2026-10-26: `-dev` experiment: temporary local edit of `contextPath`/`key` in
  `dynamic-mapper-ui/cumulocity.config.ts`, hidden with `git update-index --skip-worktree`.
  MUST be reverted (`--no-skip-worktree` + `git checkout`) before committing or deploying.
  Specs need no change: app routes are hardcoded hash paths; only `CYPRESS_C8Y_SHELL_TARGET`
  must be set to `c8y-pkg-dynamic-mapper-dev`.
- 2026-10-26: `-dev` experiment worked: login loop gone, app loads. Remaining failures were
  real test issues, fixed:
  - `connector.helpers.ts`: wait for `@getConnectorSpecifications` BEFORE clicking Add. The drawer
    builds its connector-type options once from the grid's loaded list, so an early open showed
    "No items". (UI follow-up worth considering: disable Add until specifications are loaded.)
  - `create-mapping-ui.cy.ts`: the "mapping created" toast overlays the grid search box; use
    `force: true` for clear/type.
- 2026-10-26: **Item 1 done: 9 of 9 specs, 16 tests green** against ck4 (full run 15/16, the
  last failure fixed and re-run green on its own). Added `cypress/README.md` with prerequisites.
  Cypress spec files lint clean. Open: the `-dev` workaround is still a manual local step;
  CI (item 5) needs a clean tenant where the loop does not occur.
- 2026-10-26: item 3: extracted `cypress/support/dm-api.ts` (admin auth, create/get/delete mapping
  via REST) and `cypress/support/dm-ui.ts` (deprecation modal, inbound route, c8y-select,
  stepper buttons, toolbar). The three mapping specs shrank by ~110 lines; 6 tests re-verified green.
  Decision: no page-object classes yet; plain helper functions are enough until a journey needs state.
