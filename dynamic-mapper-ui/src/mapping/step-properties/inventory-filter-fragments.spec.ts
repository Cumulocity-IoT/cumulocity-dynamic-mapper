/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import {
  extractReferencedAttributes,
  findUncachedFilterAttributes,
  isFragmentCached
} from './inventory-filter-fragments';

describe('inventory filter fragments', () => {
  describe('extractReferencedAttributes', () => {
    it('finds the attribute of a simple comparison', () => {
      expect(extractReferencedAttributes('type = "lora_device_type"')).toEqual(['type']);
    });

    it('takes only the first step of a path: the fragment, not what is read inside it', () => {
      expect(extractReferencedAttributes('c8y_Hardware.model = "x"')).toEqual(['c8y_Hardware']);
    });

    it('collects every attribute of a compound expression once', () => {
      expect(extractReferencedAttributes('type = "a" and (name = "b" or type = "c") and $exists(c8y_IsDevice)').sort())
        .toEqual(['c8y_IsDevice', 'name', 'type']);
    });

    it('ignores variables and function names', () => {
      expect(extractReferencedAttributes('$contains(type, "lora") and $count($x) > 0')).toEqual(['type']);
    });

    it('supports backquoted names', () => {
      expect(extractReferencedAttributes('`my-fragment` = "x"')).toEqual(['my-fragment']);
    });

    it('does not treat names inside a predicate as root attributes', () => {
      expect(extractReferencedAttributes('children[state = "on"]')).toEqual(['children']);
    });

    it('finds attributes inside conditionals', () => {
      expect(extractReferencedAttributes('type = "a" ? true : name = "b"').sort()).toEqual(['name', 'type']);
    });
  });

  describe('isFragmentCached', () => {
    it('matches an exact name', () => {
      expect(isFragmentCached('type', ['name', 'type'])).toBeTrue();
      expect(isFragmentCached('types', ['type'])).toBeFalse();
    });

    it('matches a * glob', () => {
      expect(isFragmentCached('sparkPlugB_DBIRTH_dev1', ['sparkPlugB_DBIRTH_*'])).toBeTrue();
      expect(isFragmentCached('other', ['sparkPlugB_DBIRTH_*'])).toBeFalse();
    });

    it('escapes regex characters in a glob', () => {
      expect(isFragmentCached('aXb', ['a.b*'])).toBeFalse();
      expect(isFragmentCached('a.bc', ['a.b*'])).toBeTrue();
    });

    it('ignores blank entries', () => {
      expect(isFragmentCached('type', ['', '  '])).toBeFalse();
    });
  });

  describe('findUncachedFilterAttributes', () => {
    it('returns nothing when everything used is cached', () => {
      expect(findUncachedFilterAttributes('type = "a"', ['type'])).toEqual([]);
    });

    it('returns the attributes that are not cached', () => {
      expect(findUncachedFilterAttributes('type = "a" and c8y_Hardware.model = "x"', ['type']))
        .toEqual(['c8y_Hardware']);
    });

    it('returns nothing for an empty expression or one that does not parse', () => {
      expect(findUncachedFilterAttributes('', ['type'])).toEqual([]);
      expect(findUncachedFilterAttributes('type = ', ['type'])).toEqual([]);
    });
  });
});
