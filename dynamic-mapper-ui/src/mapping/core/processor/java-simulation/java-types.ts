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
 * JavaScript simulations of Java classes for code-based transformations.
 * This module provides Java-like data structures and classes that can be used
 * in JavaScript code executed in Web Workers for data transformation.
 *
 * @module java-simulation
 */

/**
 * Repair strategies for handling data extraction edge cases
 */
export enum RepairStrategy {
  DEFAULT = 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY = 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY = 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE = 'IGNORE',
  REMOVE_IF_MISSING_OR_NULL = 'REMOVE_IF_MISSING_OR_NULL',
  CREATE_IF_MISSING = 'CREATE_IF_MISSING'
}

/**
 * Type enumeration for substitute values
 */
export enum TYPE {
  ARRAY = 'ARRAY',
  IGNORE = 'IGNORE',
  NUMBER = 'NUMBER',
  OBJECT = 'OBJECT',
  TEXTUAL = 'TEXTUAL'
}

/**
 * Represents a substitute value with type and repair strategy information
 */
export class SubstituteValue {
  value: any;
  type: TYPE;
  repairStrategy: RepairStrategy;
  expandArray: boolean;

  constructor(value: any, type: TYPE, repairStrategy: RepairStrategy, expandArray: boolean) {
    this.value = value;
    this.type = type;
    this.repairStrategy = repairStrategy;
    this.expandArray = expandArray;
  }

  /**
   * Creates a deep clone of this SubstituteValue
   */
  clone(): SubstituteValue {
    return new SubstituteValue(
      this.value,
      this.type,
      this.repairStrategy,
      this.expandArray
    );
  }
}

/**
 * JavaScript simulation of Java's ArrayList
 * Provides a familiar API for Java developers working with JavaScript
 */
export class ArrayList<T = any> {
  items: T[];

  constructor() {
    this.items = [];
  }

  /**
   * Adds an element to the end of the list
   * @param item - The item to add
   * @returns true (for Java compatibility)
   */
  add(item: T): boolean {
    this.items.push(item);
    return true;
  }

  /**
   * Gets an element at the specified index
   * @param index - The index
   * @returns The element at the index
   */
  get(index: number): T {
    return this.items[index];
  }

  /**
   * Returns the number of elements in the list
   */
  size(): number {
    return this.items.length;
  }

  /**
   * Returns true if the list contains no elements
   */
  isEmpty(): boolean {
    return this.items.length === 0;
  }
}

/**
 * JavaScript simulation of Java's HashMap
 * Provides key-value storage with a Java-like API
 */
export class HashMap<K = string, V = any> {
  map: Record<string, V>;

  constructor() {
    this.map = {};
  }

  /**
   * Associates the specified value with the specified key
   * @param key - The key
   * @param value - The value
   * @returns The previous value associated with key, or null
   */
  put(key: K, value: V): V | null {
    const previousValue = this.map[key as string] || null;
    this.map[key as string] = value;
    return previousValue;
  }

  /**
   * Returns the value to which the specified key is mapped
   * @param key - The key
   * @returns The value, or null if not found
   */
  get(key: K): V | null {
    return this.map[key as string] || null;
  }

  /**
   * Returns true if this map contains a mapping for the specified key
   * @param key - The key
   */
  containsKey(key: K): boolean {
    return key as string in this.map;
  }

  /**
   * Returns a Set view of the keys contained in this map
   */
  keySet(): string[] {
    return Object.keys(this.map);
  }
}

/**
 * JavaScript simulation of Java's HashSet
 * Provides a set implementation with Java-like API
 */
export class HashSet<T = any> {
  set: Set<T>;

  constructor() {
    this.set = new Set<T>();
  }

  /**
   * Adds the specified element to this set
   * @param item - The item to add
   * @returns true if the set did not already contain the element
   */
  add(item: T): boolean {
    const sizeBefore = this.set.size;
    this.set.add(item);
    return this.set.size > sizeBefore;
  }

  /**
   * Returns true if this set contains the specified element
   * @param item - The item to check
   */
  contains(item: T): boolean {
    return this.set.has(item);
  }

  /**
   * Removes the specified element from this set
   * @param item - The item to remove
   * @returns true if the set contained the element
   */
  remove(item: T): boolean {
    return this.set.delete(item);
  }

  /**
   * Returns the number of elements in this set
   */
  size(): number {
    return this.set.size;
  }

  /**
   * Returns true if this set contains no elements
   */
  isEmpty(): boolean {
    return this.set.size === 0;
  }

  /**
   * Removes all elements from this set
   */
  clear(): void {
    this.set.clear();
  }

  /**
   * Returns an array containing all elements in this set
   */
  toArray(): T[] {
    return Array.from(this.set);
  }

  /**
   * Returns an iterator over the elements in this set
   */
  iterator(): IterableIterator<T> {
    return this.set.values();
  }

  /**
   * Returns a stream for functional-style operations (Java compatibility)
   */
  stream() {
    return {
      collect: (collector: any) => {
        if (collector.toString().includes('toList')) {
          return Array.from(this.set);
        }
        throw new Error('Unsupported collector');
      },
      filter: (predicate: (item: T) => boolean) => {
        const newSet = new HashSet<T>();
        for (const item of this.set) {
          if (predicate(item)) {
            newSet.add(item);
          }
        }
        return newSet.stream();
      },
      map: (mapper: (item: T) => any) => {
        const result = new ArrayList();
        for (const item of this.set) {
          result.add(mapper(item));
        }
        return {
          collect: (collector: any) => {
            if (collector.toString().includes('toList')) {
              return result.items;
            }
            throw new Error('Unsupported collector');
          }
        };
      }
    };
  }

  /**
   * Returns a string representation of this set
   */
  toString(): string {
    return `[${Array.from(this.set).join(', ')}]`;
  }
}

/**
 * Result of a substitution operation containing key-value mappings
 */
export class SubstitutionResult {
  substitutions: HashMap;

  constructor(substitutions?: HashMap) {
    this.substitutions = substitutions || new HashMap();
  }

  /**
   * Adds a substitution mapping
   * @param key - The target path
   * @param value - The value to substitute
   */
  addSubstitute(key: string, value: any): void {
    this.substitutions.put(key, value);
  }

  /**
   * Gets all substitutions
   */
  getSubstitutions(): HashMap {
    return this.substitutions;
  }

  /**
   * Returns a string representation of this result
   */
  toString(): string {
    return `SubstitutionResult{substitutions=${JSON.stringify(this.substitutions.map)}}`;
  }
}

/**
 * Mock JsonObject for testing
 */
export class JsonObject {
  data: Record<string, any>;

  constructor(data?: Record<string, any>) {
    this.data = data || {};
  }

  /**
   * Gets a value by key
   * @param key - The key
   */
  get(key: string): any {
    return this.data[key];
  }
}

/**
 * Context object provided to JavaScript code during transformation.
 * Contains payload data, device identifiers, and topic information.
 */
export class SubstitutionContext {
  readonly IDENTITY = "_IDENTITY_";
  readonly IDENTITY_EXTERNAL = "_IDENTITY_.externalId";
  readonly IDENTITY_C8Y = "_IDENTITY_.c8ySourceId";

  #payload: any;
  #genericDeviceIdentifier: any;
  #topic: string;

  /**
   * Creates a new SubstitutionContext
   * @param genericDeviceIdentifier - The device identifier (external ID or C8Y ID)
   * @param payload - The JSON payload being processed
   * @param topic - The MQTT topic
   */
  constructor(genericDeviceIdentifier: any, payload: any, topic: string) {
    this.#payload = payload || {};
    this.#genericDeviceIdentifier = genericDeviceIdentifier;
    this.#topic = topic;
  }

  /**
   * Gets the generic device identifier
   */
  getGenericDeviceIdentifier(): any {
    return this.#genericDeviceIdentifier;
  }

  /**
   * Gets the MQTT topic
   */
  getTopic(): string {
    return this.#topic;
  }

  /**
   * Gets the external identifier from the payload's _IDENTITY_ section
   * @returns The external identifier or null if not found
   */
  getExternalIdentifier(): string | null {
    try {
      const parsedPayload = typeof this.#payload === 'string'
        ? JSON.parse(this.#payload)
        : this.#payload;
      const identityMap = parsedPayload[this.IDENTITY];
      return identityMap?.["externalId"] || null;
    } catch (e) {
      console.debug("Error retrieving external identifier", e);
      return null;
    }
  }

  /**
   * Gets the C8Y identifier from the payload's _IDENTITY_ section
   * @returns The C8Y identifier or null if not found
   */
  getC8YIdentifier(): string | null {
    try {
      const parsedPayload = typeof this.#payload === 'string'
        ? JSON.parse(this.#payload)
        : this.#payload;
      return parsedPayload[this.IDENTITY]?.["c8ySourceId"] || null;
    } catch (e) {
      console.debug("Error retrieving c8y identifier", e);
      return null;
    }
  }

  /**
   * Gets the payload object
   */
  getPayload(): any {
    return this.#payload;
  }
}

/**
 * Java.type() simulation for loading Java classes by name
 * Used in code-based transformations for Java compatibility
 */
export const Java = {
  type: function (className: string): any {
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
