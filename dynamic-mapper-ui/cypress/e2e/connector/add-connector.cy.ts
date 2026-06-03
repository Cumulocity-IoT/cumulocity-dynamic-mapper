import type { IUser } from '@c8y/client';
import { slowCypressDown } from 'cypress-slow-down';
import {
  navigateToConnectorConfiguration,
  addConnectorViaUi,
  deleteConnectorViaApi,
  getConnector,
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
  type MqttConnectionInput,
} from './connector.helpers';

slowCypressDown();

describe('Connector — Add connector', () => {
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

    navigateToConnectorConfiguration();
  });

  afterEach(() => {
    createdConnectorIds.splice(0).forEach((identifier) => {
      deleteConnectorViaApi(identifier);
    });
  });

  it('should add a new MQTT connector', () => {
    const connectorName = `${mqttConnectionInput.name}-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      // Assert the new connector is listed via its per-row data-cy hook.
      getConnector(identifier).should('exist').and('contain', connectorName);
    });

    cy.screenshot('connector-added');
  });

  it('should display connector in list after adding', () => {
    const connectorName = `${mqttConnectionInput.name}-list-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then((identifier) => {
      createdConnectorIds.push(identifier);

      getConnector(identifier).should('exist').and('contain', connectorName);
    });
  });
});
