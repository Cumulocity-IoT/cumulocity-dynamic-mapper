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

/**
 * Log levels for processor operations
 */
export enum LogLevel {
  DEBUG = 'debug',
  INFO = 'info',
  WARN = 'warn',
  ERROR = 'error'
}

/**
 * Structured log entry
 */
export interface LogEntry {
  /** Timestamp when the log was created */
  timestamp: string;

  /** Log level */
  level: LogLevel;

  /** Log message */
  message: string;

  /** Additional context data */
  context?: Record<string, any>;

  /** Optional error object */
  error?: Error;
}

/**
 * Configuration for the logger
 */
export interface LoggerConfig {
  /** Minimum log level to output (default: INFO) */
  minLevel?: LogLevel;

  /** Whether to output to console (default: true) */
  outputToConsole?: boolean;

  /** Whether to add logs to ProcessingContext.logs (default: true) */
  addToContext?: boolean;

  /** Custom log formatter */
  formatter?: (entry: LogEntry) => string;
}

/**
 * Service for structured logging in processor operations.
 * Replaces ad-hoc console.log calls with consistent, structured logging.
 *
 * @injectable
 * @providedIn root
 */
@Injectable({ providedIn: 'root' })
export class ProcessorLoggerService {
  private config: Required<Omit<LoggerConfig, 'formatter'>> & { formatter?: (entry: LogEntry) => string } = {
    minLevel: LogLevel.INFO,
    outputToConsole: true,
    addToContext: true
  };

  private readonly levelPriority: Record<LogLevel, number> = {
    [LogLevel.DEBUG]: 0,
    [LogLevel.INFO]: 1,
    [LogLevel.WARN]: 2,
    [LogLevel.ERROR]: 3
  };

  /**
   * Updates logger configuration
   */
  configure(config: LoggerConfig): void {
    this.config = { ...this.config, ...config };
  }

  /**
   * Logs a debug message
   */
  debug(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.log(LogLevel.DEBUG, message, context, undefined, processingContext);
  }

  /**
   * Logs an info message
   */
  info(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.log(LogLevel.INFO, message, context, undefined, processingContext);
  }

  /**
   * Logs a warning message
   */
  warn(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.log(LogLevel.WARN, message, context, undefined, processingContext);
  }

  /**
   * Logs an error message
   */
  error(message: string, error?: Error, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.log(LogLevel.ERROR, message, context, error, processingContext);
  }

  /**
   * Core logging method
   */
  private log(
    level: LogLevel,
    message: string,
    context?: Record<string, any>,
    error?: Error,
    processingContext?: ProcessingContext
  ): void {
    // Check if this log level should be output
    if (this.levelPriority[level] < this.levelPriority[this.config.minLevel]) {
      return;
    }

    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      level,
      message,
      context,
      error
    };

    // Add to processing context logs if enabled
    if (this.config.addToContext && processingContext?.logs) {
      processingContext.logs.push(this.formatForContext(entry));
    }

    // Output to console if enabled
    if (this.config.outputToConsole) {
      this.outputToConsole(entry);
    }
  }

  /**
   * Formats log entry for ProcessingContext.logs array
   */
  private formatForContext(entry: LogEntry): any {
    return {
      timestamp: entry.timestamp,
      level: entry.level,
      message: entry.message,
      ...(entry.context && { context: entry.context }),
      ...(entry.error && { error: { message: entry.error.message, stack: entry.error.stack } })
    };
  }

  /**
   * Outputs log entry to console
   */
  private outputToConsole(entry: LogEntry): void {
    const formatted = this.config.formatter
      ? this.config.formatter(entry)
      : this.defaultFormatter(entry);

    switch (entry.level) {
      case LogLevel.DEBUG:
        console.debug(formatted, entry.context || '');
        break;
      case LogLevel.INFO:
        console.info(formatted, entry.context || '');
        break;
      case LogLevel.WARN:
        console.warn(formatted, entry.context || '');
        if (entry.error) {
          console.warn('Error details:', entry.error);
        }
        break;
      case LogLevel.ERROR:
        console.error(formatted, entry.context || '');
        if (entry.error) {
          console.error('Error details:', entry.error);
        }
        break;
    }
  }

  /**
   * Default log formatter
   */
  private defaultFormatter(entry: LogEntry): string {
    const parts = [
      `[${entry.timestamp}]`,
      `[${entry.level.toUpperCase()}]`,
      `[Processor]`,
      entry.message
    ];

    return parts.join(' ');
  }

  /**
   * Creates a child logger with additional context that will be included in all logs
   */
  createChildLogger(baseContext: Record<string, any>): ChildLogger {
    return new ChildLogger(this, baseContext);
  }

  /**
   * Gets all logs from a processing context
   */
  getLogsFromContext(context: ProcessingContext, minLevel?: LogLevel): any[] {
    if (!context.logs) {
      return [];
    }

    if (!minLevel) {
      return context.logs;
    }

    const minPriority = this.levelPriority[minLevel];
    return context.logs.filter(log => {
      if (log.level) {
        return this.levelPriority[log.level as LogLevel] >= minPriority;
      }
      return true;
    });
  }

  /**
   * Clears all logs from a processing context
   */
  clearLogs(context: ProcessingContext): void {
    if (context.logs) {
      context.logs = [];
    }
  }
}

/**
 * Child logger that includes base context in all log calls
 */
export class ChildLogger {
  constructor(
    private parent: ProcessorLoggerService,
    private baseContext: Record<string, any>
  ) {}

  debug(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.parent.debug(message, this.mergeContext(context), processingContext);
  }

  info(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.parent.info(message, this.mergeContext(context), processingContext);
  }

  warn(message: string, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.parent.warn(message, this.mergeContext(context), processingContext);
  }

  error(message: string, error?: Error, context?: Record<string, any>, processingContext?: ProcessingContext): void {
    this.parent.error(message, error, this.mergeContext(context), processingContext);
  }

  private mergeContext(context?: Record<string, any>): Record<string, any> {
    return { ...this.baseContext, ...context };
  }
}
