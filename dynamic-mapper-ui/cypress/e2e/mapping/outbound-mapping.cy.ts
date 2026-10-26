import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import { createMappingViaApi, deleteMappingViaApi } from '../../support/dm-api';
import { buildOutboundEventMapping } from '../../support/dm-fixtures';
import { dismissDeprecationNotice, leaveApp, openOutboundMappings } from '../../support/dm-ui';

/**
 * Journey: an outbound mapping created through the REST API is listed in the OUTBOUND grid, and
 * not in the inbound grid. Only the listing is driven through the UI.
 */
describe('Mapping — outbound mapping is listed in the outbound grid', () => {
  let testUser: IUser;
  let mappingId = '';

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    if (mappingId) {
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('shows the mapping in the outbound grid', () => {
    const payload = buildOutboundEventMapping(Date.now());
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    openOutboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();

    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(payload.name, { force: true });
    cy.then(() => {
      cy.get(`[data-cy="dm-mapping-id-${mappingId}"]`, { timeout: 15000 })
        .should('exist')
        .and('contain', payload.name);
      // It was created inactive.
      cy.get(`[data-cy="dm-mapping-activation-${mappingId}"]`).should('not.be.checked');
    });

    cy.screenshot('mapping-outbound-listed');
  });
});
