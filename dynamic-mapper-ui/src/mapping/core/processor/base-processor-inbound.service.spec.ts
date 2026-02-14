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

import { HttpStatusCode } from '@angular/common/http';
import { API, RepairStrategy } from '../../../shared';
import { ErrorHandlerService } from './error-handling/error-handler.service';
import { ProcessorLoggerService } from './logging/processor-logger.service';
import { ProcessorConfigService } from './config/processor-config.service';
import { JSONataCacheService } from './performance/jsonata-cache.service';
import { JSONProcessorInbound } from './impl/json-processor-inbound.service';
import {
  ProcessingContext,
  ProcessingType,
  SubstituteValueType,
  TOKEN_IDENTITY,
  TOKEN_TOPIC_LEVEL,
  IdentityPaths
} from './processor.model';
import {
  createMockProcessingContext,
  createContextWithCache,
  mockMappings,
  mockSubstituteValues,
  mockTopics
} from './__tests__/test-fixtures';
import {
  MockAlertService,
  MockC8YAgent,
  MockSharedService
} from './__tests__/test-helpers';

describe('BaseProcessorInbound', () => {
  let service: JSONProcessorInbound;
  let mockAlert: MockAlertService;
  let mockC8YAgent: MockC8YAgent;
  let mockSharedService: MockSharedService;

  beforeEach(() => {
    mockAlert = new MockAlertService();
    mockC8YAgent = new MockC8YAgent();
    mockSharedService = new MockSharedService();
    const errorHandler = new ErrorHandlerService();
    const logger = new ProcessorLoggerService();
    const config = new ProcessorConfigService();
    const jsonataCache = new JSONataCacheService();

    service = new JSONProcessorInbound(
      mockAlert as any,
      mockC8YAgent as any,
      mockSharedService as any,
      errorHandler,
      logger,
      config,
      jsonataCache
    );
  });

  afterEach(() => {
    mockAlert.reset();
    mockC8YAgent.reset();
    mockSharedService.reset();
  });

  describe('initializeCache', () => {
    it('should call c8yClient initializeCache', () => {
      service.initializeCache();

      expect(mockC8YAgent.initializeCache).toHaveBeenCalled();
    });
  });

  describe('enrichPayload', () => {
    it('should add topic levels to payload', () => {
      const context = createMockProcessingContext({
        topic: mockTopics.simple,
        payload: { deviceId: 'sensor001' } as any
      });

      service.enrichPayload(context);

      expect(context.payload[TOKEN_TOPIC_LEVEL]).toBeDefined();
      expect(context.payload[TOKEN_TOPIC_LEVEL]).toEqual([
        'device',
        'sensor001',
        'telemetry'
      ]);
    });

    it('should handle multi-level topics', () => {
      const context = createMockProcessingContext({
        topic: mockTopics.multiLevel,
        payload: {} as any
      });

      service.enrichPayload(context);

      expect(context.payload[TOKEN_TOPIC_LEVEL].length).toBe(7);
      expect(context.payload[TOKEN_TOPIC_LEVEL][0]).toBe('org');
      expect(context.payload[TOKEN_TOPIC_LEVEL][6]).toBe('telemetry');
    });

    it('should handle single-level topics', () => {
      const context = createMockProcessingContext({
        topic: 'telemetry',
        payload: {} as any
      });

      service.enrichPayload(context);

      expect(context.payload[TOKEN_TOPIC_LEVEL]).toEqual(['telemetry']);
    });
  });

  describe('validateProcessingCache', () => {
    it('should replicate first device when entries are insufficient', () => {
      const context = createMockProcessingContext();

      // Set up cache with mismatched lengths
      context.processingCache.set('$.value', [
        mockSubstituteValues.number,
        { ...mockSubstituteValues.number, value: 26.3 },
        { ...mockSubstituteValues.number, value: 24.1 }
      ]);
      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string // Only one device entry
      ]);

      service.validateProcessingCache(context);

      const deviceEntries = context.processingCache.get(IdentityPaths.EXTERNAL_ID);
      expect(deviceEntries.length).toBe(3); // Replicated to match max
      expect(deviceEntries[0].value).toBe('sensor001');
      expect(deviceEntries[1].value).toBe('sensor001'); // Replicated
      expect(deviceEntries[2].value).toBe('sensor001'); // Replicated
    });

    it('should throw error when device ID not defined', () => {
      const context = createMockProcessingContext();
      context.processingCache.set('$.value', [mockSubstituteValues.number]);
      // No device identifier in cache

      expect(() => {
        service.validateProcessingCache(context);
      }).toThrow();

      expect(context.errors.length).toBeGreaterThan(0);
      expect(context.errors[0]).toContain('Device Id not defined');
    });

    it('should handle multiple devices correctly', () => {
      const context = createMockProcessingContext();

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        { value: 'device1', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT },
        { value: 'device2', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT },
        { value: 'device3', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);
      context.processingCache.set('$.value', [
        mockSubstituteValues.number,
        { ...mockSubstituteValues.number, value: 26.3 },
        { ...mockSubstituteValues.number, value: 24.1 }
      ]);

      expect(() => {
        service.validateProcessingCache(context);
      }).not.toThrow();

      const deviceEntries = context.processingCache.get(IdentityPaths.EXTERNAL_ID);
      expect(deviceEntries.length).toBe(3);
    });
  });

  describe('substituteInTargetAndSend', () => {
    it('should process single device mapping', async () => {
      const context = createContextWithCache();
      context.mapping = mockMappings.inboundJSON;
      context.sendPayload = false;

      // Set up device in cache
      mockC8YAgent.setupDevice('12345', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(context.requests.length).toBeGreaterThan(0);
      expect(context.requests[0].api).toBe('MEASUREMENT');
    });

    it('should resolve external ID to global ID', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.mapping.useExternalId = true;
      context.mapping.targetAPI = API.MEASUREMENT.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        { value: 'sensor001', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);
      context.processingCache.set('$.c8y_TemperatureMeasurement.T.value', [
        mockSubstituteValues.number
      ]);

      // Set up device in mock C8Y
      mockC8YAgent.setupDevice('device-id-123', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(mockC8YAgent.resolveExternalId2GlobalId).toHaveBeenCalled();
      expect(context.requests.length).toBeGreaterThan(0);
    });

    it('should create implicit device when createNonExistingDevice = true', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.mapping.useExternalId = true;
      context.mapping.createNonExistingDevice = true;
      context.mapping.targetAPI = API.MEASUREMENT.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        { value: 'new-device-001', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);
      context.processingCache.set('$.c8y_TemperatureMeasurement.T.value', [
        mockSubstituteValues.number
      ]);

      // Device does not exist in mock C8Y
      mockC8YAgent.resolveExternalId2GlobalId.and.returnValue(
        Promise.reject({ res: { status: HttpStatusCode.NotFound } })
      );

      await service.substituteInTargetAndSend(context);

      expect(mockC8YAgent.createManagedObjectWithExternalIdentity).toHaveBeenCalled();
    });

    it('should throw error when device not found and createNonExistingDevice = false', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.mapping.useExternalId = true;
      context.mapping.createNonExistingDevice = false;
      context.mapping.targetAPI = API.MEASUREMENT.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        { value: 'missing-device', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);
      context.processingCache.set('$.c8y_TemperatureMeasurement.T.value', [
        mockSubstituteValues.number
      ]);

      // Device does not exist
      mockC8YAgent.resolveExternalId2GlobalId.and.returnValue(
        Promise.reject({ res: { status: HttpStatusCode.NotFound } })
      );

      await expectAsync(
        service.substituteInTargetAndSend(context)
      ).toBeRejected();

      expect(context.errors.length).toBeGreaterThan(0);
    });

    it('should handle MEASUREMENT API', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.mapping.targetAPI = API.MEASUREMENT.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string
      ]);
      context.processingCache.set('$.c8y_TemperatureMeasurement.T.value', [
        mockSubstituteValues.number
      ]);

      mockC8YAgent.setupDevice('12345', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].api).toBe(API.MEASUREMENT.name);
      expect(mockC8YAgent.createMEAO).toHaveBeenCalled();
    });

    it('should handle EVENT API', async () => {
      const context = createMockProcessingContext();
      context.mapping = { ...mockMappings.inboundJSON };
      context.mapping.targetAPI = API.EVENT.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string
      ]);

      mockC8YAgent.setupDevice('12345', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].api).toBe(API.EVENT.name);
    });

    it('should handle ALARM API', async () => {
      const context = createMockProcessingContext();
      context.mapping = { ...mockMappings.inboundJSON };
      context.mapping.targetAPI = API.ALARM.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string
      ]);

      mockC8YAgent.setupDevice('12345', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].api).toBe(API.ALARM.name);
    });

    it('should handle INVENTORY API', async () => {
      const context = createMockProcessingContext();
      context.mapping = { ...mockMappings.inboundJSON };
      context.mapping.targetAPI = API.INVENTORY.name;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string
      ]);

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].api).toBe(API.INVENTORY.name);
    });

    it('should populate request array correctly', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;

      context.processingCache.set(IdentityPaths.EXTERNAL_ID, [
        mockSubstituteValues.string
      ]);
      context.processingCache.set('$.c8y_TemperatureMeasurement.T.value', [
        mockSubstituteValues.number
      ]);

      mockC8YAgent.setupDevice('12345', 'sensor001', 'c8y_Serial');

      await service.substituteInTargetAndSend(context);

      expect(context.requests.length).toBe(1);
      expect(context.requests[0].method).toBe('POST');
      expect(context.requests[0].request).toBeDefined();
    });
  });

  describe('createImplicitDevice', () => {
    it('should create device with external identity', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      const identity = { type: 'c8y_Serial', externalId: 'new-device-123' };

      const sourceId = await service.createImplicitDevice(identity, context);

      expect(sourceId).toBeDefined();
      expect(context.requests.length).toBe(1);
      expect(context.requests[0].api).toBe(API.INVENTORY.name);
      expect(context.requests[0].request['name']).toContain('new-device-123');
    });

    it('should create device with c8y source ID', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.sourceId = 'device-source-id';

      const sourceId = await service.createImplicitDevice(undefined, context);

      expect(sourceId).toBeDefined();
      expect(context.requests.length).toBe(1);
      expect(context.requests[0].request['name']).toContain('device-source-id');
    });

    it('should use custom device name if provided', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.deviceName = 'CustomDeviceName';
      const identity = { type: 'c8y_Serial', externalId: 'device-001' };

      await service.createImplicitDevice(identity, context);

      expect(context.requests[0].request['name']).toBe('CustomDeviceName');
    });

    it('should use default device name if not provided', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      const identity = { type: 'c8y_Serial', externalId: 'device-001' };

      await service.createImplicitDevice(identity, context);

      expect(context.requests[0].request['name']).toContain('device_');
      expect(context.requests[0].request['name']).toContain('device-001');
    });

    it('should use custom device type if provided', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      context.deviceType = 'CustomDeviceType';
      const identity = { type: 'c8y_Serial', externalId: 'device-001' };

      await service.createImplicitDevice(identity, context);

      expect(context.requests[0].request['type']).toBe('CustomDeviceType');
    });

    it('should add requests to context', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.inboundJSON;
      const identity = { type: 'c8y_Serial', externalId: 'device-001' };

      await service.createImplicitDevice(identity, context);

      expect(context.requests.length).toBe(1);
      expect(context.requests[0].predecessor).toBe(0);
      expect(context.requests[0].method).toBeDefined();
    });

    it('should mark request as hidden when createNonExistingDevice is false', async () => {
      const context = createMockProcessingContext();
      context.mapping = { ...mockMappings.inboundJSON, createNonExistingDevice: false };
      const identity = { type: 'c8y_Serial', externalId: 'device-001' };

      await service.createImplicitDevice(identity, context);

      expect(context.requests[0].hidden).toBe(true);
    });
  });

  describe('evaluateExpression', () => {
    it('should evaluate JSONata expressions', async () => {
      const json = { temperature: 25.5, humidity: 60 } as any;
      const path = '$.temperature';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe(25.5);
    });

    it('should handle nested property access', async () => {
      const json = { sensor: { readings: { temp: 25.5 } } } as any;
      const path = '$.sensor.readings.temp';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe(25.5);
    });

    it('should handle array access', async () => {
      const json = { values: [10, 20, 30] } as any;
      const path = '$.values[1]';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe(20);
    });

    it('should return empty string for undefined path', async () => {
      const json = { value: 123 } as any;
      const path = undefined;

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('');
    });

    it('should return empty string for empty path', async () => {
      const json = { value: 123 } as any;
      const path = '';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('');
    });

    it('should handle complex JSONata expressions', async () => {
      const json = {
        sensors: [
          { id: 1, value: 25.5 },
          { id: 2, value: 26.3 }
        ]
      } as any;
      const path = '$.sensors[id=2].value';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe(26.3);
    });
  });
});
