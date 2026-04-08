/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  SmartFunctionIn,
  DynamicMapperDeviceMessage,
  SmartFunctionContext,
  CumulocityObject,
  C8yManagedObject,
} from '../types';

/**
 * Device-specific managed object shape.
 * Extend C8yManagedObject to get full type safety on custom fragments —
 * no manual casting needed when accessing deeply nested properties.
 */
interface SensorDevice extends C8yManagedObject {
  c8y_Sensor?: {
    type?: {
      voltage?: boolean;
      current?: boolean;
    };
  };
}

/**
 * @name Smart Function with Device Enrichment (TypeScript)
 * @description Creates voltage or current measurement based on device configuration
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * This example demonstrates:
 * - Device lookup for enrichment
 * - Conditional measurement creation based on device properties
 * - Error handling with try-catch
 * - Type-safe access to device fragments
 *
 * Sample payload (MQTT message body):
 * {
 *   "messageId": "msg-001",
 *   "clientId": "sensor-berlin-01",
 *   "deviceId": "12345",
 *   "sensorData": {
 *     "val": 230.5
 *   }
 * }
 *
 * Sample topic: testSmartInbound/sensor-berlin-01
 *
 * Required device inventory (on the managed object):
 * {
 *   "c8y_Sensor": { "type": { "voltage": true } }
 *   // or: "c8y_Sensor": { "type": { "current": true } }
 * }
 *
 * Expected output (voltage device):
 * [{
 *   "cumulocityType": "measurement",
 *   "action": "create",
 *   "payload": {
 *     "time": "<ISO timestamp>",
 *     "type": "c8y_VoltageMeasurement",
 *     "c8y_Voltage": { "voltage": { "unit": "V", "value": 230.5 } }
 *   },
 *   "externalSource": [{ "type": "c8y_Serial", "externalId": "sensor-berlin-01" }]
 * }]
 */

/**
 * Smart Function that creates different measurements based on device type.
 * Looks up device configuration and creates either voltage or current measurement.
 */
const onMessage: SmartFunctionIn = (
  msg: DynamicMapperDeviceMessage,
  context: SmartFunctionContext
): CumulocityObject[] => {
  const payload = msg.payload;

  console.log('Config:', context.getConfig());
  console.log('Payload Raw:', payload);
  console.log('Payload messageId:', payload['messageId']);

  // Get clientId from context first, fall back to payload
  const clientId = context.getClientId() || payload['clientId'];

  if (!clientId) {
    console.error('No client ID available');
    return [];
  }

  // Try to lookup device by device ID — typed so nested fragments are fully typed
  let deviceByDeviceId: SensorDevice | null = null;
  try {
    deviceByDeviceId = context.getManagedObject<SensorDevice>(payload['deviceId']);
    console.log('Device (by device id):', deviceByDeviceId);
  } catch (e) {
    console.error('Error looking up device by ID:', e);
  }

  // Try to lookup device by external ID — typed so nested fragments are fully typed
  let deviceByExternalId: SensorDevice | null = null;
  try {
    deviceByExternalId = context.getManagedObjectByExternalId<SensorDevice>({
      externalId: clientId,
      type: 'c8y_Serial',
    });
    console.log('Device (by external id):', deviceByExternalId);
  } catch (e) {
    console.error('Error looking up device by external ID:', e);
  }

  if (!deviceByExternalId) {
    console.error(`Device not found for client ID: ${clientId}`);
    return [];
  }

  // Determine measurement type based on device configuration
  // c8y_Sensor is now fully typed — no bracket notation or casting needed
  const isVoltage = deviceByExternalId?.c8y_Sensor?.type?.voltage === true;
  const isCurrent = deviceByExternalId?.c8y_Sensor?.type?.current === true;

  let measurementPayload: any;

  // Get sensor value using bracket notation
  const sensorData = payload['sensorData'];
  const sensorValue = sensorData?.['val'];

  if (isVoltage) {
    measurementPayload = {
      time: new Date().toISOString(),
      type: 'c8y_VoltageMeasurement',
      c8y_Voltage: {
        voltage: {
          unit: 'V',
          value: sensorValue,
        },
      },
    };
    console.log('Creating c8y_VoltageMeasurement');
  } else if (isCurrent) {
    measurementPayload = {
      time: new Date().toISOString(),
      type: 'c8y_CurrentMeasurement',
      c8y_Current: {
        current: {
          unit: 'A',
          value: sensorValue,
        },
      },
    };
    console.log('Creating c8y_CurrentMeasurement');
  } else {
    console.warn('Warning: No valid sensor type configuration found');
    return []; // Return empty array if no valid configuration
  }

  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: measurementPayload,
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
  ];
};

export default onMessage;
export { onMessage };
