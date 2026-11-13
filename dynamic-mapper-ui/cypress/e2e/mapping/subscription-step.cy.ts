/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
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
  getTypeSubscriptionViaApi,
  setTypeSubscriptionViaApi,
} from '../../support/dm-api';
import { buildInboundEventMapping, buildOutboundEventMapping } from '../../support/dm-fixtures';
import {
  dismissDeprecationNotice,
  driveSmartFunctionStepperToTestStep,
  leaveApp,
  openEditorTab,
  openOutboundMappings,
  stepperButton,
} from '../../support/dm-ui';

/**
 * Journey: the Subscription step of an outbound mapping.
 *
 * The step only collects a selection; it is written after the mapping is saved. So the specs
 * assert on the order of the requests (mapping first, subscription second) and on what is not
 * sent. The type subscription is tenant-wide state: the spec records the types that were there,
 * only adds its own "Cypress*" type, and restores the original list in teardown.
 */
describe('Mapping — Subscription step of an outbound mapping', () => {
  const stamp = Date.now();
  const typeName = `CypressStepType${stamp}`;
  let testUser: IUser;
  let originalTypes: string[] | null = null;
  const apiMappingIds: string[] = [];
  const createdNames: string[] = [];

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
    createdNames.forEach(deleteMappingsByName);
    apiMappingIds.forEach(deleteMappingViaApi);
    if (originalTypes) {
      setTypeSubscriptionViaApi(originalTypes);
    }
    cleanupTestUser(testUser);
  });

  beforeEach(function () {
    // Without outbound mapping the subscription endpoints are not available.
    if (originalTypes === null) {
      this.skip();
    }
  });

  const openOutboundGrid = (): void => {
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    openOutboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();
  };

  const watchSubscriptionRequests = (): void => {
    cy.intercept('POST', `${DM_SERVICE}/mapping`).as('createMapping');
    cy.intercept('PUT', `${DM_SERVICE}/subscription/type`).as('putTypes');
    cy.intercept('PUT', `${DM_SERVICE}/subscription/group`).as('putGroups');
    cy.intercept('PUT', `${DM_SERVICE}/subscription?*`).as('putDevices');
    cy.intercept('POST', `${DM_SERVICE}/subscription/type/resync*`).as('resync');
  };

  it('writes no subscription when the step is left on "Skip for now"', () => {
    const name = `cypress-step-skip-${stamp}`;
    createdNames.push(name);
    watchSubscriptionRequests();
    openOutboundGrid();

    driveSmartFunctionStepperToTestStep(name, `cyskip/${stamp}`, 'OUTBOUND');
    stepperButton('dm-mapping-stepper-buttons-test', 'Confirm');
    cy.wait('@createMapping').its('response.statusCode').should('be.oneOf', [200, 201]);

    cy.get('@putTypes.all').should('have.length', 0);
    cy.get('@putGroups.all').should('have.length', 0);
    cy.get('@putDevices.all').should('have.length', 0);
    cy.get('@resync.all').should('have.length', 0);
  });

  it('writes the type list after the mapping and resyncs the ticked type', () => {
    const name = `cypress-step-type-${stamp}`;
    createdNames.push(name);
    watchSubscriptionRequests();
    openOutboundGrid();

    driveSmartFunctionStepperToTestStep(name, `cytype/${stamp}`, 'OUTBOUND', () => {
      // Dynamic opens "by device type" with the inline type list.
      cy.getByData('dm-mapping-stepper-subscription-dynamic').click();
      cy.getByData('dm-mapping-stepper-subscription-types').should('be.visible');

      // Add a row (the empty list offers "add first", otherwise the last row carries the add button).
      cy.get('body').then(($body) => {
        if ($body.find('[data-cy="dm-type-selector-add-first"]').length) {
          cy.getByData('dm-type-selector-add-first').click();
        } else {
          cy.get('[data-cy^="dm-type-selector-row-"]').last().find('button.text-primary').click();
        }
      });
      cy.get('[data-cy^="dm-type-selector-input-"]').last().type(typeName);
      cy.getByData('dm-mapping-stepper-subscription-dynamic-panel').contains('not saved yet').should('exist');

      // Resync of the new type: the box is enabled once the row holds a value.
      cy.get('[data-cy^="dm-type-selector-resync-"]').last().check({ force: true });
    });

    stepperButton('dm-mapping-stepper-buttons-test', 'Confirm');

    // Mapping first, then the subscription, then the resync of the added type.
    cy.wait('@createMapping');
    cy.wait('@putTypes').then(({ request, response }) => {
      expect(request.body.api, 'api').to.eq('ALL');
      expect(request.body.types, 'sent types').to.include(typeName);
      // The existing types travel along, so the replace-style write cannot drop them.
      (originalTypes as string[]).forEach((t) => expect(request.body.types).to.include(t));
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
    cy.wait('@resync').then(({ request, response }) => {
      expect(new URL(request.url).searchParams.get('type'), 'resynced type').to.eq(typeName);
      expect(response?.statusCode, 'status').to.be.within(200, 299);
    });
    cy.get('@putGroups.all').should('have.length', 0);
    cy.get('@putDevices.all').should('have.length', 0);
  });

  it('shows the Subscription tab in the editor of an outbound mapping', () => {
    const outbound = buildOutboundEventMapping(stamp);
    createMappingViaApi(outbound).then((id) => apiMappingIds.push(id));

    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    cy.visitShellAndWaitForSelector(
      `c8y-pkg-dynamic-mapper/node1/mappings/outbound/edit/${outbound.identifier}`,
      'en',
      '[data-cy="dm-unified-editor-save"]',
      60000,
    );
    cy.getByData('dm-unified-editor-tab-subscription').should('exist');
    openEditorTab('Subscription');
    cy.get('d11r-mapping-step-subscription', { timeout: 30000 }).should('exist');
    cy.getByData('dm-unified-editor-subscription-skip').should('exist');
    cy.getByData('dm-unified-editor-subscription-dynamic').should('exist');
    cy.getByData('dm-unified-editor-subscription-static').should('exist');
  });

  it('has no Subscription tab in the editor of an inbound mapping', () => {
    const inbound = buildInboundEventMapping(stamp);
    createMappingViaApi(inbound).then((id) => apiMappingIds.push(id));

    // A fresh session and page load: an editor opened by a hash-only route change from the
    // outbound editor above could keep its outbound layout.
    leaveApp();
    cy.getAuth(testUser.userName, testUser.password as string).login().disableGainsight();
    cy.visitShellAndWaitForSelector(
      `c8y-pkg-dynamic-mapper/node1/mappings/inbound/edit/${inbound.identifier}`,
      'en',
      '[data-cy="dm-unified-editor-save"]',
      60000,
    );
    // The hook sits on the tab registration, which exists only for outbound. (Do not match on text:
    // the navigator has a "Subscription outbound" entry in the same outlet.)
    cy.getByData('dm-unified-editor-tab-connector').should('exist');
    cy.getByData('dm-unified-editor-tab-subscription').should('not.exist');
  });
});
