# Smart Function Development

Smart Functions are JavaScript callbacks executed in GraalVM at runtime. Write them in TypeScript using the `dynamic-mapper-smart-function/` module for type safety, then paste the compiled JS into the mapping editor.

## Entry Point

Both inbound and outbound functions use the same name `onMessage`, but receive different argument types:

```ts
// Inbound: broker message → Cumulocity objects
function onMessage(msg: DynamicMapperDeviceMessage, context: SmartFunctionContext): CumulocityObject[]

// Outbound: Cumulocity event → broker messages
function onMessage(msg: OutboundMessage, context: SmartFunctionContext): DeviceMessage | DeviceMessage[]
```

## Input message (`msg`)

### Inbound (`DynamicMapperDeviceMessage`)

| Field | Type | Description |
|---|---|---|
| `msg.payload` | `Record<string, any>` | Pre-deserialized JSON payload — use bracket notation |
| `msg.topic` | `string` | MQTT topic or source path |
| `msg.clientId` | `string \| undefined` | Transport client ID (e.g. MQTT client ID) |

Access via field style (`msg.payload`) or getter style (`msg.getPayload()`); both work.

### Outbound (`OutboundMessage`)

| Field | Type | Description |
|---|---|---|
| `msg.payload` | `C8yPayloadTypeMap[T]` | Pre-deserialized C8y event object |
| `msg.cumulocityType` | `C8yObjectType \| undefined` | Type of the triggering event |
| `msg.sourceId` | `string \| undefined` | Internal Cumulocity device ID |

## `SmartFunctionContext` methods

### Device lookup

```ts
context.getManagedObject(c8ySourceId)            // lookup by internal C8Y id → C8yManagedObject | null
context.getManagedObjectByExternalId({ externalId, type })  // lookup by external id → C8yManagedObject | null
context.getDTMAsset(assetId)                     // lookup DTM asset → C8yManagedObject | null
```

### Persistent state (per mapping, in-memory across messages)

```ts
context.getState(key)                // retrieve value (returns any)
context.getState(key, defaultValue)  // retrieve with default
context.setState(key, value)         // store value
context.getStateAll()                // retrieve all state as object
context.getStateKeySet()             // retrieve all state keys as string[]
```

### Mapping config (read-only, reset each message)

```ts
context.getConfig()           // full mapping config (mappingId, mappingName, version, tenant, topic, targetAPI, debug, ...)
context.getExternalId()       // resolved external ID of source device (outbound only, when useExternalId is set)
context.getClientId()         // transport client ID (inbound only)
```

### Diagnostics

```ts
context.addWarning(message)   // surface a non-fatal warning in the Dynamic Mapper UI
console.log(...)              // general debugging (preferred for logs)
context.getTesting()          // true when invoked from the mapping test UI — skip side effects
```

## Return types

### Inbound — `CumulocityObject`

```ts
return [{
  cumulocityType: "measurement",   // "measurement" | "event" | "alarm" | "operation" | "managedObject" | "custom"
  action: "create",                // "create" | "update" | "delete" | "patch"
  payload: { ... },
  externalSource: [{ type: "c8y_Serial", externalId: clientId }],
  // optional:
  contextData: { deviceName, deviceType, deviceFragments, deviceGroups },
  sourceId: "12345",               // override target device
  targetPath: "/service/...",      // only for cumulocityType: "custom"
}];
```

### Outbound — `DeviceMessage`

```ts
return {
  topic: `measurements/${externalId}`,  // omit to use the mapping's fixed publish topic
  payload: { temperature: 23.5 },       // object (serialized to JSON) or Uint8Array (binary)
  // optional:
  transportFields: { key: "device-123" },
  retain: false,
  clientId: "...",
  transportId: "mqtt",
};
```

## Payload access

Payloads are pre-deserialized JSON objects. Use bracket notation:

```ts
const temp = msg.payload["sensorData"]["temp_val"];
```

> `msg.getPayload()` is a Java-style getter alias that also works. TypeScript developers should prefer direct field access (`msg.payload`).

## Timestamps

`msg` does not expose a message arrival time. Use the payload's own `time` field when available, or fall back to the current time:

```ts
var time = payload["time"] ? payload["time"] : new Date().toISOString();
```

## Build & test

```bash
cd dynamic-mapper-smart-function

npm run build   # compile TypeScript → JavaScript (output in dist/)
npm test        # run Jest unit tests
npm run lint    # lint
```

See `src/examples/` for inbound and outbound reference implementations, and `src/types/smart-function-dynamic-mapper.types.spec.ts` for testing patterns using the mock helpers (`createMockInputMessage`, `createMockRuntimeContext`).

## V2 API (typed config and state)

Use `SmartFunctionInV2` / `SmartFunctionOutV2` for full type safety on config, state, and payload:

```ts
const onMessage: SmartFunctionInV2<{
  config: { externalIdType: string };
  state:  { messageCount: number };
}> = (msg, context) => {
  const count = context.getState('messageCount', 0) + 1;  // typed as number
  context.setState('messageCount', count);
  const cfg = context.getConfig();                        // typed: { externalIdType: string }
  return [{ ... }];
};
```
