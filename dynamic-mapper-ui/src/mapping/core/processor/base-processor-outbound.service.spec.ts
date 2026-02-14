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

import { RepairStrategy } from '../../../shared';
import { JSONProcessorOutbound } from './impl/json-processor-outbound.service';
import {
  SubstituteValueType,
  TOKEN_TOPIC_LEVEL
} from './processor.model';
import {
  createMockProcessingContext,
  mockMappings,
  mockSubstituteValues
} from './__tests__/test-fixtures';
import {
  MockAlertService,
  MockC8YAgent,
  MockMQTTClient,
  MockSharedService
} from './__tests__/test-helpers';
import { ErrorHandlerService } from './error-handling/error-handler.service';
import { ProcessorLoggerService } from './logging/processor-logger.service';
import { ProcessorConfigService } from './config/processor-config.service';
import { JSONataCacheService } from './performance/jsonata-cache.service';

describe('BaseProcessorOutbound', () => {
  let service: JSONProcessorOutbound;
  let mockAlert: MockAlertService;
  let mockC8YAgent: MockC8YAgent;
  let mockMQTTClient: MockMQTTClient;
  let mockSharedService: MockSharedService;

  beforeEach(() => {
    mockAlert = new MockAlertService();
    mockC8YAgent = new MockC8YAgent();
    mockMQTTClient = new MockMQTTClient();
    mockSharedService = new MockSharedService();
    const errorHandler = new ErrorHandlerService();
    const logger = new ProcessorLoggerService();
    const config = new ProcessorConfigService();
    const jsonataCache = new JSONataCacheService();

    service = new JSONProcessorOutbound(
      mockAlert as any,
      mockC8YAgent as any,
      mockMQTTClient as any,
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
    mockMQTTClient.reset();
    mockSharedService.reset();
  });

  describe('substituteInTargetAndSend', () => {
    it('should process outbound payload substitution', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.command', [
        { value: 'restart', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);

      await service.substituteInTargetAndSend(context);

      expect(context.requests.length).toBe(1);
      expect(context.requests[0].request).toBeDefined();
      expect(mockMQTTClient.publish).toHaveBeenCalled();
    });

    it('should add topic levels to payload', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.command', [mockSubstituteValues.string]);

      await service.substituteInTargetAndSend(context);

      const request = context.requests[0].request;
      expect(request[TOKEN_TOPIC_LEVEL]).toBeDefined();
      expect(request[TOKEN_TOPIC_LEVEL]).toEqual(['device', 'sensor001', 'commands']);
    });

    it('should resolve publish topic correctly', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.mapping.publishTopic = 'device/{externalId}/config';
      context.topic = 'device/sensor001/config';

      context.processingCache.set('$.action', [mockSubstituteValues.string]);

      await service.substituteInTargetAndSend(context);

      expect(context.resolvedPublishTopic).toBeDefined();
    });

    it('should handle substitutions correctly', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.deviceId', [
        { value: '12345', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);
      context.processingCache.set('$.action', [
        { value: 'restart', type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT }
      ]);

      await service.substituteInTargetAndSend(context);

      const request = context.requests[0].request;
      expect(request['deviceId']).toBe('12345');
      expect(request['action']).toBe('restart');
    });

    it('should handle empty processing cache', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';
      context.processingCache.clear();

      await service.substituteInTargetAndSend(context);

      expect(context.requests.length).toBe(1);
    });

    it('should throw error for invalid target template JSON', async () => {
      const context = createMockProcessingContext();
      context.mapping = { ...mockMappings.outboundJSON };
      context.mapping.targetTemplate = '{ invalid json';
      context.topic = 'device/sensor001/commands';

      await expectAsync(
        service.substituteInTargetAndSend(context)
      ).toBeRejected();

      expect(context.warnings.length).toBeGreaterThan(0);
      expect(context.warnings[0]).toContain('not a valid json');
    });

    it('should populate request with correct API type', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.command', [mockSubstituteValues.string]);

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].api).toBe('OPERATION');
      expect(context.requests[0].method).toBe('POST');
    });

    it('should handle MQTT publish errors', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.command', [mockSubstituteValues.string]);

      mockMQTTClient.publish.and.returnValue(
        Promise.reject(new Error('MQTT connection failed'))
      );

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].error).toBeDefined();
    });

    it('should include predecessor in request', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'device/sensor001/commands';

      context.processingCache.set('$.command', [mockSubstituteValues.string]);

      await service.substituteInTargetAndSend(context);

      expect(context.requests[0].predecessor).toBe(-1);
    });

    it('should handle multi-level topics', async () => {
      const context = createMockProcessingContext();
      context.mapping = mockMappings.outboundJSON;
      context.topic = 'org/site/building/device/sensor001/commands';

      context.processingCache.set('$.command', [mockSubstituteValues.string]);

      await service.substituteInTargetAndSend(context);

      const request = context.requests[0].request;
      expect(request[TOKEN_TOPIC_LEVEL].length).toBe(6);
      expect(request[TOKEN_TOPIC_LEVEL][0]).toBe('org');
      expect(request[TOKEN_TOPIC_LEVEL][5]).toBe('commands');
    });
  });

  describe('evaluateExpression', () => {
    it('should evaluate JSONata expressions', async () => {
      const json = { deviceId: '12345', status: 'active' } as any;
      const path = '$.deviceId';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('12345');
    });

    it('should handle nested property access', async () => {
      const json = { device: { info: { id: '12345' } } } as any;
      const path = '$.device.info.id';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('12345');
    });

    it('should handle array access', async () => {
      const json = { devices: ['device1', 'device2', 'device3'] } as any;
      const path = '$.devices[1]';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('device2');
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
        operations: [
          { type: 'restart', deviceId: '001' },
          { type: 'update', deviceId: '002' }
        ]
      } as any;
      const path = '$.operations[type="update"].deviceId';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('002');
    });

    it('should handle undefined json', async () => {
      const json = undefined;
      const path = '$.value';

      const result = await service.evaluateExpression(json, path);

      expect(result as any).toBe('');
    });
  });
});
