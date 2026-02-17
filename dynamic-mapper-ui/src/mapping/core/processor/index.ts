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
 * @authors Christof Strack, Stefan Witschel
 */

/**
 * Public API exports for Smart Function Runtime types.
 *
 * These types can be imported by users writing Smart Functions:
 *
 * @example
 * import {
 *   SmartFunction,
 *   SmartFunctionInputMessage,
 *   SmartFunctionRuntimeContext,
 *   CumulocityObject,
 *   C8yMeasurement
 * } from '@dynamic-mapper/runtime-types';
 */

// Core Smart Function types
export {
  SmartFunction,
  SmartFunctionInputMessage,
  SmartFunctionPayload,
  SmartFunctionRuntimeContext
} from './smart-function-runtime.types';

// External ID types
export {
  ExternalId,
  ExternalSource
} from './smart-function-runtime.types';

// Cumulocity domain object types
export {
  C8yMeasurement,
  C8yEvent,
  C8yAlarm,
  C8yAlarmSeverity,
  C8yAlarmStatus,
  C8yOperation,
  C8yOperationStatus,
  C8yManagedObject,
  C8ySourceReference
} from './smart-function-runtime.types';

// Action types
export {
  CumulocityObject,
  DeviceMessage,
  C8yAction
} from './smart-function-runtime.types';

// Testing helpers
export {
  createMockPayload,
  createMockInputMessage,
  createMockRuntimeContext
} from './smart-function-runtime.types';

// Legacy exports from model-flow-function.ts for backward compatibility
export {
  DataPrepContext,
  InputMessage,
  OutputMessage,
  MappingError
} from './model-flow-function';
