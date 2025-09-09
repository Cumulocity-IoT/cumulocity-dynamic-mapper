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
// JavaScript simulation of Java classes

// Simulate Java.type function
export const Java = {
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
        throw new Error(`Unknown Java class: ${className}`);
    }
  }
};

// RepairStrategy enum
export enum RepairStrategy {
  DEFAULT = 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY = 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY = 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE = 'IGNORE',
  REMOVE_IF_MISSING_OR_NULL = 'REMOVE_IF_MISSING_OR_NULL',
  CREATE_IF_MISSING = 'CREATE_IF_MISSING'
};

// TYPE enum (inside SubstituteValue in Java)
export enum TYPE {
  ARRAY = 'ARRAY',
  IGNORE = 'IGNORE',
  NUMBER = 'NUMBER',
  OBJECT = 'OBJECT',
  TEXTUAL = 'TEXTUAL'
};

// SubstituteValue class
export class SubstituteValue {
  value;
  type;
  repairStrategy;
  expandArray;
  constructor(value, type, repairStrategy, expandArray) {
    this.value = value;
    this.type = type;
    this.repairStrategy = repairStrategy;
    this.expandArray = expandArray;
  }

  // Clone method if needed
  clone() {
    return new SubstituteValue(
      this.value,
      this.type,
      this.repairStrategy,
      this.expandArray
    );
  }
}

// ArrayList simulation
export class ArrayList {
  items;
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

  // Additional ArrayList methods as needed
}

// HashMap simulation
export class HashMap {
  map;
  constructor() {
    this.map = {};
  }

  put(key, value) {
    this.map[key] = value;
    return value;
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

  // Additional HashMap methods as needed
}

// HashSet simulation
export class HashSet {
  set;

  constructor() {
    this.set = new Set();
  }

  add(item) {
    this.set.add(item);
    return true;
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

  // For compatibility with Java's stream API
  stream() {
    return {
      collect: (collector) => {
        // Basic implementation that works with Collectors.toList()
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

  // Convert to a string representation
  toString() {
    return `[${Array.from(this.set).join(', ')}]`;
  }
}

// SubstitutionResult class
export class SubstitutionResult {
  substitutions;
  constructor(substitutions) {
    this.substitutions = substitutions || new HashMap();
  }

  getSubstitutions() {
    return this.substitutions;
  }

  toString() {
    return `SubstitutionResult{substitutions=${JSON.stringify(this.substitutions.map)}}`;
  }
}

// Mock JsonObject for testing
export class JsonObject {
  data;
  constructor(data) {
    this.data = data || {};
  }

  get(key) {
    return this.data[key];
  }
}

/**
 * JavaScript equivalent of the Java SubstitutionContext class
 */
export class SubstitutionContext {
  IDENTITY = "_IDENTITY_";
  #payload;  // Using private class field (equivalent to private final in Java)
  #genericDeviceIdentifier;
  #topic;

  // Constants
  IDENTITY_EXTERNAL = this.IDENTITY + ".externalId";
  IDENTITY_C8Y = this.IDENTITY + ".c8ySourceId";

  /**
   * Constructor for the SubstitutionContext class
   * @param {string} genericDeviceIdentifier - The generic device identifier
   * @param {string} payload - The JSON object representing the data
   * @param {string} topic - The publish/ subscribe topic
   */
  constructor(genericDeviceIdentifier, payload, topic) {
    this.#payload = (payload || {});
    this.#genericDeviceIdentifier = genericDeviceIdentifier;
    this.#topic = topic;
  }

  /**
   * Gets the generic device identifier
   * @returns {string} The generic device identifier
   */
  getGenericDeviceIdentifier() {
    return this.#genericDeviceIdentifier;
  }

  /**
   * Gets the topic
   * @returns {string} The topic
   */
  getTopic() {
    return this.#topic;
  }

  /**
   * Gets the external identifier from the JSON object
   * @returns {string|null} The external identifier or null if not found
   */
  getExternalIdentifier() {
    try {
      const parsedPayload = JSON.parse(this.#payload);
      const identityMap = parsedPayload[this.IDENTITY];
      return identityMap["externalId"];
    } catch (e) {
      console.debug("Error retrieving external identifier", e);
      return null;
    }
  }

  /**
   * Gets the C8Y identifier from the JSON object
   * @returns {string|null} The C8Y identifier or null if not found
   */
  getC8YIdentifier() {
    try {
      const parsedPayload = JSON.parse(this.#payload);
      // Optional chaining will return undefined if any part of the chain is null/undefined
      return parsedPayload[this.IDENTITY]?.["c8ySourceId"] || null;
    } catch (e) {
      console.debug("Error retrieving c8y identifier", e);
      return null;
    }
  }

  /**
   * Gets the JSON object
   * @returns {Object} The JSON object
   */
  getPayload() {
    return this.#payload;
  }
}