import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from './connector/connector.helpers';
import { dismissDeprecationNotice } from '../support/dm-ui';

const APP = 'c8y-pkg-dynamic-mapper';

/** Every page reachable from the navigator (node1 = mapping, node2 = monitoring, node3 = config). */
const ROUTES = [
  'node1/mappings/inbound',
  'node1/mappings/outbound',
  'node1/mappings/subscription/static',
  'node1/mappings/subscription/dynamic',
  'node1/mappings/relation/deviceToClientMap',
  'node1/mappings/messageExplorer',
  'node2/tree',
  'node2/monitoring/statistic/inbound',
  'node2/monitoring/statistic/outbound',
  'node2/monitoring/statistic/chart',
  'node2/monitoring/serviceEvent',
  'node2/monitoring/cache',
  'node2/monitoring/versions/inbound',
  'node2/monitoring/versions/outbound',
  'node2/monitoring/logs',
  'node3/connectorConfiguration',
  'node3/serviceConfiguration',
  'node3/codeTemplate/INBOUND_SMART_FUNCTION',
  'node3/codeTemplate/OUTBOUND_SMART_FUNCTION',
  'node3/codeTemplate/others',
  'node3/processorExtension',
];

/**
 * Smoke test: every page of the app opens for a mapper admin without an error toast and
 * without a failing backend call (any 4xx/5xx from the dynamic-mapper-service fails the test
 * and names the page).
 */
describe('Navigation — every page loads', () => {
  let testUser: IUser;

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    cleanupTestUser(testUser);
  });

  it('opens all pages without errors', () => {
    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();

    const failures: string[] = [];
    let current = '';
    let inFlight = 0;
    cy.intercept('/service/dynamic-mapper-service/**', (req) => {
      inFlight++;
      req.continue((res) => {
        inFlight--;
        if (res.statusCode >= 400) {
          failures.push(`${current}: ${req.method} ${req.url.split('/service/dynamic-mapper-service')[1]} -> ${res.statusCode}`);
        }
      });
    });

    ROUTES.forEach((route) => {
      cy.then(() => {
        current = route;
      });
      cy.visitShellAndWaitForSelector(`${APP}/${route}`, 'en', 'body', 60000);
      dismissDeprecationNotice();
      // Let the page issue its initial requests, then wait until all of them have finished,
      // otherwise the error checks below would run before anything could fail.
      // eslint-disable-next-line cypress/no-unnecessary-waiting -- no event marks "page started loading"
      cy.wait(1000);
      cy.wrap(null, { timeout: 30000 }).should(() => {
        expect(inFlight, `pending backend calls on ${route}`).to.eq(0);
      });
      // No error toast.
      cy.get('c8y-alert-outlet .alert-danger, c8y-alert-outlet .alert.alert-danger').should(
        'not.exist'
      );
    });

    cy.then(() => {
      expect(failures, 'failed backend calls').to.deep.equal([]);
    });
  });
});
