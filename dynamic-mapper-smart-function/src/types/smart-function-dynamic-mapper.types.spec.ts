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
  SmartFunctionIn,
  SmartFunctionOut,
  CumulocityObject,
  DeviceMessage,
  C8yManagedObject,
  createMockInputMessage,
  createMockOutboundMessage,
  createMockRuntimeContext,
  createMockPayload
} from './smart-function-dynamic-mapper.types';

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
        'client-123'
      );

      expect(mockMsg.payload).toBeDefined();
      expect(mockMsg.topic).toBe('device/temp/data');
      expect(mockMsg.clientId).toBe('client-123');
    });

    it('should create mock runtime context', () => {
      const mockContext = createMockRuntimeContext({
        clientId: 'client-123',
        devices: {
          '12345': { id: '12345', name: 'Test Device', type: 'c8y_Device' }
        },
        externalIdMap: {
          'SENSOR-001:c8y_Serial': { id: '12345', name: 'Test Sensor' }
        }
      });

      expect(mockContext.getClientId()).toBe('client-123');
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
      const onMessage: SmartFunctionIn = (msg, context) => {
        const payload = msg.payload;
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
      const resultArr = result as CumulocityObject[];
      expect(Array.isArray(resultArr)).toBe(true);
      expect(resultArr.length).toBe(1);

      const action = resultArr[0];
      expect(action.cumulocityType).toBe('measurement');
      expect(action.action).toBe('create');
      expect((action.payload as Record<string, unknown>)['c8y_Temperature']).toBeDefined();
      expect(action.externalSource).toEqual([{ type: 'c8y_Serial', externalId: 'SENSOR-001' }]);
    });
  });

  describe('Smart Function with Device Enrichment', () => {
    it('should create voltage measurement when device is configured for voltage', () => {
      // Define Smart Function
      const onMessage: SmartFunctionIn = (msg, context) => {
        const payload = msg.payload;
        const clientId = context.getClientId()!;

        const device: C8yManagedObject | null = context.getManagedObject({
          externalId: clientId,
          type: 'c8y_Serial'
        });

        const isVoltage = device?.['c8y_Sensor']?.['type']?.['voltage'] === true;

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
      const p1 = action.payload as Record<string, unknown>;
      expect(p1['type']).toBe('c8y_VoltageMeasurement');
      expect(p1['c8y_Voltage']).toBeDefined();
    });

    it('should create current measurement when device is configured for current', () => {
      // Define Smart Function
      const onMessage: SmartFunctionIn = (msg, context) => {
        const payload = msg.payload;
        const clientId = context.getClientId()!;

        const device: C8yManagedObject | null = context.getManagedObject({
          externalId: clientId,
          type: 'c8y_Serial'
        });

        const isCurrent = device?.['c8y_Sensor']?.['type']?.['current'] === true;

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
      const p2 = action.payload as Record<string, unknown>;
      expect(p2['type']).toBe('c8y_CurrentMeasurement');
      expect(p2['c8y_Current']).toBeDefined();
    });

    it('should return empty array when device is not found', () => {
      // Define Smart Function
      const onMessage: SmartFunctionIn = (msg, context) => {
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
      const resultArr = result as CumulocityObject[];
      expect(Array.isArray(resultArr)).toBe(true);
      expect(resultArr.length).toBe(0);
    });
  });

  describe('Outbound Smart Function', () => {
    it('should create device message for outbound communication', () => {
      // Define Smart Function (Cumulocity → Broker)
      const onMessage: SmartFunctionOut = (msg, context) => {
        // No cast needed: msg.payload is SmartFunctionPayload
        const sourceId = msg.payload['source']['id'];

        return {
          topic: `measurements/${sourceId}`,
          payload: new TextEncoder().encode(
            JSON.stringify({
              temp: msg.payload['c8y_TemperatureMeasurement']['T']['value'],
              time: new Date().toISOString()
            })
          )
        };
      };

      // Arrange - createMockOutboundMessage wraps data in SmartFunctionPayload
      const mockMsg = createMockOutboundMessage({
        source: { id: '12345' },
        c8y_TemperatureMeasurement: {
          T: { value: 25.5, unit: 'C' }
        }
      }, 'measurement');

      const mockContext = createMockRuntimeContext({});

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const deviceMsg = result as DeviceMessage;
      expect(deviceMsg.topic).toBe('measurements/12345');
      expect(deviceMsg.payload).toBeInstanceOf(Uint8Array);

      const decoded = JSON.parse(new TextDecoder().decode(deviceMsg.payload));
      expect(decoded.temp).toBe(25.5);
    });

    it('should use _externalId_ placeholder in topic', () => {
      // Define Smart Function (Cumulocity → Broker)
      const onMessage: SmartFunctionOut = (msg, context) => {
        // .get() works without casting — payload is SmartFunctionPayload
        const temp = msg.payload.get('c8y_TemperatureMeasurement')?.['T']?.['value'];

        return {
          topic: 'measurements/_externalId_',
          payload: new TextEncoder().encode(JSON.stringify({ temp })),
          externalSource: [{ type: 'c8y_Serial' }]
        };
      };

      // Arrange
      const mockMsg = createMockOutboundMessage({
        c8y_TemperatureMeasurement: {
          T: { value: 25.5, unit: 'C' }
        }
      }, 'measurement');

      const mockContext = createMockRuntimeContext({});

      // Act
      const result = onMessage(mockMsg, mockContext);

      // Assert
      const deviceMsg = result as DeviceMessage;
      expect(deviceMsg.topic).toBe('measurements/_externalId_');
      expect(deviceMsg.externalSource).toEqual([{ type: 'c8y_Serial' }]);
    });
  });

  describe('Smart Function with State Management', () => {
    it('should persist state across invocations', () => {
      // Define Smart Function
      const onMessage: SmartFunctionIn = (msg, context) => {
        const payload = msg.payload;
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
      const stats1 = (action1.payload as Record<string, any>)['c8y_Statistics'];
      expect(stats1).toBeDefined();
      expect(stats1.lastValue).toBe(0);
      expect(stats1.messageCount).toBe(1);

      const action2 = result2[0] as CumulocityObject;
      const stats2 = (action2.payload as Record<string, any>)['c8y_Statistics'];
      expect(stats2.lastValue).toBe(20.0);
      expect(stats2.messageCount).toBe(2);
    });
  });

  describe('Smart Function with Context Data', () => {
    it('should include context data for device creation', () => {
      // Define Smart Function
      const onMessage: SmartFunctionIn = (msg, context) => {
        const payload = msg.payload;
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
