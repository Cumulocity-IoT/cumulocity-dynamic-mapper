// ***********************************************************
// This example support/e2e.ts is processed and
// loaded automatically before your test files.
//
// This is a great place to put global configuration and
// behavior that modifies Cypress.
//
// You can change the location of this file or turn off
// automatically serving support files with the
// 'supportFile' configuration option.
//
// You can read more here:
// https://on.cypress.io/configuration
// ***********************************************************

import 'cumulocity-cypress/commands';
import { registerCommands } from './commands';
import { deleteAllCypressConnectors } from '../e2e/connector/connector.helpers';

registerCommands();

before(() => {
  Cypress.session.clearAllSavedSessions();
});

// Guaranteed cleanup: after every spec file, sweep all "Cypress*" connectors via
// the API (admin auth). This returns the tenant to its original state even if a
// test failed before its afterEach ran, or leaked an untracked connector.
after(() => {
  deleteAllCypressConnectors();
});