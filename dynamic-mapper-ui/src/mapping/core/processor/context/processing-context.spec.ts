/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { MappingType } from '../../../../shared';
import {
  ProcessingContextFactory,
  MappingContext,
  RoutingContext,
  ProcessingState,
  DeviceContext,
  ErrorContext,
  RequestContext,
  ProcessingContext
} from './processing-context';
import { ProcessingType, SubstituteValue, SubstituteValueType } from '../processor.model';
import { RepairStrategy } from '../../../../shared';

describe('ProcessingContextFactory', () => {
  describe('create', () => {
    it('should create a basic processing context', () => {
      const mapping = {
        id: 'mapping-1',
        name: 'Test Mapping',
        mappingType: MappingType.JSON,
        subscriptionTopic: 'device/+/telemetry',
        targetAPI: 'MEASUREMENT'
      } as any;

      const context = ProcessingContextFactory.create(
        mapping,
        'device/sensor001/telemetry'
      );

      expect(context.mapping).toBe(mapping);
      expect(context.mappingType).toBe(MappingType.JSON);
      expect(context.topic).toBe('device/sensor001/telemetry');
      expect(context.processingCache).toBeInstanceOf(Map);
      expect(context.processingCache.size).toBe(0);
      expect(context.errors).toEqual([]);
      expect(context.warnings).toEqual([]);
      expect(context.logs).toEqual([]);
      expect(context.requests).toEqual([]);
    });

    it('should include payload if provided', () => {
      const mapping = { id: 'test', mappingType: MappingType.JSON } as any;
      const payload = { temperature: 25.5 } as any;

      const context = ProcessingContextFactory.create(
        mapping,
        'test/topic',
        payload
      );

      expect(context.payload).toBe(payload);
    });

    it('should initialize with UNDEFINED processing type', () => {
      const mapping = { id: 'test', mappingType: MappingType.JSON } as any;
      const context = ProcessingContextFactory.create(mapping, 'test/topic');

      expect(context.processingType).toBe(ProcessingType.UNDEFINED);
    });

    it('should initialize device context as undefined', () => {
      const mapping = { id: 'test', mappingType: MappingType.JSON } as any;
      const context = ProcessingContextFactory.create(mapping, 'test/topic');

      expect(context.sourceId).toBeUndefined();
      expect(context.deviceName).toBeUndefined();
      expect(context.deviceType).toBeUndefined();
    });

    it('should set sendPayload to false', () => {
      const mapping = { id: 'test', mappingType: MappingType.JSON } as any;
      const context = ProcessingContextFactory.create(mapping, 'test/topic');

      expect(context.sendPayload).toBe(false);
    });
  });

  describe('createForTesting', () => {
    it('should create context with test defaults', () => {
      const context = ProcessingContextFactory.createForTesting();

      expect(context.mapping.id).toBe('test-mapping');
      expect(context.mapping.name).toBe('Test Mapping');
      expect(context.topic).toBe('test/device/telemetry');
      expect(context.processingCache).toBeInstanceOf(Map);
      expect(context.errors).toEqual([]);
      expect(context.warnings).toEqual([]);
    });

    it('should allow overriding specific properties', () => {
      const context = ProcessingContextFactory.createForTesting({
        topic: 'custom/topic',
        sourceId: 'device123',
        sendPayload: true
      });

      expect(context.topic).toBe('custom/topic');
      expect(context.sourceId).toBe('device123');
      expect(context.sendPayload).toBe(true);
      // Other defaults should remain
      expect(context.mapping.id).toBe('test-mapping');
    });

    it('should allow overriding the entire mapping', () => {
      const customMapping = {
        id: 'custom-mapping',
        name: 'Custom',
        mappingType: MappingType.EXTENSION_JAVA
      } as any;

      const context = ProcessingContextFactory.createForTesting({
        mapping: customMapping
      });

      expect(context.mapping).toBe(customMapping);
    });

    it('should allow overriding array properties', () => {
      const errors = ['Error 1', 'Error 2'];
      const warnings = ['Warning 1'];

      const context = ProcessingContextFactory.createForTesting({
        errors,
        warnings
      });

      expect(context.errors).toEqual(errors);
      expect(context.warnings).toEqual(warnings);
    });
  });

  describe('createWithErrors', () => {
    it('should create context with errors only', () => {
      const errors = ['Error 1', 'Error 2', 'Error 3'];
      const context = ProcessingContextFactory.createWithErrors(errors);

      expect(context.errors).toEqual(errors);
      expect(context.warnings).toEqual([]);
    });

    it('should create context with errors and warnings', () => {
      const errors = ['Error 1'];
      const warnings = ['Warning 1', 'Warning 2'];

      const context = ProcessingContextFactory.createWithErrors(
        errors,
        warnings
      );

      expect(context.errors).toEqual(errors);
      expect(context.warnings).toEqual(warnings);
    });

    it('should maintain test defaults for other properties', () => {
      const context = ProcessingContextFactory.createWithErrors(['Error']);

      expect(context.mapping.id).toBe('test-mapping');
      expect(context.topic).toBe('test/device/telemetry');
    });
  });

  describe('createWithCache', () => {
    it('should create context with processing cache', () => {
      const cache = new Map<string, SubstituteValue[]>();
      cache.set('$.temperature', [
        {
          value: 25.5,
          type: SubstituteValueType.NUMBER,
          repairStrategy: RepairStrategy.DEFAULT
        }
      ]);

      const context = ProcessingContextFactory.createWithCache(cache);

      expect(context.processingCache).toBe(cache);
      expect(context.processingCache.size).toBe(1);
      expect(context.processingType).toBe(ProcessingType.UNDEFINED);
    });

    it('should create context with cache and processing type', () => {
      const cache = new Map<string, SubstituteValue[]>();
      const context = ProcessingContextFactory.createWithCache(
        cache,
        ProcessingType.ONE_DEVICE_MULTIPLE_VALUE
      );

      expect(context.processingCache).toBe(cache);
      expect(context.processingType).toBe(
        ProcessingType.ONE_DEVICE_MULTIPLE_VALUE
      );
    });

    it('should maintain test defaults for other properties', () => {
      const cache = new Map<string, SubstituteValue[]>();
      const context = ProcessingContextFactory.createWithCache(cache);

      expect(context.mapping.id).toBe('test-mapping');
      expect(context.errors).toEqual([]);
      expect(context.requests).toEqual([]);
    });
  });

  describe('interface type checking', () => {
    it('should satisfy MappingContext interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const mappingContext: MappingContext = context;

      expect(mappingContext.mapping).toBeDefined();
      expect(mappingContext.mappingType).toBeDefined();
    });

    it('should satisfy RoutingContext interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const routingContext: RoutingContext = context;

      expect(routingContext.topic).toBeDefined();
    });

    it('should satisfy ProcessingState interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const processingState: ProcessingState = context;

      expect(processingState.processingCache).toBeDefined();
    });

    it('should satisfy DeviceContext interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const deviceContext: DeviceContext = context;

      // Properties can be undefined
      expect('sourceId' in deviceContext).toBe(true);
    });

    it('should satisfy ErrorContext interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const errorContext: ErrorContext = context;

      expect(errorContext.errors).toBeDefined();
      expect(errorContext.warnings).toBeDefined();
      expect(errorContext.logs).toBeDefined();
    });

    it('should satisfy RequestContext interface', () => {
      const context = ProcessingContextFactory.createForTesting();
      const requestContext: RequestContext = context;

      expect(requestContext.requests).toBeDefined();
    });
  });

  describe('immutability and independence', () => {
    it('should create independent contexts', () => {
      const context1 = ProcessingContextFactory.createForTesting();
      const context2 = ProcessingContextFactory.createForTesting();

      context1.errors.push('Error in context1');
      context1.processingCache.set('key', []);

      expect(context2.errors).toEqual([]);
      expect(context2.processingCache.size).toBe(0);
    });

    it('should allow modification after creation', () => {
      const context = ProcessingContextFactory.createForTesting();

      context.sourceId = 'device123';
      context.errors.push('New error');
      context.processingCache.set('$.value', [
        {
          value: 42,
          type: SubstituteValueType.NUMBER,
          repairStrategy: RepairStrategy.DEFAULT
        }
      ]);

      expect(context.sourceId).toBe('device123');
      expect(context.errors.length).toBe(1);
      expect(context.processingCache.size).toBe(1);
    });
  });
});
