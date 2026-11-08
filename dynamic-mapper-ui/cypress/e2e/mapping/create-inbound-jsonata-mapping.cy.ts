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
  createMappingViaApi,
  deleteMappingViaApi,
  getMappingViaApi,
} from '../../support/dm-api';
import { buildInboundEventMapping } from '../../support/dm-fixtures';
import { dismissDeprecationNotice, leaveApp, openInboundMappings } from '../../support/dm-ui';


describe('Mapping — create inbound JSON→EVENT (JSONata) mapping', () => {
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
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('creates the mapping via the dynamic-mapper API and shows it in the inbound grid', () => {
    const suffix = Date.now();
    const payload = buildInboundEventMapping(suffix);
    mappingName = payload.name;

    // 1. Create the mapping (POST /mapping) — like dm_create_mapping in the script.
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
      cy.log(`created mapping id=${mappingId} name=${mappingName}`);
    });

    // 2. Verify it was persisted with the expected shape (GET /mapping/<id>).
    cy.then(() => {
      getMappingViaApi(mappingId).then(
        (resp) => {
          const m = resp.body;
          expect(m.name, 'name').to.eq(mappingName);
          expect(m.direction, 'direction').to.eq('INBOUND');
          expect(m.targetAPI, 'targetAPI').to.eq('EVENT');
          expect(m.mappingType, 'mappingType').to.eq('JSON');
          expect(m.mappingTopic, 'mappingTopic').to.eq('dmtest/event/+');
          expect(m.substitutions, 'substitutions').to.have.length(4);
        }
      );
    });

    // 3. Log in as the test user and open the inbound mappings page.
    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();

    // 4. Filter the grid to our mapping (the grid paginates ~25/page), then assert
    //    its row via the per-row data-cy hook on the name cell (dm-mapping-id-<id>,
    //    rendered by MappingIdCellRendererComponent). MappingEnriched.id is the
    //    mapping's inventory id — the same id POST /mapping returned.
    cy.then(() => {
      cy.get('c8y-data-grid input[type="search"]').clear();
      cy.get('c8y-data-grid input[type="search"]').type(mappingName);
      cy.get(`[data-cy="dm-mapping-id-${mappingId}"]`, { timeout: 15000 })
        .should('exist')
        .and('contain', mappingName);
    });

    cy.screenshot('mapping-inbound-jsonata-created');
  });
});
