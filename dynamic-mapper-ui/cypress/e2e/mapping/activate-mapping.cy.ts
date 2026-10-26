import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import {
  DM_SERVICE,
  createMappingViaApi,
  deleteMappingViaApi,
  getMappingViaApi,
} from '../../support/dm-api';
import { buildInboundEventMapping } from '../../support/dm-fixtures';
import { dismissDeprecationNotice, leaveApp, openInboundMappings } from '../../support/dm-ui';

/**
 * Journey: an inactive mapping is activated and deactivated from the inbound grid.
 * The mapping is created through the REST API; only the activation toggle is driven through
 * the UI. While the UI is open the outcome is asserted on the activation request it sends
 * (cy.intercept); the persisted end state is checked on the backend in teardown.
 */
describe('Mapping — activate and deactivate from the inbound grid', () => {
  let testUser: IUser;
  let mappingId = '';
  let mappingName = '';

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    if (mappingId) {
      // The test deactivated the mapping again: confirm that was persisted, then clean up.
      getMappingViaApi(mappingId).its('body.active').should('eq', false);
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('toggles the activation of a mapping and persists it', () => {
    const payload = buildInboundEventMapping(Date.now());
    mappingName = payload.name;

    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();

    // Filter to our mapping (the grid paginates), then use the per-row hooks.
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(mappingName, { force: true });

    cy.intercept('POST', `${DM_SERVICE}/operation`).as('operation');

    cy.then(() => {
      const toggle = () =>
        cy.get(`[data-cy="dm-mapping-activation-${mappingId}"]`, { timeout: 15000 });
      const expectActivation = (active: boolean) =>
        cy.wait('@operation').then(({ request, response }) => {
          expect(request.body.operation, 'operation').to.eq('ACTIVATE_MAPPING');
          expect(request.body.parameter.id, 'mapping id').to.eq(mappingId);
          expect(String(request.body.parameter.active), 'requested state').to.eq(String(active));
          expect(response?.statusCode, 'status').to.be.within(200, 299);
        });

      toggle().should('exist').and('not.be.checked');

      toggle().click({ force: true });
      expectActivation(true);
      toggle().should('be.checked');

      // deactivate again (an active mapping cannot be deleted in teardown)
      toggle().click({ force: true });
      expectActivation(false);
      toggle().should('not.be.checked');
    });
  });
});
