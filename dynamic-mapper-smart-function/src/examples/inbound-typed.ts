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
  SmartFunctionOut,
  DynamicMapperDeviceMessage,
  DynamicMapperContext,
  CumulocityObject,
  DeviceMessage,
  OutboundMessage,
} from '../types';

/**
 * @name Typed Smart Function — inbound: managedObject + event
 * @description Demonstrates the SmartFunctionIn<T> type parameter.
 *   TypeScript enforces that ONLY 'managedObject' and 'event' objects may be returned.
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 */

/**
 * Inbound Smart Function constrained to 'managedObject' and 'event' outputs.
 *
 * Using `SmartFunctionIn<'managedObject' | 'event'>` means TypeScript will:
 * - ✅ accept  cumulocityType: 'managedObject'
 * - ✅ accept  cumulocityType: 'event'
 * - ❌ reject  cumulocityType: 'measurement'  (compile-time error)
 * - ❌ reject  cumulocityType: 'alarm'         (compile-time error)
 */
const onMessageInbound: SmartFunctionIn<'managedObject' | 'event'> = (
  msg: DynamicMapperDeviceMessage,
  context: DynamicMapperContext
): CumulocityObject<'managedObject' | 'event'>[] => {
  const payload = msg.payload;
  const clientId = context.getClientId() || payload['clientId'];
  const deviceName = payload['deviceName'] ?? clientId;

  return [
    // 1. Upsert (create or update) the device managed object
    {
      cumulocityType: 'managedObject',
      action: 'create',
      payload: {
        name: deviceName,
        type: 'c8y_Device',
        c8y_IsDevice: {},
        c8y_Hardware: {
          model: payload['model'] ?? 'unknown',
          revision: payload['revision'] ?? '1.0',
        },
      },
      externalSource: [
        {
          externalId: clientId!,
          type: 'c8y_Serial',
          autoCreateDeviceMO: true,
        },
      ],
    },

    // 2. Create a lifecycle event alongside the managed-object update
    {
      cumulocityType: 'event',
      action: 'create',
      payload: {
        type: 'c8y_DeviceRegistered',
        text: `Device ${deviceName} registered or updated`,
        time: new Date().toISOString(),
      },
      externalSource: [{ externalId: clientId!, type: 'c8y_Serial' }],
    },
  ];
};

export default onMessageInbound;
export { onMessageInbound };

// ---------------------------------------------------------------------------

/**
 * @name Typed Smart Function — outbound: measurement only
 * @description Demonstrates the SmartFunctionOut<T> type parameter.
 *   TypeScript narrows msg.cumulocityType to 'measurement' — this function
 *   is documented (and enforced) to handle only measurement outbound events.
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 */

/**
 * Outbound Smart Function constrained to incoming 'measurement' events.
 *
 * Using `SmartFunctionOut<'measurement'>` means:
 * - `msg.cumulocityType` is narrowed to `'measurement'` (not the full union)
 * - TypeScript will flag accidental use of this function for other event types
 */
const onMessageOutbound: SmartFunctionOut<'measurement'> = (
  msg: OutboundMessage<'measurement'>,
  _context: DynamicMapperContext
): DeviceMessage => {
  const sourceId = msg.payload['source']?.['id'] ?? 'unknown';

  // msg.cumulocityType is narrowed to 'measurement' here
  const fragment = msg.payload['c8y_TemperatureMeasurement'];
  const tempValue: number | undefined = fragment?.['T']?.['value'];

  return {
    topic: `measurements/${sourceId}`,
    payload: new TextEncoder().encode(
      JSON.stringify({
        temperature: tempValue,
        time: new Date().toISOString(),
      })
    ),
  };
};

export { onMessageOutbound };
