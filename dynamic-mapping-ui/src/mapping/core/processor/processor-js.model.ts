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
      case 'dynamic.mapping.processor.model.SubstitutionResult':
        return SubstitutionResult;
      case 'dynamic.mapping.processor.model.SubstituteValue':
        return SubstituteValue;
      case 'dynamic.mapping.processor.model.RepairStrategy':
        return RepairStrategy;
      case 'dynamic.mapping.processor.model.SubstituteValue$TYPE':
        return TYPE;
      case 'java.util.ArrayList':
        return ArrayList;
      case 'java.util.HashMap':
        return HashMap;
      default:
        throw new Error(`Unknown Java class: ${className}`);
    }
  }
};

// RepairStrategy enum
export const RepairStrategy = {
  DEFAULT: 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY: 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY: 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE: 'IGNORE',
  REMOVE_IF_MISSING_OR_NULL: 'REMOVE_IF_MISSING_OR_NULL',
  CREATE_IF_MISSING: 'CREATE_IF_MISSING'
};

// TYPE enum (inside SubstituteValue in Java)
export const TYPE = {
  ARRAY: 'ARRAY',
  IGNORE: 'IGNORE',
  NUMBER: 'NUMBER',
  OBJECT: 'OBJECT',
  TEXTUAL: 'TEXTUAL'
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

  // Constants
  IDENTITY_EXTERNAL = this.IDENTITY + ".externalId";
  IDENTITY_C8Y = this.IDENTITY + ".c8ySourceId";

  /**
   * Constructor for the SubstitutionContext class
   * @param {string} genericDeviceIdentifier - The generic device identifier
   * @param {string} payload - The JSON object representing the data
   */
  constructor(genericDeviceIdentifier, payload) {
    // this.#payload = createDualAccessObject(payload || {});
    this.#payload = (payload || {});
    //this.#payload = payload;
    this.#genericDeviceIdentifier = genericDeviceIdentifier;
  }

  /**
   * Gets the generic device identifier
   * @returns {string} The generic device identifier
   */
  getGenericDeviceIdentifier() {
    return this.#genericDeviceIdentifier;
  }

  /**
   * Gets the external identifier from the JSON object
   * @returns {string|null} The external identifier or null if not found
   */
  getExternalIdentifier() {
    try {
      // Check if payload and the IDENTITY map exist
      if (this.#payload == null || this.#payload[this.IDENTITY] == null) {
        return null;
      }

      const identityMap = this.#payload[this.IDENTITY];
      return identityMap["externalId"];
    } catch (e) {
      // Optionally log the exception
      // console.debug("Error retrieving external identifier", e);
      return null;
    }
  }

  /**
   * Gets the C8Y identifier from the JSON object
   * @returns {string|null} The C8Y identifier or null if not found
   */
  getC8YIdentifier() {
    try {
      // Check if payload and the IDENTITY map exist
      if (this.#payload == null || this.#payload[this.IDENTITY] == null) {
        return null;
      }

      const identityMap = this.#payload[this.IDENTITY];
      return identityMap["c8ySourceId"];
    } catch (e) {
      // Optionally log the exception
      // console.debug("Error retrieving c8y identifier", e);
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


export function objectToMapDeep(obj) {
  // Handle null and undefined
  if (obj === null || obj === undefined) {
    return obj;
  }
  
  // If obj is already a Map, recursively convert its values
  if (obj instanceof Map) {
    const newMap = new Map();
    for (const [key, value] of obj.entries()) {
      newMap.set(key, objectToMapDeep(value));
    }
    return newMap;
  }
  
  // If it's an array, recursively convert each element
  if (Array.isArray(obj)) {
    return obj.map(item => objectToMapDeep(item));
  }
  
  // If it's an object, convert it to a Map with recursively converted values
  if (typeof obj === 'object') {
    const map = new Map();
    for (const [key, value] of Object.entries(obj)) {
      map.set(key, objectToMapDeep(value));
    }
    return map;
  }
  
  // For primitive values (string, number, boolean), return as is
  return obj;
}


// Create enhanced object with dual access
function createDualAccessObject(initialData = {}) {
  const obj = { ...initialData };
  
  // Add Map-like methods
  Object.defineProperties(obj, {
    // Map-like get method
    get: {
      value: function(key) {
        return this[key];
      },
      writable: false,
      enumerable: false
    },
    
    // Map-like set method
    set: {
      value: function(key, value) {
        this[key] = value;
        return this;
      },
      writable: false,
      enumerable: false
    },
    
    // Map-like has method
    has: {
      value: function(key) {
        return key in this;
      },
      writable: false,
      enumerable: false
    }
  });
  
  return obj;
}