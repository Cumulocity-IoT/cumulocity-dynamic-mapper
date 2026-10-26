import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import { DM_SERVICE } from '../../support/dm-api';
import {
  dismissDeprecationNotice,
  driveSmartFunctionStepperToTestStep,
  leaveApp,
  openInboundMappings,
  stepperButton,
} from '../../support/dm-ui';

/**
 * Journey: the test step of the mapping stepper. A new Smart Function mapping is taken to the
 * test step, a dry run is executed against the backend (POST /test/mapping, nothing is sent or
 * persisted) and the console output is shown, then the flow is cancelled. No mapping is saved, so there is nothing to clean up.
 */
describe('Mapping — dry run in the stepper test step', () => {
  let testUser: IUser;
  const stamp = Date.now();

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    cleanupTestUser(testUser);
  });

  beforeEach(() => {
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  });

  it('runs a dry run in the test step and leaves without saving', () => {
    cy.intercept('POST', `${DM_SERVICE}/test/mapping`).as('testMapping');
    cy.intercept('POST', `${DM_SERVICE}/mapping`).as('createMapping');

    driveSmartFunctionStepperToTestStep(`cypress-dryrun-${stamp}`, `cydry/${stamp}`);

    // Dry run: the backend executes the transformation and the UI shows its console output.
    // NOTE: with the default code template (template-SMART-INBOUND-04, expects
    // payload.sensorData.temp_val) and the default sample payload the transformation logs a
    // TypeError and yields no request, so this deliberately asserts on the mechanics (backend
    // call + console panel) and not on a produced result. See the plan's findings.
    cy.getByData('dm-mapping-test-transform').should('be.enabled').click();
    cy.wait('@testMapping').its('response.statusCode').should('be.within', 200, 299);
    cy.getByData('dm-mapping-test-console-toggle').should('be.visible');

    // Leave without saving: back on the grid, and no mapping was created.
    stepperButton('dm-mapping-stepper-buttons-test', 'Cancel');
    cy.getByData('dm-mapping-add').filter(':visible').should('exist');
    cy.get('@createMapping.all').should('have.length', 0);
  });
});
