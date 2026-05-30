import type { IUser } from '@c8y/client';
import { slowCypressDown } from 'cypress-slow-down';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';

slowCypressDown();

const DM = '/service/dynamic-mapper-service';
const adminAuth = () => ({
  user: Cypress.env('C8Y_USERNAME'),
  pass: Cypress.env('C8Y_PASSWORD'),
});

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

  const dismissDeprecationNotice = () => {
    cy.get('body').then(($b) => {
      if ($b.find('[data-cy="dm-deprecation-accept"]').length) {
        cy.getByData('dm-deprecation-accept').click();
      }
    });
  };

  // Pick an option in a c8y-select identified by its data-cy hook.
  const selectC8yOption = (hook: string, optionText: string) => {
    cy.getByData(hook).click();
    cy.get('.dropdown-menu').filter(':visible').contains(optionText).click();
  };

  // The c8y-stepper-buttons host carries the data-cy; click the labelled button inside.
  const stepperButton = (hook: string, label: string) =>
    cy.getByData(hook).find('button').contains(label).click();

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    cy.clearCookies();
    cy.request({ url: `${DM}/mapping`, auth: adminAuth(), failOnStatusCode: false }).then(
      (resp) => {
        const arr = Array.isArray(resp.body) ? resp.body : resp.body?.mappings || [];
        arr
          .filter((m: { name?: string }) => m.name === mappingName)
          .forEach((m: { id: string }) => {
            cy.clearCookies();
            cy.request({
              method: 'POST',
              url: `${DM}/operation`,
              auth: adminAuth(),
              body: { operation: 'ACTIVATE_MAPPING', parameter: { id: m.id, active: 'false' } },
              failOnStatusCode: false,
            });
            cy.clearCookies();
            cy.request({
              method: 'DELETE',
              url: `${DM}/mapping/${m.id}`,
              auth: adminAuth(),
              failOnStatusCode: false,
            });
          });
      }
    );
    cleanupTestUser(testUser);
  });

  beforeEach(() => {
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    cy.visitShellAndWaitForSelector(
      'c8y-pkg-dynamic-mapper/node1/mappings/inbound',
      'en',
      'body',
      60000
    );
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  });

  it('creates a SMART_FUNCTION inbound mapping through the stepper', () => {
    cy.intercept('POST', `${DM}/mapping`).as('createMapping');

    // 1. Open the Add-mapping type drawer.
    cy.getByData('dm-mapping-add').filter(':visible').first().click();

    // 2. Type drawer: expert mode → Smart Function transformation → Continue.
    cy.getByData('dm-mapping-type-expert-mode-toggle').click({ force: true });
    selectC8yOption('dm-mapping-type-transformation-type-select', 'Smart Function');
    // Wait for the code-template select to load + auto-select a template before
    // continuing — otherwise the new mapping has no code and the stepper won't open.
    cy.getByData('dm-mapping-type-code-template-select').should('be.visible');
    cy.getByData('dm-mapping-type-continue').should('be.enabled').click();

    // 3. Stepper step 1 (connector): wait for the stepper to open, select a connector
    //    so Next enables, then Next.
    cy.getByData('dm-mapping-stepper-buttons-connector').should('be.visible');
    // Data-row select checkboxes carry data-cy="c8y-data-grid--checkbox" (the header
    // select-all has none); selecting one enables the step's Next button.
    cy.get('d11r-mapping-connector [data-cy="c8y-data-grid--checkbox"]')
      .first()
      .click({ force: true });
    cy.getByData('dm-mapping-stepper-buttons-connector')
      .find('button')
      .contains('Next')
      .should('not.be.disabled');
    stepperButton('dm-mapping-stepper-buttons-connector', 'Next');

    // 4. Step 2 (general settings): name + topic (Formly id hooks).
    cy.get('#mappingName').clear().type(mappingName);
    cy.get('#mappingTopic').clear().type(mappingTopic);
    // The sample must have the SAME number of topic levels as the mapping topic;
    // our topic has no wildcards, so the sample is identical.
    cy.get('#mappingTopicSample').clear().type(mappingTopic);
    cy.getByData('dm-mapping-stepper-buttons-general')
      .find('button')
      .contains('Next')
      .should('not.be.disabled');
    stepperButton('dm-mapping-stepper-buttons-general', 'Next');

    // 5. Step 3 (templates): accept defaults → Next.
    stepperButton('dm-mapping-stepper-buttons-templates', 'Next');

    // 6. Step 4 (transformation, code editor): default code → Next.
    stepperButton('dm-mapping-stepper-buttons-transformation', 'Next');

    // 7. Step 5 (test): Confirm → save.
    stepperButton('dm-mapping-stepper-buttons-test', 'Confirm');
    cy.wait('@createMapping').its('response.statusCode').should('be.oneOf', [200, 201]);

    // 8. Verify it is listed in the inbound grid (search to beat pagination).
    cy.get('c8y-data-grid input[type="search"]').clear().type(mappingName);
    cy.get('c8y-data-grid').contains(mappingName).should('exist');

    cy.screenshot('mapping-ui-created');
  });
});
