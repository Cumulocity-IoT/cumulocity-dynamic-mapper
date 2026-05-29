import type { IUser } from '@c8y/client';

/**
 * Shared helpers for connector configuration tests
 */

export type MqttConnectionInput = {
  name: string;
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
};

/**
 * Test user for connector tests - follows official Cumulocity pattern
 * @see https://community.cumulocity.com/t/cumulocity-app-and-plugin-testing-with-cypress-getting-started/7522
 */
export const createConnectorTestUser = (): IUser => ({
  userName: `cypress-connector-${Date.now()}`,
  password: 'ZVfJbDXuN!3t',
  displayName: 'Cypress Connector Test User',
  email: `cypress-connector-${Date.now()}@test.local`,
} as IUser);

/**
 * Setup test user before running connector tests
 * Uses technical user (from env vars) to create a test user with appropriate roles
 */
export const setupTestUser = (testUser: IUser): void => {
  cy.getAuth().login();
  cy.createUser(testUser, ['business'], ['administration', 'cockpit']);
};

/**
 * Cleanup test user after tests complete
 * Uses technical user (from env vars) to delete the test user
 */
export const cleanupTestUser = (testUser: IUser): void => {
  cy.getAuth().login().deleteUser(testUser);
};

/**
 * Navigate to connector configuration page
 * Assumes already authenticated as test user
 */
export const navigateToConnectorConfiguration = (): void => {
  cy.visitShellAndWaitForSelector('', 'en', '#navigator');
  cy.get('[data-cy="Settings"]').should('exist').click();
  cy.get('[data-cy="Dynamic Mapper"]')
    .should('exist')
    .should('be.visible')
    .click();

  cy.get('a[title="Connector"]').as('configuration').should('exist');
  cy.get('@configuration').click();
};

/**
 * Fill MQTT connector form with provided values
 */
export const fillMqttConnectorForm = (
  mqttConnectionInput: MqttConnectionInput,
  connectorName: string
): void => {
  cy.get('#connectorType').should('exist').should('be.visible').select('MQTT');
  cy.get('#name').clear();
  cy.get('#name').type(connectorName);
  cy.get('#mqttHost').clear();
  cy.get('#mqttHost').type(mqttConnectionInput.mqttHost);
  cy.get('#mqttPort').clear();
  cy.get('#mqttPort').type(String(mqttConnectionInput.mqttPort));
  cy.get('#user').clear();
  cy.get('#user').type(mqttConnectionInput.user);
  cy.get('#password').clear();
  cy.get('#password').type(mqttConnectionInput.password);
  cy.get('#clientId').clear();
  cy.get('#clientId').type(mqttConnectionInput.clientId);
};

/**
 * Add connector via UI and return created identifier
 */
export const addConnectorViaUi = (
  mqttConnectionInput: MqttConnectionInput,
  connectorName: string
): Cypress.Chainable<string> => {
  cy.get('#addConfiguration').should('exist').click();
  cy.wait('@getConnectorSpecifications');
  fillMqttConnectorForm(mqttConnectionInput, connectorName);
  cy.get('button[title="Save"]').click();

  return cy.wait('@postConnector').then((interception) => {
    const identifier =
      interception.response?.body?.identifier ??
      interception.request.body?.identifier;

    expect(identifier, 'created connector identifier').to.be.a('string');
    expect(String(identifier), 'created connector identifier').to.not.equal('');

    return String(identifier);
  });
};

/**
 * Clean up connector by making DELETE request
 */
export const deleteConnectorViaApi = (identifier: string): void => {
  cy.request({
    method: 'DELETE',
    url: `/service/dynamic-mapper-service/configuration/connector/instance/${identifier}`,
    failOnStatusCode: false,
  });
};
