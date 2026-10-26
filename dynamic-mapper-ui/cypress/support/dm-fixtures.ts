/**
 * Reusable REST payloads for test setup.
 */

/**
 * Inbound JSON → C8Y Event mapping payload — a faithful port of
 * resources/testing/integration/test-inbound-json-jsonata.sh (mappingTopic dmtest/event/+,
 * targetAPI EVENT, DEFAULT transformation with _TOPIC_LEVEL_, txt→text, msg_type→type
 * and $now()→time substitutions, createNonExistingDevice via c8y_Serial external id).
 */
export const buildInboundEventMapping = (suffix: number) => ({
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

/**
 * Outbound C8Y EVENT -> broker mapping payload, a port of
 * resources/testing/integration/test-outbound-event.sh (inactive, DEFAULT transformation).
 */
export const buildOutboundEventMapping = (suffix: number) => ({
  name: `test-outbound-event-${suffix}`,
  identifier: `oevt${suffix}`,
  mappingTopic: 'dmtest/out/event',
  mappingTopicSample: 'dmtest/out/event',
  publishTopic: 'dmtest/out/event',
  publishTopicSample: 'dmtest/out/event',
  targetAPI: 'EVENT',
  direction: 'OUTBOUND',
  mappingType: 'JSON',
  transformationType: 'DEFAULT',
  filterMapping: 'true',
  sourceTemplate:
    '{"text":"event text","time":"2022-08-05T00:14:49.389+02:00","type":"c8y_TestEvent"}',
  targetTemplate: '{"type":"c8y_TestEvent","text":"hello","deviceId":"source-id"}',
  substitutions: [
    {
      pathSource: '_IDENTITY_.externalId',
      pathTarget: 'deviceId',
      repairStrategy: 'DEFAULT',
      expandArray: false,
    },
  ],
  active: false,
  debug: false,
  useExternalId: true,
  externalIdType: 'c8y_Serial',
  qos: 'AT_LEAST_ONCE',
});
