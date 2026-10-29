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
  getDraftViaApi,
  getMappingViaApi,
} from '../../support/dm-api';
import { buildInboundSmartFunctionMapping, smartFunctionCode } from '../../support/dm-fixtures';
import { leaveApp, openEditorTab } from '../../support/dm-ui';

/**
 * Journey: edit the code of an existing Smart Function mapping in the editor, dry-run it on the
 * Test tab, and save it.
 *
 * The mapping is created through the REST API. The Monaco editor is changed through its
 * instance (see the comment in the test), not by keystrokes. The dry run is asserted on the response of
 * POST /test/mapping, and the save on the request the UI sends; the persisted code is read back
 * from the backend in teardown.
 */
type TestingApi = {
  ng?: { getComponent: (el: Element) => { testMapping?: { code?: string } } };
};

describe('Mapping — edit Smart Function code, dry-run and save', () => {
  const ORIGINAL = 'cypress-sf-original';
  const EDITED = 'cypress-sf-edited';
  const stamp = Date.now();
  let testUser: IUser;
  let mappingId = '';
  let identifier = '';

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    if (mappingId) {
      // The edit is stored in the draft (code base64-encoded); the live mapping is unchanged.
      getMappingViaApi(mappingId)
        .its('body.code')
        .then((code) => expect(atob(code), 'live code').to.contain(ORIGINAL));
      getDraftViaApi(mappingId)
        .its('body.code')
        .then((code) => expect(atob(code), 'draft code').to.contain(EDITED));
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('edits the code, shows the edited result in the dry run, and saves it', () => {
    const payload = buildInboundSmartFunctionMapping(stamp, ORIGINAL);
    identifier = payload.identifier;
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    cy.intercept('POST', `${DM_SERVICE}/test/mapping`).as('testMapping');
    // Editing an existing mapping saves into its draft; the live mapping changes only on publish.
    cy.intercept({ method: 'PUT', url: `${DM_SERVICE}/mapping/*/draft` }).as('saveDraft');

    cy.then(() =>
      cy.visitShellAndWaitForSelector(
        `c8y-pkg-dynamic-mapper/node1/mappings/inbound/edit/${identifier}`,
        'en',
        '[data-cy="dm-unified-editor-save"]',
        60000
      )
    );

    // Transformation tab: the editor holds the code that was created through the API.
    openEditorTab('Transformation');
    cy.get('c8y-editor', { timeout: 30000 }).should('be.visible');
    cy.get('c8y-editor .view-lines', { timeout: 30000 }).should('contain.text', ORIGINAL);

    // Replace the whole code through the editor instance. Keystrokes and paste do not work
    // (Monaco in Chromium uses EditContext, not a textarea) and the model is not on `window`
    // (the editor is bundled), so read the instance off the c8y-editor component. `ng` exists
    // because the suite runs against `ng serve`; setValue fires the change event the form needs.
    cy.get('c8y-editor').then(($el) => {
      const win = $el[0].ownerDocument.defaultView as Window & {
        ng?: { getComponent: (el: Element) => { editor: { setValue: (v: string) => void } } };
      };
      expect(typeof win.ng?.getComponent, 'Angular dev-mode helpers (ng serve)').to.eq('function');
      win.ng?.getComponent($el[0]).editor.setValue(smartFunctionCode(EDITED));
    });
    cy.get('c8y-editor .view-lines').should('contain.text', EDITED).and('not.contain.text', ORIGINAL);

    // Test tab: the dry run executes the edited code.
    openEditorTab('Testing');
    // The Test tab only applies the edited code after it has cleared the mock caches (it awaits
    // two operations before subscribing), while the Dry Run button is already enabled. A run
    // started in that window would execute the saved code, so wait until the tab holds the edit.
    cy.window().should((win) => {
      const host = win.document.querySelector('d11r-mapping-testing');
      const testing = (win as unknown as TestingApi).ng?.getComponent(host as Element);
      expect(atob(testing?.testMapping?.code ?? ''), 'code held by the Test tab').to.contain(EDITED);
    });
    cy.getByData('dm-mapping-test-transform').should('be.enabled').click();
    cy.wait('@testMapping').then(({ response }) => {
      expect(response?.statusCode, 'status').to.be.within(200, 299);
      expect(JSON.stringify(response?.body), 'dry-run result').to.contain(EDITED);
      expect(JSON.stringify(response?.body), 'dry-run result').to.not.contain(ORIGINAL);
    });

    // Save needs a connector (the editor refuses to save without one, as the stepper does).
    // Pick the first one, like the create specs; deleting the mapping in teardown removes the
    // deployment entry again.
    openEditorTab('Connector');
    cy.get('d11r-mapping-connector [data-cy="c8y-data-grid--checkbox"]')
      .first()
      .click({ force: true });

    // Save: the request carries the edited code.
    cy.getByData('dm-unified-editor-save').should('be.enabled').click();
    cy.wait('@saveDraft').then(({ request, response }) => {
      expect(atob(request.body.code), 'saved draft code').to.contain(EDITED);
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
  });
});
