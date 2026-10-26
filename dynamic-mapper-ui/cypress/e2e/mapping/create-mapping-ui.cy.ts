import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import { DM_SERVICE, deleteMappingsByName } from '../../support/dm-api';
import {
  dismissDeprecationNotice,
  driveSmartFunctionStepperToTestStep,
  leaveApp,
  openInboundMappings,
  stepperButton,
} from '../../support/dm-ui';



/**
 * Creates a *very simple* inbound mapping entirely through the UI stepper, using the
 * dm-* data-cy hooks. We use a SMART_FUNCTION (JavaScript) transformation on purpose:
 * a code-based transformation sets showCodeEditor=true, which satisfies the stepper's
 * substitution-validity gate WITHOUT having to define a device-identifier substitution
 * by clicking template-tree nodes (which a JSONata/DEFAULT mapping would require).
 */
describe('Mapping — create a simple mapping via the UI stepper', () => {
  let testUser: IUser;
  const stamp = Date.now();
  const mappingName = `cypress-ui-mapping-${stamp}`;
  const mappingTopic = `cyui/${stamp}`;

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    deleteMappingsByName(mappingName);
    cleanupTestUser(testUser);
  });

  beforeEach(() => {
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  });

  it('creates a SMART_FUNCTION inbound mapping through the stepper', () => {
    cy.intercept('POST', `${DM_SERVICE}/mapping`).as('createMapping');

    // 1-6. Type drawer, connector, general settings, templates and code -> test step.
    driveSmartFunctionStepperToTestStep(mappingName, mappingTopic);

    // 7. Step 5 (test): Confirm → save.
    stepperButton('dm-mapping-stepper-buttons-test', 'Confirm');
    cy.wait('@createMapping').its('response.statusCode').should('be.oneOf', [200, 201]);

    // 8. Verify it is listed in the inbound grid (search to beat pagination).
    // The "mapping created" toast (c8y-alert-outlet) overlays the search box; it is only
    // a transient overlay, so interact through it.
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(mappingName, { force: true });
    cy.get('c8y-data-grid').contains(mappingName).should('exist');

    cy.screenshot('mapping-ui-created');
  });
});
