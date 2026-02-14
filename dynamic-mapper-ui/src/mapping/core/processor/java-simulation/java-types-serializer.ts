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
 * Serializes Java type simulations into executable JavaScript code for Web Workers.
 * This ensures a single source of truth - the serialized code is generated from
 * the TypeScript definitions, not maintained separately.
 *
 * @module java-types-serializer
 */

/**
 * Generates executable JavaScript code containing Java type simulations.
 * This code can be injected into Web Workers for sandboxed code execution.
 *
 * @returns JavaScript code as a string
 */
export function serializeJavaTypes(): string {
  return `
// Java type simulations for Web Worker execution
// Generated from java-types.ts - DO NOT EDIT MANUALLY

const RepairStrategy = {
  DEFAULT: 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY: 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY: 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE: 'IGNORE',
  REMOVE_IF_MISSING_OR_NULL: 'REMOVE_IF_MISSING_OR_NULL',
  CREATE_IF_MISSING: 'CREATE_IF_MISSING'
};

const TYPE = {
  ARRAY: 'ARRAY',
  IGNORE: 'IGNORE',
  NUMBER: 'NUMBER',
  OBJECT: 'OBJECT',
  TEXTUAL: 'TEXTUAL'
};

class SubstituteValue {
  constructor(value, type, repairStrategy, expandArray) {
    this.value = value;
    this.type = type;
    this.repairStrategy = repairStrategy;
    this.expandArray = expandArray;
  }

  clone() {
    return new SubstituteValue(
      this.value,
      this.type,
      this.repairStrategy,
      this.expandArray
    );
  }
}

class ArrayList {
  constructor() {
    this.items = [];
  }

  add(item) {
    this.items.push(item);
    return true;
  }

  get(index) {
    return this.items[index];
  }

  size() {
    return this.items.length;
  }

  isEmpty() {
    return this.items.length === 0;
  }
}

class HashMap {
  constructor() {
    this.map = {};
  }

  put(key, value) {
    const previousValue = this.map[key] || null;
    this.map[key] = value;
    return previousValue;
  }

  get(key) {
    return this.map[key] || null;
  }

  containsKey(key) {
    return key in this.map;
  }

  keySet() {
    return Object.keys(this.map);
  }
}

class HashSet {
  constructor() {
    this.set = new Set();
  }

  add(item) {
    const sizeBefore = this.set.size;
    this.set.add(item);
    return this.set.size > sizeBefore;
  }

  contains(item) {
    return this.set.has(item);
  }

  remove(item) {
    return this.set.delete(item);
  }

  size() {
    return this.set.size;
  }

  isEmpty() {
    return this.set.size === 0;
  }

  clear() {
    this.set.clear();
  }

  toArray() {
    return Array.from(this.set);
  }

  iterator() {
    return this.set.values();
  }

  stream() {
    return {
      collect: (collector) => {
        if (collector.toString().includes('toList')) {
          return Array.from(this.set);
        }
        throw new Error('Unsupported collector');
      },
      filter: (predicate) => {
        const newSet = new HashSet();
        for (const item of this.set) {
          if (predicate(item)) {
            newSet.add(item);
          }
        }
        return newSet.stream();
      },
      map: (mapper) => {
        const result = new ArrayList();
        for (const item of this.set) {
          result.add(mapper(item));
        }
        return {
          collect: (collector) => {
            if (collector.toString().includes('toList')) {
              return result.items;
            }
            throw new Error('Unsupported collector');
          }
        };
      }
    };
  }

  toString() {
    return '[' + Array.from(this.set).join(', ') + ']';
  }
}

class SubstitutionResult {
  constructor(substitutions) {
    this.substitutions = substitutions || new HashMap();
  }

  addSubstitute(key, value) {
    this.substitutions.put(key, value);
  }

  getSubstitutions() {
    return this.substitutions;
  }

  toString() {
    return 'SubstitutionResult{substitutions=' + JSON.stringify(this.substitutions.map) + '}';
  }
}

class JsonObject {
  constructor(data) {
    this.data = data || {};
  }

  get(key) {
    return this.data[key];
  }
}

class SubstitutionContext {
  constructor(genericDeviceIdentifier, payload, topic) {
    this.IDENTITY = "_IDENTITY_";
    this.IDENTITY_EXTERNAL = "_IDENTITY_.externalId";
    this.IDENTITY_C8Y = "_IDENTITY_.c8ySourceId";
    this._payload = payload || {};
    this._genericDeviceIdentifier = genericDeviceIdentifier;
    this._topic = topic;
  }

  getGenericDeviceIdentifier() {
    return this._genericDeviceIdentifier;
  }

  getTopic() {
    return this._topic;
  }

  getExternalIdentifier() {
    try {
      const parsedPayload = typeof this._payload === 'string'
        ? JSON.parse(this._payload)
        : this._payload;
      const identityMap = parsedPayload[this.IDENTITY];
      return identityMap ? identityMap["externalId"] : null;
    } catch (e) {
      console.debug("Error retrieving external identifier", e);
      return null;
    }
  }

  getC8YIdentifier() {
    try {
      const parsedPayload = typeof this._payload === 'string'
        ? JSON.parse(this._payload)
        : this._payload;
      const identity = parsedPayload[this.IDENTITY];
      return identity ? identity["c8ySourceId"] : null;
    } catch (e) {
      console.debug("Error retrieving c8y identifier", e);
      return null;
    }
  }

  getPayload() {
    return this._payload;
  }
}

const Java = {
  type: function (className) {
    switch (className) {
      case 'dynamic.mapper.processor.model.SubstitutionResult':
        return SubstitutionResult;
      case 'dynamic.mapper.processor.model.SubstituteValue':
        return SubstituteValue;
      case 'dynamic.mapper.processor.model.RepairStrategy':
        return RepairStrategy;
      case 'dynamic.mapper.processor.model.SubstituteValue$TYPE':
        return TYPE;
      case 'java.util.ArrayList':
        return ArrayList;
      case 'java.util.HashMap':
        return HashMap;
      case 'java.util.HashSet':
        return HashSet;
      default:
        throw new Error('Unknown Java class: ' + className);
    }
  }
};
`;
}

/**
 * Gets the serialized Java types code.
 * Alias for serializeJavaTypes() for backward compatibility.
 */
export const getSerializedJavaTypes = serializeJavaTypes;
