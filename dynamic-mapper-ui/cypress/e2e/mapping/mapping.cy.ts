import type { IUser } from '@c8y/client';
import { slowCypressDown } from 'cypress-slow-down';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';

slowCypressDown();

/**
 * Example mapping specs demonstrating the `dm-mapping-*` data-cy hooks added to
 * the mapping templates. They navigate to the (inbound) Mappings page and exercise
 * the toolbar plus the "Add mapping" type drawer via stable, locale-proof selectors
 * (`cy.getByData('dm-...')`) instead of fragile text/title/class selectors.
 *
 * The test user is created with ROLE_DYNAMIC_MAPPER_ADMIN (via the shared connector
 * helpers), which is what gates the mapping management actions.
 */
describe('Mapping — toolbar & add drawer (data-cy example)', () => {
  let testUser: IUser;

  // The mappings page may show a one-off deprecation notice modal that covers the
  // toolbar; dismiss it via its data-cy hook if present.
  const dismissDeprecationNotice = () => {
    cy.get('body').then(($body) => {
      if ($body.find('[data-cy="dm-deprecation-accept"]').length) {
        cy.getByData('dm-deprecation-accept').click();
      }
    });
  };

  // The c8y action bar collapses overflowing buttons into a "more" menu, so a hook
  // can match both an inline and a hidden copy — target the visible one.
  const toolbar = (hook: string) => cy.getByData(hook).filter(':visible').first();

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    cleanupTestUser(testUser);
  });

  beforeEach(() => {
    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();

    // Navigate directly to the inbound mappings route (mirrors the NavigatorNode
    // path in src/shared/misc/navigation.factory.ts). The deprecation-notice modal
    // overlays (and hides) the shell, so we can't wait on a shell element first —
    // wait for the grid to exist in the DOM (it renders behind the modal), then
    // dismiss the modal, which restores the toolbar's visibility.
    cy.visitShellAndWaitForSelector(
      'c8y-pkg-dynamic-mapper/node1/mappings/inbound',
      'en',
      'body',
      60000
    );
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  });

  it('shows the mapping toolbar actions', () => {
    toolbar('dm-mapping-add').should('be.enabled');
    cy.getByData('dm-mapping-add-sample').should('exist');
    cy.getByData('dm-mapping-reload').should('exist');
    cy.getByData('dm-mapping-export').should('exist');
    cy.getByData('dm-mapping-import').should('exist');
    cy.getByData('dm-mapping-reset-cache').should('exist');
    toolbar('dm-mapping-refresh').should('be.visible');

    cy.screenshot('mapping-toolbar');
  });

  it('opens the Add-mapping type drawer and cancels', () => {
    toolbar('dm-mapping-add').click();

    // The type drawer (MappingTypeDrawerComponent) opens; its footer Cancel button
    // carries the dm-mapping-type-cancel hook.
    cy.getByData('dm-mapping-type-cancel').should('be.visible').click();

    // Drawer closed → back on the grid toolbar.
    toolbar('dm-mapping-add').should('be.visible');

    cy.screenshot('mapping-add-drawer-cancelled');
  });
});
