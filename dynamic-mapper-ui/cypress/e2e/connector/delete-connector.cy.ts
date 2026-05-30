import type { IUser } from '@c8y/client';
import { slowCypressDown } from 'cypress-slow-down';
import {
  navigateToConnectorConfiguration,
  addConnectorViaUi,
  deleteConnectorViaUi,
  deleteConnectorViaApi,
  openConnectorRowAction,
  getConnector,
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

      deleteConnectorViaUi(identifier);
      cy.wait('@deleteConnector');

      getConnector(identifier).should('not.exist');

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

      openConnectorRowAction(identifier, 'Delete');

      // The confirm dialog's OK button carries the c8y data-cy hook; assert it's
      // shown (i.e. the modal opened) before confirming.
      cy.get('[data-cy="c8y-confirm-modal--ok"]').should('be.visible').click();
      cy.wait('@deleteConnector');

      getConnector(identifier).should('not.exist');

      const index = createdConnectorIds.indexOf(identifier);
      if (index >= 0) {
        createdConnectorIds.splice(index, 1);
      }
    });

    cy.screenshot('connector-deletion-confirmed');
  });
});
