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
 */

import {
  SmartFunctionOut,
  OutboundMessage,
  DynamicMapperContext,
  DeviceMessage,
} from '../types';

/**
 * @name Default template for Smart Function (TypeScript)
 * @description Creates one measurement for outbound communication
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 * @defaultTemplate true
 *
 * This is a TypeScript version of template-SMART-OUTBOUND-01.js
 *
 * Benefits of TypeScript version:
 * - Type safety: Catch errors at compile time
 * - IntelliSense: Get autocomplete suggestions
 * - Documentation: Inline JSDoc comments
 * - Refactoring: Safe renaming and refactoring
 */

/**
 * Smart Function that converts Cumulocity measurements to device messages.
 * Demonstrates:
 * - Accessing Cumulocity payload data
 * - Creating device messages with proper typing
 * - Using topic placeholders (_externalId_)
 * - Converting data to Uint8Array for transmission
 */
const onMessage: SmartFunctionOut = (
  msg: OutboundMessage,
  context: DynamicMapperContext
): DeviceMessage => {
  // Access payload directly — already pre-deserialized from JSON
  const payload = msg.payload;

  // Log context and payload for debugging
  console.log('Context state:', context.getStateAll());
  console.log('Payload Raw:', payload);
  console.log('Payload messageId:', payload['messageId']);

  // Example 1: Using _externalId_ placeholder (recommended)
  // The placeholder is automatically resolved using the externalId type from externalSource
  // Uncomment to use:
  /*
  return {
    topic: 'measurements/_externalId_',
    payload: new TextEncoder().encode(
      JSON.stringify({
        time: new Date().toISOString(),
        c8y_Steam: {
          Temperature: {
            unit: 'C',
            value: payload['c8y_TemperatureMeasurement']['T']['value'],
          },
        },
      })
    ),
    transportFields: { key: payload['source']['id'] }, // Kafka record key
    externalSource: [{ type: 'c8y_Serial' }],
  };
  */

  // Example 2: Using explicit device ID in topic (current implementation)
  return {
    topic: `measurements/${payload['source']['id']}`,
    payload: new TextEncoder().encode(
      JSON.stringify({
        time: new Date().toISOString(),
        c8y_Steam: {
          Temperature: {
            unit: 'C',
            value: payload['c8y_TemperatureMeasurement']['T']['value'],
          },
        },
      })
    ),
  };
};

// Export for use in other modules or testing
export default onMessage;

// Also export as named export for flexibility
export { onMessage };
