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

/** Read a mapping (GET /mapping/<id>). */
export const getMappingViaApi = (id: string): Cypress.Chainable<Cypress.Response<any>> => {
  cy.clearCookies();
  return cy.request({ url: `${DM_SERVICE}/mapping/${id}`, auth: adminAuth() });
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
    }
  );
};
