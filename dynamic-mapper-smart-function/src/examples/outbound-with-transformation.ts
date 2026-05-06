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
  C8yMeasurement,
} from '../types';

/**
 * Declare the exact measurement shape expected from Cumulocity.
 * Extending C8yMeasurement gives full type safety on the custom fragments
 * without any bracket-notation casting — the same pattern as getManagedObject<T>.
 *
 * Exported so TypeScript can reference it when this function is re-exported.
 */
export interface SteamAndHumidityMeasurement extends C8yMeasurement {
  c8y_TemperatureMeasurement?: { T?: { value: number; unit: string } };
  c8y_HumidityMeasurement?: { H?: { value: number; unit: string } };
}

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
 *
 * Sample payload (Cumulocity measurement triggering this function):
 * {
 *   "id": "98765",
 *   "type": "c8y_TemperatureMeasurement",
 *   "time": "2024-01-15T10:30:00.000Z",
 *   "source": { "id": "12345" },
 *   "c8y_TemperatureMeasurement": { "T": { "value": 23.5, "unit": "C" } },
 *   "c8y_HumidityMeasurement": { "H": { "value": 65.0, "unit": "%" } }
 * }
 *
 * Expected output (DeviceMessage):
 * {
 *   "topic": "device/12345/measurements",
 *   "payload": {
 *     "timestamp": "<ISO timestamp>",
 *     "deviceId": "12345",
 *     "sensors": {
 *       "temperature": { "value": 23.5, "unit": "C" },
 *       "humidity": { "value": 65.0, "unit": "%" }
 *     },
 *     "metadata": { "type": "c8y_TemperatureMeasurement", "source": "cumulocity" }
 *   },
 *   "transportFields": { "key": "12345", "content-type": "application/json" }
 * }
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
 * Uses `SmartFunctionOut<'measurement', SteamAndHumidityMeasurement>` so that
 * `msg.payload` is fully typed — no bracket notation or manual casting needed.
 */
const onMessage: SmartFunctionOut<'measurement', SteamAndHumidityMeasurement> = (
  msg: OutboundMessage<'measurement', SteamAndHumidityMeasurement>,
  context: SmartFunctionContext
): DeviceMessage => {
  const payload = msg.payload;

  console.log('Config:', context.getConfig());
  console.log('Processing Cumulocity payload:', payload);

  // Extract device ID — prefer msg.sourceId (populated by the runtime from the processing
  // context) over payload.source?.id to avoid failures when the source template
  // does not include the 'source' field.
  const sourceId = msg.sourceId ?? payload.source?.id ?? 'unknown';
  const measurementType = payload.type || 'unknown';

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

  // Transform temperature data if available — fully typed, no bracket notation
  const tempData = payload.c8y_TemperatureMeasurement?.T;
  if (tempData) {
    customPayload.sensors.temperature = {
      value: tempData.value,
      unit: tempData.unit || 'C',
    };
  }

  // Transform humidity data if available — fully typed, no bracket notation
  const humData = payload.c8y_HumidityMeasurement?.H;
  if (humData) {
    customPayload.sensors.humidity = {
      value: humData.value,
      unit: humData.unit || '%',
    };
  }

  console.log('Transformed payload:', customPayload);

  // Create device message with transformed payload
  // JSON object payload — no manual serialization needed
  return {
    topic: `device/${sourceId}/measurements`,
    payload: customPayload,
    transportFields: {
      key: sourceId, // Kafka record key
      'content-type': 'application/json',
    },
  };
};

export default onMessage;
export { onMessage };
