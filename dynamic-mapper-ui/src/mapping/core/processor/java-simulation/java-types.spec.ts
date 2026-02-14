/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  ArrayList,
  HashMap,
  HashSet,
  Java,
  RepairStrategy,
  SubstitutionContext,
  SubstitutionResult,
  SubstituteValue,
  TYPE
} from './java-types';

describe('Java Type Simulations', () => {
  describe('ArrayList', () => {
    let list: ArrayList<number>;

    beforeEach(() => {
      list = new ArrayList<number>();
    });

    it('should add items', () => {
      expect(list.add(1)).toBe(true);
      expect(list.add(2)).toBe(true);
      expect(list.size()).toBe(2);
    });

    it('should get items by index', () => {
      list.add(10);
      list.add(20);
      list.add(30);

      expect(list.get(0)).toBe(10);
      expect(list.get(1)).toBe(20);
      expect(list.get(2)).toBe(30);
    });

    it('should return correct size', () => {
      expect(list.size()).toBe(0);
      list.add(1);
      expect(list.size()).toBe(1);
      list.add(2);
      expect(list.size()).toBe(2);
    });

    it('should check if empty', () => {
      expect(list.isEmpty()).toBe(true);
      list.add(1);
      expect(list.isEmpty()).toBe(false);
    });
  });

  describe('HashMap', () => {
    let map: HashMap<string, any>;

    beforeEach(() => {
      map = new HashMap<string, any>();
    });

    it('should put and get values', () => {
      map.put('key1', 'value1');
      expect(map.get('key1')).toBe('value1');
    });

    it('should return null for non-existent keys', () => {
      expect(map.get('nonexistent')).toBeNull();
    });

    it('should check if key exists', () => {
      expect(map.containsKey('key1')).toBe(false);
      map.put('key1', 'value1');
      expect(map.containsKey('key1')).toBe(true);
    });

    it('should return key set', () => {
      map.put('key1', 'value1');
      map.put('key2', 'value2');

      const keys = map.keySet();
      expect(keys).toContain('key1');
      expect(keys).toContain('key2');
      expect(keys.length).toBe(2);
    });

    it('should return previous value when putting', () => {
      expect(map.put('key1', 'first')).toBeNull();
      expect(map.put('key1', 'second')).toBe('first');
    });
  });

  describe('HashSet', () => {
    let set: HashSet<string>;

    beforeEach(() => {
      set = new HashSet<string>();
    });

    it('should add items', () => {
      expect(set.add('item1')).toBe(true);
      expect(set.add('item1')).toBe(false); // Already exists
      expect(set.size()).toBe(1);
    });

    it('should check if contains item', () => {
      expect(set.contains('item1')).toBe(false);
      set.add('item1');
      expect(set.contains('item1')).toBe(true);
    });

    it('should remove items', () => {
      set.add('item1');
      expect(set.remove('item1')).toBe(true);
      expect(set.remove('item1')).toBe(false); // Already removed
      expect(set.size()).toBe(0);
    });

    it('should return correct size', () => {
      expect(set.size()).toBe(0);
      set.add('item1');
      set.add('item2');
      expect(set.size()).toBe(2);
    });

    it('should check if empty', () => {
      expect(set.isEmpty()).toBe(true);
      set.add('item1');
      expect(set.isEmpty()).toBe(false);
    });

    it('should clear all items', () => {
      set.add('item1');
      set.add('item2');
      set.clear();
      expect(set.size()).toBe(0);
      expect(set.isEmpty()).toBe(true);
    });

    it('should convert to array', () => {
      set.add('item1');
      set.add('item2');
      const arr = set.toArray();
      expect(arr).toContain('item1');
      expect(arr).toContain('item2');
      expect(arr.length).toBe(2);
    });

    it('should provide iterator', () => {
      set.add('item1');
      set.add('item2');
      const iterator = set.iterator();
      const values = Array.from(iterator);
      expect(values).toContain('item1');
      expect(values).toContain('item2');
    });

    it('should support stream operations', () => {
      const numSet = new HashSet<number>();
      numSet.add(1);
      numSet.add(2);
      numSet.add(3);

      const filtered = numSet.stream()
        .filter((n: number) => n > 1)
        .collect({ toString: () => 'toList' });

      expect(filtered.length).toBe(2);
    });

    it('should convert to string', () => {
      set.add('a');
      set.add('b');
      const str = set.toString();
      expect(str).toContain('a');
      expect(str).toContain('b');
    });
  });

  describe('SubstituteValue', () => {
    it('should create with all properties', () => {
      const value = new SubstituteValue(
        42,
        TYPE.NUMBER,
        RepairStrategy.DEFAULT,
        false
      );

      expect(value.value).toBe(42);
      expect(value.type).toBe(TYPE.NUMBER);
      expect(value.repairStrategy).toBe(RepairStrategy.DEFAULT);
      expect(value.expandArray).toBe(false);
    });

    it('should clone correctly', () => {
      const original = new SubstituteValue(
        'test',
        TYPE.TEXTUAL,
        RepairStrategy.CREATE_IF_MISSING,
        true
      );

      const cloned = original.clone();

      expect(cloned.value).toBe(original.value);
      expect(cloned.type).toBe(original.type);
      expect(cloned.repairStrategy).toBe(original.repairStrategy);
      expect(cloned.expandArray).toBe(original.expandArray);
      expect(cloned).not.toBe(original); // Different instance
    });
  });

  describe('SubstitutionResult', () => {
    let result: SubstitutionResult;

    beforeEach(() => {
      result = new SubstitutionResult();
    });

    it('should create with empty substitutions', () => {
      expect(result.getSubstitutions()).toBeDefined();
      expect(result.getSubstitutions().keySet().length).toBe(0);
    });

    it('should add substitutes', () => {
      result.addSubstitute('$.path', 'value');
      expect(result.getSubstitutions().get('$.path')).toBe('value');
    });

    it('should accept custom HashMap in constructor', () => {
      const customMap = new HashMap();
      customMap.put('key1', 'value1');
      const customResult = new SubstitutionResult(customMap);

      expect(customResult.getSubstitutions().get('key1')).toBe('value1');
    });

    it('should convert to string', () => {
      result.addSubstitute('$.key', 'value');
      const str = result.toString();
      expect(str).toContain('SubstitutionResult');
      expect(str).toContain('substitutions');
    });
  });

  describe('SubstitutionContext', () => {
    it('should parse external identifier', () => {
      const payload = {
        _IDENTITY_: {
          externalId: 'device-001'
        }
      };
      const ctx = new SubstitutionContext(
        { type: 'c8y_Serial', externalId: 'device-001' },
        payload,
        'test/topic'
      );

      expect(ctx.getExternalIdentifier()).toBe('device-001');
    });

    it('should parse C8Y identifier', () => {
      const payload = {
        _IDENTITY_: {
          c8ySourceId: '12345'
        }
      };
      const ctx = new SubstitutionContext(null, payload, 'test/topic');

      expect(ctx.getC8YIdentifier()).toBe('12345');
    });

    it('should return null for missing external identifier', () => {
      const ctx = new SubstitutionContext(null, {}, 'test/topic');
      expect(ctx.getExternalIdentifier()).toBeNull();
    });

    it('should return null for missing C8Y identifier', () => {
      const ctx = new SubstitutionContext(null, {}, 'test/topic');
      expect(ctx.getC8YIdentifier()).toBeNull();
    });

    it('should get payload', () => {
      const payload = { test: 'data' };
      const ctx = new SubstitutionContext(null, payload, 'test/topic');
      expect(ctx.getPayload()).toEqual(payload);
    });

    it('should get topic', () => {
      const ctx = new SubstitutionContext(null, {}, 'device/001/telemetry');
      expect(ctx.getTopic()).toBe('device/001/telemetry');
    });

    it('should get generic device identifier', () => {
      const identifier = { type: 'c8y_Serial', externalId: 'device-001' };
      const ctx = new SubstitutionContext(identifier, {}, 'test/topic');
      expect(ctx.getGenericDeviceIdentifier()).toEqual(identifier);
    });

    it('should handle string payload for external identifier', () => {
      const payloadString = JSON.stringify({
        _IDENTITY_: { externalId: 'string-device' }
      });
      const ctx = new SubstitutionContext(null, payloadString, 'test/topic');
      expect(ctx.getExternalIdentifier()).toBe('string-device');
    });
  });

  describe('Java.type', () => {
    it('should return SubstitutionResult class', () => {
      const ResultClass = Java.type('dynamic.mapper.processor.model.SubstitutionResult');
      expect(ResultClass).toBe(SubstitutionResult);
    });

    it('should return SubstituteValue class', () => {
      const ValueClass = Java.type('dynamic.mapper.processor.model.SubstituteValue');
      expect(ValueClass).toBe(SubstituteValue);
    });

    it('should return RepairStrategy enum', () => {
      const Strategy = Java.type('dynamic.mapper.processor.model.RepairStrategy');
      expect(Strategy).toBe(RepairStrategy);
    });

    it('should return TYPE enum', () => {
      const TypeEnum = Java.type('dynamic.mapper.processor.model.SubstituteValue$TYPE');
      expect(TypeEnum).toBe(TYPE);
    });

    it('should return ArrayList class', () => {
      const ListClass = Java.type('java.util.ArrayList');
      expect(ListClass).toBe(ArrayList);
    });

    it('should return HashMap class', () => {
      const MapClass = Java.type('java.util.HashMap');
      expect(MapClass).toBe(HashMap);
    });

    it('should return HashSet class', () => {
      const SetClass = Java.type('java.util.HashSet');
      expect(SetClass).toBe(HashSet);
    });

    it('should throw error for unknown class', () => {
      expect(() => {
        Java.type('unknown.Class');
      }).toThrowError(/Unknown Java class/);
    });
  });
});
