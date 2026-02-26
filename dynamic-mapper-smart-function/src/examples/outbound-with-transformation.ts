/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  SmartFunctionOut,
  OutboundMessage,
  SmartFunctionContext,
  DeviceMessage,
} from '../types';

/**
 * @name Smart Function with Data Transformation (TypeScript)
 * @description Transforms Cumulocity data format to custom device format
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 *
 * This example demonstrates:
 * - Complex data transformation
 * - Custom payload formatting
 * - Using Kafka transport fields
 * - Type-safe payload construction
 */

/**
 * Custom device payload format
 */
interface CustomDevicePayload {
  timestamp: string;
  deviceId: string;
  sensors: {
    temperature?: {
      value: number;
      unit: string;
    };
    humidity?: {
      value: number;
      unit: string;
    };
  };
  metadata: {
    type: string;
    source: string;
  };
}

/**
 * Smart Function that transforms Cumulocity measurements to custom device format.
 */
const onMessage: SmartFunctionOut = (
  msg: OutboundMessage,
  context: SmartFunctionContext
): DeviceMessage => {
  const payload = msg.payload;

  console.log('Config:', context.getConfig());
  console.log('Processing Cumulocity payload:', payload);

  // Extract measurement data
  const sourceId = payload['source']?.['id'] || 'unknown';
  const measurementType = payload['type'] || 'unknown';

  // Build custom device payload
  const customPayload: CustomDevicePayload = {
    timestamp: new Date().toISOString(),
    deviceId: sourceId,
    sensors: {},
    metadata: {
      type: measurementType,
      source: 'cumulocity',
    },
  };

  // Transform temperature data if available
  if (payload['c8y_TemperatureMeasurement']) {
    const tempData = payload['c8y_TemperatureMeasurement']['T'];
    if (tempData) {
      customPayload.sensors.temperature = {
        value: tempData['value'],
        unit: tempData['unit'] || 'C',
      };
    }
  }

  // Transform humidity data if available
  if (payload['c8y_HumidityMeasurement']) {
    const humData = payload['c8y_HumidityMeasurement']['H'];
    if (humData) {
      customPayload.sensors.humidity = {
        value: humData['value'],
        unit: humData['unit'] || '%',
      };
    }
  }

  console.log('Transformed payload:', customPayload);

  // Create device message with transformed payload
  return {
    topic: `device/${sourceId}/measurements`,
    payload: new TextEncoder().encode(JSON.stringify(customPayload)),
    transportFields: {
      key: sourceId, // Kafka record key
      'content-type': 'application/json',
    },
  };
};

export default onMessage;
export { onMessage };
