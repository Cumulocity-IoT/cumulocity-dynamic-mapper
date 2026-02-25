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
 *   SmartFunctionIn,
 *   SmartFunctionOut,
 *   SmartFunction,
 *   DynamicMapperDeviceMessage,
 *   SmartFunctionContext,
 *   CumulocityObject,
 *   DeviceMessage
 * } from '@dynamic-mapper/runtime-types';
 */

// IDP DataPrep base context (standard interface)
export {
  DataPrepContext
} from '@c8y/dynamic-mapper-smart-function';

// Smart Function types
export {
  SmartFunctionIn,
  SmartFunctionOut,
  SmartFunction,
  SmartFunctionPayload
} from '@c8y/dynamic-mapper-smart-function';

// Dynamic Mapper message and context types
export {
  DynamicMapperDeviceMessage,
  SmartFunctionContext,
  OutboundMessage
} from '@c8y/dynamic-mapper-smart-function';

// External ID types
export {
  ExternalId,
  ExternalSource
} from '@c8y/dynamic-mapper-smart-function';

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
} from '@c8y/dynamic-mapper-smart-function';

// Smart Function output/input object types
export {
  CumulocityObject,
  DeviceMessage,
  C8yObjectAction,
  C8yObjectType
} from '@c8y/dynamic-mapper-smart-function';

// Flow function types
export {
  InputMessage,
  OutputMessage,
  MappingError
} from '@c8y/dynamic-mapper-smart-function';

// Testing helpers
export {
  createMockPayload,
  createMockInputMessage,
  createMockOutboundMessage,
  createMockRuntimeContext
} from '@c8y/dynamic-mapper-smart-function';
