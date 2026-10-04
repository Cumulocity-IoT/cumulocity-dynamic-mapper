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

// Same loading style as MappingService / TreeService: the package is CommonJS (`export =`).
// eslint-disable-next-line @typescript-eslint/no-require-imports
const jsonata = require('jsonata');

/** AST child properties that hold sub-expressions evaluated against the same (root) context. */
const CHILD_KEYS = ['lhs', 'rhs', 'expression', 'condition', 'then', 'else', 'procedure', 'body'];
const CHILD_LIST_KEYS = ['arguments', 'expressions'];

/**
 * The inventory attributes (top-level fragment names) a filter expression reads.
 *
 * The filter is evaluated against the cached copy of the managed object, which only contains the
 * fragments listed under "Fragments from inventory to cache". Only the first step of a path is the
 * attribute that has to be cached: for {@code c8y_Hardware.model} that is {@code c8y_Hardware};
 * {@code model} is read from inside the cached fragment. Variables and function names
 * ({@code $exists(...)}) are not attributes. Paths inside a step's predicate or in a path's later
 * steps are relative to that step, so they are not collected.
 *
 * Throws if the expression does not parse; callers validate syntax separately.
 */
export function extractReferencedAttributes(expression: string): string[] {
  const found = new Set<string>();
  const visit = (node: any): void => {
    if (!node || typeof node !== 'object') {
      return;
    }
    if (node.type === 'path' && Array.isArray(node.steps) && node.steps.length > 0) {
      const first = node.steps[0];
      if (first?.type === 'name' && typeof first.value === 'string') {
        found.add(first.value);
      } else {
        // e.g. a path starting with a function call or a parenthesised block
        visit(first);
      }
      return;
    }
    if (node.type === 'name' && typeof node.value === 'string') {
      found.add(node.value);
      return;
    }
    CHILD_KEYS.forEach(key => visit(node[key]));
    CHILD_LIST_KEYS.forEach(key => Array.isArray(node[key]) && node[key].forEach(visit));
  };
  visit(jsonata(expression).ast());
  return [...found];
}

/** {@code true} when {@code name} equals the configured entry or matches it as a {@code *} glob. */
export function isFragmentCached(name: string, cachedFragments: string[]): boolean {
  return cachedFragments.some(entry => {
    const pattern = entry.trim();
    if (!pattern) {
      return false;
    }
    if (!pattern.includes('*')) {
      return pattern === name;
    }
    const regex = new RegExp(
      '^' + pattern.split('*').map(part => part.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$'
    );
    return regex.test(name);
  });
}

/**
 * The attributes the filter reads that are not in the inventory cache configuration — the ones
 * that will be missing from the object the filter is evaluated on. Empty when the expression is
 * empty, does not parse, or everything it uses is cached.
 */
export function findUncachedFilterAttributes(expression: string, cachedFragments: string[]): string[] {
  if (!expression || !expression.trim()) {
    return [];
  }
  try {
    return extractReferencedAttributes(expression).filter(name => !isFragmentCached(name, cachedFragments));
  } catch {
    return [];
  }
}
