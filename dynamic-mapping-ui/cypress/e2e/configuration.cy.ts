/* eslint-disable no-useless-escape */
import { slowCypressDown } from 'cypress-slow-down';
slowCypressDown();

describe('Specs for connector configuration', () => {
  beforeEach(() => {
    // login
    cy.getAuth().login();
    cy.hideCookieBanner();
    cy.disableGainsight();

    cy.visitAndWaitForSelector(
      '/apps/administration/index.html?remotes=%7B%22sag-ps-pkg-dynamic-mapping%22%3A%5B%22DynamicMappingModule%22%5D%7D',
      'en',
      '#navigator'
    );
    cy.get('[data-cy="Settings"]').should('exist').click();
    cy.get('[data-cy="Dynamic Mapping"]')
      .should('exist')
      .should('be.visible')
      .click();

    // navigate to configuration
    cy.get('a[title="Connector"]').as('configuration').should('exist');
    cy.get('@configuration').click();
  });

  before(function () {
    cy.fixture('mqttConnectionInput').then((data) => {
      this.mqttConnectionInput = data;
    });

    // Setup interceptors
    cy.intercept(
      'POST',
      '/service/dynamic-mapping-service/configuration/connector/instance'
    ).as('postMqttConnection');
    cy.intercept(
      'GET',
      '/service/dynamic-mapping-service/configuration/connector/specifications'
    ).as('getConnectorSpecifications');
  });

  it('Add connector', function () {
    // click button 'Add configuration'
    cy.get('#addConfiguration').should('exist').click();
    cy.wait('@getConnectorSpecifications');
    // cy.get('#connectorType').should('exist').should('be.visible');
    cy.get('#connectorType')
      .should('exist')
      .should('be.visible')
      .select('MQTT');
    // // fill in form
    cy.get('#name').type(this.mqttConnectionInput.name);
    cy.get('#mqttHost').type(this.mqttConnectionInput.mqttHost);
    cy.get('#mqttPort').type(this.mqttConnectionInput.mqttPort);
    cy.get('#user').type(this.mqttConnectionInput.user);
    cy.get('#password').type(this.mqttConnectionInput.password);
    cy.get('#clientId').type(this.mqttConnectionInput.clientId);

    // // save
    // cy.get('.modal-footer > .btn-primary').click();
    cy.get('button[title="Save"]').click();
    // // record post request of new connector
    cy.wait('@postMqttConnection').then((interception) => {
      const requestData = interception.request.body;
      cy.writeFile(
        'cypress/fixtures/mqttConnectionPostRequest.json',
        requestData
      );
    });
    cy.screenshot();
  });

  it('Delete connector', function () {
    // navigate to configuration
    cy.get('a[title="Connector"]').as('configuration').should('exist');
    cy.get('@configuration').click();

    // read ident from previously added connector
    cy.fixture('mqttConnectionPostRequest').then((data) => {
      this.mqttConnectionPostRequest = data;
      // cy.get(`#connector_${this.mqttConnectionPostRequest.ident} btn[title="Action"]`)
      // identify respective row with connector
      cy.get(`#connector_${this.mqttConnectionPostRequest.ident}`, { timeout: 10000 }).click();
      cy.get('.dropdown #delete').click({ force: true });
      cy.get('[data-cy="c8y-confirm-modal--ok"]').click();
    });
  });
});
