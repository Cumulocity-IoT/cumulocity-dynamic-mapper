/**
 * UI helpers shared by the specs. Keep selectors on `data-cy` hooks (`dm-<area>-<element>`).
 */

export const INBOUND_MAPPINGS_ROUTE = 'c8y-pkg-dynamic-mapper/node1/mappings/inbound';
export const OUTBOUND_MAPPINGS_ROUTE = 'c8y-pkg-dynamic-mapper/node1/mappings/outbound';

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

/** Open the outbound mappings page (see openInboundMappings for the loading caveat). */
export const openOutboundMappings = (): void => {
  cy.visitShellAndWaitForSelector(OUTBOUND_MAPPINGS_ROUTE, 'en', 'body', 60000);
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

/**
 * Drive the Add-mapping flow for an inbound SMART_FUNCTION mapping up to (and including) the
 * test step of the stepper: type drawer (expert mode, Smart Function), connector, general
 * settings (name + topic), default templates and default code. Leaves the stepper on the test
 * step; the caller decides whether to dry-run, confirm or cancel.
 *
 * A SMART_FUNCTION is used on purpose: it needs no device-identifier substitution, so the
 * validity gate passes without clicking template-tree nodes. Hooks: dm-mapping-*, plus the Formly
 * ids #mappingName / #mappingTopic / #mappingTopicSample (stable, defined in the form).
 */
export const driveSmartFunctionStepperToTestStep = (name: string, topic: string): void => {
  // 1. Open the Add-mapping type drawer.
  cy.getByData('dm-mapping-add').filter(':visible').first().click();

  // 2. Type drawer: expert mode -> Smart Function transformation -> Continue. Wait for the
  //    code-template select to load + auto-select a template, otherwise the new mapping has no
  //    code and the stepper won't open.
  cy.getByData('dm-mapping-type-expert-mode-toggle').click({ force: true });
  selectC8yOption('dm-mapping-type-transformation-type-select', 'Smart Function');
  cy.getByData('dm-mapping-type-code-template-select').should('be.visible');
  cy.getByData('dm-mapping-type-continue').should('be.enabled').click();

  // 3. Connector step: selecting a row enables Next (the header select-all has no hook).
  cy.getByData('dm-mapping-stepper-buttons-connector').should('be.visible');
  cy.get('d11r-mapping-connector [data-cy="c8y-data-grid--checkbox"]')
    .first()
    .click({ force: true });
  cy.getByData('dm-mapping-stepper-buttons-connector')
    .find('button')
    .contains('Next')
    .should('not.be.disabled');
  stepperButton('dm-mapping-stepper-buttons-connector', 'Next');

  // 4. General settings: name + topic. The sample must have the same number of topic levels
  //    as the mapping topic; ours has no wildcards, so the sample is identical.
  cy.get('#mappingName').clear();
  cy.get('#mappingName').type(name);
  cy.get('#mappingTopic').clear();
  cy.get('#mappingTopic').type(topic);
  cy.get('#mappingTopicSample').clear();
  cy.get('#mappingTopicSample').type(topic);
  cy.getByData('dm-mapping-stepper-buttons-general')
    .find('button')
    .contains('Next')
    .should('not.be.disabled');
  stepperButton('dm-mapping-stepper-buttons-general', 'Next');

  // 5. Templates and 6. transformation code: accept the defaults.
  stepperButton('dm-mapping-stepper-buttons-templates', 'Next');
  stepperButton('dm-mapping-stepper-buttons-transformation', 'Next');

  // 7. The test step is now showing.
  cy.getByData('dm-mapping-stepper-buttons-test').should('be.visible');
};
