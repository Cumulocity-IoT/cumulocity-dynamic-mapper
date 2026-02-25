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
  SmartFunctionIn,
  DynamicMapperDeviceMessage,
  DynamicMapperContext,
  CumulocityObject,
  C8yManagedObject,
} from '../types';

/**
 * @name Default template for Smart Function (TypeScript)
 * @description Default template for Smart Function, creates one measurement
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate true
 *
 * This is a TypeScript version of template-SMART-INBOUND-01.js
 *
 * Benefits of TypeScript version:
 * - Type safety: Catch errors at compile time
 * - IntelliSense: Get autocomplete suggestions
 * - Documentation: Inline JSDoc comments
 * - Refactoring: Safe renaming and refactoring
 */

/**
 * Smart Function that creates a temperature measurement.
 * Demonstrates:
 * - Accessing payload with type safety
 * - Looking up devices by device ID and external ID
 * - Creating measurements with proper typing
 */
const onMessage: SmartFunctionIn = (
  msg: DynamicMapperDeviceMessage,
  context: DynamicMapperContext
): CumulocityObject[] => {
  // Access payload directly — already pre-deserialized from JSON
  const payload = msg.payload;

  // Log context and payload for debugging
  console.log('Context state:', context.getStateAll());
  console.log('Payload Raw:', payload);
  console.log('Payload messageId:', payload['messageId']);

  // Get clientId from context first, fall back to payload
  const clientId = context.getClientId() || payload['clientId'];

  // Lookup device by device ID for enrichment
  const deviceByDeviceId: C8yManagedObject | null = context.getManagedObject(
    payload['deviceId']
  );
  console.log('Device (by device id):', deviceByDeviceId);

  // Lookup device by external ID for enrichment
  const deviceByExternalId: C8yManagedObject | null = context.getManagedObjectByExternalId({
    externalId: clientId!,
    type: 'c8y_Serial',
  });
  console.log('Device (by external id):', deviceByExternalId);

  // Create and return measurement action
  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: {
        time: new Date().toISOString(),
        type: 'c8y_TemperatureMeasurement',
        c8y_Steam: {
          Temperature: {
            unit: 'C',
            value: payload['sensorData']['temp_val'],
          },
        },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId! }],
    },
  ];
};

// Export for use in other modules or testing
export default onMessage;

// Also export as named export for flexibility
export { onMessage };
