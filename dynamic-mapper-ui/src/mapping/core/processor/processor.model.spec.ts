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
  substituteValueInPayload,
  SubstituteValue,
  SubstituteValueType
} from './processor.model';

describe('processor.model utility functions', () => {
  describe('getTypedValue', () => {
    it('should convert NUMBER type to number', () => {
      const subValue: SubstituteValue = {
        value: 25.5,
        type: SubstituteValueType.NUMBER,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
      expect(result).toBe(25.5);
      expect(typeof result).toBe('number');
    });

    it('should convert TEXTUAL type to string', () => {
      const subValue: SubstituteValue = {
        value: 'sensor001',
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
      expect(result).toBe('sensor001');
      expect(typeof result).toBe('string');
    });

    it('should return value as-is for OBJECT type', () => {
      const subValue: SubstituteValue = {
        value: { key: 'value' },
        type: SubstituteValueType.OBJECT,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
      expect(result).toEqual({ key: 'value' });
      expect(typeof result).toBe('object');
    });

    it('should return value as-is for ARRAY type', () => {
      const subValue: SubstituteValue = {
        value: [1, 2, 3],
        type: SubstituteValueType.ARRAY,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
      expect(result).toEqual([1, 2, 3]);
      expect(Array.isArray(result)).toBe(true);
    });

    it('should return value as-is for BOOLEAN type', () => {
      const subValue: SubstituteValue = {
        value: true,
        type: SubstituteValueType.BOOLEAN,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
      expect(result).toBe(true);
      expect(typeof result).toBe('boolean');
    });

    it('should return value as-is for IGNORE type', () => {
      const subValue: SubstituteValue = {
        value: null,
        type: SubstituteValueType.IGNORE,
        repairStrategy: RepairStrategy.DEFAULT
      };
      const result = getTypedValue(subValue);
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
      substitution = {
        pathSource: '$.testPath',
        pathTarget: '$.targetPath',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      };
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
});
