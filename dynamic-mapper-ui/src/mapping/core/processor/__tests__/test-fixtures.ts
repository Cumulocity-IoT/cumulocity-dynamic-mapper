/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

/**
 * Test fixtures for processor unit tests.
 * Provides reusable mock data for mappings, payloads, and contexts.
 */

import {
  Direction,
  Mapping,
  MappingType,
  Qos,
  RepairStrategy,
  SnoopStatus,
  Substitution,
  TransformationType
} from '../../../../shared';
import {
  ProcessingContext,
  ProcessingType,
  SubstituteValue,
  SubstituteValueType,
  IdentityPaths
} from '../processor.model';

/**
 * Base mapping configuration with common defaults
 */
const BASE_MAPPING: Partial<Mapping> = {
  active: true,
  debug: false,
  tested: false,
  createNonExistingDevice: false,
  updateExistingDevice: true,
  useExternalId: true,
  externalIdType: 'c8y_Serial',
  snoopStatus: SnoopStatus.NONE,
  qos: Qos.AT_LEAST_ONCE,
  lastUpdate: Date.now()
};

/**
 * Mock mappings for different scenarios
 */
export const mockMappings = {
  /**
   * Simple JSON inbound mapping with default transformation
   */
  inboundJSON: {
    ...BASE_MAPPING,
    id: 'test-inbound-json-1',
    identifier: 'test_inbound_json',
    name: 'Test Inbound JSON Mapping',
    mappingTopic: 'device/+/telemetry',
    mappingTopicSample: 'device/sensor001/telemetry',
    targetAPI: 'MEASUREMENT',
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    sourceTemplate: JSON.stringify({
      deviceId: 'sensor001',
      temperature: 25.5,
      humidity: 60
    }),
    targetTemplate: JSON.stringify({
      source: { id: '12345' },
      type: 'c8y_TemperatureMeasurement',
      c8y_TemperatureMeasurement: {
        T: { value: 0, unit: 'C' }
      },
      time: '2025-01-01T00:00:00.000Z'
    }),
    substitutions: [
      {
        pathSource: '$.temperature',
        pathTarget: '$.c8y_TemperatureMeasurement.T.value',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      },
      {
        pathSource: '$.deviceId',
        pathTarget: `$.${IdentityPaths.EXTERNAL_ID}`,
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      }
    ]
  } as Mapping,

  /**
   * JSON inbound mapping with array expansion
   */
  inboundJSONWithArrayExpansion: {
    ...BASE_MAPPING,
    id: 'test-inbound-json-array-1',
    identifier: 'test_inbound_json_array',
    name: 'Test Inbound JSON with Array Expansion',
    mappingTopic: 'device/+/bulk',
    mappingTopicSample: 'device/sensor001/bulk',
    targetAPI: 'MEASUREMENT',
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    sourceTemplate: JSON.stringify({
      devices: [
        { id: 'device1', temp: 25.5 },
        { id: 'device2', temp: 26.3 }
      ]
    }),
    targetTemplate: JSON.stringify({
      source: { id: '12345' },
      type: 'c8y_TemperatureMeasurement',
      c8y_TemperatureMeasurement: {
        T: { value: 0, unit: 'C' }
      }
    }),
    substitutions: [
      {
        pathSource: '$.devices',
        pathTarget: '$',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: true
      },
      {
        pathSource: '$.temp',
        pathTarget: '$.c8y_TemperatureMeasurement.T.value',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      },
      {
        pathSource: '$.id',
        pathTarget: `$.${IdentityPaths.EXTERNAL_ID}`,
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      }
    ]
  } as Mapping,

  /**
   * Code-based inbound mapping using JavaScript
   */
  inboundCodeBased: {
    ...BASE_MAPPING,
    id: 'test-inbound-code-1',
    identifier: 'test_inbound_code',
    name: 'Test Inbound Code-Based Mapping',
    mappingTopic: 'device/+/data',
    mappingTopicSample: 'device/sensor001/data',
    targetAPI: 'MEASUREMENT',
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.CODE_BASED,
    sourceTemplate: JSON.stringify({ raw: '0x1A2B3C' }),
    targetTemplate: JSON.stringify({
      source: { id: '12345' },
      type: 'c8y_CustomMeasurement'
    }),
    code: btoa(`
      // Custom JavaScript transformation
      const result = new SubstitutionResult();
      const payload = arg0.getPayload();
      result.addSubstitute('$.source.id', payload.deviceId);
      result.addSubstitute('$.type', 'c8y_CustomMeasurement');
      return result;
    `)
  } as Mapping,

  /**
   * Simple JSON outbound mapping
   */
  outboundJSON: {
    ...BASE_MAPPING,
    id: 'test-outbound-json-1',
    identifier: 'test_outbound_json',
    name: 'Test Outbound JSON Mapping',
    publishTopic: 'device/{externalId}/commands',
    publishTopicSample: 'device/sensor001/commands',
    filterInventory: 'has(c8y_IsDevice)',
    targetAPI: 'OPERATION',
    direction: Direction.OUTBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    sourceTemplate: JSON.stringify({
      deviceId: '12345',
      c8y_Restart: {}
    }),
    targetTemplate: JSON.stringify({
      command: 'restart'
    }),
    substitutions: [
      {
        pathSource: '$.deviceId',
        pathTarget: '$.command',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      }
    ]
  } as Mapping,

  /**
   * Code-based outbound mapping
   */
  outboundCodeBased: {
    ...BASE_MAPPING,
    id: 'test-outbound-code-1',
    identifier: 'test_outbound_code',
    name: 'Test Outbound Code-Based Mapping',
    publishTopic: 'device/{externalId}/config',
    publishTopicSample: 'device/sensor001/config',
    filterInventory: 'has(c8y_IsDevice)',
    targetAPI: 'OPERATION',
    direction: Direction.OUTBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.CODE_BASED,
    sourceTemplate: JSON.stringify({ deviceId: '12345' }),
    targetTemplate: JSON.stringify({ action: 'update' }),
    code: btoa(`
      const result = new SubstitutionResult();
      result.addSubstitute('$.action', 'update');
      return result;
    `)
  } as Mapping
};

/**
 * Mock payload samples for testing
 */
export const mockPayloads = {
  /**
   * Simple device telemetry payload
   */
  simpleDevice: {
    deviceId: 'sensor001',
    temperature: 25.5,
    humidity: 60,
    timestamp: '2025-01-01T12:00:00.000Z'
  },

  /**
   * Multi-device payload for array expansion
   */
  multiDevice: {
    devices: [
      { id: 'device1', temp: 25.5, humidity: 60 },
      { id: 'device2', temp: 26.3, humidity: 55 },
      { id: 'device3', temp: 24.1, humidity: 65 }
    ]
  },

  /**
   * Nested structure payload
   */
  nestedStructure: {
    sensor: {
      metadata: {
        id: 'sensor001',
        type: 'temperature'
      },
      readings: {
        temperature: { value: 25.5, unit: 'C' },
        humidity: { value: 60, unit: '%' }
      }
    }
  },

  /**
   * Payload with arrays
   */
  withArrays: {
    deviceId: 'multi-sensor-001',
    measurements: [
      { type: 'temperature', value: 25.5 },
      { type: 'humidity', value: 60 },
      { type: 'pressure', value: 1013.25 }
    ]
  },

  /**
   * Malformed JSON string
   */
  malformed: '{ "deviceId": "sensor001", invalid json',

  /**
   * Empty payload
   */
  empty: {},

  /**
   * Null payload
   */
  null: null
};

/**
 * Mock substitution configurations
 */
export const mockSubstitutions: {
  [key: string]: Substitution;
} = {
  simpleValue: {
    pathSource: '$.temperature',
    pathTarget: '$.c8y_TemperatureMeasurement.T.value',
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: false
  },

  deviceId: {
    pathSource: '$.deviceId',
    pathTarget: `$.${IdentityPaths.EXTERNAL_ID}`,
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: false
  },

  withArrayExpansion: {
    pathSource: '$.devices',
    pathTarget: '$',
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: true
  },

  useFirstValue: {
    pathSource: '$.measurements',
    pathTarget: '$.value',
    repairStrategy: RepairStrategy.USE_FIRST_VALUE_OF_ARRAY,
    expandArray: false
  },

  removeIfMissing: {
    pathSource: '$.optionalField',
    pathTarget: '$.optional',
    repairStrategy: RepairStrategy.REMOVE_IF_MISSING_OR_NULL,
    expandArray: false
  },

  createIfMissing: {
    pathSource: '$.dynamicField',
    pathTarget: '$.dynamic',
    repairStrategy: RepairStrategy.CREATE_IF_MISSING,
    expandArray: false
  }
};

/**
 * Mock SubstituteValue instances
 */
export const mockSubstituteValues = {
  number: {
    value: 25.5,
    type: SubstituteValueType.NUMBER,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue,

  string: {
    value: 'sensor001',
    type: SubstituteValueType.TEXTUAL,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue,

  boolean: {
    value: true,
    type: SubstituteValueType.BOOLEAN,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue,

  object: {
    value: { key: 'value' },
    type: SubstituteValueType.OBJECT,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue,

  array: {
    value: [1, 2, 3],
    type: SubstituteValueType.ARRAY,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue,

  ignore: {
    value: null,
    type: SubstituteValueType.IGNORE,
    repairStrategy: RepairStrategy.DEFAULT
  } as SubstituteValue
};

/**
 * Factory for creating ProcessingContext instances with defaults
 *
 * @deprecated Consider using ProcessingContextFactory.createForTesting() from context/processing-context.ts
 * This function is maintained for backward compatibility in existing tests.
 */
export function createMockProcessingContext(
  overrides?: Partial<ProcessingContext>
): ProcessingContext {
  // Use ProcessingContextFactory for consistent initialization
  const { ProcessingContextFactory } = require('../context/processing-context');

  const baseContext = ProcessingContextFactory.createForTesting({
    mapping: mockMappings.inboundJSON,
    topic: 'device/sensor001/telemetry',
    payload: mockPayloads.simpleDevice as any
  });

  return { ...baseContext, ...overrides };
}

/**
 * Create context with errors for testing error handling
 *
 * @deprecated Consider using ProcessingContextFactory.createWithErrors() from context/processing-context.ts
 */
export function createContextWithErrors(): ProcessingContext {
  const { ProcessingContextFactory } = require('../context/processing-context');

  return ProcessingContextFactory.createWithErrors(
    ['Test error 1', 'Test error 2'],
    ['Test warning']
  );
}

/**
 * Create context with processing cache for testing cache operations
 *
 * @deprecated Consider using ProcessingContextFactory.createWithCache() from context/processing-context.ts
 */
export function createContextWithCache(): ProcessingContext {
  const { ProcessingContextFactory } = require('../context/processing-context');

  const cache = new Map<string, SubstituteValue[]>();
  cache.set('$.c8y_TemperatureMeasurement.T.value', [mockSubstituteValues.number]);
  cache.set(`$.${IdentityPaths.EXTERNAL_ID}`, [mockSubstituteValues.string]);

  return ProcessingContextFactory.createWithCache(
    cache,
    ProcessingType.ONE_DEVICE_MULTIPLE_VALUE
  );
}

/**
 * Create context for multi-device scenario
 */
export function createMultiDeviceContext(): ProcessingContext {
  const cache = new Map<string, SubstituteValue[]>();
  cache.set('$.c8y_TemperatureMeasurement.T.value', [
    { value: 25.5, type: SubstituteValueType.NUMBER, repairStrategy: RepairStrategy.DEFAULT },
    { value: 26.3, type: SubstituteValueType.NUMBER, repairStrategy: RepairStrategy.DEFAULT },
    { value: 24.1, type: SubstituteValueType.NUMBER, repairStrategy: RepairStrategy.DEFAULT }
  ]);
  cache.set(`$.${IdentityPaths.EXTERNAL_ID}`, [
    { value: 'device1', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT },
    { value: 'device2', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT },
    { value: 'device3', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
  ]);

  return createMockProcessingContext({
    mapping: mockMappings.inboundJSONWithArrayExpansion,
    payload: mockPayloads.multiDevice as any,
    processingCache: cache,
    processingType: ProcessingType.MULTIPLE_DEVICE_MULTIPLE_VALUE
  });
}

/**
 * Mock topics for testing
 */
export const mockTopics = {
  simple: 'device/sensor001/telemetry',
  multiLevel: 'org/site/building/floor/room/device001/telemetry',
  withWildcardSingle: 'device/+/telemetry',
  withWildcardMulti: 'device/sensor001/#',
  mixed: 'org/+/building/#'
};
