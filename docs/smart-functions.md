# Smart Function Development

> **This page is a guide, not the contract.** The contract lives in the Java classes, and the
> mirror the build actually enforces is the TypeScript in `dynamic-mapper-smart-function/`. If this
> page and the types disagree, the types win — and this page has a bug. To change the API, follow
> [changing-the-runtime-api.md](changing-the-runtime-api.md); for what is enforced where, see
> [contract-sync.md](contract-sync.md).

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
| `msg.time` | `string \| undefined` | ISO-8601 stamp taken when the runtime builds the message — see [Timestamps](#timestamps) |
| `msg.transportId` | `string \| undefined` | Identifier of the connector that delivered the message |
| `msg.transportFields` | `Record<string, string>` | Transport extras, e.g. MQTT 5 user properties. For Kafka this carries the record **key** as `{ key: … }`. Empty object when the transport has none — never `undefined` |

`msg.sourceId` and `msg.cumulocityType` are typed `never` inbound: an inbound message arrives before
any device has been resolved, so the runtime passes `null` for both.

Access via field style (`msg.payload`) or getter style (`msg.getPayload()`); both work.

### Outbound (`OutboundMessage`)

| Field | Type | Description |
|---|---|---|
| `msg.payload` | `C8yReceivedPayloadTypeMap[T]` | Pre-deserialized C8y object. Note the *Received* map: what a measurement looks like arriving from Cumulocity differs from what you send, so this is not `C8yPayloadTypeMap` |
| `msg.cumulocityType` | `C8yObjectType \| undefined` | Type of the triggering event |
| `msg.sourceId` | `string \| undefined` | Internal Cumulocity device ID |
| `msg.topic` | `string \| undefined` | The mapping's topic |
| `msg.time` | `string \| undefined` | ISO-8601 stamp taken when the runtime builds the message |

`msg.clientId`, `msg.transportId` and `msg.transportFields` are typed `never` outbound: there is no
publishing client or transport yet at the point the function runs.

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
context.clearState()                 // drop all state for this mapping
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

context.logMessage(message)   // @deprecated — use console.log
context.addLogMessage(message)// @deprecated — use console.log
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

`msg.time` is set by both processors, so use it as the fallback rather than `new Date()`:

```ts
var time = payload["time"] || msg.time;
```

Be precise about what it means: `msg.time` is `Instant.now()` at the moment the runtime *builds the
input message* — processing time, not the time the connector received the message, and not the time
the device took the reading. Whenever the device sends its own timestamp, prefer that.

> An earlier version of this page claimed `msg` had no arrival time and told you to use
> `new Date().toISOString()`. That was a misdiagnosis: `msg.time` is populated in both directions, 14 of the 18 shipped
> templates use it, and none use `new Date()`.

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
