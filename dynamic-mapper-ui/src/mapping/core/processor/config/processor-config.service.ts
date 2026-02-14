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
import { ProcessingConfig } from '../processor.constants';

/**
 * Configuration options for processor behavior
 */
export interface ProcessorConfig {
  /** Timeout for code execution in milliseconds (default: 250ms) */
  codeExecutionTimeoutMs?: number;

  /** Maximum allowed timeout in milliseconds (default: 5000ms) */
  maxTimeoutMs?: number;

  /** Minimum allowed timeout in milliseconds (default: 50ms) */
  minTimeoutMs?: number;

  /** Whether to stream console logs from code execution (default: true) */
  streamConsoleLogs?: boolean;

  /** Whether to enable strict mode for JSONata expressions (default: false) */
  jsonataStrictMode?: boolean;

  /** Maximum depth for recursive JSONPath evaluation (default: 100) */
  maxJsonPathDepth?: number;

  /** Whether to cache compiled JSONata expressions (default: true) */
  cacheCompiledExpressions?: boolean;
}

/**
 * Service for managing processor configuration
 * Provides centralized configuration management with validation
 *
 * @injectable
 * @providedIn root
 */
@Injectable({ providedIn: 'root' })
export class ProcessorConfigService {
  private config: Required<ProcessorConfig> = {
    codeExecutionTimeoutMs: ProcessingConfig.DEFAULT_TIMEOUT_MS,
    maxTimeoutMs: ProcessingConfig.MAX_TIMEOUT_MS,
    minTimeoutMs: 50,
    streamConsoleLogs: ProcessingConfig.DEFAULT_STREAM_LOGS,
    jsonataStrictMode: false,
    maxJsonPathDepth: 100,
    cacheCompiledExpressions: true
  };

  /**
   * Updates processor configuration
   * Validates values before applying
   *
   * @param config - Partial configuration to update
   * @throws Error if configuration values are invalid
   */
  configure(config: ProcessorConfig): void {
    // Validate timeout values
    if (config.codeExecutionTimeoutMs !== undefined) {
      this.validateTimeout(config.codeExecutionTimeoutMs);
    }

    if (config.maxTimeoutMs !== undefined && config.maxTimeoutMs <= 0) {
      throw new Error('maxTimeoutMs must be greater than 0');
    }

    if (config.minTimeoutMs !== undefined && config.minTimeoutMs <= 0) {
      throw new Error('minTimeoutMs must be greater than 0');
    }

    if (config.maxJsonPathDepth !== undefined && config.maxJsonPathDepth <= 0) {
      throw new Error('maxJsonPathDepth must be greater than 0');
    }

    // Merge with existing config
    this.config = { ...this.config, ...config };
  }

  /**
   * Gets the code execution timeout value
   *
   * @returns Timeout in milliseconds
   */
  getCodeExecutionTimeout(): number {
    return this.config.codeExecutionTimeoutMs;
  }

  /**
   * Gets the maximum allowed timeout
   *
   * @returns Maximum timeout in milliseconds
   */
  getMaxTimeout(): number {
    return this.config.maxTimeoutMs;
  }

  /**
   * Gets the minimum allowed timeout
   *
   * @returns Minimum timeout in milliseconds
   */
  getMinTimeout(): number {
    return this.config.minTimeoutMs;
  }

  /**
   * Gets whether console logs should be streamed during code execution
   *
   * @returns True if console logs should be streamed
   */
  shouldStreamConsoleLogs(): boolean {
    return this.config.streamConsoleLogs;
  }

  /**
   * Gets whether JSONata expressions should use strict mode
   *
   * @returns True if strict mode is enabled
   */
  isJsonataStrictMode(): boolean {
    return this.config.jsonataStrictMode;
  }

  /**
   * Gets the maximum JSONPath evaluation depth
   *
   * @returns Maximum depth
   */
  getMaxJsonPathDepth(): number {
    return this.config.maxJsonPathDepth;
  }

  /**
   * Gets whether compiled JSONata expressions should be cached
   *
   * @returns True if caching is enabled
   */
  shouldCacheCompiledExpressions(): boolean {
    return this.config.cacheCompiledExpressions;
  }

  /**
   * Gets the complete current configuration
   *
   * @returns Full configuration object
   */
  getConfig(): Readonly<Required<ProcessorConfig>> {
    return { ...this.config };
  }

  /**
   * Resets configuration to defaults
   */
  reset(): void {
    this.config = {
      codeExecutionTimeoutMs: ProcessingConfig.DEFAULT_TIMEOUT_MS,
      maxTimeoutMs: ProcessingConfig.MAX_TIMEOUT_MS,
      minTimeoutMs: 50,
      streamConsoleLogs: ProcessingConfig.DEFAULT_STREAM_LOGS,
      jsonataStrictMode: false,
      maxJsonPathDepth: 100,
      cacheCompiledExpressions: true
    };
  }

  /**
   * Validates a timeout value
   *
   * @param timeoutMs - Timeout to validate
   * @throws Error if timeout is invalid
   */
  validateTimeout(timeoutMs: number): void {
    if (timeoutMs < this.config.minTimeoutMs) {
      throw new Error(
        `Timeout ${timeoutMs}ms is below minimum ${this.config.minTimeoutMs}ms`
      );
    }

    if (timeoutMs > this.config.maxTimeoutMs) {
      throw new Error(
        `Timeout ${timeoutMs}ms exceeds maximum ${this.config.maxTimeoutMs}ms`
      );
    }
  }

  /**
   * Validates and clamps a timeout value to allowed range
   *
   * @param timeoutMs - Timeout to clamp
   * @returns Clamped timeout value
   */
  clampTimeout(timeoutMs: number): number {
    return Math.max(
      this.config.minTimeoutMs,
      Math.min(timeoutMs, this.config.maxTimeoutMs)
    );
  }
}
