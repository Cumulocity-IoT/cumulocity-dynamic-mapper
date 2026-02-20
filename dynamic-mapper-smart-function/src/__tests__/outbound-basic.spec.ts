/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { onMessage } from '../examples/outbound-basic';
import {
  createMockOutboundMessage,
  createMockRuntimeContext,
  DeviceMessage,
} from '../types';

describe('Outbound Basic Smart Function', () => {
  it('should create device message with correct topic', () => {
    // Arrange
    const mockMsg = createMockOutboundMessage({
      messageId: 'msg-123',
      type: 'c8y_TemperatureMeasurement',
      source: {
        id: '12345',
      },
      c8y_TemperatureMeasurement: {
        T: {
          value: 25.5,
          unit: 'C',
        },
      },
    });

    const mockContext = createMockRuntimeContext({});

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    expect(action.topic).toBe('measurements/12345');
  });

  it('should encode payload as Uint8Array', () => {
    // Arrange
    const mockMsg = createMockOutboundMessage({
      source: { id: '12345' },
      c8y_TemperatureMeasurement: {
        T: {
          value: 30.0,
          unit: 'C',
        },
      },
    });

    const mockContext = createMockRuntimeContext({});

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    expect(action.payload).toBeInstanceOf(Uint8Array);
  });

  it('should contain correct temperature value in payload', () => {
    // Arrange
    const mockMsg = createMockOutboundMessage({
      source: { id: '12345' },
      c8y_TemperatureMeasurement: {
        T: {
          value: 42.5,
          unit: 'C',
        },
      },
    });

    const mockContext = createMockRuntimeContext({});

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    const decodedPayload = JSON.parse(new TextDecoder().decode(action.payload));

    expect(decodedPayload.c8y_Steam).toBeDefined();
    expect(decodedPayload.c8y_Steam.Temperature.value).toBe(42.5);
    expect(decodedPayload.c8y_Steam.Temperature.unit).toBe('C');
    expect(decodedPayload.time).toBeDefined();
  });

  it('should generate valid ISO timestamp', () => {
    // Arrange
    const mockMsg = createMockOutboundMessage({
      source: { id: '12345' },
      c8y_TemperatureMeasurement: {
        T: { value: 25.5, unit: 'C' },
      },
    });

    const mockContext = createMockRuntimeContext({});

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    const decodedPayload = JSON.parse(new TextDecoder().decode(action.payload));

    expect(decodedPayload.time).toBeDefined();
    expect(() => new Date(decodedPayload.time)).not.toThrow();
  });
});
