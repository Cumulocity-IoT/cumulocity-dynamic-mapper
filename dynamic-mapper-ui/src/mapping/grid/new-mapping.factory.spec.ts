/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { buildNewMapping, ExplorerPreFill, NewMappingRequest, toSourceSample } from './new-mapping.factory';
import { Direction, MappingType, Qos, TransformationType } from '../../shared';
import { stringToBase64 } from '../../shared/mapping/util';

function request(overrides: Partial<NewMappingRequest> = {}): NewMappingRequest {
  return {
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.JSONATA,
    substitutionsAsCode: false,
    mappingsCount: 3,
    ...overrides
  };
}

function preFill(overrides: Partial<ExplorerPreFill> = {}): ExplorerPreFill {
  return { topic: 'fridge/sensor-ny-99', payload: '{"temp":4}', ...overrides };
}

describe('buildNewMapping', () => {
  describe('defaults', () => {
    it('names the mapping after the existing count and starts inactive with default delivery settings', () => {
      const { mapping } = buildNewMapping(request({ mappingsCount: 3 }));

      expect(mapping.name).toBe('Mapping - 04');
      expect(mapping.active).toBeFalse();
      expect(mapping.qos).toBe(Qos.AT_LEAST_ONCE);
      expect(mapping.filterMapping).toBe('true');
      expect(mapping.substitutions).toEqual([]);
    });

    it('INBOUND: id "any", blank topics, a Cumulocity sample as target and an empty source', () => {
      const { mapping } = buildNewMapping(request());

      expect(mapping.id).toBe('any');
      expect(mapping.mappingTopic).toBe('');
      expect(mapping.sourceTemplate).toBe('{}');
      expect(JSON.parse(mapping.targetTemplate).type).toBeDefined();
    });

    it('OUTBOUND: id equals the identifier, no mapping topic, the Cumulocity sample as source', () => {
      const { mapping } = buildNewMapping(request({ direction: Direction.OUTBOUND }));

      expect(mapping.id).toBe(mapping.identifier);
      expect('mappingTopic' in mapping).toBeFalse();
      expect(mapping.targetTemplate).toBe('{}');
      expect(JSON.parse(mapping.sourceTemplate).type).toBeDefined();
    });

    it('does not seed a generic sample for code or extension transformations', () => {
      const { mapping } = buildNewMapping(request({ transformationType: TransformationType.SMART_FUNCTION }));
      expect(mapping.targetTemplate).toBe('{}');
    });

    it('seeds a flat file with a payload wrapper', () => {
      const { mapping } = buildNewMapping(request({ mappingType: MappingType.FLAT_FILE }));
      expect(JSON.parse(mapping.sourceTemplate)).toEqual({ payload: '10,temp,1666963367' });
    });

    it('takes the code of the chosen code template only for smart functions', () => {
      const codeTemplate = { code: 'function onMessage() {}' } as any;

      const asCode = buildNewMapping(request({ substitutionsAsCode: true, codeTemplate })).mapping;
      const notAsCode = buildNewMapping(request({ substitutionsAsCode: false, codeTemplate })).mapping;

      expect(asCode.code).toBe('function onMessage() {}');
      expect(notAsCode.code).toBeUndefined();
    });

    it('carries the extension entry onto the mapping', () => {
      const extension = { extensionName: 'ext', eventName: 'evt' };
      expect(buildNewMapping(request({ extension })).mapping.extension).toEqual(extension as any);
    });

    it('creates a different identifier every time', () => {
      expect(buildNewMapping(request()).mapping.identifier).not.toBe(buildNewMapping(request()).mapping.identifier);
    });
  });

  describe('Message Explorer pre-fill', () => {
    it('INBOUND: uses the session topic as mapping topic and the concrete topic as sample', () => {
      const { mapping } = buildNewMapping(request({ preFill: preFill({ sessionTopic: 'fridge/#' }) }));

      expect(mapping.mappingTopic).toBe('fridge/#');
      expect(mapping.mappingTopicSample).toBe('fridge/sensor-ny-99');
      expect(mapping.sourceTemplate).toBe('{"temp":4}');
    });

    it('OUTBOUND: puts the captured payload on the source and sets the filter', () => {
      const { mapping } = buildNewMapping(
        request({ direction: Direction.OUTBOUND, preFill: preFill({ payload: '{"id":"1"}' }) })
      );

      expect(mapping.sourceTemplate).toBe('{"id":"1"}');
      expect(mapping.filterMapping).toBe('true');
      expect('mappingTopic' in mapping).toBeFalse();
    });

    it('wraps a text payload of an Any Payload mapping as Base64', () => {
      const { mapping } = buildNewMapping(
        request({ mappingType: MappingType.ANY_PAYLOAD, preFill: preFill({ payload: 'hello' }) })
      );
      expect(JSON.parse(mapping.sourceTemplate)).toEqual({ payload: stringToBase64('hello') });
    });

    it('keeps a binary Any Payload as it arrived (already Base64)', () => {
      const { mapping } = buildNewMapping(
        request({ mappingType: MappingType.ANY_PAYLOAD, preFill: preFill({ payload: 'AAEC', binary: true }) })
      );
      expect(JSON.parse(mapping.sourceTemplate)).toEqual({ payload: 'AAEC' });
    });

    it('wraps a non-JSON payload of a raw mapping type, and leaves JSON alone', () => {
      const text = buildNewMapping(request({ mappingType: MappingType.HEX, preFill: preFill({ payload: '0a0b' }) }));
      const json = buildNewMapping(request({ mappingType: MappingType.HEX, preFill: preFill({ payload: '{"a":1}' }) }));

      expect(JSON.parse(text.mapping.sourceTemplate)).toEqual({ payload: '0a0b' });
      expect(json.mapping.sourceTemplate).toBe('{"a":1}');
    });

    it('reports what is known about the captured message as stepper state', () => {
      const { sampleMessageInfo } = buildNewMapping(
        request({ preFill: preFill({ payloadType: 'JSON', payloadSize: 10, clientId: 'c1', connectorType: 'MQTT' }) })
      );

      expect(sampleMessageInfo).toEqual(
        jasmine.objectContaining({ payloadType: 'JSON', payloadSize: 10, clientId: 'c1', connectorType: 'MQTT', topic: 'fridge/sensor-ny-99' })
      );
    });

    it('JSONata: injects the broker message key into the sample payload as _CONTEXT_DATA_', () => {
      const { mapping, sampleTransportFields } = buildNewMapping(request({ preFill: preFill({ key: 'k-1' }) }));

      expect(JSON.parse(mapping.sourceTemplate)['_CONTEXT_DATA_']).toEqual({ key: 'k-1' });
      expect(sampleTransportFields).toBeUndefined();
    });

    it('Smart Function: carries key and metadata as transport fields and keeps the payload clean', () => {
      const { mapping, sampleTransportFields } = buildNewMapping(
        request({
          transformationType: TransformationType.SMART_FUNCTION,
          preFill: preFill({ key: 'k-1', metadata: { header: 'h' } })
        })
      );

      expect(sampleTransportFields).toEqual({ header: 'h', key: 'k-1' });
      expect(JSON.parse(mapping.sourceTemplate)['_CONTEXT_DATA_']).toBeUndefined();
    });

    it('JSONata does not receive broker metadata', () => {
      const { sampleTransportFields } = buildNewMapping(request({ preFill: preFill({ metadata: { header: 'h' } }) }));
      expect(sampleTransportFields).toBeUndefined();
    });

    it('shapes the extra messages like the primary sample', () => {
      const { additionalSamples } = buildNewMapping(
        request({
          mappingType: MappingType.ANY_PAYLOAD,
          preFill: preFill({ additionalMessages: [{ topic: 't2', payload: 'x' } as any] })
        })
      );

      expect(additionalSamples).toEqual([
        { topic: 't2', payload: JSON.stringify({ payload: stringToBase64('x') }), transportFields: undefined }
      ]);
    });

    it('takes the captured target API and seeds INBOUND with its sample', () => {
      const { mapping } = buildNewMapping(request({ preFill: preFill({ targetAPI: 'EVENT' }) }));

      expect(mapping.targetAPI).toBe('EVENT');
      expect(JSON.parse(mapping.targetTemplate).type).toBeDefined();
    });

    it('takes the publish topic and switches to external ids', () => {
      const { mapping } = buildNewMapping(
        request({ direction: Direction.OUTBOUND, preFill: preFill({ publishTopic: 'out/t', publishTopicSample: 'out/s' }) })
      );

      expect(mapping.publishTopic).toBe('out/t');
      expect(mapping.publishTopicSample).toBe('out/s');
      expect(mapping.useExternalId).toBeTrue();
    });

    it('changes nothing when there is no pre-fill', () => {
      const result = buildNewMapping(request());
      expect(result.sampleMessageInfo).toBeUndefined();
      expect(result.sampleTransportFields).toBeUndefined();
      expect(result.additionalSamples).toBeUndefined();
    });
  });

  describe('toSourceSample', () => {
    it('Base64-wraps raw text for Any Payload inbound only', () => {
      expect(JSON.parse(toSourceSample('hi', false, MappingType.ANY_PAYLOAD, true))).toEqual({ payload: stringToBase64('hi') });
      expect(toSourceSample('hi', false, MappingType.JSON, true)).toBe('hi');
    });

    it('wraps non-JSON binary payloads and leaves JSON as it is', () => {
      expect(JSON.parse(toSourceSample('zz', true, MappingType.JSON, true))).toEqual({ payload: 'zz' });
      expect(toSourceSample('{"a":1}', true, MappingType.JSON, true)).toBe('{"a":1}');
    });
  });
});
