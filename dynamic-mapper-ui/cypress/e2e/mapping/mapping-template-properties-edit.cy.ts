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
  createMappingViaApi,
  deleteMappingViaApi,
  getDraftViaApi,
} from '../../support/dm-api';
import { buildOutboundEventMapping } from '../../support/dm-fixtures';
import { formField, leaveApp, openEditorTab } from '../../support/dm-ui';

/**
 * Journey: the execution filter of an outbound mapping is edited in the General Settings tab and
 * in the Templates tab, and each tab follows the other. The Templates tab also hides and shows
 * the metadata keys of the source template.
 *
 * The mapping is created through the REST API. The filter is asserted on the form fields; the
 * saved draft is asserted on the request the UI sends and read back from the backend in teardown.
 */
/** The select's option text carries padding, so compare it trimmed. */
const expectFilterMode = (label: string) =>
  formField('executionFilterMode')
    .find(':selected')
    .should(($option) => expect($option.text().trim()).to.eq(label));

describe('Mapping — execution filter across the General Settings and Templates tabs', () => {
  const stamp = Date.now();
  const FROM_PROPERTIES = 'c8y_FromProperties';
  // A fragment filter that the source template satisfies, so the result flips from false to true and the
  // General Settings tab has to switch from "by type" to "by fragment".
  const FROM_TEMPLATES = '$exists(type)';
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
      getDraftViaApi(mappingId)
        .its('body.filterMapping')
        .should('eq', FROM_TEMPLATES);
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('keeps the execution filter in step between the tabs and saves it', () => {
    const payload = {
      ...buildOutboundEventMapping(stamp),
      filterMapping: "type = 'c8y_TestEvent'",
      // A source template with metadata, so the Templates tab offers to hide it.
      sourceTemplate:
        '{"text":"event text","type":"c8y_TestEvent","_IDENTITY_":{"externalId":"cypress-device"}}',
    };
    identifier = payload.identifier;
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
    });

    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    cy.intercept({ method: 'PUT', url: `${DM_SERVICE}/mapping/*/draft` }).as('saveDraft');

    cy.then(() =>
      cy.visitShellAndWaitForSelector(
        `c8y-pkg-dynamic-mapper/node1/mappings/outbound/edit/${identifier}`,
        'en',
        '[data-cy="dm-unified-editor-save"]',
        60000
      )
    );

    // General Settings: the stored filter is shown as "by type" with its value.
    openEditorTab('General Settings');
    expectFilterMode('Filter by type');
    formField('executionFilterValue').should('have.value', 'c8y_TestEvent');

    // Edit the value here; the Templates tab shows the expression it writes.
    formField('executionFilterValue').clear();
    formField('executionFilterValue').type(FROM_PROPERTIES);
    openEditorTab('Templates');
    formField('filterMapping').should('have.value', `type = '${FROM_PROPERTIES}'`);
    // The filter is evaluated against the source template (type c8y_TestEvent) and its result is shown.
    cy.getByData('dm-mapping-template-filter-result').should('have.value', 'false');

    // The metadata keys of the source template can be hidden and shown again.
    cy.getByData('dm-mapping-template-toggle-source-metadata')
      .invoke('text')
      .then((before) => {
        cy.getByData('dm-mapping-template-toggle-source-metadata').click();
        cy.getByData('dm-mapping-template-toggle-source-metadata')
          .invoke('text')
          .should('not.eq', before);
      });

    // Edit the expression here; General Settings follows it, even when the tab is left right away.
    formField('filterMapping').clear();
    formField('filterMapping').type(FROM_TEMPLATES, { parseSpecialCharSequences: false });
    // Leave the tab at once, inside the filter's 500 ms debounce: the editor destroys the step, which has to
    // hand the pending edit to the mapping rather than drop it.
    openEditorTab('General Settings');
    expectFilterMode('Filter by fragment');
    formField('executionFilterValue').should('have.value', 'type');

    // Save: the editor needs a connector (see smart-function-editor.cy.ts).
    openEditorTab('Connector');
    cy.get('d11r-mapping-connector [data-cy="c8y-data-grid--checkbox"]')
      .first()
      .click({ force: true });
    cy.getByData('dm-unified-editor-save').should('be.enabled').click();
    cy.wait('@saveDraft').then(({ request, response }) => {
      expect(request.body.filterMapping, 'saved filter').to.eq(FROM_TEMPLATES);
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
  });
});
