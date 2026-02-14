/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { API, RepairStrategy } from '../../../../shared';
import { JSONProcessorInbound } from './json-processor-inbound.service';
import { KEY_TIME, SubstituteValueType } from '../processor.model';
import {
  createMockProcessingContext,
  mockMappings,
  mockPayloads
} from '../__tests__/test-fixtures';
import {
  MockAlertService,
  MockC8YAgent,
  MockSharedService
} from '../__tests__/test-helpers';
import { ErrorHandlerService } from '../error-handling/error-handler.service';
import { ProcessorLoggerService } from '../logging/processor-logger.service';
import { ProcessorConfigService } from '../config/processor-config.service';
import { JSONataCacheService } from '../performance/jsonata-cache.service';

describe('JSONProcessorInbound', () => {
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

  describe('deserializePayload', () => {
    it('should deserialize JSON payload', () => {
      const context = createMockProcessingContext();
      const message = mockPayloads.simpleDevice;

      const result = service.deserializePayload(
        mockMappings.inboundJSON,
        message,
        context
      );

      expect(result.payload as any).toEqual(message);
    });

    it('should handle object payloads', () => {
      const context = createMockProcessingContext();
      const message = { key: 'value', nested: { data: 123 } };

      const result = service.deserializePayload(
        mockMappings.inboundJSON,
        message,
        context
      );

      expect(result.payload as any).toEqual(message);
    });
  });

  describe('extractFromSource', () => {
    it('should extract simple properties', async () => {
      const context = createMockProcessingContext({
        mapping: mockMappings.inboundJSON,
        payload: mockPayloads.simpleDevice as any
      });

      await service.extractFromSource(context);

      const cache = context.processingCache;
      expect(cache.size).toBeGreaterThan(0);
    });

    it('should extract nested properties', async () => {
      const context = createMockProcessingContext({
        payload: mockPayloads.nestedStructure as any
      });
      context.mapping.substitutions = [
        {
          pathSource: '$.sensor.metadata.id',
          pathTarget: '$.deviceId',
          repairStrategy: RepairStrategy.DEFAULT,
          expandArray: false
        }
      ];

      await service.extractFromSource(context);

      const deviceId = context.processingCache.get('$.deviceId');
      expect(deviceId).toBeDefined();
      expect(deviceId[0].value).toBe('sensor001');
    });

    it('should extract array values with expandArray = true', async () => {
      const context = createMockProcessingContext({
        mapping: mockMappings.inboundJSONWithArrayExpansion,
        payload: mockPayloads.multiDevice as any
      });

      await service.extractFromSource(context);

      // Should have expanded array into multiple entries
      const cache = context.processingCache;
      expect(cache.size).toBeGreaterThan(0);
    });

    it('should extract array values with expandArray = false', async () => {
      const context = createMockProcessingContext({
        payload: mockPayloads.withArrays as any
      });
      context.mapping.substitutions = [
        {
          pathSource: '$.measurements',
          pathTarget: '$.data',
          repairStrategy: RepairStrategy.DEFAULT,
          expandArray: false
        }
      ];

      await service.extractFromSource(context);

      const data = context.processingCache.get('$.data');
      expect(data).toBeDefined();
      expect(data[0].type).toBe(SubstituteValueType.ARRAY);
    });

    it('should handle missing properties gracefully', async () => {
      const context = createMockProcessingContext({
        payload: mockPayloads.simpleDevice as any
      });
      context.mapping.substitutions = [
        {
          pathSource: '$.nonExistentProperty',
          pathTarget: '$.output',
          repairStrategy: RepairStrategy.DEFAULT,
          expandArray: false
        }
      ];

      await service.extractFromSource(context);

      // Should not throw, but may add to errors
      expect(context.processingCache.has('$.output')).toBe(true);
    });

    it('should add default timestamp when not present', async () => {
      const context = createMockProcessingContext({
        mapping: mockMappings.inboundJSON,
        payload: mockPayloads.simpleDevice as any
      });
      context.mapping.targetAPI = API.MEASUREMENT.name;

      await service.extractFromSource(context);

      const time = context.processingCache.get(KEY_TIME);
      expect(time).toBeDefined();
      expect(time[0].type).toBe(SubstituteValueType.TEXTUAL);
      expect(time[0].value).toMatch(/\d{4}-\d{2}-\d{2}T/); // ISO format
    });

    it('should not add timestamp for INVENTORY API', async () => {
      const context = createMockProcessingContext({
        payload: mockPayloads.simpleDevice as any
      });
      context.mapping.targetAPI = API.INVENTORY.name;
      context.mapping.substitutions = [];

      await service.extractFromSource(context);

      const time = context.processingCache.get(KEY_TIME);
      expect(time).toBeUndefined();
    });

    it('should not add timestamp for OPERATION API', async () => {
      const context = createMockProcessingContext({
        payload: mockPayloads.simpleDevice as any
      });
      context.mapping.targetAPI = API.OPERATION.name;
      context.mapping.substitutions = [];

      await service.extractFromSource(context);

      const time = context.processingCache.get(KEY_TIME);
      expect(time).toBeUndefined();
    });

    it('should populate processing cache correctly', async () => {
      const context = createMockProcessingContext({
        mapping: mockMappings.inboundJSON,
        payload: mockPayloads.simpleDevice as any
      });

      await service.extractFromSource(context);

      const cache = context.processingCache;
      expect(cache.size).toBeGreaterThan(0);

      // Check that values have correct structure
      cache.forEach((values, key) => {
        expect(Array.isArray(values)).toBe(true);
        values.forEach(v => {
          expect(v.value).toBeDefined();
          expect(v.type).toBeDefined();
          expect(v.repairStrategy).toBeDefined();
        });
      });
    });
  });
});
