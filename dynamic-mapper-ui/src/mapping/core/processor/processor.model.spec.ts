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

import { RepairStrategy, Substitution } from '../../../shared';
import {
  getTypedValue,
  isNumeric,
  processSubstitute,
  getDeviceEntries,
  prepareAndSubstituteInPayload,
  substituteValueInPayload,
  sortProcessingCache,
  patchC8YTemplateForTesting,
  evaluateWithArgsWebWorker,
  ProcessingContext,
  ProcessingType,
  SubstituteValue,
  SubstituteValueType,
  TOKEN_CONTEXT_DATA,
  TOKEN_IDENTITY,
  TOKEN_TOPIC_LEVEL,
  IdentityPaths,
  ContextDataPaths
} from './processor.model';
import {
  createMockProcessingContext,
  createContextWithCache,
  mockMappings,
  mockSubstitutions,
  mockSubstituteValues
} from './__tests__/test-fixtures';
import { MockAlertService, setupMockWorkerFactory, restoreWorkerFactory } from './__tests__/test-helpers';
import { SubstitutionContext } from './processor-js.model';

describe('processor.model utility functions', () => {
  describe('getTypedValue', () => {
    it('should convert NUMBER type to number', () => {
      const result = getTypedValue(mockSubstituteValues.number);
      expect(result).toBe(25.5);
      expect(typeof result).toBe('number');
    });

    it('should convert TEXTUAL type to string', () => {
      const result = getTypedValue(mockSubstituteValues.string);
      expect(result).toBe('sensor001');
      expect(typeof result).toBe('string');
    });

    it('should return value as-is for OBJECT type', () => {
      const result = getTypedValue(mockSubstituteValues.object);
      expect(result).toEqual({ key: 'value' });
      expect(typeof result).toBe('object');
    });

    it('should return value as-is for ARRAY type', () => {
      const result = getTypedValue(mockSubstituteValues.array);
      expect(result).toEqual([1, 2, 3]);
      expect(Array.isArray(result)).toBe(true);
    });

    it('should return value as-is for BOOLEAN type', () => {
      const result = getTypedValue(mockSubstituteValues.boolean);
      expect(result).toBe(true);
      expect(typeof result).toBe('boolean');
    });

    it('should return value as-is for IGNORE type', () => {
      const result = getTypedValue(mockSubstituteValues.ignore);
      expect(result).toBeNull();
    });
  });

  describe('isNumeric', () => {
    it('should return true for numeric strings', () => {
      expect(isNumeric('123')).toBe(true);
      expect(isNumeric('45.67')).toBe(true);
      expect(isNumeric('0')).toBe(true);
      expect(isNumeric('-123')).toBe(true);
    });

    it('should return true for numbers', () => {
      expect(isNumeric(123)).toBe(true);
      expect(isNumeric(45.67)).toBe(true);
      expect(isNumeric(0)).toBe(true);
      expect(isNumeric(-123)).toBe(true);
    });

    it('should return false for non-numeric strings', () => {
      expect(isNumeric('abc')).toBe(false);
      expect(isNumeric('12abc')).toBe(false);
      expect(isNumeric('abc123')).toBe(false);
    });

    it('should return false for empty strings', () => {
      expect(isNumeric('')).toBe(false);
      expect(isNumeric('   ')).toBe(false);
    });

    it('should return false for null and undefined', () => {
      expect(isNumeric(null)).toBe(false);
      expect(isNumeric(undefined)).toBe(false);
    });
  });

  describe('processSubstitute', () => {
    let processingCacheEntry: SubstituteValue[];
    let substitution: Substitution;

    beforeEach(() => {
      processingCacheEntry = [];
      substitution = { ...mockSubstitutions['simpleValue'] };
    });

    it('should handle null values with IGNORE type', () => {
      processSubstitute(processingCacheEntry, null, substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.IGNORE);
      expect(processingCacheEntry[0].value).toBeNull();
    });

    it('should handle string values correctly', () => {
      processSubstitute(processingCacheEntry, 'test-value', substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.TEXTUAL);
      expect(processingCacheEntry[0].value).toBe('test-value');
      expect(processingCacheEntry[0].repairStrategy).toBe(substitution.repairStrategy);
    });

    it('should handle number values correctly', () => {
      processSubstitute(processingCacheEntry, 42.5, substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.NUMBER);
      expect(processingCacheEntry[0].value).toBe(42.5);
    });

    it('should handle array values correctly', () => {
      const arrayValue = [1, 2, 3];
      processSubstitute(processingCacheEntry, arrayValue, substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.ARRAY);
      expect(processingCacheEntry[0].value).toEqual(arrayValue);
    });

    it('should handle object values correctly', () => {
      const objectValue = { key: 'value', nested: { data: 123 } };
      processSubstitute(processingCacheEntry, objectValue, substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.OBJECT);
      expect(processingCacheEntry[0].value).toEqual(objectValue);
    });

    it('should handle boolean values correctly', () => {
      processSubstitute(processingCacheEntry, true, substitution);

      expect(processingCacheEntry.length).toBe(1);
      expect(processingCacheEntry[0].type).toBe(SubstituteValueType.BOOLEAN);
      expect(processingCacheEntry[0].value).toBe(true);
    });

    it('should preserve repairStrategy from substitution', () => {
      substitution.repairStrategy = RepairStrategy.USE_FIRST_VALUE_OF_ARRAY;
      processSubstitute(processingCacheEntry, 'test', substitution);

      expect(processingCacheEntry[0].repairStrategy).toBe(RepairStrategy.USE_FIRST_VALUE_OF_ARRAY);
    });
  });

  describe('getDeviceEntries', () => {
    it('should return device entries from processing cache', () => {
      const context = createContextWithCache();
      const devicePath = IdentityPaths.EXTERNAL_ID;
      context.processingCache.set(devicePath, [mockSubstituteValues.string]);

      const result = getDeviceEntries(context);

      expect(result).toBeDefined();
      expect(result.length).toBe(1);
      expect(result[0].value).toBe('sensor001');
    });

    it('should return undefined when no device entries exist', () => {
      const context = createMockProcessingContext();
      context.processingCache.clear();

      const result = getDeviceEntries(context);

      expect(result).toBeUndefined();
    });
  });

  describe('prepareAndSubstituteInPayload', () => {
    let context: ProcessingContext;
    let alert: MockAlertService;
    let payloadTarget: any;

    beforeEach(() => {
      context = createMockProcessingContext();
      alert = new MockAlertService();
      payloadTarget = { type: 'c8y_Measurement' };
    });

    it('should set deviceName in context when path is CONTEXT_DATA.deviceName', () => {
      const substitute: SubstituteValue = {
        value: 'TestDevice',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };

      prepareAndSubstituteInPayload(
        context,
        substitute,
        payloadTarget,
        ContextDataPaths.DEVICE_NAME,
        alert as any
      );

      expect(context.deviceName).toBe('TestDevice');
    });

    it('should set deviceType in context when path is CONTEXT_DATA.deviceType', () => {
      const substitute: SubstituteValue = {
        value: 'TestDeviceType',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };

      prepareAndSubstituteInPayload(
        context,
        substitute,
        payloadTarget,
        ContextDataPaths.DEVICE_TYPE,
        alert as any
      );

      expect(context.deviceType).toBe('TestDeviceType');
    });

    it('should substitute value in payload for regular paths', () => {
      payloadTarget = { value: 0 };
      const substitute: SubstituteValue = {
        value: 25.5,
        type: SubstituteValueType.NUMBER,
        repairStrategy: RepairStrategy.DEFAULT
      };

      prepareAndSubstituteInPayload(
        context,
        substitute,
        payloadTarget,
        '$.value',
        alert as any
      );

      expect(payloadTarget.value).toBe(25.5);
    });
  });

  describe('substituteValueInPayload', () => {
    it('should substitute at root level ($)', () => {
      const payloadTarget: any = {};
      const substitute: SubstituteValue = {
        value: { key1: 'value1', key2: 'value2' },
        type: SubstituteValueType.OBJECT,
        repairStrategy: RepairStrategy.DEFAULT
      };

      substituteValueInPayload(substitute, payloadTarget, '$');

      expect(payloadTarget.key1).toBe('value1');
      expect(payloadTarget.key2).toBe('value2');
    });

    it('should substitute nested properties', () => {
      const payloadTarget: any = { nested: { value: 0 } };
      const substitute: SubstituteValue = {
        value: 42,
        type: SubstituteValueType.NUMBER,
        repairStrategy: RepairStrategy.DEFAULT
      };

      substituteValueInPayload(substitute, payloadTarget, 'nested.value');

      expect(payloadTarget.nested.value).toBe(42);
    });

    it('should handle REMOVE_IF_MISSING_OR_NULL strategy with null value', () => {
      const payloadTarget: any = { optional: 'existingValue' };
      const substitute: SubstituteValue = {
        value: null,
        type: SubstituteValueType.IGNORE,
        repairStrategy: RepairStrategy.REMOVE_IF_MISSING_OR_NULL
      };

      substituteValueInPayload(substitute, payloadTarget, 'optional');

      expect(payloadTarget.optional).toBeUndefined();
    });

    it('should handle CREATE_IF_MISSING strategy', () => {
      const payloadTarget: any = {};
      const substitute: SubstituteValue = {
        value: 'createdValue',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.CREATE_IF_MISSING
      };

      substituteValueInPayload(substitute, payloadTarget, 'newField');

      expect(payloadTarget.newField).toBe('createdValue');
    });

    it('should throw error for missing path with DEFAULT strategy', () => {
      const payloadTarget: any = {};
      const substitute: SubstituteValue = {
        value: 'value',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };

      expect(() => {
        substituteValueInPayload(substitute, payloadTarget, 'missingPath');
      }).toThrowError(/Path.*not found/);
    });

    it('should update existing path with DEFAULT strategy', () => {
      const payloadTarget: any = { existingPath: 'oldValue' };
      const substitute: SubstituteValue = {
        value: 'newValue',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };

      substituteValueInPayload(substitute, payloadTarget, 'existingPath');

      expect(payloadTarget.existingPath).toBe('newValue');
    });
  });

  describe('sortProcessingCache', () => {
    it('should sort cache entries alphabetically by key', () => {
      const context = createMockProcessingContext();

      // Add entries in non-alphabetical order
      context.processingCache.set('$.zebra', [mockSubstituteValues.string]);
      context.processingCache.set('$.apple', [mockSubstituteValues.number]);
      context.processingCache.set('$.middle', [mockSubstituteValues.boolean]);

      sortProcessingCache(context);

      const keys = Array.from(context.processingCache.keys());
      expect(keys).toEqual(['$.apple', '$.middle', '$.zebra']);
    });

    it('should maintain value integrity after sorting', () => {
      const context = createMockProcessingContext();
      const value1 = [mockSubstituteValues.string];
      const value2 = [mockSubstituteValues.number];

      context.processingCache.set('$.second', value2);
      context.processingCache.set('$.first', value1);

      sortProcessingCache(context);

      expect(context.processingCache.get('$.first')).toBe(value1);
      expect(context.processingCache.get('$.second')).toBe(value2);
    });

    it('should handle empty cache', () => {
      const context = createMockProcessingContext();
      context.processingCache.clear();

      expect(() => sortProcessingCache(context)).not.toThrow();
      expect(context.processingCache.size).toBe(0);
    });
  });

  describe('patchC8YTemplateForTesting', () => {
    it('should add test device identifier to template', () => {
      const template: any = { type: 'c8y_Measurement' };
      const mapping = mockMappings.inboundJSON;

      patchC8YTemplateForTesting(template, mapping);

      expect(template['source.id']).toBeDefined();
      expect(template[TOKEN_IDENTITY]).toBeDefined();
      expect(template[TOKEN_IDENTITY].c8ySourceId).toBeDefined();
    });

    it('should generate random identifier', () => {
      const template1: any = {};
      const template2: any = {};
      const mapping = mockMappings.inboundJSON;

      patchC8YTemplateForTesting(template1, mapping);
      patchC8YTemplateForTesting(template2, mapping);

      // Identifiers should be different (random)
      expect(template1['source.id']).not.toBe(template2['source.id']);
    });
  });

  describe('evaluateWithArgsWebWorker', () => {
    beforeEach(() => {
      setupMockWorkerFactory();
    });

    afterEach(() => {
      restoreWorkerFactory();
    });

    it('should execute simple JavaScript code', async () => {
      const code = 'return 42;';
      const ctx = new SubstitutionContext(
        { type: 'c8y_Serial', externalId: 'test001' },
        { value: 123 },
        'test/topic'
      );

      // We need to mock the worker response since we're using MockWorker
      const result = await evaluateWithArgsWebWorker(code, ctx);

      expect(result.success).toBeDefined();
    });

    it('should handle timeout (250ms)', async () => {
      jasmine.clock().install();

      const code = 'while(true) {}'; // Infinite loop
      const ctx = new SubstitutionContext(
        { type: 'c8y_Serial', externalId: 'test001' },
        {},
        'test/topic'
      );

      const resultPromise = evaluateWithArgsWebWorker(code, ctx);

      jasmine.clock().tick(300); // Advance past timeout
      const result = await resultPromise;

      expect(result.success).toBe(false);
      expect(result.error?.message).toContain('timed out');

      jasmine.clock().uninstall();
    }, 10000);
  });
});
