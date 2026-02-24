/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  SmartFunctionIn,
  DynamicMapperDeviceMessage,
  DynamicMapperContext,
  CumulocityObject,
} from '../types';

/**
 * @name Smart Function with State Management (TypeScript)
 * @description Demonstrates state persistence across invocations
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * This example demonstrates:
 * - State management (setState, getState, getStateAll)
 * - Calculating statistics across messages
 * - Detecting value changes
 */

/**
 * Smart Function that tracks temperature statistics using state.
 * Stores min/max values and message count across invocations.
 */
const onMessage: SmartFunctionIn = (
  msg: DynamicMapperDeviceMessage,
  context: DynamicMapperContext
): CumulocityObject[] => {
  const payload = msg.payload;
  const clientId = context.getClientId() || payload['clientId'];

  if (!clientId) {
    console.error('No client ID available');
    return [];
  }

  // Get current temperature from payload
  const currentTemp = payload['temperature'] as number;

  // Retrieve previous state
  const lastTemp = context.getState('lastTemperature') as number | undefined;
  const messageCount = (context.getState('messageCount') as number | undefined) || 0;
  const maxTemp = context.getState('maxTemperature') as number | undefined;
  const minTemp = context.getState('minTemperature') as number | undefined;

  // Calculate new statistics
  const newMessageCount = messageCount + 1;
  const newMaxTemp = maxTemp ? Math.max(maxTemp, currentTemp) : currentTemp;
  const newMinTemp = minTemp ? Math.min(minTemp, currentTemp) : currentTemp;

  // Update state
  context.setState('lastTemperature', currentTemp);
  context.setState('messageCount', newMessageCount);
  context.setState('maxTemperature', newMaxTemp);
  context.setState('minTemperature', newMinTemp);

  // Calculate temperature change
  const tempChange = lastTemp !== undefined ? currentTemp - lastTemp : 0;

  console.log('Statistics:', {
    current: currentTemp,
    last: lastTemp,
    change: tempChange,
    max: newMaxTemp,
    min: newMinTemp,
    count: newMessageCount,
  });

  // Create measurement with embedded statistics
  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: {
        time: new Date().toISOString(),
        type: 'c8y_TemperatureMeasurement',
        c8y_Temperature: {
          T: {
            unit: 'C',
            value: currentTemp,
          },
        },
        // Include statistics as custom fragment
        c8y_Statistics: {
          lastValue: lastTemp || 0,
          change: tempChange,
          maxValue: newMaxTemp,
          minValue: newMinTemp,
          messageCount: newMessageCount,
        },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
  ];
};

export default onMessage;
export { onMessage };
