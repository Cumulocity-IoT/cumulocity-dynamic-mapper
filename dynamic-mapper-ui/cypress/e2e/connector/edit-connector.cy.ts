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

describe('Connector — Edit connector', () => {
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

  it('should edit connector properties', () => {
    const originalName = `${mqttConnectionInput.name}-edit-${Date.now()}`;
    const updatedName = `${mqttConnectionInput.name}-updated-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, originalName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #edit').click({ force: true });

      cy.get('#name').clear();
      cy.get('#name').type(updatedName);
      cy.get('button[title="Save"]').click();
      cy.wait('@putConnector');

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).should(
        'contain',
        updatedName
      );
    });

    cy.screenshot('connector-edited');
  });

  it('should update connector MQTT port', () => {
    const connectorName = `${mqttConnectionInput.name}-port-${Date.now()}`;
    const newPort = '9883';

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).click();
      cy.get('.dropdown #edit').click({ force: true });

      cy.get('#mqttPort').clear();
      cy.get('#mqttPort').type(newPort);
      cy.get('button[title="Save"]').click();
      cy.wait('@putConnector');

      cy.get(`#connector_${identifier}`, { timeout: 10000 }).should('exist');
    });

    cy.screenshot('connector-port-updated');
  });
});
