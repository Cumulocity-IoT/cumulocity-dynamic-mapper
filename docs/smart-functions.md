# Smart Function Development

Smart Functions are JavaScript callbacks executed in GraalVM at runtime. Write them in TypeScript using the `dynamic-mapper-smart-function/` module for type safety, then paste the compiled JS into the mapping editor.

## Entry Point

Same signature for both directions:

```ts
function onMessage(msg: DynamicMapperDeviceMessage, context: SmartFunctionContext): CumulocityObject[] | DeviceMessage[]
```

## Key Types (`src/types/`)

| Type | Direction | Description |
|------|-----------|-------------|
| `DynamicMapperDeviceMessage` | Inbound | Pre-deserialized incoming message (`payload`, `topic`, `clientId`, `transportFields`) |
| `OutboundMessage` | Outbound | Cumulocity notification triggering the function |
| `SmartFunctionContext` | Both | Runtime context — device lookup, logging, config access |
| `CumulocityObject` | Inbound return | C8Y object to create (measurement, event, alarm, inventory) |
| `DeviceMessage` | Outbound return | Message to publish to the broker (`topic`, `payload`, `transportFields`) |

## `SmartFunctionContext` Methods

```ts
context.getConfig()           // mapping config (targetAPI, externalIdType, externalId, ...)
context.getDevice(externalId) // resolve device by external ID → C8yManagedObject
context.log(message)          // write to mapper log
context.getCache(key)         // per-mapping persistent state
context.setCache(key, value)  // store per-mapping state
```

## Payload Access

Payloads are pre-deserialized JSON objects:

```ts
const temp = msg.payload["sensorData"]["temp_val"]; // bracket notation (preferred)
```

> **Deprecation:** `payload.get(key)` is a legacy alias for bracket notation. Use `payload["key"]` directly.

## Build & Test

```bash
cd dynamic-mapper-smart-function

npm run build   # compile TypeScript → JavaScript (output in dist/)
npm test        # run Jest unit tests
npm run lint    # lint
```

See `src/examples/` for inbound and outbound reference implementations, and `src/__tests__/` for testing patterns with mock helpers.
