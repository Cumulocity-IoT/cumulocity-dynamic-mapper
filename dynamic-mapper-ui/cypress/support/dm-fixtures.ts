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
  name: `cypress-inbound-json-jsonata-${suffix}`,
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
  name: `cypress-outbound-event-${suffix}`,
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

/**
 * Inbound SMART_FUNCTION mapping payload (inactive). The function creates one event whose text is
 * `marker`, so a spec can edit the code and see the changed text in the dry-run result. Modelled
 * on resources/testing/integration/test-inbound-smartfunction-03.sh; the code travels
 * base64-encoded and ends with `export { onMessage };`, which the Smart Function loader requires.
 */
export const buildInboundSmartFunctionMapping = (suffix: number, marker: string) => ({
  name: `cypress-sf-${suffix}`,
  identifier: `cysf${suffix}`,
  mappingTopic: `cysf/${suffix}/+`,
  mappingTopicSample: `cysf/${suffix}/dev1`,
  targetAPI: 'EVENT',
  direction: 'INBOUND',
  mappingType: 'JSON',
  transformationType: 'SMART_FUNCTION',
  sourceTemplate: '{"trigger":"cypress"}',
  targetTemplate: '{}',
  substitutions: [],
  code: btoa(smartFunctionCode(marker)),
  active: false,
  debug: false,
  // a dry run resolves devices against a mock (nothing is created in the tenant)
  createNonExistingDevice: true,
  useExternalId: true,
  externalIdType: 'c8y_Serial',
  genericDeviceIdentifier: '_IDENTITY_.externalId',
  qos: 'AT_LEAST_ONCE',
});

/** Smart Function source that emits one event with the given text. */
export const smartFunctionCode = (marker: string): string =>
  `function onMessage(msg, context) {
  return [{
    cumulocityType: "event",
    action: "create",
    payload: { type: "cypress_SmartFunction", text: "${marker}", time: new Date().toISOString() },
    externalSource: [{ type: "c8y_Serial", externalId: "cypress-device" }]
  }];
}

export { onMessage };
`;
