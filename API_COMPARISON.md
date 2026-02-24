# Smart Function API Comparison: IDP DataPrep vs Dynamic Mapper

> **✅ UPDATE:** Dynamic Mapper now extends IDP DataPrep standard types!
>
> - Base types: `DataPrepContext` (IDP standard)
> - Extended types: `DynamicMapperContext extends DataPrepContext`
> - Best of both worlds: Standard compliance + enhanced features

## Side-by-Side Comparison

### Function Signatures

| Aspect | IDP DataPrep | Dynamic Mapper |
|--------|-------------|----------------|
| **Input Type** | `DeviceMessage` (raw message) | `DynamicMapperDeviceMessage` (extends concept) |
| **Context Type** | `DataPrepContext` (minimal) | `DynamicMapperContext extends DataPrepContext` ✅ |
| **Return Type** | `(CumulocityObject \| DeviceMessage)[]` | `CumulocityObject[] \| DeviceMessage[]` ✅ Same! |
| **Philosophy** | Standard, minimal, portable | IDP-compatible + Feature-rich |

---

## Input Message API

### IDP DataPrep: Direct Property Access
```typescript
interface DeviceMessage {
  payload: Uint8Array;        // Raw binary
  transportID: string;         // e.g., "mqtt"
  clientID?: string;          // Client identifier
  topic: string;              // MQTT topic
  transportFields?: { [key: string]: any };
  time?: Date;
}

// Usage
function onMessage(msg: DeviceMessage, context: DataPrepContext) {
  const rawBytes = msg.payload;          // Uint8Array
  const jsonStr = new TextDecoder().decode(rawBytes);
  const data = JSON.parse(jsonStr);      // Manual parsing
  const temp = data.temperature;          // Direct access
}
```

### Dynamic Mapper: Direct Property Access (Pre-Deserialized)
```typescript
interface DynamicMapperDeviceMessage {
  payload: SmartFunctionPayload;         // Pre-deserialized!
  topic: string;
  clientId?: string;
  transportId?: string;
  transportFields?: { [key: string]: any };
  time?: Date;
}

interface SmartFunctionPayload {
  [key: string]: any;                    // Object access
  get(key: string): any;                 // Map-like API
}

// Usage
function onMessage(msg: DynamicMapperDeviceMessage, context: DynamicMapperContext) {
  const payload = msg.payload;           // Already parsed!
  const temp = payload.temperature;       // Direct access
  const temp2 = payload.get("temperature"); // Or Map API
  const clientId = msg.clientId;         // Direct property
}
```

**Key Difference:**
- IDP: You manually decode and parse `Uint8Array` → JSON
- Dynamic Mapper: We pre-deserialize payload to JSON objects (same structure, different payload type)

---

## Runtime Context API

### IDP DataPrep: Minimal State Management
```typescript
interface DataPrepContext {
  readonly runtime: "c8y-data-preparation";  // Runtime discriminator

  // State management only
  getState(key: string, defaultValue?: any): any;
  setState(key: string, value: any): void;
}

// Usage - Very limited!
function onMessage(msg: DeviceMessage, context: DataPrepContext) {
  // State
  context.setState("lastTemp", 25.5);
  const lastTemp = context.getState("lastTemp", 0);

  // ❌ No device lookups
  // ❌ No configuration access
  // ❌ No enrichment capabilities
  // Must make external API calls for everything!
}
```

### Dynamic Mapper: Rich Integration (Extends IDP)
```typescript
interface DynamicMapperContext extends DataPrepContext {
  readonly runtime: "dynamic-mapper";

  // IDP Standard (inherited)
  getState(key: string, defaultValue?: any): any;
  setState(key: string, value: any): void;

  // Dynamic Mapper enhancements
  getStateAll(): Record<string, any>;
  getClientId(): string | undefined;

  // Device lookup & enrichment
  getManagedObject(externalId: ExternalId): any;
  getManagedObjectByDeviceId(deviceId: string): any;

  // DTM (Digital Twin Manager) integration
  getDTMAsset(assetId: string): Record<string, any>;
}

// Usage - Feature-complete!
function onMessage(msg: DynamicMapperDeviceMessage, context: DynamicMapperContext) {
  // State (IDP standard)
  context.setState("lastTemp", 25.5);
  const lastTemp = context.getState("lastTemp");

  // ✅ Device enrichment (Dynamic Mapper)
  const device = context.getManagedObject({
    externalId: context.getClientId()!,
    type: "c8y_Serial"
  });

  const deviceType = device?.type;
  const customConfig = device?.c8y_CustomConfig;

  // ✅ DTM assets (Dynamic Mapper)
  const asset = context.getDTMAsset("asset-123");
}
```

**Key Difference:**
- IDP: Minimal API, forces external calls
- Dynamic Mapper: **Extends IDP** with rich API, everything available in-context

---

## Complete Function Examples

### IDP DataPrep Style
```typescript
import {
  DeviceMessage,
  CumulocityObject,
  DataPrepContext
} from './dataprep';

function onMessage(
  msg: DeviceMessage,
  context: DataPrepContext
): (CumulocityObject | DeviceMessage)[] {

  // 1. Manually decode payload
  const jsonStr = new TextDecoder().decode(msg.payload);
  const data = JSON.parse(jsonStr);

  // 2. Only state available
  context.setState("lastValue", data.temperature);

  // 3. No device lookup - must use external ID in return
  return [{
    cumulocityType: "measurement",
    payload: {
      type: "c8y_TemperatureMeasurement",
      time: new Date().toISOString(),
      c8y_Temperature: {
        T: { value: data.temperature, unit: "C" }
      }
    },
    externalSource: [
      { externalId: msg.clientID!, type: "c8y_Serial" }
    ]
  }];
}
```

### Dynamic Mapper Style
```typescript
import {
  SmartFunction,
  DynamicMapperDeviceMessage,
  DynamicMapperContext
} from './smart-function-runtime.types';

const onMessage: SmartFunction = (msg, context) => {

  // 1. Payload already deserialized!
  const payload = msg.payload;
  const temp = payload.temperature;  // Direct access!

  // 2. Rich context features (extends IDP)
  context.setState("lastValue", temp);
  const clientId = context.getClientId()!;

  // 3. Device enrichment available (Dynamic Mapper enhancement)
  const device = context.getManagedObject({
    externalId: clientId,
    type: "c8y_Serial"
  });

  const measurementType = device?.c8y_CustomConfig?.measurementType
    || "c8y_TemperatureMeasurement";

  // 4. Return with enriched data
  return [{
    cumulocityType: "measurement",
    action: "create",
    payload: {
      type: measurementType,
      time: new Date().toISOString(),
      c8y_Temperature: {
        T: { value: temp, unit: "C" }
      }
    },
    externalSource: [
      { externalId: clientId, type: "c8y_Serial" }
    ]
  }];
};
```

---

## Output Types (IDENTICAL!)

Both use the same output structure:

```typescript
// CumulocityObject - Send to Cumulocity APIs
interface CumulocityObject {
  payload: object;
  cumulocityType: 'measurement' | 'event' | 'alarm' | 'operation' | 'managedObject';
  externalSource?: ExternalId[] | ExternalSource[];
  destination?: 'cumulocity' | 'iceflow' | 'streaming-analytics';
  // Dynamic Mapper adds:
  action?: 'create' | 'update' | 'delete' | 'patch';
  contextData?: object;
  sourceId?: string;
}

// DeviceMessage - Send back to broker
interface DeviceMessage {
  payload: Uint8Array;
  topic: string;
  transportId?: string;
  clientId?: string;
  transportFields?: { [key: string]: any };
  time?: Date;
  // Dynamic Mapper adds:
  externalSource?: Array<{ type: string }>;
  action?: 'create' | 'update' | 'delete' | 'patch';
  cumulocityType?: string;
  sourceId?: string;
}

// Smart Function types with explicit directionality
type SmartFunctionIn = (
  msg: DynamicMapperDeviceMessage,
  context: DynamicMapperContext
) => Array<CumulocityObject> | CumulocityObject | [];

type SmartFunctionOut = (
  msg: CumulocityObject,
  context: DynamicMapperContext
) => Array<DeviceMessage> | DeviceMessage | [];
```

✅ **Output types are 95% compatible!** Dynamic Mapper uses explicit inbound/outbound types for clarity.

---

## When to Use Which?

### Use IDP DataPrep Base Types When:
- ✅ Writing minimal Smart Functions (state only)
- ✅ Maximum portability across IDP-compatible runtimes
- ✅ Don't need device enrichment or configuration access
- ✅ Simple use cases with external API calls

### Use Dynamic Mapper Extended Types When: (Recommended ⭐)
- ✅ Building new Smart Functions from scratch
- ✅ Need device enrichment/lookups from inventory cache
- ✅ Want configuration access
- ✅ Need DTM integration
- ✅ Want best developer experience
- ✅ Using Dynamic Mapper runtime (which extends IDP standard)

---

## Migration Path

### From IDP to Dynamic Mapper (Easy! We extend IDP)

```typescript
// Before: IDP style
function onMessage(msg: DeviceMessage, context: DataPrepContext) {
  const data = JSON.parse(new TextDecoder().decode(msg.payload));
  context.setState("last", data.value);

  return [{
    cumulocityType: "measurement",
    payload: { /* ... */ },
    externalSource: [{ externalId: msg.clientID!, type: "c8y_Serial" }]
  }];
}

// After: Dynamic Mapper style (same structure, enhanced types!)
const onMessage: SmartFunction = (msg, context) => {
  const payload = msg.payload;                // Already parsed!
  context.setState("last", payload.value);    // SAME API (IDP compatible)

  const clientId = context.getClientId()!;    // Enhanced: no need for msg.clientId

  return [{
    cumulocityType: "measurement",
    action: "create",                          // More explicit
    payload: { /* ... */ },
    externalSource: [{ externalId: clientId, type: "c8y_Serial" }]
  }];
};
```

### Type Relationship

```typescript
// IDP Base (standard)
interface DataPrepContext {
  readonly runtime: string;
  getState(key: string, defaultValue?: any): any;
  setState(key: string, value: any): void;
}

// Dynamic Mapper (extends IDP)
interface DynamicMapperContext extends DataPrepContext {
  readonly runtime: "dynamic-mapper";
  // + All IDP methods inherited
  // + Enhanced methods added
  getStateAll(): Record<string, any>;
  getClientId(): string | undefined;
  getManagedObject(externalId: ExternalId): any;
  getManagedObjectByDeviceId(deviceId: string): any;
  getDTMAsset(assetId: string): Record<string, any>;
}

// ✅ DynamicMapperContext IS-A DataPrepContext!
// Any code expecting DataPrepContext works with DynamicMapperContext
```

---

## Summary

| Feature | IDP DataPrep | Dynamic Mapper |
|---------|-------------|----------------|
| **Type Relationship** | Base standard | **✅ Extends IDP** |
| **Payload Handling** | Manual decode | Auto-deserialized ✅ |
| **State Management** | ✅ Yes (base) | ✅ Yes (inherited + enhanced) |
| **Configuration Access** | ❌ No | ✅ Yes (enhanced) |
| **Device Lookups** | ❌ No | ✅ Yes (enhanced) |
| **DTM Integration** | ❌ No | ✅ Yes (enhanced) |
| **Type Safety** | ✅ Yes | ✅ Yes |
| **Output Types** | ✅ Standard | ✅ Compatible |
| **IDP Compatibility** | ✅ Standard | **✅ Extends standard** |
| **Portability** | ✅ All IDP runtimes | ✅ Dynamic Mapper (IDP-compatible) |
| **Developer Experience** | ⚠️ Basic | ✅ Rich |
| **Learning Curve** | ✅ Simple | ⚠️ More features |

**Recommendation:** Use **Dynamic Mapper API** (DynamicMapperContext, DynamicMapperDeviceMessage) for all new projects.
It **extends** IDP standard, so you get compatibility + enhancements!
