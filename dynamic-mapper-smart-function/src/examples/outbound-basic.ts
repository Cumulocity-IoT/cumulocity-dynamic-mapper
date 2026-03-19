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
  SmartFunctionContext,
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
 * - Using context.getConfig().externalId for the outbound topic
 * - Converting data to Uint8Array for transmission
 *
 * Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
 */
const onMessage: SmartFunctionOut = (
  msg: OutboundMessage,
  context: SmartFunctionContext
): DeviceMessage => {
  // Access payload directly — already pre-deserialized from JSON
  const payload = msg.payload;

  // context.getConfig().externalId contains the resolved external ID of the source device.
  // Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
  const externalId = context.getConfig().externalId;

  // Log context and payload for debugging
  console.log('Config:', context.getConfig());
  console.log('Payload Raw:', payload);
  console.log('ExternalId:', externalId);

  return {
    topic: `measurements/${externalId}`,
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
    transportFields: { key: externalId }, // Kafka record key
  };
};

// Export for use in other modules or testing
export default onMessage;

// Also export as named export for flexibility
export { onMessage };
