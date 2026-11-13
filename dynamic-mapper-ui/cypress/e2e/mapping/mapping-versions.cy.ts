/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import {
  DM_SERVICE,
  adminAuth,
  createMappingViaApi,
  deleteMappingViaApi,
  getDraftViaApi,
  getMappingViaApi,
} from '../../support/dm-api';
import { buildInboundSmartFunctionMapping, smartFunctionCode } from '../../support/dm-fixtures';
import { dismissDeprecationNotice, leaveApp, openInboundMappings } from '../../support/dm-ui';

/**
 * Journey: the versions drawer of a mapping. An older published version is activated, a change
 * note is edited, and the draft is published as the next version.
 *
 * The history is built through the REST API before the UI opens (the first version 1.0.0 that
 * creating the mapping makes, a draft published as 1.0.1 and activated, a second draft left open), because the API calls clear the
 * session cookie and cannot run while the UI is under test. The UI is asserted on the requests it
 * sends; the stored end state is read back from the backend in teardown.
 */
describe('Mapping — versions drawer', () => {
  const stamp = Date.now();
  const NOTE = 'cypress note for the first version';
  let testUser: IUser;
  let mappingId = '';
  let mappingName = '';

  const saveDraftViaApi = (marker: string) => {
    getMappingViaApi(mappingId).then(({ body }) => {
      cy.clearCookies();
      cy.request({
        method: 'PUT',
        url: `${DM_SERVICE}/mapping/${mappingId}/draft`,
        auth: adminAuth(),
        body: { ...body, code: btoa(smartFunctionCode(marker)) },
      }).then((resp) => {
        expect(resp.status, `save draft (${marker})`).to.be.within(200, 299);
      });
      // The publish call needs the draft to exist; fail here, with the reason, if it does not.
      getDraftViaApi(mappingId).then((resp) => {
        expect(resp.status, `draft exists (${marker}): ${JSON.stringify(resp.body)}`).to.eq(200);
      });
    });
  };

  const publishViaApi = (version: string) => {
    cy.clearCookies();
    cy.request({
      method: 'POST',
      url: `${DM_SERVICE}/mapping/${mappingId}/publish?version=${version}`,
      auth: adminAuth(),
      headers: { 'content-type': 'application/json' },
    })
      .its('status')
      .should('be.within', 200, 299);
  };

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    if (mappingId) {
      // The UI activated 1.0.0, edited its note and published the draft as 1.0.2.
      getMappingViaApi(mappingId).its('body.version').should('eq', '1.0.0');
      cy.clearCookies();
      cy.request({
        url: `${DM_SERVICE}/mapping/${mappingId}/version/1.0.0`,
        auth: adminAuth(),
      })
        .its('body.note')
        .should('eq', NOTE);
      cy.clearCookies();
      cy.request({
        url: `${DM_SERVICE}/mapping/${mappingId}/version/1.0.2`,
        auth: adminAuth(),
      })
        .its('status')
        .should('eq', 200);
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('activates an older version, edits its note and publishes the draft', () => {
    const payload = buildInboundSmartFunctionMapping(stamp, 'cypress-v-original');
    mappingName = payload.name;

    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });
    cy.then(() => {
      // Creating the mapping makes 1.0.0 its first version, so the history starts there.
      saveDraftViaApi('cypress-v-1');
      publishViaApi('1.0.1');
      // Make 1.0.1 the active version, as the UI would, then leave one draft open.
      cy.clearCookies();
      cy.request({
        method: 'POST',
        url: `${DM_SERVICE}/operation`,
        auth: adminAuth(),
        body: {
          operation: 'ACTIVATE_MAPPING',
          parameter: { id: mappingId, active: 'true', version: '1.0.1' },
        },
      })
        .its('status')
        .should('be.within', 200, 299);
      saveDraftViaApi('cypress-v-2');
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    cy.intercept('POST', `${DM_SERVICE}/operation`).as('operation');
    cy.intercept('PATCH', `${DM_SERVICE}/mapping/*/version/*`).as('saveNote');
    cy.intercept('POST', `${DM_SERVICE}/mapping/*/publish*`).as('publish');

    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(mappingName, { force: true });

    // The Version/Status cell opens the drawer.
    cy.then(() => cy.get(`[data-cy="dm-mapping-status-link-${mappingId}"]`, { timeout: 15000 }).click());
    const drawer = () => cy.get('d11r-mapping-version-drawer', { timeout: 15000 });
    drawer().should('be.visible');
    drawer().contains('Versions of').should('contain.text', mappingName);

    // The history: the open draft, the active 1.0.1 and the older 1.0.0.
    cy.getByData('dm-version-state-draft').should('be.visible');
    drawer().should('contain.text', 'v1.0.1').and('contain.text', 'v1.0.0');

    // Activate the older version: the only toggle that is enabled belongs to the published,
    // inactive row (1.0.1 is active, the draft cannot be activated).
    drawer()
      .find('[data-cy^="dm-version-state-toggle-"]:not([disabled])')
      .should('have.length', 1)
      .click({ force: true });
    cy.wait('@operation').then(({ request, response }) => {
      expect(request.body.operation, 'operation').to.eq('ACTIVATE_MAPPING');
      expect(request.body.parameter.id, 'mapping id').to.eq(mappingId);
      expect(request.body.parameter.version, 'requested version').to.eq('1.0.0');
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
    // The rows update in place: nothing is left to activate except 1.0.1.
    drawer()
      .find('[data-cy^="dm-version-state-toggle-"]:not([disabled])')
      .should('have.length', 1);

    // Edit the note of 1.0.0 (edit on focus, saved on blur).
    const noteInput = () =>
      drawer()
        .contains('v1.0.0')
        .parents('[role="row"], .c8y-data-grid__row, tr')
        .first()
        .find('[data-cy="dm-version-note-cell"]')
        .first();
    noteInput().focus();
    noteInput().type(NOTE);
    noteInput().blur();
    cy.wait('@saveNote').then(({ request, response }) => {
      expect(request.method, 'method').to.eq('PATCH');
      expect(request.url, 'version in the url').to.contain('/version/1.0.0');
      expect(decodeURIComponent(request.url), 'note in the url').to.contain(`note=${NOTE}`);
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });

    // Publish the draft: the dialog suggests the next patch version (the highest is 1.0.1).
    // The row action is an icon button that only shows on hover, so click it without the hover.
    drawer().find('[data-cy="c8y-data-grid--button-in-row--Publish"]').click({ force: true });
    cy.get('d11r-publish-version-modal').should('be.visible');
    cy.get('d11r-publish-version-modal').contains('button', 'Patch').click();
    cy.get('d11r-publish-version-modal input[type="text"]').should('have.value', '1.0.2');
    // Publish without activating: 1.0.0 must stay the active version.
    cy.getByData('dm-publish-activate').uncheck({ force: true });
    cy.get('d11r-publish-version-modal')
      .contains('button', /^\s*Publish\s*$/)
      .should('be.enabled')
      .click();
    cy.wait('@publish').then(({ request, response }) => {
      expect(request.url, 'version in the url').to.contain('version=1.0.2');
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });

    // The draft is gone and the new version is listed.
    cy.getByData('dm-version-state-draft').should('not.exist');
    drawer().should('contain.text', 'v1.0.2');
    cy.getByData('dm-version-close').click();
  });
});
