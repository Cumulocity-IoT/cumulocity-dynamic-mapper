# UI Build & Test

The frontend is an Angular plugin built with npm.

```bash
cd dynamic-mapper-ui

npm start          # dev server with live reload
npm run build      # production build
npm run deploy     # deploy to Cumulocity tenant (requires env vars for tenant URL/credentials)
npm test           # unit tests
npm run lint       # lint
```

## End-to-End (Cypress)

E2E specs live under `dynamic-mapper-ui/cypress/e2e/`. UI elements are annotated with `data-cy` hooks namespaced under `dm-`.
