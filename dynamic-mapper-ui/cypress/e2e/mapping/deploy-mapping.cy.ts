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
  getDefinedDeploymentViaApi,
} from '../../support/dm-api';
import { buildInboundEventMapping } from '../../support/dm-fixtures';
import {
  dismissDeprecationNotice,
  leaveApp,
  openEditorTab,
  openInboundMappings,
} from '../../support/dm-ui';

/**
 * Journey: deploy a mapping to a connector.
 *
 * A mapping created through the REST API has no connector, so the grid lists no connector
 * tag for it (the "No active connector" warning is only shown for active mappings). The spec opens it in the editor, selects a connector on the Connector tab and saves.
 * The result is asserted on the deployment request the UI sends, on the confirmation toast, and on the backend's deployment entry (read in teardown). Deleting the mapping
 * afterwards removes the deployment again.
 */
type EditorApi = {
  ng?: {
    getComponent: (el: Element) => {
      deploymentMapEntry?: { connectors?: string[] };
      sourceTemplate?: unknown;
      targetTemplate?: unknown;
    };
  };
};

describe('Mapping — deploy to a connector', () => {
  let testUser: IUser;
  let mappingId = '';
  let identifier = '';
  let deployedConnectors: unknown;

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    if (mappingId) {
      getDefinedDeploymentViaApi(identifier)
        .its('body')
        .then((body) => expect(body, 'persisted deployment').to.deep.eq(deployedConnectors));
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('starts without a connector, deploys through the editor and stores the assignment', () => {
    const payload = buildInboundEventMapping(Date.now());
    payload.name = `cypress-deploy-${Date.now()}`;
    identifier = payload.identifier;
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    cy.intercept('PUT', `${DM_SERVICE}/deployment/defined/*`).as('putDeployment');

    // The grid starts without a connector for the new mapping.
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(payload.name, { force: true });
    cy.then(() => {
      cy.get(`[data-cy="dm-mapping-id-${mappingId}"]`, { timeout: 15000 }).should('exist');
      cy.get(`[data-cy^="dm-mapping-deployment-${mappingId}-"]`).should('not.exist');
    });

    // Open the mapping in the editor (by route) and pick the first connector on the Connector tab.
    cy.then(() =>
      cy.visitShellAndWaitForSelector(
        `c8y-pkg-dynamic-mapper/node1/mappings/inbound/edit/${identifier}`,
        'en',
        '[data-cy="dm-unified-editor-save"]',
        60000,
      ),
    );
    // Open the Templates tab first. Product bug (see the plan's findings): until the editor has
    // initialised its templates, Save throws in reduceSourceTemplate (undefined template) and
    // silently does nothing, so a connector-only change cannot be saved straight away.
    openEditorTab('Templates');
    cy.get('d11r-mapping-template-step', { timeout: 30000 }).should('exist');
    cy.window().should((win) => {
      const host = win.document.querySelector('d11r-mapping-unified-editor');
      const editor = (win as unknown as EditorApi).ng?.getComponent(host as Element);
      expect(
        JSON.stringify([typeof editor?.sourceTemplate, typeof editor?.targetTemplate]),
        'templates initialised',
      ).to.eq('["object","object"]');
    });
    openEditorTab('Connector');
    cy.get('d11r-mapping-connector [data-cy="c8y-data-grid--checkbox"]', { timeout: 30000 })
      .first()
      .click({ force: true });
    // The grid's selection reaches the editor's deployment entry asynchronously; a Save clicked
    // before that is refused with "Select at least one connector".
    cy.window().should((win) => {
      const host = win.document.querySelector('d11r-mapping-unified-editor');
      const editor = (win as unknown as EditorApi).ng?.getComponent(host as Element);
      expect(
        editor?.deploymentMapEntry?.connectors?.length,
        'selected connectors',
      ).to.be.greaterThan(0);
    });
    cy.getByData('dm-unified-editor-save').should('be.enabled').click();

    cy.wait('@putDeployment').then(({ request, response }) => {
      expect(request.url, 'deployment url').to.contain(`/deployment/defined/${identifier}`);
      expect(request.body, 'deployed connectors').to.have.length.greaterThan(0);
      deployedConnectors = request.body;
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });

    // Saving closes the editor and reports that the connector assignment was stored. The grid's
    // "For connectors" column shows the effective deployment, which only changes once the mapping
    // is published and activated, so the stored assignment is checked on the backend in teardown.
    cy.get('c8y-alert-outlet').should('contain.text', 'connector assignments');
    cy.get('c8y-data-grid').should('exist');
  });
});
