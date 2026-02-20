/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { onMessage } from '../examples/inbound-basic';
import {
  createMockInputMessage,
  createMockRuntimeContext,
  CumulocityObject,
} from '../types';

describe('Inbound Basic Smart Function', () => {
  it('should create a temperature measurement', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      messageId: 'msg-123',
      deviceId: '12345',
      clientId: 'SENSOR-001',
      sensorData: {
        temp_val: 25.5,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'SENSOR-001',
      devices: {
        '12345': {
          id: '12345',
          name: 'Temperature Sensor',
          type: 'c8y_Device',
        },
      },
      externalIdMap: {
        'SENSOR-001:c8y_Serial': {
          id: '12345',
          name: 'Temperature Sensor',
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
    expect(action.action).toBe('create');
    expect(action.payload).toHaveProperty('c8y_Steam');
    expect((action.payload as any).c8y_Steam.Temperature.value).toBe(25.5);
    expect((action.payload as any).c8y_Steam.Temperature.unit).toBe('C');
    expect(action.externalSource).toEqual([
      { type: 'c8y_Serial', externalId: 'SENSOR-001' },
    ]);
  });

  it('should use context client ID when payload does not have one', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      messageId: 'msg-456',
      sensorData: {
        temp_val: 30.0,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'CONTEXT-CLIENT-ID',
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    if (!Array.isArray(result)) return; // Type guard
    const action = result[0] as CumulocityObject;
    expect(action.externalSource).toEqual([
      { type: 'c8y_Serial', externalId: 'CONTEXT-CLIENT-ID' },
    ]);
  });

  it('should include ISO timestamp in measurement', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      sensorData: {
        temp_val: 20.0,
      },
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'SENSOR-001',
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    if (!Array.isArray(result)) return; // Type guard
    const action = result[0] as CumulocityObject;
    const timestamp = (action.payload as any).time;
    expect(timestamp).toBeDefined();
    expect(() => new Date(timestamp)).not.toThrow();
  });
});
