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

/**
 * Standardized error handling for the processor module.
 * Provides consistent error categorization and handling across all processors.
 *
 * @module processor-error
 */

/**
 * Error codes for processor operations.
 * Used to categorize and handle different types of errors consistently.
 */
export enum ProcessorErrorCode {
  // Device-related errors
  DEVICE_NOT_FOUND = 'DEVICE_NOT_FOUND',
  DEVICE_CREATION_FAILED = 'DEVICE_CREATION_FAILED',
  EXTERNAL_ID_RESOLUTION_FAILED = 'EXTERNAL_ID_RESOLUTION_FAILED',
  IMPLICIT_DEVICE_CREATION_DISABLED = 'IMPLICIT_DEVICE_CREATION_DISABLED',

  // Payload processing errors
  PAYLOAD_PARSING_FAILED = 'PAYLOAD_PARSING_FAILED',
  INVALID_PAYLOAD_STRUCTURE = 'INVALID_PAYLOAD_STRUCTURE',
  PATH_NOT_FOUND = 'PATH_NOT_FOUND',
  SUBSTITUTION_FAILED = 'SUBSTITUTION_FAILED',

  // Code execution errors
  CODE_EXECUTION_FAILED = 'CODE_EXECUTION_FAILED',
  CODE_EXECUTION_TIMEOUT = 'CODE_EXECUTION_TIMEOUT',
  JAVASCRIPT_SYNTAX_ERROR = 'JAVASCRIPT_SYNTAX_ERROR',

  // Expression evaluation errors
  JSONATA_EVALUATION_FAILED = 'JSONATA_EVALUATION_FAILED',
  EXPRESSION_SYNTAX_ERROR = 'EXPRESSION_SYNTAX_ERROR',

  // Template errors
  TEMPLATE_NOT_FOUND = 'TEMPLATE_NOT_FOUND',
  TEMPLATE_INVALID = 'TEMPLATE_INVALID',

  // API/Communication errors
  API_REQUEST_FAILED = 'API_REQUEST_FAILED',
  MQTT_PUBLISH_FAILED = 'MQTT_PUBLISH_FAILED',

  // Configuration errors
  INVALID_MAPPING_CONFIGURATION = 'INVALID_MAPPING_CONFIGURATION',
  MISSING_REQUIRED_FIELD = 'MISSING_REQUIRED_FIELD',

  // Generic errors
  UNKNOWN_ERROR = 'UNKNOWN_ERROR',
  VALIDATION_ERROR = 'VALIDATION_ERROR'
}

/**
 * Context data attached to processor errors for debugging and logging
 */
export interface ProcessorErrorContext {
  /** The mapping being processed */
  mappingId?: string;

  /** The MQTT topic */
  topic?: string;

  /** The path being processed */
  path?: string;

  /** Device identifier */
  deviceId?: string;

  /** External identifier */
  externalId?: string;

  /** API type being used */
  api?: string;

  /** Original error if wrapped */
  originalError?: Error;

  /** Additional custom data */
  [key: string]: any;
}

/**
 * Standardized error class for processor operations.
 * Provides consistent error handling with categorization, recoverability, and context.
 */
export class ProcessorError extends Error {
  /**
   * Error code for categorization
   */
  readonly code: ProcessorErrorCode;

  /**
   * Whether this error is recoverable (can continue processing)
   */
  readonly recoverable: boolean;

  /**
   * Additional context data for debugging
   */
  readonly context: ProcessorErrorContext;

  /**
   * HTTP status code if applicable
   */
  readonly statusCode?: number;

  constructor(
    code: ProcessorErrorCode,
    message: string,
    recoverable: boolean = false,
    context: ProcessorErrorContext = {},
    statusCode?: number
  ) {
    super(message);
    this.name = 'ProcessorError';
    this.code = code;
    this.recoverable = recoverable;
    this.context = context;
    this.statusCode = statusCode;

    // Maintains proper stack trace for where our error was thrown (only available on V8)
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ProcessorError);
    }
  }

  /**
   * Returns a user-friendly error message
   */
  getUserMessage(): string {
    return this.message;
  }

  /**
   * Returns detailed error information for logging
   */
  getDetailedMessage(): string {
    const contextStr = Object.keys(this.context).length > 0
      ? ` | Context: ${JSON.stringify(this.context)}`
      : '';
    return `[${this.code}] ${this.message}${contextStr}`;
  }

  /**
   * Converts error to JSON for serialization
   */
  toJSON(): object {
    return {
      name: this.name,
      code: this.code,
      message: this.message,
      recoverable: this.recoverable,
      context: this.context,
      statusCode: this.statusCode,
      stack: this.stack
    };
  }

  // ========== Factory Methods for Common Errors ==========

  /**
   * Creates an error for when a device is not found
   */
  static deviceNotFound(deviceId: string, context: ProcessorErrorContext = {}): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.DEVICE_NOT_FOUND,
      `Device not found: ${deviceId}`,
      true, // Recoverable - can skip this device
      { ...context, deviceId },
      404
    );
  }

  /**
   * Creates an error for when device creation fails
   */
  static deviceCreationFailed(
    externalId: string,
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.DEVICE_CREATION_FAILED,
      `Failed to create device with external ID '${externalId}': ${reason}`,
      false, // Not recoverable - device creation is critical
      { ...context, externalId }
    );
  }

  /**
   * Creates an error for when external ID resolution fails
   */
  static externalIdResolutionFailed(
    externalId: string,
    type: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.EXTERNAL_ID_RESOLUTION_FAILED,
      `Failed to resolve external ID '${externalId}' of type '${type}'`,
      true, // Recoverable if createNonExistingDevice is enabled
      { ...context, externalId, type }
    );
  }

  /**
   * Creates an error for when implicit device creation is disabled
   */
  static implicitDeviceCreationDisabled(
    externalId: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.IMPLICIT_DEVICE_CREATION_DISABLED,
      `The testing resulted in an error, that the referenced device does not exist! External ID: ${externalId}`,
      false,
      { ...context, externalId }
    );
  }

  /**
   * Creates an error for when payload parsing fails
   */
  static payloadParsingFailed(
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.PAYLOAD_PARSING_FAILED,
      `Failed to parse payload: ${reason}`,
      false,
      context
    );
  }

  /**
   * Creates an error for when a path is not found in the payload
   */
  static pathNotFound(path: string, context: ProcessorErrorContext = {}): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.PATH_NOT_FOUND,
      `Message could NOT be parsed, ignoring this message: Path: ${path} not found!`,
      false,
      { ...context, path }
    );
  }

  /**
   * Creates an error for when code execution fails
   */
  static codeExecutionFailed(
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.CODE_EXECUTION_FAILED,
      `Code execution failed: ${reason}`,
      false,
      context
    );
  }

  /**
   * Creates an error for when code execution times out
   */
  static codeExecutionTimeout(
    timeoutMs: number,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.CODE_EXECUTION_TIMEOUT,
      `Execution timed out after ${timeoutMs}ms`,
      false,
      { ...context, timeoutMs }
    );
  }

  /**
   * Creates an error for JSONata evaluation failures
   */
  static jsonataEvaluationFailed(
    expression: string,
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.JSONATA_EVALUATION_FAILED,
      `JSONata evaluation failed for expression '${expression}': ${reason}`,
      false,
      { ...context, expression }
    );
  }

  /**
   * Creates an error for API request failures
   */
  static apiRequestFailed(
    api: string,
    statusCode: number,
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.API_REQUEST_FAILED,
      `${api} API request failed (${statusCode}): ${reason}`,
      false,
      { ...context, api },
      statusCode
    );
  }

  /**
   * Creates an error for MQTT publish failures
   */
  static mqttPublishFailed(
    topic: string,
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.MQTT_PUBLISH_FAILED,
      `Failed to publish to MQTT topic '${topic}': ${reason}`,
      false,
      { ...context, topic }
    );
  }

  /**
   * Creates an error for invalid mapping configuration
   */
  static invalidMappingConfiguration(
    reason: string,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    return new ProcessorError(
      ProcessorErrorCode.INVALID_MAPPING_CONFIGURATION,
      `Invalid mapping configuration: ${reason}`,
      false,
      context
    );
  }

  /**
   * Wraps an unknown error into a ProcessorError
   */
  static fromUnknownError(
    error: any,
    context: ProcessorErrorContext = {}
  ): ProcessorError {
    if (error instanceof ProcessorError) {
      return error;
    }

    const message = error?.message || String(error);
    const originalError = error instanceof Error ? error : new Error(String(error));

    return new ProcessorError(
      ProcessorErrorCode.UNKNOWN_ERROR,
      message,
      false,
      { ...context, originalError }
    );
  }
}
