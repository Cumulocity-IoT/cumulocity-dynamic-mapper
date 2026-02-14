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

import { Mapping, MappingType } from '../../../../shared';
import { DynamicMapperRequest, ProcessingType, SubstituteValue } from '../processor.model';

/**
 * Mapping configuration context
 * Contains mapping-related data and configuration
 */
export interface MappingContext {
  /** The mapping configuration being processed */
  mapping: Mapping;

  /** Type of mapping (JSON, CODE_BASED, etc.) */
  mappingType: MappingType;
}

/**
 * Routing context for topic and payload handling
 * Contains message routing and payload data
 */
export interface RoutingContext {
  /** Original MQTT topic */
  topic: string;

  /** Resolved publish topic (for outbound processing) */
  resolvedPublishTopic?: string;

  /** Message payload */
  payload?: JSON;
}

/**
 * Processing state for transformation operations
 * Contains cache and processing metadata
 */
export interface ProcessingState {
  /** Type of processing based on device/value cardinality */
  processingType?: ProcessingType;

  /** Cache of substitution values by path */
  processingCache: Map<string, SubstituteValue[]>;
}

/**
 * Device-specific context
 * Contains device identification and metadata
 */
export interface DeviceContext {
  /** Source device ID (C8Y managed object ID or external ID) */
  sourceId?: string;

  /** Device name (used for implicit device creation) */
  deviceName?: string;

  /** Device type (used for implicit device creation) */
  deviceType?: string;
}

/**
 * Error and logging context
 * Contains error, warning, and log messages
 */
export interface ErrorContext {
  /** Error messages collected during processing */
  errors?: string[];

  /** Warning messages collected during processing */
  warnings?: string[];

  /** Structured log entries */
  logs?: any[];
}

/**
 * Request tracking context
 * Contains API requests and execution control
 */
export interface RequestContext {
  /** Sequence of API requests made during processing */
  requests?: DynamicMapperRequest[];

  /** Whether to actually send the payload (vs dry-run) */
  sendPayload?: boolean;
}

/**
 * Complete processing context
 * Combines all sub-contexts for processor operations
 */
export interface ProcessingContext
  extends MappingContext,
    RoutingContext,
    ProcessingState,
    DeviceContext,
    ErrorContext,
    RequestContext {}

/**
 * Partial overrides for creating test contexts
 */
export type ProcessingContextOverrides = Partial<ProcessingContext>;

/**
 * Factory for creating ProcessingContext instances
 * Provides consistent initialization and reduces boilerplate
 */
export class ProcessingContextFactory {
  /**
   * Creates a production ProcessingContext
   *
   * @param mapping - The mapping configuration
   * @param topic - The MQTT topic
   * @param payload - Optional message payload
   * @returns Initialized ProcessingContext
   */
  static create(
    mapping: Mapping,
    topic: string,
    payload?: JSON
  ): ProcessingContext {
    return {
      // MappingContext
      mapping,
      mappingType: mapping.mappingType,

      // RoutingContext
      topic,
      resolvedPublishTopic: undefined,
      payload,

      // ProcessingState
      processingType: ProcessingType.UNDEFINED,
      processingCache: new Map<string, SubstituteValue[]>(),

      // DeviceContext
      sourceId: undefined,
      deviceName: undefined,
      deviceType: undefined,

      // ErrorContext
      errors: [],
      warnings: [],
      logs: [],

      // RequestContext
      requests: [],
      sendPayload: false
    };
  }

  /**
   * Creates a test ProcessingContext with overrides
   *
   * @param overrides - Partial context properties to override
   * @returns Initialized ProcessingContext with test defaults
   */
  static createForTesting(
    overrides: ProcessingContextOverrides = {}
  ): ProcessingContext {
    // Create a minimal test mapping with required fields
    const defaultMapping: Mapping = {
      id: 'test-mapping',
      name: 'Test Mapping',
      mappingType: MappingType.JSON,
      subscriptionTopic: 'test/topic',
      publishTopic: 'c8y/test',
      targetTemplate: '{}',
      substitutions: [],
      targetAPI: 'MEASUREMENT',
      direction: 'INBOUND',
      active: true,
      tested: false,
      createNonExistingDevice: false,
      updateExistingDevice: false,
      externalIdType: 'c8y_Serial',
      useExternalId: false
    } as any; // Use 'as any' to avoid strict type checking for test fixtures

    const baseContext = this.create(
      defaultMapping,
      'test/device/telemetry',
      {} as JSON
    );

    return {
      ...baseContext,
      ...overrides
    };
  }

  /**
   * Creates a ProcessingContext with errors for testing error handling
   *
   * @param errors - Array of error messages
   * @param warnings - Optional array of warning messages
   * @returns ProcessingContext with errors
   */
  static createWithErrors(
    errors: string[],
    warnings: string[] = []
  ): ProcessingContext {
    return this.createForTesting({
      errors,
      warnings
    });
  }

  /**
   * Creates a ProcessingContext with a pre-populated cache
   *
   * @param cache - Map of path to substitute values
   * @param processingType - Optional processing type
   * @returns ProcessingContext with cache
   */
  static createWithCache(
    cache: Map<string, SubstituteValue[]>,
    processingType: ProcessingType = ProcessingType.UNDEFINED
  ): ProcessingContext {
    return this.createForTesting({
      processingCache: cache,
      processingType
    });
  }
}
