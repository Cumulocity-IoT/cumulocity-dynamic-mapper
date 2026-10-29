/**
 * REST helpers for test setup/teardown against the dynamic-mapper-service.
 *
 * Use these instead of the UI for everything that is not the flow under test. All calls use
 * explicit admin basic auth and clear cookies first: after cy.login the OAI-Secure session
 * cookie is auto-attached and its XSRF protection rejects cookie-auth'd POST/DELETE that do
 * not carry X-XSRF-TOKEN.
 */
export const DM_SERVICE = '/service/dynamic-mapper-service';

export const adminAuth = (): { user: string; pass: string } => ({
  user: Cypress.env('C8Y_USERNAME'),
  pass: Cypress.env('C8Y_PASSWORD'),
});

/** Create a mapping (POST /mapping) and yield its id. */
export const createMappingViaApi = (payload: object): Cypress.Chainable<string> => {
  cy.clearCookies();
  return cy
    .request({ method: 'POST', url: `${DM_SERVICE}/mapping`, auth: adminAuth(), body: payload })
    .then((resp) => {
      expect(resp.status, 'create mapping status').to.be.oneOf([200, 201]);
      const id = String(resp.body.id);
      expect(id, 'created mapping id').to.match(/^\d+$/);
      return id;
    });
};

/**
 * Read a mapping (GET /mapping/<id>) with admin basic auth. Call it only while no UI session is
 * open (before login, or in teardown): cy.request attaches the browser's session cookie, and a
 * cookie plus basic auth conflict with a 401. Clearing cookies is not an option while the UI is
 * under test, because it logs the test user out. While the UI is open, assert on the UI's own
 * network calls with cy.intercept instead.
 */
export const getMappingViaApi = (id: string): Cypress.Chainable<Cypress.Response<any>> => {
  cy.clearCookies();
  return cy.request({ url: `${DM_SERVICE}/mapping/${id}`, auth: adminAuth() });
};

/** Read a mapping's unpublished draft (GET /mapping/<id>/draft); same cookie rule as above. */
export const getDraftViaApi = (id: string): Cypress.Chainable<Cypress.Response<any>> => {
  cy.clearCookies();
  return cy.request({ url: `${DM_SERVICE}/mapping/${id}/draft`, auth: adminAuth() });
};

/** Deactivate (an active mapping cannot be deleted) and delete a mapping; never fails. */
export const deleteMappingViaApi = (id: string): void => {
  cy.clearCookies();
  cy.request({
    method: 'POST',
    url: `${DM_SERVICE}/operation`,
    auth: adminAuth(),
    body: { operation: 'ACTIVATE_MAPPING', parameter: { id, active: 'false' } },
    failOnStatusCode: false,
  });
  cy.clearCookies();
  cy.request({
    method: 'DELETE',
    url: `${DM_SERVICE}/mapping/${id}`,
    auth: adminAuth(),
    failOnStatusCode: false,
  });
};

/** Delete every mapping with the given name (for mappings created through the UI). */
export const deleteMappingsByName = (name: string): void => {
  cy.clearCookies();
  cy.request({ url: `${DM_SERVICE}/mapping`, auth: adminAuth(), failOnStatusCode: false }).then(
    (resp) => {
      const all = Array.isArray(resp.body) ? resp.body : resp.body?.mappings || [];
      all
        .filter((m: { name?: string }) => m.name === name)
        .forEach((m: { id: string }) => deleteMappingViaApi(m.id));
    },
  );
};

/**
 * Guaranteed cleanup: delete every mapping whose name starts with "cypress" (case-insensitive),
 * tracked by the current run or not. Safe in a global after() hook.
 */
export const deleteAllCypressMappings = (): void => {
  cy.clearCookies();
  cy.request({ url: `${DM_SERVICE}/mapping`, auth: adminAuth(), failOnStatusCode: false }).then(
    (resp) => {
      const all = Array.isArray(resp.body) ? resp.body : resp.body?.mappings || [];
      all
        .filter((m: { name?: string }) => /^cypress/i.test(m.name || ''))
        .forEach((m: { id: string }) => deleteMappingViaApi(m.id));
    },
  );
};

/**
 * Guaranteed cleanup: delete every test user ("cypress-*") that a run failed to remove, for
 * example because its after() hook never ran.
 */
export const deleteAllCypressUsers = (): void => {
  const tenant = Cypress.env('C8Y_TENANT');
  cy.clearCookies();
  cy.request({
    url: `/user/${tenant}/users?pageSize=2000`,
    auth: adminAuth(),
    failOnStatusCode: false,
  }).then((resp) => {
    const users: Array<{ userName?: string }> = resp.body?.users || [];
    users
      .filter((u) => (u.userName || '').startsWith('cypress-'))
      .forEach((u) =>
        cy.request({
          method: 'DELETE',
          url: `/user/${tenant}/users/${encodeURIComponent(u.userName as string)}`,
          auth: adminAuth(),
          failOnStatusCode: false,
        }),
      );
  });
};

/**
 * Read the tenant-wide device-type subscription (GET /subscription/type). Yields the subscribed
 * types, or null when the endpoint is unavailable (for example outbound mapping disabled).
 * Same cookie rule as getMappingViaApi: call it only while no UI session is open.
 */
export const getTypeSubscriptionViaApi = (): Cypress.Chainable<string[] | null> => {
  cy.clearCookies();
  return cy
    .request({
      url: `${DM_SERVICE}/subscription/type`,
      auth: adminAuth(),
      failOnStatusCode: false,
    })
    .then((resp) => (resp.status === 200 ? ((resp.body?.types as string[]) ?? []) : null));
};

/** Replace the tenant-wide device-type subscription (PUT /subscription/type); never fails. */
export const setTypeSubscriptionViaApi = (types: string[]): void => {
  cy.clearCookies();
  cy.request({
    method: 'PUT',
    url: `${DM_SERVICE}/subscription/type`,
    auth: adminAuth(),
    body: { api: 'ALL', types },
    failOnStatusCode: false,
  });
};

/** Guaranteed cleanup: drop every "Cypress*" type from the type subscription, keep the rest. */
export const removeCypressTypeSubscriptions = (): void => {
  getTypeSubscriptionViaApi().then((types) => {
    if (types?.some((t) => t.startsWith('Cypress'))) {
      setTypeSubscriptionViaApi(types.filter((t) => !t.startsWith('Cypress')));
    }
  });
};

/**
 * Read the connector identifiers a mapping is deployed to (GET /deployment/defined/<identifier>,
 * yields a JSON array). The endpoint answers 415 without a JSON content-type, even for a GET.
 */
export const getDefinedDeploymentViaApi = (
  identifier: string,
): Cypress.Chainable<Cypress.Response<any>> => {
  cy.clearCookies();
  return cy.request({
    url: `${DM_SERVICE}/deployment/defined/${identifier}`,
    auth: adminAuth(),
    headers: { 'content-type': 'application/json' },
  });
};
