/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { onMessage } from '../examples/inbound-enrichment';
import {
  createMockInputMessage,
  createMockRuntimeContext,
  CumulocityObject,
} from '../types';

describe('Inbound Enrichment Smart Function', () => {
  it('should create voltage measurement for voltage sensor', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      messageId: 'msg-123',
      deviceId: '12345',
      clientId: 'VOLTAGE-SENSOR-001',
      sensorData: {
        val: 220.5,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'VOLTAGE-SENSOR-001',
      externalIdMap: {
        'VOLTAGE-SENSOR-001:c8y_Serial': {
          id: '12345',
          name: 'Voltage Sensor',
          c8y_Sensor: {
            type: {
              voltage: true,
            },
          },
        },
      },
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    expect(Array.isArray(result)).toBe(true);
    if (!Array.isArray(result)) return; // Type guard
    expect(result.length).toBe(1);

    const action = result[0] as CumulocityObject;
    expect(action.cumulocityType).toBe('measurement');
    expect((action.payload as any).type).toBe('c8y_VoltageMeasurement');
    expect((action.payload as any).c8y_Voltage).toBeDefined();
    expect((action.payload as any).c8y_Voltage.voltage.value).toBe(220.5);
    expect((action.payload as any).c8y_Voltage.voltage.unit).toBe('V');
  });

  it('should create current measurement for current sensor', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      messageId: 'msg-456',
      deviceId: '67890',
      clientId: 'CURRENT-SENSOR-002',
      sensorData: {
        val: 5.25,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'CURRENT-SENSOR-002',
      externalIdMap: {
        'CURRENT-SENSOR-002:c8y_Serial': {
          id: '67890',
          name: 'Current Sensor',
          c8y_Sensor: {
            type: {
              current: true,
            },
          },
        },
      },
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    expect(Array.isArray(result)).toBe(true);
    if (!Array.isArray(result)) return; // Type guard
    expect(result.length).toBe(1);

    const action = result[0] as CumulocityObject;
    expect((action.payload as any).type).toBe('c8y_CurrentMeasurement');
    expect((action.payload as any).c8y_Current).toBeDefined();
    expect((action.payload as any).c8y_Current.current.value).toBe(5.25);
    expect((action.payload as any).c8y_Current.current.unit).toBe('A');
  });

  it('should return empty array when device is not found', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      messageId: 'msg-789',
      deviceId: '99999',
      clientId: 'UNKNOWN-SENSOR',
      sensorData: {
        val: 100.0,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'UNKNOWN-SENSOR',
      externalIdMap: {}, // No devices configured
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    expect(Array.isArray(result)).toBe(true);
    if (!Array.isArray(result)) return; // Type guard
    expect(result.length).toBe(0);
  });

  it('should return empty array when device has no sensor type configuration', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      clientId: 'UNCONFIGURED-SENSOR',
      sensorData: {
        val: 100.0,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'UNCONFIGURED-SENSOR',
      externalIdMap: {
        'UNCONFIGURED-SENSOR:c8y_Serial': {
          id: '12345',
          name: 'Unconfigured Sensor',
          // No c8y_Sensor fragment
        },
      },
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    expect(Array.isArray(result)).toBe(true);
    if (!Array.isArray(result)) return; // Type guard
    expect(result.length).toBe(0);
  });
});
