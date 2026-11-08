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
import * as _ from 'lodash';

// ─── General utilities ────────────────────────────────────────────────────────

export function isExpression(path) {
  const containsSpecialChars = (str: string): boolean => {
    // IMPORTANT: here all special characters that are part of an expression must be listed, as they cause the editor to crash
    // when the path is used in a selection, e.g. 2 * c8yTemperature.T.value
    const regex = /[\$\(\)&\s\+\-\/\*\=]/;
    return regex.test(str);
  }
  return containsSpecialChars(path)
}

export function getTypeOf(object) {
  const stringConstructor = 'test'.constructor;
  const arrayConstructor = [].constructor;
  const objectConstructor = {}.constructor;
  const booleanConstructor = true.constructor;
  // console.log("Object constructor", object, object.constructor);
  if (object === null) {
    return 'null';
  } else if (object === undefined) {
    return 'undefined';
  } else if (object.constructor === stringConstructor) {
    return 'String';
  } else if (object.constructor === arrayConstructor) {
    return 'Array';
  } else if (object.constructor === objectConstructor) {
    return 'Object';
  } else if (object.constructor === booleanConstructor) {
    return 'Boolean';
  } else if (typeof object === 'number') {
    return 'Number';
  } else {
    return "don't know";
  }
}

// ─── Object utilities ─────────────────────────────────────────────────────────

/**
* Creates a new object with sorted keys, optionally placing specified keys at the end
* @param {Object} obj - The original object
* @param {Object} options - Configuration options
* @param {boolean} [options.underscoreKeysAtEnd=true] - Whether to place keys starting with "_" at the end
* @param {Function} [options.sortFn=null] - Optional custom sort function for keys
* @returns {Object} - New object with sorted keys
*/
export function sortObjectKeys(obj, options = {}) {
  // Set default options
  const defaultOptions = {
    underscoreKeysAtEnd: true,
    sortFn: null
  };

  const config = { ...defaultOptions, ...options };

  // Get the keys of the object
  let keys = Object.keys(obj);
  let underscoreKeys = [];

  // If we need to place underscore keys at the end, separate them
  if (config.underscoreKeysAtEnd) {
    // Extract keys starting with "_"
    underscoreKeys = keys.filter(key => key.startsWith('_'));

    // Remove underscore keys from the main keys array
    keys = keys.filter(key => !key.startsWith('_'));
  }

  // Sort the remaining keys
  if (config.sortFn && typeof config.sortFn === 'function') {
    keys.sort(config.sortFn);
  } else {
    keys.sort();
  }

  // Sort underscore keys alphabetically among themselves
  if (config.underscoreKeysAtEnd) {
    underscoreKeys.sort();
    // Append underscore keys to the end
    keys = [...keys, ...underscoreKeys];
  }

  // Create a new object with the sorted keys
  const sortedObj = {};
  keys.forEach(key => {
    sortedObj[key] = obj[key];
  });

  return sortedObj;
}
