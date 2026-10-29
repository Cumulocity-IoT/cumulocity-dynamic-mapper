# Cypress end-to-end tests

Browser tests for the Dynamic Mapper UI. They run against `ng serve` on
`http://localhost:4200`, which proxies to a real Cumulocity tenant, so they need a tenant
with the `dynamic-mapper-service` microservice deployed and subscribed.

## Prerequisites

1. `dynamic-mapper-ui/.env` with `C8Y_TENANT`, `C8Y_BASEURL`, `C8Y_USERNAME`, `C8Y_PASSWORD`
   (an admin user) and `C8Y_CYPRESS_URL=http://localhost:4200`. Do not `source` this file in a
   shell if the password contains `$`; Cypress reads it with dotenv.
2. A running dev server: `npm start` (in `dynamic-mapper-ui`).
3. If the specs fail with "redirected to /apps/public/login/index.html more than 20 times",
   serve the app under a throwaway application key (see "Local dev login loop" below).

## Run

```bash
npm run e2e:open                       # interactive
npm run e2e:run                        # headless, all specs
npm run e2e:run -- --spec cypress/e2e/connector/add-connector.cy.ts
```

The scripts unset `ELECTRON_RUN_AS_NODE`, which some IDE terminals set and which breaks Cypress.

## Local dev login loop

Locally, `ng serve` with the real `contextPath`/`key` can get stuck in a 401/login redirect loop.
Workaround, verified: temporarily append `-dev` to `contextPath` and `key` in
`cumulocity.config.ts`, restart `ng serve`, and run Cypress with the matching shell target:

```bash
CYPRESS_C8Y_SHELL_TARGET=c8y-pkg-dynamic-mapper-dev npm run e2e:run
```

Never commit or deploy that change; hide it locally with
`git update-index --skip-worktree cumulocity.config.ts` and undo it with `--no-skip-worktree`
plus `git checkout cumulocity.config.ts`.

## Conventions

- Select elements with `cy.getByData('dm-...')` (`data-cy` hooks, naming `dm-<area>-<element>[-<id>]`).
- Set up and clean up test data through the REST API; use the UI only for the flow under test.
- Specs create resources named `Cypress*` (connectors, types) or `cypress*` (mappings, users);
  `support/e2e.ts` sweeps leftovers after each spec.
- Wait on `cy.intercept` aliases, not fixed delays.

## Cleanup job

`npm run e2e:cleanup` removes everything a crashed or cancelled run left on the tenant: `cypress*`
mappings (deactivated first), `Cypress*` connectors, `Cypress*` device types in the outbound type
subscription (other types stay) and `cypress-*` users. It uses REST with the credentials from `.env`,
needs no browser or dev server, and exits 1 if a deletion fails. Add `-- --dry-run` to only list what
it would delete. In CI, run it as a final step with `if: always()`.
