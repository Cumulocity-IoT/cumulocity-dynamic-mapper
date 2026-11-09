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
  deleteConnectorViaApi,
  toggleConnectorEnabled,
  getConnector,
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
  type MqttConnectionInput,
} from './connector.helpers';


describe('Connector — Toggle connector state', () => {
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
    // Enabling/disabling a connector triggers a CONNECT/DISCONNECT operation
    // (POST .../operation), not a connector PUT.
    cy.intercept('POST', '/service/dynamic-mapper-service/operation').as(
      'operation'
    );

    navigateToConnectorConfiguration();
  });

  afterEach(() => {
    createdConnectorIds.splice(0).forEach((identifier) => {
      deleteConnectorViaApi(identifier);
    });
  });

  it('should disable connector', () => {
    const connectorName = `${mqttConnectionInput.name}-disable-${Date.now()}`;

    // A freshly created connector is disabled, so enable it first, then disable it.
    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      toggleConnectorEnabled(identifier); // enable
      cy.wait('@operation');
      toggleConnectorEnabled(identifier); // disable
      cy.wait('@operation');

      getConnector(identifier).should('exist').and('contain', connectorName);
    });

    cy.screenshot('connector-disabled');
  });

  it('should re-enable connector', () => {
    const connectorName = `${mqttConnectionInput.name}-enable-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      toggleConnectorEnabled(identifier); // enable
      cy.wait('@operation');
      toggleConnectorEnabled(identifier); // disable
      cy.wait('@operation');
      toggleConnectorEnabled(identifier); // re-enable
      cy.wait('@operation');

      getConnector(identifier).should('exist').and('contain', connectorName);

      // Leave it disabled so the afterEach API cleanup can remove it.
      toggleConnectorEnabled(identifier);
      cy.wait('@operation');
    });

    cy.screenshot('connector-reenabled');
  });
});
