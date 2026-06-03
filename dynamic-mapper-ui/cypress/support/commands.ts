/// <reference types="cypress" />

declare global {
  namespace Cypress {
    interface Chainable {
      getByData(value: string): Chainable<JQuery<HTMLElement>>;
      /**
       * Visit a page inside the configured shell application and wait for a selector.
       * Constructs the URL using C8Y_SHELL_TARGET and adds C8Y_SHELL_EXTENSION remotes
       * as query parameters — mirrors the official Cumulocity Cypress guide pattern.
       */
      visitShellAndWaitForSelector(
        url: string,
        language?: 'en' | 'de',
        selector?: string,
        timeout?: number
      ): Chainable<void>;
    }
  }
}

export function registerCommands() {
  Cypress.Commands.add('getByData', (selector: string) => {
    return cy.get(`[data-cy=${selector}]`);
  });

  Cypress.Commands.add(
    'visitShellAndWaitForSelector',
    (
      url: string,
      language: 'en' | 'de' = 'en',
      selector = 'c8y-navigator-outlet c8y-app-icon',
      timeout = Cypress.config().pageLoadTimeout || 60000
    ) => {
      if (Cypress.env('C8Y_SHELL_TARGET')) {
        const app = Cypress.env('C8Y_SHELL_TARGET') as string;
        url = `/apps/${app}/index.html#/${url}`;
      }

      cy.setLanguage(language);

      if (Cypress.env('C8Y_SHELL_EXTENSION')) {
        const plugins = Cypress.env('C8Y_SHELL_EXTENSION') as string;
        cy.visit(url, { qs: { remotes: plugins } });
      } else {
        cy.visit(url);
      }

      cy.get(selector, { timeout }).should('be.visible');
    }
  );
}