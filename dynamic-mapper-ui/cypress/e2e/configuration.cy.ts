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
  navigateToConnectorConfiguration,
  addConnectorViaUi,
  deleteConnectorViaUi,
  deleteConnectorViaApi,
  getConnector,
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
  type MqttConnectionInput,
} from './connector/connector.helpers';


describe('Specs for connector configuration', () => {
  const createdConnectorIds: string[] = [];
  let mqttConnectionInput: MqttConnectionInput;
  let testUser: IUser;

  before(() => {
    Cypress.session.clearAllSavedSessions();

    cy.fixture('mqttConnectionInput').then((data) => {
      mqttConnectionInput = data as MqttConnectionInput;
    });

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

    cy.intercept(
      'GET',
      '/service/dynamic-mapper-service/configuration/connector/specifications'
    ).as('getConnectorSpecifications');
    cy.intercept(
      'POST',
      '/service/dynamic-mapper-service/configuration/connector/instance'
    ).as('postConnector');
    cy.intercept(
      'DELETE',
      '/service/dynamic-mapper-service/configuration/connector/instance/**'
    ).as('deleteConnector');

    navigateToConnectorConfiguration();
  });

  afterEach(() => {
    createdConnectorIds.splice(0).forEach((identifier) => {
      deleteConnectorViaApi(identifier);
    });
  });

  it('Add connector', () => {
    const connectorName = `${mqttConnectionInput.name}-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      getConnector(identifier).should('exist').and('contain', connectorName);
    });

    cy.screenshot('connector-added');
  });

  it('Delete connector', () => {
    const connectorName = `${mqttConnectionInput.name}-delete-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      getConnector(identifier).should('exist').and('contain', connectorName);

      deleteConnectorViaUi(identifier);
      cy.wait('@deleteConnector');
      getConnector(identifier).should('not.exist');

      // Deleted via UI — drop it from the API-cleanup list (afterEach is a backstop).
      const index = createdConnectorIds.indexOf(identifier);
      if (index >= 0) {
        createdConnectorIds.splice(index, 1);
      }
    });

    cy.screenshot('connector-deleted');
  });
});
