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

/**
 * Structured view over `Mapping.filterMapping` (the "Filter execution mapping" expression).
 * The three options of the General Settings control translate to and from the JSONata
 * expression; nothing but the expression is persisted.
 */
export type ExecutionFilterMode = 'type' | 'fragment' | 'custom';

export interface ExecutionFilterChoice {
  mode: ExecutionFilterMode;
  /** The type or fragment name; unused for `custom`. */
  value?: string;
}

// A fragment name that is a plain JSONata identifier needs no quoting.
const PLAIN_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const TYPE_EXPRESSION = /^type\s*=\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")$/;
const EXISTS_EXPRESSION = /^\$exists\(\s*(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\)$/;

/** Choice to expression. `custom`, and an empty value, give an empty expression. */
export function toFilterMapping(choice: ExecutionFilterChoice): string {
  const value = (choice.value ?? '').trim();
  if (choice.mode === 'custom' || !value) return '';
  if (choice.mode === 'type') {
    return `type = '${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
  }
  return `$exists(${PLAIN_NAME.test(value) ? value : '`' + value.replace(/`/g, '') + '`'})`;
}

/**
 * Expression to choice. Only the exact forms {@link toFilterMapping} produces map to `type` or
 * `fragment`; anything else (empty, `true`, compound expressions) is `custom`, and the caller
 * keeps the expression untouched.
 */
export function fromFilterMapping(expression: string | undefined | null): ExecutionFilterChoice {
  const expr = (expression ?? '').trim();
  const type = TYPE_EXPRESSION.exec(expr);
  if (type) {
    const raw = type[1] ?? type[2];
    return { mode: 'type', value: raw.replace(/\\(.)/g, '$1') };
  }
  const fragment = EXISTS_EXPRESSION.exec(expr);
  if (fragment) return { mode: 'fragment', value: fragment[1] ?? fragment[2] };
  return { mode: 'custom' };
}
