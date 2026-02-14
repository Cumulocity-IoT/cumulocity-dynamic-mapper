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
import { ProcessingContext } from '../processor.model';
import { ProcessorError, ProcessorErrorCode } from './processor-error';

/**
 * Configuration options for error handling behavior
 */
export interface ErrorHandlerConfig {
  /** Whether to log errors to console (default: true) */
  logToConsole?: boolean;

  /** Whether to include stack traces in error messages (default: true in dev, false in prod) */
  includeStackTrace?: boolean;

  /** Whether to throw on non-recoverable errors (default: true) */
  throwOnNonRecoverable?: boolean;
}

/**
 * Service for handling processor errors consistently.
 * Manages error logging, reporting, and decision making (throw vs continue).
 *
 * @injectable
 * @providedIn root
 */
@Injectable({ providedIn: 'root' })
export class ErrorHandlerService {
  private config: Required<ErrorHandlerConfig> = {
    logToConsole: true,
    includeStackTrace: true,
    throwOnNonRecoverable: true
  };

  /**
   * Updates the error handler configuration
   */
  configure(config: ErrorHandlerConfig): void {
    this.config = { ...this.config, ...config };
  }

  /**
   * Handles a processor error according to configured rules.
   *
   * For recoverable errors:
   * - Adds to context.errors[]
   * - Logs to console (if enabled)
   * - Allows processing to continue
   *
   * For non-recoverable errors:
   * - Adds to context.errors[]
   * - Logs to console (if enabled)
   * - Throws the error (if throwOnNonRecoverable is true)
   *
   * @param error - The error to handle
   * @param context - The processing context
   * @throws {ProcessorError} If error is non-recoverable and throwOnNonRecoverable is true
   */
  handle(error: ProcessorError, context: ProcessingContext): void {
    // Add error message to context
    const errorMessage = this.formatErrorMessage(error);
    context.errors.push(errorMessage);

    // Log to console if enabled
    if (this.config.logToConsole) {
      this.logError(error, context);
    }

    // Store error details in logs for debugging
    if (context.logs) {
      context.logs.push({
        level: 'error',
        timestamp: new Date().toISOString(),
        code: error.code,
        message: error.message,
        recoverable: error.recoverable,
        context: error.context
      });
    }

    // Throw if non-recoverable and configured to do so
    if (!error.recoverable && this.config.throwOnNonRecoverable) {
      throw error;
    }
  }

  /**
   * Handles an unknown error by wrapping it in a ProcessorError
   */
  handleUnknownError(
    error: any,
    context: ProcessingContext,
    additionalContext: Record<string, any> = {}
  ): void {
    const processorError = ProcessorError.fromUnknownError(error, {
      mappingId: context.mapping?.id,
      topic: context.topic,
      ...additionalContext
    });

    this.handle(processorError, context);
  }

  /**
   * Checks if an error code represents a recoverable error
   */
  isRecoverable(code: ProcessorErrorCode): boolean {
    // Define which error codes are recoverable
    const recoverableCodes: ProcessorErrorCode[] = [
      ProcessorErrorCode.DEVICE_NOT_FOUND,
      ProcessorErrorCode.EXTERNAL_ID_RESOLUTION_FAILED
    ];

    return recoverableCodes.includes(code);
  }

  /**
   * Formats an error message for display
   */
  private formatErrorMessage(error: ProcessorError): string {
    let message = error.getUserMessage();

    // Add error code for clarity
    message = `[${error.code}] ${message}`;

    // Add stack trace if enabled and available
    if (this.config.includeStackTrace && error.stack) {
      message += `\n${error.stack}`;
    }

    return message;
  }

  /**
   * Logs an error to the console with appropriate formatting
   */
  private logError(error: ProcessorError, context: ProcessingContext): void {
    const logData = {
      code: error.code,
      message: error.message,
      recoverable: error.recoverable,
      mappingId: context.mapping?.id,
      topic: context.topic,
      errorContext: error.context
    };

    if (error.recoverable) {
      console.warn('[ProcessorError - Recoverable]', logData);
    } else {
      console.error('[ProcessorError - Non-Recoverable]', logData);
    }

    // Log stack trace separately for better console formatting
    if (this.config.includeStackTrace && error.stack) {
      console.error('Stack trace:', error.stack);
    }
  }

  /**
   * Creates a summary of all errors in a processing context
   */
  getErrorSummary(context: ProcessingContext): {
    total: number;
    recoverable: number;
    nonRecoverable: number;
    byCodes: Record<string, number>;
  } {
    const summary = {
      total: context.errors.length,
      recoverable: 0,
      nonRecoverable: 0,
      byCodes: {} as Record<string, number>
    };

    // Parse error codes from error messages
    context.errors.forEach(errorMsg => {
      const codeMatch = errorMsg.match(/\[([A-Z_]+)\]/);
      if (codeMatch) {
        const code = codeMatch[1];
        summary.byCodes[code] = (summary.byCodes[code] || 0) + 1;

        if (this.isRecoverable(code as ProcessorErrorCode)) {
          summary.recoverable++;
        } else {
          summary.nonRecoverable++;
        }
      }
    });

    return summary;
  }

  /**
   * Clears all errors from a processing context
   */
  clearErrors(context: ProcessingContext): void {
    context.errors = [];
  }

  /**
   * Checks if a processing context has any errors
   */
  hasErrors(context: ProcessingContext): boolean {
    return context.errors.length > 0;
  }

  /**
   * Checks if a processing context has any non-recoverable errors
   */
  hasNonRecoverableErrors(context: ProcessingContext): boolean {
    return context.errors.some(errorMsg => {
      const codeMatch = errorMsg.match(/\[([A-Z_]+)\]/);
      if (codeMatch) {
        const code = codeMatch[1] as ProcessorErrorCode;
        return !this.isRecoverable(code);
      }
      return true; // Treat unknown errors as non-recoverable
    });
  }
}
