import { slowCypressDown } from 'cypress-slow-down';
slowCypressDown();

type MqttConnectionInput = {
  name: string;
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
};

describe('Specs for connector configuration', () => {
  const createdConnectorIds: string[] = [];
  let mqttConnectionInput: MqttConnectionInput;

  const navigateToConnectorConfiguration = () => {
    // login
    cy.getAuth().login();
    cy.hideCookieBanner();
    cy.disableGainsight();

    cy.visitAndWaitForSelector(
      '/apps/administration/index.html?remotes=%7B%22c8y-pkg-dynamic-mapper%22%3A%5B%22DynamicMappingModule%22%5D%7D',
      'en',
      '#navigator'
    );
    cy.get('[data-cy="Settings"]').should('exist').click();
    cy.get('[data-cy="Dynamic Mapper"]')
      .should('exist')
      .should('be.visible')
      .click();

    // navigate to configuration
    cy.get('a[title="Connector"]').as('configuration').should('exist');
    cy.get('@configuration').click();
  };

  const addConnectorViaUi = (
    mqttConnectionInput: MqttConnectionInput,
    connectorName: string
  ): Cypress.Chainable<string> => {
    cy.get('#addConfiguration').should('exist').click();
    cy.wait('@getConnectorSpecifications');

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

  beforeEach(() => {
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

  before(() => {
    cy.fixture('mqttConnectionInput').then((data) => {
      mqttConnectionInput = data as MqttConnectionInput;
    });
  });

  afterEach(() => {
    if (!createdConnectorIds.length) {
      return;
    }

    createdConnectorIds.splice(0).forEach((identifier) => {
      cy.request({
        method: 'DELETE',
        url: `/service/dynamic-mapper-service/configuration/connector/instance/${identifier}`,
        failOnStatusCode: false,
      });
    });
  });

  it('Add connector', () => {
    const connectorName = `${mqttConnectionInput.name}-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then(
      (identifier) => {
        createdConnectorIds.push(identifier);

        cy.get(`#connector_${identifier}`, { timeout: 10000 }).should('exist');
      }
    );

    cy.screenshot('connector-added');
  });

  it('Delete connector', () => {
    const connectorName = `${mqttConnectionInput.name}-delete-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, connectorName).then(
      (identifier) => {
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
      }
    );
    cy.screenshot('connector-deleted');
  });
});
