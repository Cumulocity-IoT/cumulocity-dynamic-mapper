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
  SmartFunctionInV2,
  SmartFunctionOutV2,
  CumulocityObject,
  DeviceMessage,
  C8yManagedObject,
} from '../types';

/**
 * @name V2 Inbound Smart Function — typed config, state, and return tuple
 * @description Demonstrates SmartFunctionInV2 with a typed object generic.
 *   - config keys are typed — getConfig().mappingName is a string, not any
 *   - state keys are typed — getState('messageCount', 0) returns number
 *   - return type is a tuple — TypeScript enforces [measurement, managedObject] order
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * Compiled JavaScript is identical to a plain (msg, context) => [...] arrow function.
 * The object generic only exists at type-checking time — no runtime overhead.
 */

// ---------------------------------------------------------------------------
// Custom types
// ---------------------------------------------------------------------------

/**
 * Device managed object shape for voltage/current sensors.
 * Extend C8yManagedObject so custom fragments are typed without casting.
 */
interface VoltageCurrentSensor extends C8yManagedObject {
  c8y_Sensor?: {
    type?: { voltage?: boolean; current?: boolean };
    unit?: string;
  };
}

// ---------------------------------------------------------------------------
// Inbound V2 — typed config, typed state, tuple return
// ---------------------------------------------------------------------------

/**
 * Inbound function that:
 * 1. Reads typed config (mappingName, tenant)
 * 2. Tracks message count in typed state
 * 3. Returns exactly [measurement, managedObject] — enforced by TypeScript
 *
 * Compare with V1 SmartFunctionIn where getConfig() / getState() return `any`.
 */
export const onMessageInbound: SmartFunctionInV2<{
  // Tuple: enforces exactly one measurement followed by one managedObject.
  // Returning only a measurement, or returning them in the wrong order, is a
  // compile-time error — not a silent runtime bug.
  returns: [CumulocityObject<'measurement'>, CumulocityObject<'managedObject'>];
  config: {
    mappingName: string;
    tenant: string;
  };
  state: {
    messageCount: number;
    lastTemperature: number;
  };
}> = (msg, context) => {
  // getConfig() is typed — no 'as' cast needed
  const { mappingName } = context.getConfig();

  // getState() is typed — returns number, not any
  const count = context.getState('messageCount', 0) + 1;
  const temp = (msg.payload['temperature'] as number) ?? 0;

  // setState() enforces the TState shape — setState('messageCount', 'oops') is an error
  context.setState('messageCount', count);
  context.setState('lastTemperature', temp);

  console.log(`[${mappingName}] message #${count}, temp=${temp}`);

  const clientId = context.getClientId() ?? msg.payload['clientId'] as string;

  // Return tuple — TypeScript verifies order and count at compile time
  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: {
        type: 'c8y_TemperatureMeasurement',
        time: new Date().toISOString(),
        c8y_Temperature: { T: { value: temp, unit: 'C' } },
        c8y_Statistics: { messageCount: count },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
    {
      cumulocityType: 'managedObject',
      action: 'update',
      payload: {
        c8y_LastTemperature: { value: temp, unit: 'C', time: new Date().toISOString() },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
  ];
};

// ---------------------------------------------------------------------------
// Inbound V2 — typed device lookup with custom managed object interface
// ---------------------------------------------------------------------------

/**
 * Inbound function that demonstrates typed getManagedObject combined with V2
 * context typing. Device fragments are accessed without bracket notation or casts.
 *
 * Note: The object generic has no `mappings` key here, so the return type
 * falls back to the broad `CumulocityObject | CumulocityObject[]` default.
 */
export const onMessageInboundEnrichment: SmartFunctionInV2<{
  config: { mappingName: string };
  state: { processedDevices: number };
}> = (msg, context) => {
  const clientId = context.getClientId() ?? msg.payload['clientId'] as string;
  const val = msg.payload['sensorData']?.['val'] as number ?? 0;

  // getManagedObjectByExternalId<VoltageCurrentSensor> — custom fragments are typed
  const device = context.getManagedObjectByExternalId<VoltageCurrentSensor>({
    externalId: clientId,
    type: 'c8y_Serial',
  });

  if (!device) {
    context.addWarning(`Device not found for clientId=${clientId}`);
    return [];
  }

  // Typed dot notation — no ['c8y_Sensor'] bracket or 'as' needed
  const isVoltage = device.c8y_Sensor?.type?.voltage === true;
  const unit = device.c8y_Sensor?.unit ?? (isVoltage ? 'V' : 'A');

  context.setState('processedDevices', context.getState('processedDevices', 0) + 1);

  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: {
        type: isVoltage ? 'c8y_VoltageMeasurement' : 'c8y_CurrentMeasurement',
        time: new Date().toISOString(),
        ...(isVoltage
          ? { c8y_Voltage: { voltage: { value: val, unit } } }
          : { c8y_Current: { current: { value: val, unit } } }),
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
  ];
};

// ---------------------------------------------------------------------------
// Outbound V2 — discriminated union narrowing on cumulocityType
// ---------------------------------------------------------------------------

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
export const onMessageOutbound: SmartFunctionOutV2<{
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

  return {
    topic: `measurements/${externalId}`,
    payload: new TextEncoder().encode(
      JSON.stringify({
        type: measurementType,
        temperature: tempValue,
        forwardedCount: count,
        time: new Date().toISOString(),
      })
    ),
    transportFields: { key: externalId },
    cumulocityType: 'measurement',
  };
};

// ---------------------------------------------------------------------------
// Exports
// ---------------------------------------------------------------------------

export default onMessageInbound;
