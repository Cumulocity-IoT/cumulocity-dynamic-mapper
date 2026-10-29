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
  openOutboundMappings,
  stepperButton,
} from '../../support/dm-ui';

/**
 * Journey: create an OUTBOUND Smart Function mapping through the stepper on the outbound page.
 *
 * Same flow as the inbound spec (see create-mapping-ui.cy.ts), but the general-settings step asks
 * for a publish topic instead of a mapping topic. The result is asserted on the create request
 * (direction and publish topic) and on the outbound grid listing; the mapping is removed by name
 * in teardown.
 */
describe('Mapping — create an outbound mapping via the UI stepper', () => {
  let testUser: IUser;
  const stamp = Date.now();
  const mappingName = `cypress-ui-outbound-${stamp}`;
  const publishTopic = `cyout/${stamp}`;

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
    openOutboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  });

  it('creates a SMART_FUNCTION outbound mapping through the stepper', () => {
    cy.intercept('POST', `${DM_SERVICE}/mapping`).as('createMapping');

    driveSmartFunctionStepperToTestStep(mappingName, publishTopic, 'OUTBOUND');

    stepperButton('dm-mapping-stepper-buttons-test', 'Confirm');
    cy.wait('@createMapping').then(({ request, response }) => {
      expect(request.body.direction, 'direction').to.eq('OUTBOUND');
      expect(request.body.transformationType, 'transformation type').to.eq('SMART_FUNCTION');
      expect(request.body.publishTopic, 'publish topic').to.eq(publishTopic);
      expect(response?.statusCode, 'status').to.be.oneOf([200, 201]);
    });

    // Listed in the outbound grid. The "mapping created" toast overlays the search box, so
    // interact through it.
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(mappingName, { force: true });
    cy.get('c8y-data-grid').contains(mappingName).should('exist');

    cy.screenshot('mapping-outbound-created');
  });
});
