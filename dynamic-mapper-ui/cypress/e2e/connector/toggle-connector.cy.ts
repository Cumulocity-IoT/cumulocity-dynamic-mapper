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
    cy.intercept(
      'PUT',
      '/service/dynamic-mapper-service/configuration/connector/instance/**'
    ).as('putConnector');

    navigateToConnectorConfiguration();
  });

  afterEach(() => {
    createdConnectorIds.splice(0).forEach((identifier) => {
      deleteConnectorViaApi(identifier);
    });
  });

  it('should disable connector', () => {
    const connectorName = `${mqttConnectionInput.name}-disable-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #disable').click({ force: true });
      cy.wait('@putConnector');

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).should('exist');
    });

    cy.screenshot('connector-disabled');
  });

  it('should re-enable connector', () => {
    const connectorName = `${mqttConnectionInput.name}-enable-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #disable').click({ force: true });
      cy.wait('@putConnector');

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #enable').click({ force: true });
      cy.wait('@putConnector');

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).should('exist');
    });

    cy.screenshot('connector-reenabled');
  });
});
