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
 * Centralized constants for the processor module.
 * All magic strings and configuration values are defined here for maintainability.
 *
 * @module processor.constants
 */

/**
 * Special mapping tokens used in payload transformation.
 * These tokens are injected into payloads to provide metadata and context.
 */
export const MappingTokens = {
  /**
   * Identity token containing device identifiers (externalId, c8ySourceId)
   */
  IDENTITY: '_IDENTITY_' as const,

  /**
   * Topic level token containing MQTT topic segments as an array
   */
  TOPIC_LEVEL: '_TOPIC_LEVEL_' as const,

  /**
   * Context data token for additional metadata (deviceName, deviceType, etc.)
   */
  CONTEXT_DATA: '_CONTEXT_DATA_' as const,
} as const;

/**
 * Protected tokens that cannot be modified in the payload editor.
 * Changes to these tokens are blocked to prevent data integrity issues.
 */
export const PROTECTED_TOKENS = [
  MappingTokens.IDENTITY,
  MappingTokens.TOPIC_LEVEL,
  MappingTokens.CONTEXT_DATA
] as const;

/**
 * Identity-related path constants
 */
export const IdentityPaths = {
  /**
   * Path to external identifier in payload: _IDENTITY_.externalId
   */
  EXTERNAL_ID: `${MappingTokens.IDENTITY}.externalId` as const,

  /**
   * Path to C8Y source identifier in payload: _IDENTITY_.c8ySourceId
   */
  C8Y_SOURCE_ID: `${MappingTokens.IDENTITY}.c8ySourceId` as const,
} as const;

/**
 * Context data field names used within _CONTEXT_DATA_ token
 */
export const ContextDataKeys = {
  /**
   * Device name field
   */
  DEVICE_NAME: 'deviceName' as const,

  /**
   * Device type field
   */
  DEVICE_TYPE: 'deviceType' as const,

  /**
   * Generic key identifier
   */
  KEY_NAME: 'key' as const,

  /**
   * Timestamp field name
   */
  TIME: 'time' as const,
} as const;


/**
 * Legacy export for backward compatibility.
 * @deprecated Use ContextDataKeys.TIME instead
 */
export const KEY_TIME = ContextDataKeys.TIME;

/**
 * Context data path constants for common fields
 */
export const ContextDataPaths = {
  /**
   * Path to device name: _CONTEXT_DATA_.deviceName
   */
  DEVICE_NAME: `${MappingTokens.CONTEXT_DATA}.${ContextDataKeys.DEVICE_NAME}` as const,

  /**
   * Path to device type: _CONTEXT_DATA_.deviceType
   */
  DEVICE_TYPE: `${MappingTokens.CONTEXT_DATA}.${ContextDataKeys.DEVICE_TYPE}` as const,
} as const;

/**
 * MQTT topic wildcard characters
 */
export const TopicWildcards = {
  /**
   * Multi-level wildcard: matches zero or more levels
   */
  MULTI_LEVEL: '#' as const,

  /**
   * Single-level wildcard: matches exactly one level
   */
  SINGLE_LEVEL: '+' as const,
} as const;

/**
 * Legacy exports for backward compatibility.
 * @deprecated Use TopicWildcards.MULTI_LEVEL instead
 */
export const TOPIC_WILDCARD_MULTI = TopicWildcards.MULTI_LEVEL;

/**
 * @deprecated Use TopicWildcards.SINGLE_LEVEL instead
 */
export const TOPIC_WILDCARD_SINGLE = TopicWildcards.SINGLE_LEVEL;

/**
 * Processing configuration constants
 */
export const ProcessingConfig = {
  /**
   * Default timeout for JavaScript code execution in milliseconds
   */
  DEFAULT_TIMEOUT_MS: 250,

  /**
   * Maximum allowed timeout for code execution in milliseconds
   */
  MAX_TIMEOUT_MS: 5000,

  /**
   * Default behavior for streaming console logs during code execution
   */
  DEFAULT_STREAM_LOGS: true,
} as const;

/**
 * JSONPath root selector
 */
export const JSON_PATH_ROOT = '$' as const;
