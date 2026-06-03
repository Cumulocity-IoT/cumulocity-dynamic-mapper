/* eslint-disable @typescript-eslint/no-unused-vars */
/* eslint-disable @typescript-eslint/no-namespace */
/// <reference types="cypress" />

declare global {
  namespace Cypress {
    interface Chainable<Subject = any> {
        getByData(value: string): Chainable<any>
    }
  }
}
export {};
