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
  SmartFunctionOutV2,
  DeviceMessage,
} from '../types';

/**
 * @name V2 Outbound Smart Function — discriminated union narrowing on cumulocityType
 * @description Demonstrates SmartFunctionOutV2 with payload type narrowing.
 *   - `input: 'measurement'` narrows msg.cumulocityType to 'measurement'
 *   - msg.payload is narrowed to C8yMeasurement — known fields are typed without casting
 *   - The Java runtime populates msg.cumulocityType via InputMessage.cumulocityType
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 */

/**
 * Outbound function that demonstrates V2 discriminant narrowing.
 *
 * When `input: 'measurement'` is declared:
 * - `msg.cumulocityType` is narrowed to `'measurement'` (not the full union)
 * - `msg.payload` is narrowed to `C8yMeasurement` — known fields are typed
 *
 * The Java runtime populates `msg.cumulocityType` via InputMessage.cumulocityType
 * (set in FlowProcessorOutboundProcessor) so narrowing also holds at runtime.
 */
export const onMessage: SmartFunctionOutV2<{
  input: 'measurement';
  config: { externalId: string; mappingName: string };
  state: { forwardedCount: number };
  message: DeviceMessage;
}> = (msg, context) => {
  // msg.cumulocityType is 'measurement' (narrowed — not the full C8yObjectType union)
  // msg.payload is C8yMeasurement — source, type, time are typed without casting
  const sourceId = msg.payload.source?.id ?? 'unknown';
  const measurementType = msg.payload.type;

  const count = context.getState('forwardedCount', 0) + 1;
  context.setState('forwardedCount', count);

  // context.getConfig() is typed — .externalId and .mappingName are strings
  const { externalId, mappingName } = context.getConfig();
  console.log(`[${mappingName}] forwarding #${count} for device ${sourceId}`);

  // Access custom fragment via bracket notation (not declared on C8yMeasurement)
  const tempValue: number | undefined =
    msg.payload['c8y_TemperatureMeasurement']?.['T']?.['value'];

  // TextEncoder is not available in GraalJS — encode the JSON string manually.
  const json = JSON.stringify({
    type: measurementType,
    temperature: tempValue,
    forwardedCount: count,
    time: new Date().toISOString(),
  });
  const bytes = new Uint8Array(json.length);
  for (let i = 0; i < json.length; i++) {
    bytes[i] = json.charCodeAt(i);
  }

  return {
    topic: `measurements/${externalId}`,
    payload: bytes,
    transportFields: { key: externalId },
    cumulocityType: 'measurement',
  };
};

export default onMessage;
