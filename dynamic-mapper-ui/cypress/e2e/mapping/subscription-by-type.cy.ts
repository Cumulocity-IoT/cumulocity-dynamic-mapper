import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import {
  DM_SERVICE,
  getTypeSubscriptionViaApi,
  setTypeSubscriptionViaApi,
} from '../../support/dm-api';
import { leaveApp } from '../../support/dm-ui';

/**
 * Journey: add a device type to the outbound type subscription and remove it again.
 *
 * The type subscription is tenant-wide state, so the spec records the types that were there
 * before, only adds and removes its own "Cypress*" type, and restores the original list in
 * teardown. The result is asserted on the request the UI sends (cy.intercept) and on what the
 * page shows after it reloads the subscription.
 */
describe('Subscription — device types for outbound mappings', () => {
  const SUBSCRIPTION_ROUTE = 'c8y-pkg-dynamic-mapper/node1/mappings/subscription/dynamic';
  const typeName = `CypressType${Date.now()}`;
  let testUser: IUser;
  let originalTypes: string[] | null = null;

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
    getTypeSubscriptionViaApi().then((types) => {
      originalTypes = types;
    });
  });

  after(() => {
    leaveApp();
    if (originalTypes) {
      setTypeSubscriptionViaApi(originalTypes);
    }
    cleanupTestUser(testUser);
  });

  beforeEach(function () {
    // Without outbound mapping the endpoint is not available; there is nothing to test.
    if (originalTypes === null) {
      this.skip();
    }
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    cy.intercept('PUT', `${DM_SERVICE}/subscription/type`).as('putTypes');
    cy.intercept('GET', `${DM_SERVICE}/subscription/type`).as('getTypes');
    cy.visitShellAndWaitForSelector(
      SUBSCRIPTION_ROUTE,
      'en',
      '[data-cy="dm-subscription-manage-by-type"]',
      60000
    );
    cy.wait('@getTypes');
  });

  /** Open the type drawer; the drawer is filled from the types the page loaded. */
  const openTypeDrawer = (): void => {
    // The button stays disabled until the user's roles have been loaded.
    cy.getByData('dm-subscription-manage-by-type')
      .filter(':visible')
      .first()
      .should('be.enabled')
      .click();
    cy.getByData('dm-type-selector-save').should('be.visible');
  };

  /** Row inputs of the open drawer that currently hold the given type. */
  const inputWithValue = (value: string) =>
    cy
      .get('[data-cy^="dm-type-selector-input-"]')
      .filter((_, el) => (el as HTMLInputElement).value === value);

  it('adds a type and removes it again', () => {
    openTypeDrawer();

    // Without types the drawer offers an "add first" button; otherwise the last row carries the
    // add button (c8y-input-group-list shows the plus button on the last row only).
    cy.get('body').then(($body) => {
      if ($body.find('[data-cy="dm-type-selector-add-first"]').length) {
        cy.getByData('dm-type-selector-add-first').click();
      } else {
        cy.get('[data-cy^="dm-type-selector-row-"]').last().find('button.text-primary').click();
      }
    });
    cy.get('[data-cy^="dm-type-selector-input-"]').last().type(typeName);
    cy.getByData('dm-type-selector-save').should('be.enabled').click();

    cy.wait('@putTypes').then(({ request, response }) => {
      expect(request.body.api, 'api').to.eq('ALL');
      expect(request.body.types, 'sent types').to.include(typeName);
      // The existing types travel in the same request, so saving cannot drop them.
      (originalTypes as string[]).forEach((t) => expect(request.body.types).to.include(t));
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });

    // The page reloads the subscription after saving; reopening the drawer shows the new type.
    cy.wait('@getTypes');
    openTypeDrawer();
    inputWithValue(typeName).should('have.length', 1);

    // Remove the row with its minus button. (Clearing the text would leave a blank entry, which
    // the drawer rejects as the only entry; an empty list is the valid "no types" state.)
    inputWithValue(typeName)
      .closest('[data-cy^="dm-type-selector-row-"]')
      .find('button.btn-dot--danger')
      // the "request submitted" toast from the first save overlays the row
      .click({ force: true });
    cy.getByData('dm-type-selector-save').should('be.enabled').click();
    cy.wait('@putTypes').then(({ request, response }) => {
      expect(request.body.types, 'sent types').to.not.include(typeName);
      expect(request.body.types, 'sent types').to.deep.eq(originalTypes);
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
  });
});
