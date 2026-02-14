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
import { serializeJavaTypes } from '../java-simulation/java-types-serializer';

/**
 * Error information from code evaluation
 */
export interface EvaluationError {
  message: string;
  stack: string | null;
  location: { line: number; column: number | null } | null;
}

/**
 * Result of code evaluation
 */
export interface EvaluationResult {
  success: boolean;
  result?: any;
  error?: EvaluationError;
  logs: string[];
}

/**
 * Context provided to the evaluated code
 */
export interface ExecutionContext {
  identifier: any;
  payload: any;
  topic: string;
}

/**
 * Configuration for code evaluation
 */
export interface EvaluationConfig {
  /** Timeout in milliseconds (default: 250ms) */
  timeoutMs?: number;
  /** Enable streaming logs to console (default: true) */
  streamLogs?: boolean;
}

/**
 * Service for evaluating JavaScript code in a sandboxed Web Worker environment.
 * Provides safe code execution with timeout protection and console log capture.
 *
 * @injectable
 * @providedIn root
 */
@Injectable({ providedIn: 'root' })
export class CodeEvaluatorService {
  private readonly DEFAULT_TIMEOUT_MS = 250;
  private readonly MAX_TIMEOUT_MS = 5000;

  /**
   * Evaluates JavaScript code in a Web Worker with the provided context.
   *
   * @param codeString - The JavaScript code to execute
   * @param ctx - The execution context (identifier, payload, topic)
   * @param config - Optional configuration (timeout, streaming)
   * @returns Promise resolving to evaluation result
   *
   * @example
   * ```typescript
   * const result = await evaluator.evaluate(
   *   'return arg0.getPayload().temperature;',
   *   { identifier: 'device001', payload: { temperature: 25 }, topic: 'test' }
   * );
   * if (result.success) {
   *   console.log('Result:', result.result);
   * }
   * ```
   */
  async evaluate(
    codeString: string,
    ctx: ExecutionContext,
    config: EvaluationConfig = {}
  ): Promise<EvaluationResult> {
    const timeoutMs = this.validateTimeout(config.timeoutMs ?? this.DEFAULT_TIMEOUT_MS);
    const streamLogs = config.streamLogs ?? true;

    return Promise.race([
      this.createTimeoutPromise(timeoutMs),
      this.executeInWorker(codeString, ctx, timeoutMs, streamLogs)
    ]);
  }

  /**
   * Validates and clamps timeout value
   */
  private validateTimeout(timeoutMs: number): number {
    if (timeoutMs < 0) {
      console.warn(`Invalid timeout ${timeoutMs}ms, using default ${this.DEFAULT_TIMEOUT_MS}ms`);
      return this.DEFAULT_TIMEOUT_MS;
    }
    if (timeoutMs > this.MAX_TIMEOUT_MS) {
      console.warn(`Timeout ${timeoutMs}ms exceeds maximum, clamping to ${this.MAX_TIMEOUT_MS}ms`);
      return this.MAX_TIMEOUT_MS;
    }
    return timeoutMs;
  }

  /**
   * Creates a promise that resolves after the specified timeout
   */
  private createTimeoutPromise(timeoutMs: number): Promise<EvaluationResult> {
    return new Promise<EvaluationResult>(resolve =>
      setTimeout(() => {
        resolve({
          success: false,
          error: {
            message: `Execution timed out after ${timeoutMs}ms`,
            stack: null,
            location: null
          },
          logs: ['Execution timed out']
        });
      }, timeoutMs)
    );
  }

  /**
   * Executes code in a Web Worker
   */
  private executeInWorker(
    codeString: string,
    ctx: ExecutionContext,
    timeoutMs: number,
    streamLogs: boolean
  ): Promise<EvaluationResult> {
    return new Promise<EvaluationResult>((resolve) => {
      const logs: string[] = [];
      let worker: Worker | null = null;
      let workerUrl: string | null = null;
      let timeoutId: number | null = null;

      try {
        // Create worker script with Java type simulations and console capture
        const workerScript = this.createWorkerScript();
        const blob = new Blob([workerScript], { type: 'application/javascript' });
        workerUrl = URL.createObjectURL(blob);

        // Create and configure worker
        worker = new Worker(workerUrl);

        // Set up timeout
        timeoutId = window.setTimeout(() => {
          this.cleanup(worker, workerUrl, timeoutId);
          resolve({
            success: false,
            error: {
              message: `Execution timed out after ${timeoutMs}ms`,
              stack: null,
              location: null
            },
            logs
          });
        }, timeoutMs);

        // Handle messages from worker
        worker.onmessage = (e) => {
          const { type, data } = e.data;

          if (type === 'log') {
            logs.push(data);
            if (streamLogs) {
              console.log(`[Worker] ${data}`);
            }
          } else if (type === 'result') {
            this.cleanup(worker, workerUrl, timeoutId);

            // Merge streaming logs with final logs
            const workerLogs = e.data.logs || [];
            const allLogs = [...logs];
            for (const wLog of workerLogs) {
              if (!logs.includes(wLog)) {
                allLogs.push(wLog);
              }
            }

            resolve({
              success: e.data.success,
              result: e.data.result,
              error: e.data.error,
              logs: allLogs
            });
          }
        };

        // Handle worker errors
        worker.onerror = (error) => {
          this.cleanup(worker, workerUrl, timeoutId);
          resolve({
            success: false,
            error: {
              message: `Worker error: ${error.message}`,
              stack: null,
              location: null
            },
            logs
          });
        };

        // Start execution
        worker.postMessage({
          code: codeString,
          ctx: {
            identifier: ctx.identifier,
            payload: ctx.payload,
            topic: ctx.topic
          }
        });
      } catch (error: any) {
        this.cleanup(worker, workerUrl, timeoutId);
        resolve({
          success: false,
          error: {
            message: `Failed to create or run worker: ${error.message}`,
            stack: error.stack || null,
            location: null
          },
          logs
        });
      }
    });
  }

  /**
   * Creates the Web Worker script with Java type simulations and console capture
   */
  private createWorkerScript(): string {
    const javaTypes = serializeJavaTypes();

    return `
      ${javaTypes}

      self.onmessage = function(event) {
        const logs = [];

        // Console capture function
        const consoleLog = function(...args) {
          const logString = args.map(arg => {
            if (arg === undefined) return 'undefined';
            if (arg === null) return 'null';
            if (typeof arg === 'object') {
              try {
                return JSON.stringify(arg);
              } catch (e) {
                return String(arg);
              }
            }
            return String(arg);
          }).join(' ');

          logs.push(logString);

          // Stream log to main thread
          self.postMessage({
            type: 'log',
            data: logString
          });
        };

        // Create console object
        const console = {
          log: consoleLog,
          info: consoleLog,
          warn: consoleLog,
          error: consoleLog,
          debug: consoleLog
        };

        const { code, ctx } = event.data;

        try {
          // Create execution context
          const arg0 = new SubstitutionContext(ctx.identifier, ctx.payload, ctx.topic);

          // Execute code
          const fn = new Function('arg0', 'console', code);
          const result = fn(arg0, console);

          // Send success result
          self.postMessage({
            type: 'result',
            success: true,
            result,
            logs
          });
        } catch (error) {
          console.error("Error in worker:", error.message);

          // Parse error information
          let errorStack = error.stack || "";
          let errorMessage = error.message || "Unknown error";
          let lineInfo = null;

          // Extract line numbers (adjust for Function constructor overhead)
          const lineMatches = errorStack.match(/<anonymous>:(\\d+):(\\d+)/);
          const evalMatches = errorStack.match(/eval.*<anonymous>:(\\d+):(\\d+)/);
          const functionMatches = errorStack.match(/Function:(\\d+):(\\d+)/);

          let adjustedLine = null;
          let column = null;

          if (lineMatches) {
            adjustedLine = Math.max(1, parseInt(lineMatches[1]) - 2);
            column = parseInt(lineMatches[2]);
          } else if (evalMatches) {
            adjustedLine = Math.max(1, parseInt(evalMatches[1]) - 2);
            column = parseInt(evalMatches[2]);
          } else if (functionMatches) {
            adjustedLine = Math.max(1, parseInt(functionMatches[1]) - 2);
            column = parseInt(functionMatches[2]);
          }

          // Add line info to error message
          if (adjustedLine !== null) {
            errorMessage = errorMessage + " (at line " + adjustedLine +
                          (column ? ", column " + column : "") + ")";
            lineInfo = { line: adjustedLine, column: column };
          }

          // Send error result
          self.postMessage({
            type: 'result',
            success: false,
            error: {
              message: errorMessage,
              stack: errorStack,
              location: lineInfo
            },
            logs
          });
        }
      };
    `;
  }

  /**
   * Cleans up worker resources
   */
  private cleanup(
    worker: Worker | null,
    workerUrl: string | null,
    timeoutId: number | null
  ): void {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }
    if (worker) {
      worker.terminate();
    }
    if (workerUrl) {
      URL.revokeObjectURL(workerUrl);
    }
  }
}
