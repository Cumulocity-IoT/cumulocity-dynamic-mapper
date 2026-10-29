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
import { leaveApp } from './dm-ui';
import {
  deleteAllCypressMappings,
  deleteAllCypressUsers,
  removeCypressTypeSubscriptions,
} from './dm-api';
import { deleteAllCypressConnectors } from '../e2e/connector/connector.helpers';

registerCommands();

// "ResizeObserver loop completed with undelivered notifications" is a harmless browser warning
// raised by resizable editors (the code/JSON editors); it must not fail a test. Any other
// uncaught application error still does.
Cypress.on('uncaught:exception', (err) => !err.message.includes('ResizeObserver loop'));

before(() => {
  Cypress.session.clearAllSavedSessions();
});

// Guaranteed cleanup: after every spec file, sweep all "Cypress*" connectors, "cypress*"
// mappings, "Cypress*" device types in the type subscription and "cypress-*" test users via the API (admin auth). `npm run e2e:cleanup` does the
// same standalone, for crashed runs. This returns the tenant to its original state even if a
// test failed before its afterEach ran, or leaked an untracked connector.
after(() => {
  leaveApp();
  deleteAllCypressMappings();
  removeCypressTypeSubscriptions();
  deleteAllCypressConnectors();
  deleteAllCypressUsers();
});