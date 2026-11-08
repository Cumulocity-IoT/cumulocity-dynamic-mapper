/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

/// <reference types="cypress" />

declare global {
  // Cypress extends its types through declaration merging, which requires a namespace.
  // eslint-disable-next-line @typescript-eslint/no-namespace
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