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

import { Injectable } from '@angular/core';

// JSONata expression type (imported module)
type JSONataExpression = any;

/**
 * Cache statistics for monitoring
 */
export interface CacheStats {
  /** Total number of cache lookups */
  hits: number;

  /** Number of cache misses */
  misses: number;

  /** Current cache size */
  size: number;

  /** Maximum cache size */
  maxSize: number;

  /** Hit rate percentage */
  hitRate: number;
}

/**
 * LRU (Least Recently Used) cache for compiled JSONata expressions
 * Improves performance by avoiding repeated compilation of the same expressions
 *
 * @injectable
 * @providedIn root
 */
@Injectable({ providedIn: 'root' })
export class JSONataCacheService {
  private cache: Map<string, JSONataExpression> = new Map();
  private maxSize: number = 100;
  private hits: number = 0;
  private misses: number = 0;

  /**
   * Gets or compiles a JSONata expression
   *
   * @param expression - JSONata expression string
   * @param compiler - Function to compile expression if not cached
   * @returns Compiled JSONata expression
   */
  getOrCompile(
    expression: string,
    compiler: (expr: string) => JSONataExpression
  ): JSONataExpression {
    // Check cache first
    if (this.cache.has(expression)) {
      this.hits++;
      // Move to end (most recently used)
      const compiled = this.cache.get(expression)!;
      this.cache.delete(expression);
      this.cache.set(expression, compiled);
      return compiled;
    }

    // Cache miss - compile and store
    this.misses++;
    const compiled = compiler(expression);

    // Evict oldest entry if cache is full
    if (this.cache.size >= this.maxSize) {
      const firstKey = this.cache.keys().next().value;
      this.cache.delete(firstKey);
    }

    this.cache.set(expression, compiled);
    return compiled;
  }

  /**
   * Sets the maximum cache size
   * Evicts oldest entries if current size exceeds new max
   *
   * @param maxSize - Maximum number of cached expressions
   */
  setMaxSize(maxSize: number): void {
    if (maxSize < 1) {
      throw new Error('Max size must be at least 1');
    }

    this.maxSize = maxSize;

    // Evict oldest entries if necessary
    while (this.cache.size > this.maxSize) {
      const firstKey = this.cache.keys().next().value;
      this.cache.delete(firstKey);
    }
  }

  /**
   * Clears the entire cache
   */
  clear(): void {
    this.cache.clear();
    this.hits = 0;
    this.misses = 0;
  }

  /**
   * Removes a specific expression from cache
   *
   * @param expression - Expression to remove
   * @returns True if expression was in cache
   */
  invalidate(expression: string): boolean {
    return this.cache.delete(expression);
  }

  /**
   * Gets cache statistics
   *
   * @returns Cache statistics object
   */
  getStats(): CacheStats {
    const total = this.hits + this.misses;
    return {
      hits: this.hits,
      misses: this.misses,
      size: this.cache.size,
      maxSize: this.maxSize,
      hitRate: total > 0 ? (this.hits / total) * 100 : 0
    };
  }

  /**
   * Resets statistics counters without clearing cache
   */
  resetStats(): void {
    this.hits = 0;
    this.misses = 0;
  }

  /**
   * Checks if an expression is cached
   *
   * @param expression - Expression to check
   * @returns True if expression is in cache
   */
  has(expression: string): boolean {
    return this.cache.has(expression);
  }

  /**
   * Gets current cache size
   *
   * @returns Number of cached expressions
   */
  getSize(): number {
    return this.cache.size;
  }

  /**
   * Gets all cached expression strings (for debugging)
   *
   * @returns Array of cached expression strings
   */
  getCachedExpressions(): string[] {
    return Array.from(this.cache.keys());
  }

  /**
   * Preloads common expressions into cache
   * Useful for warming up the cache with frequently used expressions
   *
   * @param expressions - Array of expression strings to preload
   * @param compiler - Function to compile expressions
   */
  preload(
    expressions: string[],
    compiler: (expr: string) => JSONataExpression
  ): void {
    for (const expression of expressions) {
      if (!this.cache.has(expression)) {
        this.getOrCompile(expression, compiler);
      }
    }
  }
}
