/**
 * UI helpers shared by the specs. Keep selectors on `data-cy` hooks (`dm-<area>-<element>`).
 */

export const INBOUND_MAPPINGS_ROUTE = 'c8y-pkg-dynamic-mapper/node1/mappings/inbound';

/**
 * The mappings page may show a one-off deprecation notice modal that covers the toolbar;
 * dismiss it via its data-cy hook if present.
 */
export const dismissDeprecationNotice = (): void => {
  cy.get('body').then(($body) => {
    if ($body.find('[data-cy="dm-deprecation-accept"]').length) {
      cy.getByData('dm-deprecation-accept').click();
    }
  });
};

/**
 * Open the inbound mappings page. Mirrors the NavigatorNode path in
 * src/shared/misc/navigation.factory.ts. The deprecation modal overlays (and hides) the shell,
 * so wait only for `body`; callers wait for the element they need after dismissing the modal.
 */
export const openInboundMappings = (): void => {
  cy.visitShellAndWaitForSelector(INBOUND_MAPPINGS_ROUTE, 'en', 'body', 60000);
};

/** Pick an option in a c8y-select identified by its data-cy hook. */
export const selectC8yOption = (hook: string, optionText: string): void => {
  cy.getByData(hook).click();
  cy.get('.dropdown-menu').filter(':visible').contains(optionText).click();
};

/** The c8y-stepper-buttons host carries the data-cy; click the labelled button inside. */
export const stepperButton = (hook: string, label: string): Cypress.Chainable =>
  cy.getByData(hook).find('button').contains(label).click();

/**
 * The c8y action bar collapses overflowing buttons into a "more" menu, so a hook can match
 * both an inline and a hidden copy: target the visible one.
 */
export const toolbarButton = (hook: string): Cypress.Chainable<JQuery<HTMLElement>> =>
  cy.getByData(hook).filter(':visible').first();

/**
 * Navigate away from the app before teardown code that clears cookies (REST cleanup does).
 * Otherwise the still-open app loses its session, redirects to a login page and the next
 * command can hang on a page load that never completes.
 */
export const leaveApp = (): void => {
  // cy.visit('about:blank') is not supported (resolved against baseUrl -> 404), so serve a
  // stub page from the dev server's origin and visit that.
  cy.intercept('GET', '/cypress-blank', {
    headers: { 'content-type': 'text/html' },
    body: '<!doctype html><title>blank</title>',
  });
  cy.visit('/cypress-blank');
};
