## TODO I
* can you compate the different typeScript APIs:
    * approach I , used in the project so far: /Users/ck/work/git/cumulocity-dynamic-mapper/dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts
    * approach II, proposed by another team: https://matj-sag.github.io/c8y-smart/modules/dynamicmapper.html
* The following document contains a result from a comparisons with an earlier version from th eother team: attic/guides/IDP_COMPATIBILITY_PROPOSAL.md

---

## API Comparison Result

> Generated: 2026-03-10
> Approach I source: `dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts`
> Approach II source: `@c8y/smart-functions-definitions v0.1.0` (https://matj-sag.github.io/c8y-smart/)

---

### 1. Function Signature

| Aspect | Approach I (Dynamic Mapper) | Approach II (Other team) |
|--------|----------------------------|--------------------------|
| Type name | `SmartFunctionIn` / `SmartFunctionOut` | `OnMessage` |
| Return type | `Array<CumulocityObject<T>> \| CumulocityObject<T> \| []` | `CumulocityObject[] \| Promise<CumulocityObject[]>` |
| Async support | ❌ No (sync only) | ✅ Yes (Promise supported) |

**Key difference:** Approach II supports `Promise` returns, enabling async operations (e.g., external lookups). Approach I is synchronous only.

---

### 2. Input Message (`DeviceMessage`)

| Field | Approach I | Approach II |
|-------|-----------|------------|
| `payload` | `SmartFunctionPayload` (pre-deserialized JSON object) | `PAYLOAD` generic, defaults to `Uint8Array` (raw bytes) |
| `topic` | `string` (required) | `string` (required) |
| `clientId` / `clientID` | `clientId?: string` (optional, camelCase) | `clientID?: string` (optional, UPPERCASE ID) |
| `transportId` / `transportID` | `transportId?: string` (optional) | `transportID: string` (**required**) |
| `transportFields` | `{ [key: string]: any }` | `{ [key: string]: any }` |
| `time` | `Date` (optional) | `Date` (optional) |

**Key differences:**
- **Payload type**: Approach I pre-deserializes JSON payloads to objects for ergonomics. Approach II passes raw `Uint8Array` bytes (matching IDP DataPrep standard).
- **Field naming**: `clientId` vs `clientID`, `transportId` vs `transportID` — casing differs.
- **`transportID` required**: Approach II requires it; Approach I makes it optional.

---

### 3. Context

| Method/Property | Approach I (`SmartFunctionContext`) | Approach II (`DynamicMapperContext`) |
|-----------------|-------------------------------------|--------------------------------------|
| `runtime` | `"dynamic-mapper"` (readonly) | `"dynamic-mapper"` (readonly) |
| `getState(key, defaultValue?)` | ✅ | ✅ |
| `setState(key, value)` | ✅ | ✅ |
| `getStateAll()` | ✅ | ❌ |
| `getClientId()` | ✅ | ❌ |
| `getManagedObject(c8yId)` | ✅ (by internal ID) | ❌ |
| `getManagedObjectByExternalId(externalId)` | ✅ (by external ID) | ❌ |
| `getDTMAsset(assetId)` | ✅ (DTM integration) | ❌ |
| `getConfig()` | ✅ (mapping config) | ❌ |
| `addWarning(warning)` | ✅ | ❌ |
| Base type | `extends DataPrepContext` | standalone (no explicit base in docs) |

**Key differences:**
- Approach II context is **minimal** — only state (`getState`/`setState`) and `runtime`.
- Approach I context is **rich** — adds device inventory lookups, DTM, mapping config, and warnings.
- Both share the same `runtime: "dynamic-mapper"` discriminator.

---

### 4. Output Object (`CumulocityObject`)

| Field | Approach I | Approach II |
|-------|-----------|------------|
| `cumulocityType` | `'measurement' \| 'event' \| 'alarm' \| 'operation' \| 'managedObject'` | same union |
| `payload` | `C8yPayloadTypeMap[T]` (typed per cumulocityType) | `PAYLOAD` generic (defaults to `object`) |
| `action` | ✅ `'create' \| 'update' \| 'delete' \| 'patch'` (**required**) | ❌ Not present |
| `externalSource` | `ExternalId[] \| ExternalId \| ExternalSource[]` (**optional**) | `ExternalId[]` (**required**) |
| `destination` | `'cumulocity' \| 'iceflow' \| 'streaming-analytics'` | `'cumulocity' \| 'offloading' \| 'streaming-analytics'` |
| `contextData` | ✅ `Record<string, string>` | ❌ Not present |
| `sourceId` | ✅ `string` (explicit device override) | ❌ Not present |

**Key differences:**
- **`action` field**: Approach I requires an explicit CRUD action (`create`, `update`, `delete`, `patch`). Approach II has no such field (semantics implied by context or payload).
- **`externalSource` optionality**: Approach I makes it optional; Approach II requires it.
- **`externalSource` type**: Approach I supports richer `ExternalSource` with auto-creation flags, parent hierarchy, and clientId. Approach II uses plain `ExternalId[]` only.
- **`destination` values**: `'iceflow'` (Approach I) vs `'offloading'` (Approach II) — different naming for the same concept.
- **Typed payload**: Approach I uses discriminated union via `C8yPayloadTypeMap` for stronger typing. Approach II uses an unconstrained generic.

---

### 5. `ExternalId` / `ExternalSource`

Both define `ExternalId` identically:
```typescript
interface ExternalId {
  externalId: string;  // External ID value
  type: string;         // e.g., "c8y_Serial"
}
```

Approach I additionally defines `ExternalSource` (superset):
```typescript
interface ExternalSource extends ExternalId {
  autoCreateDeviceMO?: boolean;
  parentId?: string;
  childReference?: 'device' | 'asset' | 'addition';
  clientId?: string;
}
```
This is for advanced device creation scenarios not covered by Approach II.

---

### 6. Domain Object Types (Measurement, Event, Alarm, etc.)

| Type | Approach I | Approach II |
|------|-----------|------------|
| `C8yMeasurement` | Full typed interface with fragments | Inferred from `Measurement<TFragments>` |
| `C8yEvent` | Full typed interface | `Event` interface |
| `C8yAlarm` | Full typed + `C8yAlarmSeverity` / `C8yAlarmStatus` types | Inline `severity`/`status` union types |
| `C8yOperation` | Full typed interface | `Operation` interface |
| `C8yManagedObject` | Full typed interface | Not explicitly in output types |
| Alarm `time` | `string` (ISO 8601) | `Date` |

**Key difference:** Approach II uses `Date` objects for timestamps; Approach I uses ISO 8601 strings. This matters for serialization.

---

### 7. Outbound (C8y → Broker) Support

| Aspect | Approach I | Approach II |
|--------|-----------|------------|
| Outbound function type | `SmartFunctionOut` with `OutboundMessage<T>` input | Not defined (OnMessage is inbound-only) |
| Outbound message type | `DeviceMessage` (Uint8Array payload, topic, clientId, retain, etc.) | `DeviceMessage<PAYLOAD>` (generic) |
| `retain` flag | ✅ `boolean` | ❓ Not documented |

Approach I explicitly models the outbound direction with `SmartFunctionOut` and `OutboundMessage`. Approach II's `OnMessage` only returns `CumulocityObject[]`, not `DeviceMessage[]`.

---

### 8. Summary: What Needs Alignment

| Area | Gap | Recommendation |
|------|-----|----------------|
| **Payload bytes vs object** | Approach II uses `Uint8Array`, Approach I uses pre-deserialized object | Document clearly; Dynamic Mapper pre-deserializes for ergonomics — this is intentional. Retain current approach. |
| **Field naming casing** | `clientId` vs `clientID`, `transportId` vs `transportID` | Align to `clientId`/`transportId` (camelCase) for consistency with JSON conventions. Approach I is correct here. |
| **`action` field** | Approach I has it, Approach II doesn't | The `action` field is important for update/delete semantics. Keep it in Approach I. |
| **`externalSource` required vs optional** | Approach II requires it; Approach I makes it optional | Optional is more flexible (e.g., when sourceId is used directly). Keep optional in Approach I. |
| **`destination` naming** | `'iceflow'` vs `'offloading'` | Align naming; `'offloading'` is the standard term from IDP. Consider renaming `'iceflow'` → `'offloading'` in Approach I. |
| **Async support** | Approach I is sync only | Consider adding `Promise` return support in `SmartFunctionIn` / `SmartFunctionOut`. |
| **Context richness** | Approach II is minimal; Approach I is rich | No action needed — extra methods are additive and non-breaking. |
| **Timestamp type** | `string` (Approach I) vs `Date` (Approach II) | `Date` is more ergonomic. Consider aligning alarm/event/measurement `time` to `Date` in Approach I. |
