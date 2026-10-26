import type { IUser } from '@c8y/client';
import {
  addConnectorViaUi,
  cleanupTestUser,
  createConnectorTestUser,
  deleteConnectorViaApi,
  getConnector,
  navigateToConnectorConfiguration,
  openConnectorRowAction,
  setupTestUser,
  type MqttConnectionInput,
} from './connector.helpers';
import { formField } from '../../support/dm-ui';

describe('Connector — Duplicate connector', () => {
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

  it('duplicates a connector under a new name', () => {
    const originalName = `${mqttConnectionInput.name}-dup-${Date.now()}`;
    const copyName = `${mqttConnectionInput.name}-copy-${Date.now()}`;

    addConnectorViaUi(mqttConnectionInput, originalName).then((originalId) => {
      createdConnectorIds.push(originalId);

      openConnectorRowAction(originalId, 'Duplicate');

      // The drawer opens in "create" mode, pre-filled from the original: the type is fixed and
      // the broker properties are copied. Only the name has to be made unique.
      formField('name').should('be.visible').clear();
      formField('name').type(copyName);
      formField('properties-mqttHost').should('have.value', mqttConnectionInput.mqttHost);
      cy.getByData('dm-connector-save').should('be.enabled').click();

      cy.wait('@postConnector').then((interception) => {
        expect(interception.response?.statusCode, 'status').to.be.within(200, 299);
        const copyId = String(
          interception.response?.body?.identifier ?? interception.request.body?.identifier
        );
        expect(copyId, 'copy identifier').to.not.equal(originalId);
        createdConnectorIds.push(copyId);

        getConnector(copyId).should('contain', copyName);
        getConnector(originalId).should('contain', originalName);
      });
    });
  });
});
