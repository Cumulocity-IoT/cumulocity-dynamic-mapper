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
 *
 * @authors Christof Strack, Stefan Witschel
 */

import {
  SmartFunction,
  SmartFunctionInputMessage,
  SmartFunctionRuntimeContext,
  CumulocityObject,
  DeviceMessage,
  C8yManagedObject,
  createMockInputMessage,
  createMockRuntimeContext,
  createMockPayload
} from './smart-function-runtime.types';

describe('Smart Function Runtime Types', () => {
  describe('Mock Helpers', () => {
    it('should create mock payload with object-style access', () => {
      const mockPayload = createMockPayload({
        messageId: 'msg-123',
        temperature: 25.5,
        sensorData: {
          temp_val: 30.0
        }
      });

      expect(mockPayload['messageId']).toBe('msg-123');
      expect(mockPayload['temperature']).toBe(25.5);
      expect(mockPayload['sensorData']['temp_val']).toBe(30.0);
    });

    it('should create mock payload with Map-like get() method', () => {
      const mockPayload = createMockPayload({
        messageId: 'msg-123',
        temperature: 25.5
      });

      expect(mockPayload.get('messageId')).toBe('msg-123');
      expect(mockPayload.get('temperature')).toBe(25.5);
    });

    it('should create mock input message', () => {
      const mockMsg = createMockInputMessage(
        {
          messageId: 'msg-123',
          temperature: 25.5
        },
        'device/temp/data',
        'msg-123'
      );

      expect(mockMsg.getPayload()).toBeDefined();
      expect(mockMsg.getTopic?.()).toBe('device/temp/data');
      expect(mockMsg.getMessageId?.()).toBe('msg-123');
    });

    it('should create mock runtime context', () => {
      const mockContext = createMockRuntimeContext({
        clientId: 'client-123',
        config: { timeout: 5000 },
        devices: {
          '12345': { id: '12345', name: 'Test Device', type: 'c8y_Device' }
        },
        externalIdMap: {
          'SENSOR-001:c8y_Serial': { id: '12345', name: 'Test Sensor' }
        }
      });

      expect(mockContext.getClientId()).toBe('client-123');
      expect(mockContext.getConfig()).toEqual({ timeout: 5000 });
      expect(mockContext.getManagedObjectByDeviceId('12345')).toEqual({
        id: '12345',
        name: 'Test Device',
        type: 'c8y_Device'
      });
      expect(
        mockContext.getManagedObject({ externalId: 'SENSOR-001', type: 'c8y_Serial' })
      ).toEqual({ id: '12345', name: 'Test Sensor' });
    });

    it('should handle state management in mock context', () => {
      const mockContext = createMockRuntimeContext({});

      mockContext.setState('lastTemp', 25.5);
      mockContext.setState('count', 42);

      expect(mockContext.getState('lastTemp')).toBe(25.5);
      expect(mockContext.getState('count')).toBe(42);
      expect(mockContext.getStateAll()).toEqual({
        lastTemp: 25.5,
        count: 42
      });
    });
  });

  describe('Basic Inbound Smart Function', () => {
    it('should create a measurement from incoming message', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();
        const clientId = context.getClientId();

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: {
              type: 'c8y_TemperatureMeasurement',
              time: new Date().toISOString(),
              c8y_Temperature: {
                T: {
                  value: payload.get('temperature'),
                  unit: 'C'
                }
              }
            },
            externalSource: [{ type: 'c8y_Serial', externalId: clientId! }]
          }
        ];
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        temperature: 25.5
      });

      const mockContext = createMockRuntimeContext({
        clientId: 'SENSOR-001'
      });

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(1);

      const action = result[0] as CumulocityObject;
      expect(action.cumulocityType).toBe('measurement');
      expect(action.action).toBe('create');
      expect(action.payload).toHaveProperty('c8y_Temperature');
      expect(action.externalSource).toEqual([{ type: 'c8y_Serial', externalId: 'SENSOR-001' }]);
    });
  });

  describe('Smart Function with Device Enrichment', () => {
    it('should create voltage measurement when device is configured for voltage', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();
        const clientId = context.getClientId()!;

        const device: C8yManagedObject | null = context.getManagedObject({
          externalId: clientId,
          type: 'c8y_Serial'
        });

        const isVoltage = device?.c8y_Sensor?.type?.voltage === true;

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: isVoltage
              ? {
                  type: 'c8y_VoltageMeasurement',
                  time: new Date().toISOString(),
                  c8y_Voltage: {
                    voltage: { value: payload.get('val'), unit: 'V' }
                  }
                }
              : {
                  type: 'c8y_CurrentMeasurement',
                  time: new Date().toISOString(),
                  c8y_Current: {
                    current: { value: payload.get('val'), unit: 'A' }
                  }
                },
            externalSource: [{ type: 'c8y_Serial', externalId: clientId }]
          }
        ];
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        val: 220.5
      });

      const mockContext = createMockRuntimeContext({
        clientId: 'SENSOR-001',
        externalIdMap: {
          'SENSOR-001:c8y_Serial': {
            id: '12345',
            name: 'Voltage Sensor',
            c8y_Sensor: {
              type: { voltage: true }
            }
          }
        }
      });

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const action = result[0] as CumulocityObject;
      expect(action.payload).toHaveProperty('type', 'c8y_VoltageMeasurement');
      expect(action.payload).toHaveProperty('c8y_Voltage');
    });

    it('should create current measurement when device is configured for current', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();
        const clientId = context.getClientId()!;

        const device: C8yManagedObject | null = context.getManagedObject({
          externalId: clientId,
          type: 'c8y_Serial'
        });

        const isCurrent = device?.c8y_Sensor?.type?.current === true;

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: isCurrent
              ? {
                  type: 'c8y_CurrentMeasurement',
                  time: new Date().toISOString(),
                  c8y_Current: {
                    current: { value: payload.get('val'), unit: 'A' }
                  }
                }
              : {
                  type: 'c8y_VoltageMeasurement',
                  time: new Date().toISOString(),
                  c8y_Voltage: {
                    voltage: { value: payload.get('val'), unit: 'V' }
                  }
                },
            externalSource: [{ type: 'c8y_Serial', externalId: clientId }]
          }
        ];
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        val: 5.25
      });

      const mockContext = createMockRuntimeContext({
        clientId: 'SENSOR-002',
        externalIdMap: {
          'SENSOR-002:c8y_Serial': {
            id: '67890',
            name: 'Current Sensor',
            c8y_Sensor: {
              type: { current: true }
            }
          }
        }
      });

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const action = result[0] as CumulocityObject;
      expect(action.payload).toHaveProperty('type', 'c8y_CurrentMeasurement');
      expect(action.payload).toHaveProperty('c8y_Current');
    });

    it('should return empty array when device is not found', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const clientId = context.getClientId()!;

        const device: C8yManagedObject | null = context.getManagedObject({
          externalId: clientId,
          type: 'c8y_Serial'
        });

        if (!device) {
          return [];
        }

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: {
              type: 'c8y_TemperatureMeasurement',
              time: new Date().toISOString()
            },
            externalSource: [{ type: 'c8y_Serial', externalId: clientId }]
          }
        ];
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        val: 25.5
      });

      const mockContext = createMockRuntimeContext({
        clientId: 'UNKNOWN-SENSOR',
        externalIdMap: {}
      });

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(0);
    });
  });

  describe('Outbound Smart Function', () => {
    it('should create device message for outbound communication', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();

        const deviceMessage: DeviceMessage = {
          topic: `measurements/${payload['source']['id']}`,
          payload: new TextEncoder().encode(
            JSON.stringify({
              temp: payload['c8y_TemperatureMeasurement']['T']['value'],
              time: new Date().toISOString()
            })
          )
        };

        return deviceMessage;
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        source: { id: '12345' },
        c8y_TemperatureMeasurement: {
          T: { value: 25.5, unit: 'C' }
        }
      });

      const mockContext = createMockRuntimeContext({});

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const action = result as DeviceMessage;
      expect(action.topic).toBe('measurements/12345');
      expect(action.payload).toBeInstanceOf(Uint8Array);

      const decodedPayload = JSON.parse(new TextDecoder().decode(action.payload));
      expect(decodedPayload.temp).toBe(25.5);
    });

    it('should use _externalId_ placeholder in topic', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();

        const deviceMessage: DeviceMessage = {
          topic: 'measurements/_externalId_',
          payload: new TextEncoder().encode(
            JSON.stringify({
              temp: payload['c8y_TemperatureMeasurement']['T']['value']
            })
          ),
          externalSource: [{ type: 'c8y_Serial' }]
        };

        return deviceMessage;
      };

      // Arrange
      const mockMsg = createMockInputMessage({
        c8y_TemperatureMeasurement: {
          T: { value: 25.5, unit: 'C' }
        }
      });

      const mockContext = createMockRuntimeContext({});

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const action = result as DeviceMessage;
      expect(action.topic).toBe('measurements/_externalId_');
      expect(action.externalSource).toEqual([{ type: 'c8y_Serial' }]);
    });
  });

  describe('Smart Function with State Management', () => {
    it('should persist state across invocations', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();
        const currentTemp = payload.get('temperature');

        const lastTemp = context.getState('lastTemperature');
        const messageCount = (context.getState('messageCount') || 0) + 1;

        context.setState('lastTemperature', currentTemp);
        context.setState('messageCount', messageCount);

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: {
              type: 'c8y_TemperatureMeasurement',
              time: new Date().toISOString(),
              c8y_Temperature: {
                T: { value: currentTemp, unit: 'C' }
              },
              c8y_Statistics: {
                lastValue: lastTemp || 0,
                messageCount: messageCount
              }
            },
            externalSource: [{ type: 'c8y_Serial', externalId: context.getClientId()! }]
          }
        ];
      };

      // Arrange
      const mockContext = createMockRuntimeContext({
        clientId: 'SENSOR-001'
      });

      // First invocation
      const msg1 = createMockInputMessage({ temperature: 20.0 });
      const result1 = onMessage(msg1, mockContext);

      // Second invocation
      const msg2 = createMockInputMessage({ temperature: 25.5 });
      const result2 = onMessage(msg2, mockContext);

      // Assert
      const action1 = result1[0] as CumulocityObject;
      expect(action1.payload).toHaveProperty('c8y_Statistics');
      expect((action1.payload as any).c8y_Statistics.lastValue).toBe(0);
      expect((action1.payload as any).c8y_Statistics.messageCount).toBe(1);

      const action2 = result2[0] as CumulocityObject;
      expect((action2.payload as any).c8y_Statistics.lastValue).toBe(20.0);
      expect((action2.payload as any).c8y_Statistics.messageCount).toBe(2);
    });
  });

  describe('Smart Function with Context Data', () => {
    it('should include context data for device creation', () => {
      // Define Smart Function
      const onMessage: SmartFunction = (msg, context) => {
        const payload = msg.getPayload();
        const clientId = context.getClientId()!;

        return [
          {
            cumulocityType: 'measurement',
            action: 'create',
            payload: {
              type: 'c8y_TemperatureMeasurement',
              time: new Date().toISOString(),
              c8y_Temperature: {
                T: { value: payload.get('temperature'), unit: 'C' }
              }
            },
            externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
            contextData: {
              deviceName: 'Test Sensor',
              deviceType: 'c8y_Sensor'
            }
          }
        ];
      };

      // Arrange
      const mockMsg = createMockInputMessage({ temperature: 25.5 });
      const mockContext = createMockRuntimeContext({ clientId: 'SENSOR-001' });

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const action = result[0] as CumulocityObject;
      expect(action.contextData).toEqual({
        deviceName: 'Test Sensor',
        deviceType: 'c8y_Sensor'
      });
    });
  });
});
