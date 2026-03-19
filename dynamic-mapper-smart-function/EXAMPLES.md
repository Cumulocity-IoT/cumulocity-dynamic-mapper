# Smart Function Examples - Detailed Guide

This document provides detailed explanations of each example Smart Function.

## 📚 Table of Contents

- [Inbound Examples](#inbound-examples)
  - [Basic Inbound](#1-basic-inbound)
  - [Device Enrichment](#2-device-enrichment)
  - [State Management](#3-state-management)
- [Outbound Examples](#outbound-examples)
  - [Basic Outbound](#4-basic-outbound)
  - [Data Transformation](#5-data-transformation)

---

## Inbound Examples

### 1. Basic Inbound

**File:** [`src/examples/inbound-basic.ts`](src/examples/inbound-basic.ts)

**Corresponds to:** `template-SMART-INBOUND-01.js`

**What it does:**
- Receives device data from MQTT/broker
- Creates a temperature measurement in Cumulocity
- Demonstrates basic payload access and device identification

**Key Features:**
```typescript
// Get payload (supports both styles)
const payload = msg.getPayload();
const temp = payload["sensorData"]["temp_val"];  // Object-style
const msgId = payload.get("messageId");           // Map-style

// Get client ID
const clientId = context.getClientId();

// Lookup devices
const device = context.getManagedObjectByDeviceId("12345");
const device2 = context.getManagedObject({
  externalId: clientId,
  type: "c8y_Serial"
});

// Create measurement
return [{
  cumulocityType: "measurement",
  action: "create",
  payload: { /* measurement data */ },
  externalSource: [{ type: "c8y_Serial", externalId: clientId }]
}];
```

**Input Example:**
```json
{
  "messageId": "msg-123",
  "deviceId": "12345",
  "clientId": "SENSOR-001",
  "sensorData": {
    "temp_val": 25.5
  }
}
```

**Output (Cumulocity Measurement):**
```json
{
  "time": "2025-02-17T10:30:00.000Z",
  "type": "c8y_TemperatureMeasurement",
  "c8y_Steam": {
    "Temperature": {
      "unit": "C",
      "value": 25.5
    }
  }
}
```

**Compiled JavaScript:**
After running `npm run build`, find the compiled version at:
```
dist/examples/inbound-basic.js
```

---

### 2. Device Enrichment

**File:** [`src/examples/inbound-enrichment.ts`](src/examples/inbound-enrichment.ts)

**Corresponds to:** `template-SMART-INBOUND-02.js`

**What it does:**
- Looks up device configuration from Cumulocity inventory
- Creates different measurement types based on device properties
- Demonstrates conditional logic based on device enrichment

**Key Features:**
```typescript
// Lookup device
const device = context.getManagedObject({
  externalId: clientId,
  type: "c8y_Serial"
});

// Type-safe access to device fragments
const isVoltage = device?.c8y_Sensor?.type?.voltage === true;
const isCurrent = device?.c8y_Sensor?.type?.current === true;

// Create measurement based on device type
if (isVoltage) {
  return [{ /* Voltage measurement */ }];
} else if (isCurrent) {
  return [{ /* Current measurement */ }];
}
```

**Device Configuration Example:**
```json
{
  "id": "12345",
  "name": "Voltage Sensor",
  "type": "c8y_Device",
  "c8y_Sensor": {
    "type": {
      "voltage": true
    }
  }
}
```

**Input Example:**
```json
{
  "clientId": "VOLTAGE-SENSOR-001",
  "sensorData": {
    "val": 220.5
  }
}
```

**Output (Voltage Measurement):**
```json
{
  "time": "2025-02-17T10:30:00.000Z",
  "type": "c8y_VoltageMeasurement",
  "c8y_Voltage": {
    "voltage": {
      "unit": "V",
      "value": 220.5
    }
  }
}
```

**Output (Current Measurement):**
```json
{
  "time": "2025-02-17T10:30:00.000Z",
  "type": "c8y_CurrentMeasurement",
  "c8y_Current": {
    "current": {
      "unit": "A",
      "value": 5.25
    }
  }
}
```

---

### 3. State Management

**File:** [`src/examples/inbound-with-state.ts`](src/examples/inbound-with-state.ts)

**What it does:**
- Maintains state across multiple invocations
- Tracks temperature statistics (min, max, count)
- Detects temperature changes
- Demonstrates persistent state management

**Key Features:**
```typescript
// Retrieve state
const lastTemp = context.getState('lastTemperature');
const messageCount = context.getState('messageCount') || 0;
const maxTemp = context.getState('maxTemperature');
const minTemp = context.getState('minTemperature');

// Calculate new statistics
const newMaxTemp = Math.max(maxTemp || currentTemp, currentTemp);
const newMinTemp = Math.min(minTemp || currentTemp, currentTemp);

// Update state
context.setState('lastTemperature', currentTemp);
context.setState('messageCount', messageCount + 1);
context.setState('maxTemperature', newMaxTemp);
context.setState('minTemperature', newMinTemp);
```

**First Invocation:**
```typescript
Input: { temperature: 20.0 }
State Before: {}
State After: {
  lastTemperature: 20.0,
  messageCount: 1,
  maxTemperature: 20.0,
  minTemperature: 20.0
}
```

**Second Invocation:**
```typescript
Input: { temperature: 25.5 }
State Before: {
  lastTemperature: 20.0,
  messageCount: 1,
  maxTemperature: 20.0,
  minTemperature: 20.0
}
State After: {
  lastTemperature: 25.5,
  messageCount: 2,
  maxTemperature: 25.5,
  minTemperature: 20.0
}
```

**Output (with statistics):**
```json
{
  "time": "2025-02-17T10:30:00.000Z",
  "type": "c8y_TemperatureMeasurement",
  "c8y_Temperature": {
    "T": {
      "unit": "C",
      "value": 25.5
    }
  },
  "c8y_Statistics": {
    "lastValue": 20.0,
    "change": 5.5,
    "maxValue": 25.5,
    "minValue": 20.0,
    "messageCount": 2
  }
}
```

---

## Outbound Examples

### 4. Basic Outbound

**File:** [`src/examples/outbound-basic.ts`](src/examples/outbound-basic.ts)

**Corresponds to:** `template-SMART-OUTBOUND-01.js`

**What it does:**
- Receives Cumulocity measurements
- Converts to device message format
- Sends back to device via MQTT/broker

**Key Features:**
```typescript
// Access Cumulocity payload
const sourceId = payload["source"]["id"];
const tempValue = payload["c8y_TemperatureMeasurement"]["T"]["value"];

// Create device message
return {
  topic: `measurements/${sourceId}`,
  payload: new TextEncoder().encode(JSON.stringify({
    time: new Date().toISOString(),
    c8y_Steam: {
      Temperature: {
        unit: "C",
        value: tempValue
      }
    }
  }))
};
```

**Using Topic Placeholder:**
```typescript
// Alternative: Use _externalId_ placeholder
return {
  topic: 'measurements/_externalId_',
  payload: new TextEncoder().encode(JSON.stringify({ /* ... */ })),
  externalSource: [{ type: "c8y_Serial" }]
};
```

**Input (Cumulocity Measurement):**
```json
{
  "id": "67890",
  "type": "c8y_TemperatureMeasurement",
  "source": {
    "id": "12345"
  },
  "c8y_TemperatureMeasurement": {
    "T": {
      "value": 25.5,
      "unit": "C"
    }
  }
}
```

**Output (Device Message):**
```
Topic: measurements/12345
Payload (JSON):
{
  "time": "2025-02-17T10:30:00.000Z",
  "c8y_Steam": {
    "Temperature": {
      "unit": "C",
      "value": 25.5
    }
  }
}
```

---

### 5. Data Transformation

**File:** [`src/examples/outbound-with-transformation.ts`](src/examples/outbound-with-transformation.ts)

**What it does:**
- Transforms Cumulocity measurements to custom device format
- Demonstrates complex data mapping
- Shows how to use Kafka transport fields

**Key Features:**
```typescript
// Define custom device payload format
interface CustomDevicePayload {
  timestamp: string;
  deviceId: string;
  sensors: {
    temperature?: { value: number; unit: string };
    humidity?: { value: number; unit: string };
  };
  metadata: {
    type: string;
    source: string;
  };
}

// Transform data
const customPayload: CustomDevicePayload = {
  timestamp: new Date().toISOString(),
  deviceId: sourceId,
  sensors: {},
  metadata: { type: measurementType, source: 'cumulocity' }
};

// Add temperature if available
if (payload['c8y_TemperatureMeasurement']) {
  customPayload.sensors.temperature = {
    value: payload['c8y_TemperatureMeasurement']['T']['value'],
    unit: payload['c8y_TemperatureMeasurement']['T']['unit'] || 'C'
  };
}
```

**Input (Cumulocity Measurement):**
```json
{
  "source": { "id": "12345" },
  "type": "c8y_TemperatureMeasurement",
  "c8y_TemperatureMeasurement": {
    "T": { "value": 25.5, "unit": "C" }
  },
  "c8y_HumidityMeasurement": {
    "H": { "value": 65.0, "unit": "%" }
  }
}
```

**Output (Custom Device Format):**
```json
{
  "timestamp": "2025-02-17T10:30:00.000Z",
  "deviceId": "12345",
  "sensors": {
    "temperature": {
      "value": 25.5,
      "unit": "C"
    },
    "humidity": {
      "value": 65.0,
      "unit": "%"
    }
  },
  "metadata": {
    "type": "c8y_TemperatureMeasurement",
    "source": "cumulocity"
  }
}
```

**With Kafka Transport Fields:**
```typescript
return {
  topic: `device/${sourceId}/measurements`,
  payload: new TextEncoder().encode(JSON.stringify(customPayload)),
  transportFields: {
    key: sourceId,                  // Kafka record key
    'content-type': 'application/json'
  }
};
```

---

## 🔄 Compilation Process

### From TypeScript to JavaScript

**TypeScript Source:**
```typescript
import { SmartFunction } from '../types';

const onMessage: SmartFunction = (msg, context) => {
  const payload = msg.getPayload();
  return [{ /* ... */ }];
};

export default onMessage;
```

**Compiled JavaScript:**
```javascript
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.onMessage = void 0;

const onMessage = (msg, context) => {
    const payload = msg.getPayload();
    return [{ /* ... */ }];
};
exports.default = onMessage;
exports.onMessage = onMessage;
```

**Steps to Compile:**
```bash
# 1. Install dependencies
npm install

# 2. Compile TypeScript to JavaScript
npm run build

# 3. Find compiled files
ls dist/examples/
# Output:
# inbound-basic.js
# inbound-enrichment.js
# inbound-with-state.js
# outbound-basic.js
# outbound-with-transformation.js
```

---

## 🧪 Testing Examples

Each example has corresponding tests in `src/__tests__/`:

```bash
# Run all tests
npm test

# Run specific test file
npm test -- inbound-basic.spec.ts

# Run tests in watch mode
npm run test:watch
```

**Example Test:**
```typescript
import { onMessage } from '../examples/inbound-basic';
import {
  createMockInputMessage,
  createMockRuntimeContext,
  CumulocityObject
} from '../types';

describe('Inbound Basic', () => {
  it('should create measurement', () => {
    const mockMsg = createMockInputMessage({
      sensorData: { temp_val: 25.5 }
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'SENSOR-001'
    });

    const result = onMessage(mockMsg, mockContext);

    expect(result[0].cumulocityType).toBe('measurement');
    expect(result[0].payload.c8y_Steam.Temperature.value).toBe(25.5);
  });
});
```

---

## 📝 Summary

| Example | Input | Output | Use Case |
|---------|-------|--------|----------|
| **Inbound Basic** | Device data | Measurement | Simple data ingestion |
| **Device Enrichment** | Device data + Config | Conditional measurements | Type-based processing |
| **State Management** | Device data | Measurement + Statistics | Stateful processing |
| **Outbound Basic** | Measurement | Device message | Simple data export |
| **Data Transformation** | Measurement | Custom format | Complex transformations |

---

## 🚀 Next Steps

1. **Study the examples** - Understand the patterns
2. **Run the tests** - See how testing works
3. **Build the project** - Compile to JavaScript
4. **Create your own** - Apply the patterns to your use case
5. **Deploy to Dynamic Mapper** - Use the compiled JavaScript

For more information, see:
- [README.md](README.md) - Complete documentation
- [QUICK_START.md](QUICK_START.md) - Get started quickly
- [../SMART_FUNCTION_TYPINGS.md](../SMART_FUNCTION_TYPINGS.md) - Type system documentation
