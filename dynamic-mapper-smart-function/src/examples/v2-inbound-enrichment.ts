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
  C8yManagedObject,
} from '../types';

/**
 * @name V2 Inbound Smart Function — typed device lookup with custom managed object interface
 * @description Demonstrates SmartFunctionInV2 combined with a typed getManagedObjectByExternalId.
 *   - Custom managed object interface extends C8yManagedObject so device fragments are typed
 *   - Dot-notation access on custom fragments — no bracket notation or 'as' casts needed
 *   - No `returns` key in the generic, so return type falls back to the broad default
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 */

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

/**
 * Inbound function that demonstrates typed getManagedObject combined with V2
 * context typing. Device fragments are accessed without bracket notation or casts.
 */
export const onMessage: SmartFunctionInV2<{
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

export default onMessage;
