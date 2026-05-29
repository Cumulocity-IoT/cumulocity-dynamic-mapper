import type { IUser } from '@c8y/client';
import { slowCypressDown } from 'cypress-slow-down';
import {
  navigateToConnectorConfiguration,
  addConnectorViaUi,
  deleteConnectorViaApi,
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
  type MqttConnectionInput,
} from './connector.helpers';

slowCypressDown();

describe('Connector — Delete connector', () => {
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

  it('should delete connector via UI', () => {
    const connectorName = `${mqttConnectionInput.name}-delete-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #delete').click({ force: true });
      cy.get('[data-cy="c8y-confirm-modal--ok"]').click();
      cy.wait('@deleteConnector');

      cy.get(`#connector_${identifier}`).should('not.exist');

      const index = createdConnectorIds.indexOf(identifier);
      if (index >= 0) {
        createdConnectorIds.splice(index, 1);
      }
    });

    cy.screenshot('connector-deleted');
  });

  it('should confirm deletion in modal before removing', () => {
    const connectorName = `${mqttConnectionInput.name}-confirm-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #delete').click({ force: true });

      cy.get('[data-cy="c8y-confirm-modal"]').should('be.visible');
      cy.get('[data-cy="c8y-confirm-modal--ok"]').click();
      cy.wait('@deleteConnector');

      cy.get(`#connector_${identifier}`, { timeout: 5000 }).should('not.exist');

      const index = createdConnectorIds.indexOf(identifier);
      if (index >= 0) {
        createdConnectorIds.splice(index, 1);
      }
    });

    cy.screenshot('connector-deletion-confirmed');
  });
});
