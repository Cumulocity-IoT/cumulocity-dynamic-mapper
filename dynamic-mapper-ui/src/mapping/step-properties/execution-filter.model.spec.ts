/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { fromFilterMapping, toFilterMapping } from './execution-filter.model';

describe('execution filter translation', () => {
  it('translates type to type = quoted value', () => {
    expect(toFilterMapping({ mode: 'type', value: ' lora ' })).toBe("type = 'lora'");
  });

  it('escapes quotes and backslashes in a type', () => {
    expect(toFilterMapping({ mode: 'type', value: "it's" })).toBe("type = 'it\\'s'");
  });

  it('translates a plain fragment to $exists(name)', () => {
    expect(toFilterMapping({ mode: 'fragment', value: 'c8y_Temperature' })).toBe('$exists(c8y_Temperature)');
  });

  it('backtick-quotes a fragment that is not a plain identifier', () => {
    expect(toFilterMapping({ mode: 'fragment', value: 'my-fragment' })).toBe('$exists(`my-fragment`)');
  });

  it('gives an empty expression for custom and for an empty value', () => {
    expect(toFilterMapping({ mode: 'custom' })).toBe('');
    expect(toFilterMapping({ mode: 'type', value: '  ' })).toBe('');
    expect(toFilterMapping({ mode: 'fragment' })).toBe('');
  });

  it('round-trips type and fragment', () => {
    for (const choice of [
      { mode: 'type', value: 'lora' },
      { mode: 'type', value: "it's" },
      { mode: 'fragment', value: 'c8y_Temperature' },
      { mode: 'fragment', value: 'my-fragment' }
    ] as const) {
      expect(fromFilterMapping(toFilterMapping(choice))).toEqual(choice);
    }
  });

  it('accepts double quotes and spacing when reading a type', () => {
    expect(fromFilterMapping('type="abc"')).toEqual({ mode: 'type', value: 'abc' });
    expect(fromFilterMapping('  type =  \'abc\' ')).toEqual({ mode: 'type', value: 'abc' });
  });

  it('treats everything else as custom', () => {
    for (const expr of [undefined, null, '', 'true', "type = 'a' and $exists(b)", '$exists(a) or $exists(b)', 'name = \'x\'']) {
      expect(fromFilterMapping(expr)).toEqual({ mode: 'custom' });
    }
  });
});
