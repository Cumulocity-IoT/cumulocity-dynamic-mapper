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

    // outbound-basic.ts builds the topic from context.getConfig().externalId, so the mock
    // context has to provide it — otherwise the topic resolves to 'measurements/undefined'.
    const mockContext = createMockRuntimeContext({ config: { externalId: '12345' } });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    expect(action.topic).toBe('measurements/12345');
  });

  it('should return a plain JSON object payload', () => {
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

    // outbound-basic.ts builds the topic from context.getConfig().externalId, so the mock
    // context has to provide it — otherwise the topic resolves to 'measurements/undefined'.
    const mockContext = createMockRuntimeContext({ config: { externalId: '12345' } });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    // Plain JSON object, not Uint8Array: the runtime serializes objects itself and only binary
    // protocols (e.g. SparkPlug B) return bytes. See DeviceMessage.payload.
    expect(ArrayBuffer.isView(action.payload)).toBe(false);
    expect(action.payload).toEqual(
      expect.objectContaining({ c8y_Steam: expect.any(Object) })
    );
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

    // outbound-basic.ts builds the topic from context.getConfig().externalId, so the mock
    // context has to provide it — otherwise the topic resolves to 'measurements/undefined'.
    const mockContext = createMockRuntimeContext({ config: { externalId: '12345' } });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    // The example returns a plain JSON object and lets the runtime serialize it; only binary
    // protocols hand back a Uint8Array. Decoding here was a leftover from the pre-v2 API when
    // every payload was bytes.
    const decodedPayload = action.payload as Record<string, any>;

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

    // outbound-basic.ts builds the topic from context.getConfig().externalId, so the mock
    // context has to provide it — otherwise the topic resolves to 'measurements/undefined'.
    const mockContext = createMockRuntimeContext({ config: { externalId: '12345' } });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    const action = result as DeviceMessage;
    // The example returns a plain JSON object and lets the runtime serialize it; only binary
    // protocols hand back a Uint8Array. Decoding here was a leftover from the pre-v2 API when
    // every payload was bytes.
    const decodedPayload = action.payload as Record<string, any>;

    expect(decodedPayload.time).toBeDefined();
    expect(() => new Date(decodedPayload.time)).not.toThrow();
  });
});
