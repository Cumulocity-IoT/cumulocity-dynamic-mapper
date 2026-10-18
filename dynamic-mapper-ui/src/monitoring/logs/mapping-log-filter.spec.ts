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
 */import { filterLogByMapping, MAPPING_IDENTIFIER } from './mapping-log-filter';

describe('filterLogByMapping', () => {
  const line = (time: string, tag: string, text: string) =>
    `2026-10-16T04:13:${time}Z  INFO 1 --- [virtThread-39] [t2050305588] ${tag}d.m.Foo : ${text}`;

  it('keeps only the records of the mapping, in order', () => {
    const logs = [line('01.001', '', 'connector'), line('02.002', '[m:aaa] ', 'a1'), line('03.003', '[m:bbb] ', 'b1'), line('04.004', '[m:aaa] ', 'a2')].join('\n');

    expect(filterLogByMapping(logs, 'aaa')).toBe([line('02.002', '[m:aaa] ', 'a1'), line('04.004', '[m:aaa] ', 'a2')].join('\n'));
    expect(filterLogByMapping(logs, 'bbb')).toBe(line('03.003', '[m:bbb] ', 'b1'));
    expect(filterLogByMapping(logs, 'ccc')).toBe('');
  });

  it('keeps continuation lines with their record', () => {
    const logs = [line('01.001', '[m:aaa] ', 'failed'), 'java.lang.IllegalStateException: boom', '\tat d.m.Foo.bar(Foo.java:1)', line('02.002', '[m:bbb] ', 'other')].join('\n');

    expect(filterLogByMapping(logs, 'aaa')).toBe([line('01.001', '[m:aaa] ', 'failed'), 'java.lang.IllegalStateException: boom', '\tat d.m.Foo.bar(Foo.java:1)'].join('\n'));
  });

  it('does not take a tag from the message', () => {
    const logs = [line('01.001', '', 'payload contains [m:aaa] here'), line('02.002', '[m:bbb] ', 'says [m:aaa] ')].join('\n');

    expect(filterLogByMapping(logs, 'aaa')).toBe('');
  });

  it('does not take a tag from a continuation line', () => {
    const logs = [line('01.001', '', 'multi-line'), '[m:aaa] d.m.Foo : looks like a tag'].join('\n');

    expect(filterLogByMapping(logs, 'aaa')).toBe('');
  });

  it('returns the text unchanged without a mapping and handles empty input', () => {
    expect(filterLogByMapping('x\ny', '')).toBe('x\ny');
    expect(filterLogByMapping('', 'aaa')).toBe('');
    expect(filterLogByMapping(undefined as unknown as string, 'aaa')).toBe('');
  });

  it('accepts generated identifiers only', () => {
    expect(MAPPING_IDENTIFIER.test('l19zjk')).toBe(true);
    expect(MAPPING_IDENTIFIER.test('a b')).toBe(false);
    expect(MAPPING_IDENTIFIER.test('x&y=1')).toBe(false);
  });
});
