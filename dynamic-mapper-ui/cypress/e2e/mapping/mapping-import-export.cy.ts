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
  deleteMappingsByName,
} from '../../support/dm-api';
import { buildInboundEventMapping } from '../../support/dm-fixtures';
import {
  dismissDeprecationNotice,
  leaveApp,
  openInboundMappings,
} from '../../support/dm-ui';

/**
 * Journeys: export the inbound mappings to a file, and import mappings from a file.
 *
 * Export is asserted on the downloaded file. The import file is built by the spec and holds only
 * the spec's own mapping: an export of the whole tenant would re-create every other inbound
 * mapping when imported back. The imported mapping gets a new identifier and starts inactive;
 * both are asserted on the request the UI sends.
 */
describe('Mapping — export and import', () => {
  const stamp = Date.now();
  let testUser: IUser;
  const createdIds: string[] = [];
  const importedNames: string[] = [];

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    leaveApp();
    createdIds.forEach((id) => deleteMappingViaApi(id));
    importedNames.forEach((name) => deleteMappingsByName(name));
    cleanupTestUser(testUser);
  });

  const login = () =>
    cy
      .getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();

  /** The export and import actions live in the toolbar's "More..." dropdown, which renders lazily. */
  const clickMoreAction = (hook: string) => {
    cy.getByData('action-bar--button-more').filter(':visible').first().click();
    cy.getByData(hook).filter(':visible').first().click();
  };

  const openGrid = () => {
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  };

  it('exports the inbound mappings to a JSON file without their ids', () => {
    const payload = buildInboundEventMapping(stamp);
    createMappingViaApi(payload).then((id) => createdIds.push(id));

    login();
    openGrid();
    // The export takes the mappings the grid holds, so wait until it lists ours.
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(payload.name, { force: true });
    cy.get('c8y-data-grid').contains(payload.name, { timeout: 15000 }).should('be.visible');
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });

    clickMoreAction('dm-mapping-export');

    cy.readFile(`${Cypress.config('downloadsFolder')}/mappings-INBOUND.json`, { timeout: 30000 }).then(
      (exported: Array<Record<string, unknown>>) => {
        expect(exported, 'exported list').to.be.an('array');
        const mine = exported.find((m) => m['name'] === payload.name);
        expect(mine, `the mapping created by the spec, in: ${exported.map((m) => m['name']).join(', ')}`).to.not.equal(undefined);
        // The export is meant to be imported again, as new mappings.
        expect(mine, 'no id').to.not.have.property('id');
        expect(mine, 'no identifier').to.not.have.property('identifier');
        expect(mine?.['mappingTopic']).to.eq(payload.mappingTopic);
      },
    );
  });

  it('imports a mapping from a file as a new, inactive mapping', () => {
    const payload = buildInboundEventMapping(stamp + 1);
    importedNames.push(payload.name);
    cy.intercept('POST', `${DM_SERVICE}/mapping`).as('createMapping');

    login();
    openGrid();

    clickMoreAction('dm-mapping-import');
    cy.getByData('dm-import-file-input')
      .find('input[type="file"]')
      .selectFile(
        {
          contents: Cypress.Buffer.from(JSON.stringify([{ ...payload, active: true }])),
          fileName: 'cypress-import.json',
          mimeType: 'application/json',
        },
        { force: true },
      );

    cy.wait('@createMapping').then(({ request, response }) => {
      expect(response?.statusCode, 'status').to.be.within(200, 299);
      expect(request.body.name, 'name').to.eq(payload.name);
      // The import never keeps the identifier of the file, and never starts a mapping.
      expect(request.body.identifier, 'new identifier').to.not.eq(payload.identifier);
      expect(request.body.active, 'inactive').to.eq(false);
    });
    cy.getByData('dm-import-result').should('contain.text', 'Imported 1 mapping(s) successfully.');
    cy.getByData('dm-import-failed').should('not.exist');

    // Done closes the dialog and the grid shows the new mapping.
    cy.get('c8y-modal').contains('button', 'Done').click();
    cy.get('c8y-data-grid input[type="search"]').clear({ force: true });
    cy.get('c8y-data-grid input[type="search"]').type(payload.name, { force: true });
    cy.get('c8y-data-grid').contains(payload.name, { timeout: 15000 }).should('be.visible');
  });

  it('shows an error and creates nothing when the file is not a mapping list', () => {
    cy.intercept('POST', `${DM_SERVICE}/mapping`, cy.spy().as('createCall'));

    login();
    openGrid();

    clickMoreAction('dm-mapping-import');
    cy.getByData('dm-import-file-input')
      .find('input[type="file"]')
      .selectFile(
        {
          contents: Cypress.Buffer.from('this is not json'),
          fileName: 'cypress-broken.json',
          mimeType: 'application/json',
        },
        { force: true },
      );

    cy.getByData('dm-import-error').should('be.visible');
    cy.getByData('dm-import-result').should('not.exist');
    cy.get('@createCall').should('not.have.been.called');
  });
});
