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

});
