import type { IUser } from '@c8y/client';
import {
  createConnectorTestUser,
  setupTestUser,
  cleanupTestUser,
} from '../connector/connector.helpers';
import {
  createMappingViaApi,
  deleteMappingViaApi,
  getMappingViaApi,
} from '../../support/dm-api';
import { dismissDeprecationNotice, openInboundMappings } from '../../support/dm-ui';


/**
 * Inbound JSON → C8Y Event mapping payload — a faithful port of
 * resources/testing/integration/test-inbound-json-jsonata.sh (mappingTopic dmtest/event/+,
 * targetAPI EVENT, DEFAULT transformation with _TOPIC_LEVEL_, txt→text, msg_type→type
 * and $now()→time substitutions, createNonExistingDevice via c8y_Serial external id).
 */
const buildMappingPayload = (suffix: number) => ({
  name: `test-inbound-json-jsonata-${suffix}`,
  identifier: `ibj${suffix}`,
  mappingTopic: 'dmtest/event/+',
  mappingTopicSample: `dmtest/event/dmtest-jsonata-${suffix}`,
  targetAPI: 'EVENT',
  direction: 'INBOUND',
  mappingType: 'JSON',
  transformationType: 'DEFAULT',
  sourceTemplate:
    '{"msg_type":"c8y_TestEvent","txt":"hello world","td":"2022-09-08T16:21:53.389+02:00"}',
  targetTemplate:
    '{"text":"event text","time":"2022-08-05T00:14:49.389+02:00","type":"c8y_TestEvent"}',
  substitutions: [
    {
      pathSource: '_TOPIC_LEVEL_[2]',
      pathTarget: '_IDENTITY_.externalId',
      repairStrategy: 'DEFAULT',
      expandArray: false,
    },
    { pathSource: 'txt', pathTarget: 'text', repairStrategy: 'DEFAULT', expandArray: false },
    { pathSource: 'msg_type', pathTarget: 'type', repairStrategy: 'DEFAULT', expandArray: false },
    { pathSource: '$now()', pathTarget: 'time', repairStrategy: 'DEFAULT', expandArray: false },
  ],
  active: false,
  debug: false,
  createNonExistingDevice: true,
  updateExistingDevice: false,
  useExternalId: true,
  externalIdType: 'c8y_Serial',
  genericDeviceIdentifier: '_IDENTITY_.externalId',
  qos: 'AT_LEAST_ONCE',
});

describe('Mapping — create inbound JSON→EVENT (JSONata) mapping', () => {
  let testUser: IUser;
  let mappingId = '';
  let mappingName = '';

  before(() => {
    Cypress.session.clearAllSavedSessions();
    testUser = createConnectorTestUser();
    setupTestUser(testUser);
  });

  after(() => {
    if (mappingId) {
      deleteMappingViaApi(mappingId);
    }
    cleanupTestUser(testUser);
  });

  it('creates the mapping via the dynamic-mapper API and shows it in the inbound grid', () => {
    const suffix = Date.now();
    const payload = buildMappingPayload(suffix);
    mappingName = payload.name;

    // 1. Create the mapping (POST /mapping) — like dm_create_mapping in the script.
    createMappingViaApi(payload).then((id) => {
      mappingId = id;
      cy.log(`created mapping id=${mappingId} name=${mappingName}`);
    });

    // 2. Verify it was persisted with the expected shape (GET /mapping/<id>).
    cy.then(() => {
      getMappingViaApi(mappingId).then(
        (resp) => {
          const m = resp.body;
          expect(m.name, 'name').to.eq(mappingName);
          expect(m.direction, 'direction').to.eq('INBOUND');
          expect(m.targetAPI, 'targetAPI').to.eq('EVENT');
          expect(m.mappingType, 'mappingType').to.eq('JSON');
          expect(m.mappingTopic, 'mappingTopic').to.eq('dmtest/event/+');
          expect(m.substitutions, 'substitutions').to.have.length(4);
        }
      );
    });

    // 3. Log in as the test user and open the inbound mappings page.
    cy.getAuth(testUser.userName, testUser.password as string)
      .login()
      .disableGainsight();
    openInboundMappings();
    cy.get('c8y-data-grid', { timeout: 60000 }).should('exist');
    dismissDeprecationNotice();

    // 4. Filter the grid to our mapping (the grid paginates ~25/page), then assert
    //    its row via the per-row data-cy hook on the name cell (dm-mapping-id-<id>,
    //    rendered by MappingIdCellRendererComponent). MappingEnriched.id is the
    //    mapping's inventory id — the same id POST /mapping returned.
    cy.then(() => {
      cy.get('c8y-data-grid input[type="search"]').clear();
      cy.get('c8y-data-grid input[type="search"]').type(mappingName);
      cy.get(`[data-cy="dm-mapping-id-${mappingId}"]`, { timeout: 15000 })
        .should('exist')
        .and('contain', mappingName);
    });

    cy.screenshot('mapping-inbound-jsonata-created');
  });
});
