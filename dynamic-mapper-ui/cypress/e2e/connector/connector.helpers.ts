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

const CONNECTOR_INSTANCE_URL =
  '/service/dynamic-mapper-service/configuration/connector/instance';

/**
 * Explicit basic-auth (technical/admin user from env) for cy.request calls.
 * cy.request does NOT carry the HttpOnly OAI-Secure session cookie, so admin API
 * calls (role assignment, connector cleanup) must authenticate explicitly —
 * otherwise they silently 401 and, with failOnStatusCode:false, leak resources.
 */
const adminAuth = (): { user: string; pass: string } => ({
  user: Cypress.env('C8Y_USERNAME'),
  pass: Cypress.env('C8Y_PASSWORD'),
});

/**
 * Test user for connector tests - follows official Cumulocity pattern
 * @see https://community.cumulocity.com/t/cumulocity-app-and-plugin-testing-with-cypress-getting-started/7522
 */
export const createConnectorTestUser = (): IUser => {
  // Use a single unique suffix for userName, email AND displayName. Cumulocity
  // enforces uniqueness on the displayName/alias too (not just userName), so a
  // hardcoded displayName causes a 409 "users/Duplicate" on reruns whenever a
  // prior run's cleanup didn't complete.
  const suffix = Date.now();
  return {
    userName: `cypress-connector-${suffix}`,
    password: 'ZVfJbDXuN!3t',
    displayName: `Cypress Connector Test User ${suffix}`,
    email: `cypress-connector-${suffix}@test.local`,
  } as IUser;
};

/**
 * Setup test user before running connector tests
 * Uses technical user (from env vars) to create a test user with appropriate roles
 */
export const setupTestUser = (testUser: IUser): void => {
  cy.getAuth().login();
  // The dynamic-mapper UI is its own standalone app (name "Dynamic Mapper",
  // contextPath c8y-pkg-dynamic-mapper). The test opens it directly, so the user
  // must have access to it — otherwise the app bounces to login in a redirect loop.
  cy.createUser(testUser, ['business'], ['administration', 'cockpit', 'Dynamic Mapper']);
  // createUser's `roles` argument assigns global role *groups* (via groupByName),
  // not individual ROLE_* permissions. The Dynamic Mapper plugin only activates
  // (registers its navigation + routes, and its REST calls succeed) for users
  // holding ROLE_DYNAMIC_MAPPER_ADMIN, so assign that microservice role directly.
  assignRoleToUser(testUser.userName as string, 'ROLE_DYNAMIC_MAPPER_ADMIN');
};

/**
 * Assign an individual ROLE_* permission to a user (not a global role group).
 * Resolves the role's self reference, then POSTs a role reference to the user.
 */
const assignRoleToUser = (userName: string, roleId: string): void => {
  const tenant = Cypress.env('C8Y_TENANT');
  const auth = adminAuth();
  cy.request({ url: `/user/roles/${roleId}`, auth }).then((roleResp) => {
    cy.request({
      method: 'POST',
      url: `/user/${tenant}/users/${userName}/roles`,
      auth,
      body: { role: { self: roleResp.body.self } },
      // 409 if the role is already assigned — harmless on reruns
      failOnStatusCode: false,
    });
  });
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
  // Navigate directly to the connector configuration route. The plugin's
  // navigator nodes carry no data-cy attributes and the menu labels are
  // "Configuration" → "Connectors" (not "Settings"/"Dynamic Mapper"/"Connector"),
  // so clicking through the nav is brittle. The route path mirrors the
  // NavigatorNode path in src/shared/misc/navigation.factory.ts
  // (`/c8y-pkg-dynamic-mapper/node3/connectorConfiguration`). Wait for the
  // Add-connector button via its data-cy hook (locale-proof, unlike its title).
  cy.visitShellAndWaitForSelector(
    'c8y-pkg-dynamic-mapper/node3/connectorConfiguration',
    'en',
    '[data-cy="connector-add"]',
    60000
  );
};

/**
 * Get a connector's grid row name link by its identifier (data-cy hook added in
 * connector-link.renderer.component.ts). Stable across renames and locales.
 */
export const getConnector = (
  identifier: string
): Cypress.Chainable<JQuery<HTMLElement>> =>
  // Use a generous timeout: the grid reloads asynchronously after create/update,
  // and the status fetch can lag when connectors are actively (re)connecting.
  cy.get(`[data-cy="connector-name-${identifier}"]`, { timeout: 15000 });

/**
 * Fill MQTT connector form with provided values
 */
export const fillMqttConnectorForm = (
  mqttConnectionInput: MqttConnectionInput,
  connectorName: string
): void => {
  cy.get('#connectorType').should('exist').should('be.visible');
  // The type <option>s populate asynchronously from the connector specifications;
  // wait for them to load (beyond the placeholder) before selecting to avoid a race.
  cy.get('#connectorType option').should('have.length.greaterThan', 1);
  cy.get('#connectorType').select('MQTT');
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
  cy.getByData('connector-add').should('be.enabled').click();
  cy.wait('@getConnectorSpecifications');
  fillMqttConnectorForm(mqttConnectionInput, connectorName);
  // The drawer's save button stays disabled until the form is valid, so wait
  // for it to enable before clicking.
  cy.getByData('connector-save').should('be.enabled').click();

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
 * Open a connector row's action (by connector identifier) and click an action by
 * its label (e.g. 'Edit', 'Delete', 'Duplicate'). The row is located via its
 * data-cy name hook; the action controls are defined in
 * src/shared/connector-configuration/action-controls.ts and are only offered for
 * disabled connectors held by an admin user.
 */
export const openConnectorRowAction = (
  identifier: string,
  actionLabel: string
): void => {
  getConnector(identifier).closest('[role="row"]').as('connectorRow');

  // c8y renders the Edit action as a dedicated primary button in the row
  // (data-cy="c8y-data-grid--edit-button-in-row"); the remaining actions
  // (Duplicate, Delete) live in the row's overflow "Actions" menu.
  if (actionLabel === 'Edit') {
    cy.get('@connectorRow')
      .find('[data-cy="c8y-data-grid--edit-button-in-row"]')
      .click({ force: true });
    return;
  }

  cy.get('@connectorRow')
    .find('[data-cy="c8y-data-grid--row-actions-dropdown"]')
    .click({ force: true });
  cy.get('.dropdown-menu').filter(':visible').contains(actionLabel).click({
    force: true,
  });
};

/**
 * Toggle a connector's enabled state (by identifier) via the "Enabled" column
 * switch. This triggers a CONNECT/DISCONNECT operation (POST .../operation), NOT a
 * connector PUT — see ConnectorStatusEnabledRendererComponent.
 */
export const toggleConnectorEnabled = (identifier: string): void => {
  cy.getByData(`connector-toggle-${identifier}`).click({ force: true });
};

/**
 * Delete a connector via the UI: open its row action menu, choose "Delete", and
 * confirm the modal.
 */
export const deleteConnectorViaUi = (identifier: string): void => {
  openConnectorRowAction(identifier, 'Delete');
  cy.get('[data-cy="c8y-confirm-modal--ok"]').click();
};

/**
 * Clean up a single connector via the API, authenticating with admin basic auth.
 *
 * We clear cookies first: after cy.login the browser holds an OAI-Secure session
 * cookie that cy.request auto-attaches. OAI-Secure enforces XSRF on state-changing
 * requests (DELETE), but cy.request doesn't send X-XSRF-TOKEN — so the cookie-auth'd
 * DELETE is rejected and (with failOnStatusCode:false) the connector silently leaks.
 * Stripping cookies forces clean basic-auth, which the proxy forwards to the tenant.
 */
export const deleteConnectorViaApi = (identifier: string): void => {
  cy.clearCookies();
  cy.request({
    method: 'DELETE',
    url: `${CONNECTOR_INSTANCE_URL}/${identifier}`,
    auth: adminAuth(),
    failOnStatusCode: false,
  });
};

/**
 * Guaranteed cleanup: delete EVERY connector whose name starts with "Cypress",
 * regardless of whether the current run tracked it. This returns the tenant to its
 * original state even if individual tests failed before their afterEach ran or
 * leaked an untracked connector. Safe to call in a global after() hook.
 */
export const deleteAllCypressConnectors = (): void => {
  cy.request({
    url: CONNECTOR_INSTANCE_URL,
    auth: adminAuth(),
    failOnStatusCode: false,
  }).then((resp) => {
    const body = resp.body as unknown;
    const items: Array<{ name?: string; identifier?: string }> = Array.isArray(
      body
    )
      ? body
      : (body as { connectors?: []; configurations?: [] })?.connectors ||
        (body as { configurations?: [] })?.configurations ||
        [];
    items
      .filter((c) => (c?.name || '').startsWith('Cypress') && c?.identifier)
      .forEach((c) => deleteConnectorViaApi(c.identifier as string));
  });
};
