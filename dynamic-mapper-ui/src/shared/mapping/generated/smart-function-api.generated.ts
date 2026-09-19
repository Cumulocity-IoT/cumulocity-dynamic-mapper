/*
 * GENERATED FILE — DO NOT EDIT.
 *
 * Produced by dynamic-mapper-smart-function/scripts/generate-editor-api.cjs from
 * src/types/smart-function-dynamic-mapper.types.ts.
 *
 * To change what the mapping editor's autocomplete and hover show, change the TypeScript type
 * definitions and re-run:
 *
 *     cd dynamic-mapper-smart-function && npm run generate:editor-api
 *
 * CI regenerates this file and fails if the result differs from what is committed.
 */

/** One class or enum as the editor's completion and hover providers consume it. */
export interface GeneratedApiEntry {
  name: string;
  isEnum: boolean;
  documentation: string;
  deprecated?: boolean;
  properties?: Array<{ name: string; type: string; documentation: string }>;
  methods?: Array<{ name: string; parameters: string[]; returnType: string; documentation: string }>;
  values?: string[];
}

export const SMART_FUNCTION_API: GeneratedApiEntry[] = [
  {
    "name": "CumulocityObject",
    "isEnum": false,
    "properties": [
      {
        "name": "payload",
        "type": "C8yPayloadTypeMap[C8yObjectType]",
        "documentation": "The Cumulocity API object payload. Should match the structure used in the C8Y REST API. The type is derived from `T` via {@link C8yPayloadTypeMap} by default: - `CumulocityObject<'measurement'>` → `C8yMeasurement` - `CumulocityObject<'event'>` → `C8yEvent` - `CumulocityObject<'alarm'>` → `C8yAlarm` - `CumulocityObject<'operation'>` → `C8yOperation` - `CumulocityObject<'managedObject'>` → `C8yManagedObject` The optional second parameter `TPayload` lets you supply a more specific sub-type (e.g. an interface that extends `C8yAlarm` with custom fragments) for full type safety without casting. Defaults to `C8yPayloadTypeMap[T]` so existing code that omits it is completely unaffected."
      },
      {
        "name": "cumulocityType",
        "type": "C8yObjectType",
        "documentation": "Which Cumulocity API type is being modified. This determines which API endpoint will be used. Must match the shape of {@link payload}. Available values: - \"measurement\" - Single measurement or bulk (payload: `{ measurements: [...] }`); source.id is injected automatically - \"event\" - Events from devices - \"alarm\" - Alarm notifications - \"operation\" - Device operations/commands - \"managedObject\" - Inventory/device objects - \"custom\" - Tenant-local microservice call; set {@link targetPath} to `/service/…`"
      },
      {
        "name": "action",
        "type": "C8yObjectAction",
        "documentation": "HTTP method to use for this operation. - \"create\" - POST (create a new object) - \"update\" - PUT (replace an existing object) - \"delete\" - DELETE - \"patch\" - PATCH (partial update) When {@link cumulocityType} is `\"custom\"`, this controls the HTTP verb sent to the tenant-local microservice at {@link targetPath}."
      },
      {
        "name": "targetPath",
        "type": "string",
        "documentation": "Target microservice path used when {@link cumulocityType} is `\"custom\"`. Must start with `/service/` to ensure requests stay within the tenant. The HTTP method is determined by {@link action}."
      },
      {
        "name": "externalSource",
        "type": "ExternalId | ExternalId[] | ExternalSource[]",
        "documentation": "External ID configuration for device resolution. - Use ExternalId[] for simple lookups - Use ExternalSource for advanced device creation scenarios (see {@link ExternalSource} — its extra fields are not yet implemented on the backend) When a Cumulocity message (e.g., operation) is received, this will contain all external IDs for the Cumulocity ID."
      },
      {
        "name": "destination",
        "type": "C8yDestination",
        "documentation": "Destination for the message. Default: \"cumulocity\" - \"cumulocity\" - Send to Cumulocity core - \"iceflow\" - Send to IceFlow for offloading - \"streaming-analytics\" - Send to Streaming Analytics"
      },
      {
        "name": "contextData",
        "type": "{ deviceName?: string; deviceType?: string; processingMode?: \"PERSISTENT\" | \"TRANSIENT\"; attachmentName?: string; attachmentType?: string; attachmentData?: string; deviceFragments?: Record<string, any>; deviceGroups?: string[]; }",
        "documentation": "Context data for device creation. Used when automatically creating new devices."
      },
      {
        "name": "sourceId",
        "type": "string",
        "documentation": "Explicitly set the Cumulocity device ID (sourceId) for this object. When set, this overrides automatic device resolution from externalSource. Useful for routing data to a different device than the one that originated it."
      }
    ],
    "methods": [],
    "documentation": "A Cumulocity action object that can be returned from a Smart Function. Represents a request to create/update/delete data in Cumulocity. The optional type parameter `T` constrains which `cumulocityType` values are allowed, enabling per-function return-type documentation and type checking. Defaults to the full {@link C8yObjectType} union so existing code is unaffected."
  },
  {
    "name": "DeviceMessage",
    "isEnum": false,
    "properties": [
      {
        "name": "payload",
        "type": "Record<string, any> | Uint8Array<ArrayBufferLike>",
        "documentation": "Message payload — either a plain JSON object or a `Uint8Array` of raw bytes. **JSON object (recommended):** Return a plain JavaScript object and the runtime will serialize it to JSON before publishing. No manual serialization needed. **Uint8Array (binary / legacy):** Use when the broker requires raw bytes or a non-JSON encoding (e.g. SparkPlug B protobuf, custom binary protocol). `TextEncoder` / `TextDecoder` are available (GraalJS is started with `js.text-encoding=true`)."
      },
      {
        "name": "topic",
        "type": "string",
        "documentation": "The topic on the transport (e.g., MQTT topic). **Optional when the mapping has a fixed (non-wildcard) publish topic.** If omitted, the runtime falls back to the publish topic configured in the mapping itself — no need to repeat it here. Provide a value when you need to override or dynamically construct the topic (e.g. include the device external ID via `context.getConfig().externalId` or the `_externalId_` placeholder token). Requires the mapping to have `useExternalId` enabled and an `externalIdType` configured when using the external-ID placeholder."
      },
      {
        "name": "transportId",
        "type": "string",
        "documentation": "Identifier for the source/destination transport. Examples: \"mqtt\", \"kafka\", \"opc-ua\" Mandatory unless in thin-edge (when it can be inferred from context)."
      },
      {
        "name": "clientId",
        "type": "string",
        "documentation": "Transport/MQTT client ID. Mandatory unless in thin-edge (when it can be inferred from context)."
      },
      {
        "name": "retain",
        "type": "boolean",
        "documentation": "Set the MQTT retain flag on the outgoing message. When true, the broker retains the last message on the topic for new subscribers."
      },
      {
        "name": "transportFields",
        "type": "{ [key: string]: string; }",
        "documentation": "Dictionary of transport-specific fields/properties/headers. Values must be strings. For Kafka, use \"key\" to define the record key."
      },
      {
        "name": "time",
        "type": "Date",
        "documentation": "Timestamp of the message. For incoming messages, this is set automatically. For outgoing messages, this is optional."
      },
      {
        "name": "externalSource",
        "type": "{ type: string; externalId?: string; }[]",
        "documentation": "External identity descriptor for device/topic resolution. Only `type` is required — `externalId` is resolved from context at runtime (e.g. when using the `_externalId_` placeholder in the topic). Provide `externalId` explicitly when the value is known upfront."
      },
      {
        "name": "action",
        "type": "C8yObjectAction",
        "documentation": "What kind of operation is being performed. Similar to CumulocityObject action field."
      },
      {
        "name": "cumulocityType",
        "type": "C8yObjectType",
        "documentation": "Specifies which Cumulocity API type this device message maps to. Helps determine the target API endpoint. Narrowed by the type parameter `T`. If not specified, the target API is derived from the topic or mapping."
      },
      {
        "name": "sourceId",
        "type": "string",
        "documentation": "Explicitly set the Cumulocity device ID for this message. Overrides automatic device resolution when set."
      }
    ],
    "methods": [],
    "documentation": "A device/broker message that can be returned from a Smart Function. Used primarily in outbound scenarios to send data back to devices/brokers. The optional type parameter `T` narrows the {@link DeviceMessage.cumulocityType} field, documenting which Cumulocity event type this outbound message is derived from. Defaults to the full {@link C8yObjectType} union so existing code is unaffected."
  },
  {
    "name": "ExternalId",
    "isEnum": false,
    "properties": [
      {
        "name": "externalId",
        "type": "string",
        "documentation": "External ID to be looked up (e.g., device serial number)"
      },
      {
        "name": "type",
        "type": "string",
        "documentation": "External ID type (e.g., \"c8y_Serial\", \"c8y_DeviceId\")"
      }
    ],
    "methods": [],
    "documentation": "External identifier used for device lookup. Used to reference devices by their external ID and type."
  },
  {
    "name": "ExternalSource",
    "isEnum": false,
    "properties": [
      {
        "name": "externalId",
        "type": "string",
        "documentation": "External Id to be looked up and/or created to get C8Y \"id\""
      },
      {
        "name": "type",
        "type": "string",
        "documentation": "External ID type (e.g., \"c8y_Serial\")"
      },
      {
        "name": "autoCreateDeviceMO",
        "type": "boolean",
        "documentation": "Whether to automatically create the device managed object if it doesn't exist. Default: true"
      },
      {
        "name": "parentId",
        "type": "string",
        "documentation": "Parent device ID for creating child devices. Used when creating hierarchical device structures."
      },
      {
        "name": "childReference",
        "type": "C8yChildReference",
        "documentation": "Type of child reference when creating a child device. - \"device\": Child device - \"asset\": Child asset - \"addition\": Addition to parent"
      },
      {
        "name": "clientId",
        "type": "string",
        "documentation": "Transport/MQTT client ID. Stored on the managed object for use in outbound messages."
      }
    ],
    "methods": [],
    "documentation": "Details of external Id for advanced device creation scenarios. For simple lookups, use {@link ExternalId} instead."
  },
  {
    "name": "DataPrepContext",
    "isEnum": false,
    "properties": [
      {
        "name": "runtime",
        "type": "string",
        "documentation": "Runtime identifier - \"dynamic-mapper\" for Dynamic Mapper"
      }
    ],
    "methods": [
      {
        "name": "getState",
        "parameters": [
          "key",
          "defaultValue"
        ],
        "returnType": "TValue",
        "documentation": "Retrieves a persisted state value by key. State **persists across message invocations** for the same mapping. Values written by a previous message are available when the next message arrives. State is scoped per tenant + mapping — it is not shared across mappings or tenants. State does not survive a service restart (in-memory only). The optional type parameter `TValue` lets callers annotate the expected value type and avoid `as` casts on the result. Defaults to `any` so existing code that omits the type parameter continues to work unchanged. **Limitation:** Because `TValue` is a method-level generic (not class-level), TypeScript cannot enforce that `getState<T>` and `setState<T>` use the *same* type for the same key across calls. Nothing prevents: ```ts context.setState<string>('count', 'hello'); context.getState<number>('count', 0); // compiles, but wrong ``` For cross-call consistency use {@link SmartFunctionContextV2 } (V2), which declares the full state shape once via the class-level `TState` generic. When `defaultValue` is provided and `TValue` is omitted, TypeScript infers `TValue` from the default — e.g. `getState('count', 0)` returns `number`."
      },
      {
        "name": "setState",
        "parameters": [
          "key",
          "value"
        ],
        "returnType": "void",
        "documentation": "Persists a state value by key. The value is stored in memory and made available to subsequent invocations of the same mapping. State is automatically cleared when the mapping is deleted. For concurrent invocations of the same mapping, last-writer-wins. The optional type parameter `TValue` constrains the stored value type at the call site only — see `getState` for the cross-call consistency limitation. Defaults to `any` so existing code is unaffected."
      }
    ],
    "documentation": "Standard IDP DataPrep context interface. Minimal context with state management only. Dynamic Mapper extends this with additional capabilities. See {@link SmartFunctionContext } for the extended version."
  },
  {
    "name": "SmartFunctionContext",
    "isEnum": false,
    "properties": [
      {
        "name": "runtime",
        "type": "\"dynamic-mapper\"",
        "documentation": "Runtime identifier for Dynamic Mapper"
      }
    ],
    "methods": [
      {
        "name": "getStateAll",
        "parameters": [],
        "returnType": "Record<string, any>",
        "documentation": "Retrieves all state as a single object. Useful for debugging or logging all state at once."
      },
      {
        "name": "getClientId",
        "parameters": [],
        "returnType": "string",
        "documentation": "Retrieves the MQTT client ID or transport client identifier."
      },
      {
        "name": "getExternalId",
        "parameters": [],
        "returnType": "string",
        "documentation": "Returns the resolved external ID of the source device for outbound mappings. Only populated when the mapping has `useExternalId` enabled and a non-empty `externalIdType` configured. Equivalent to `context.getConfig().externalId`."
      },
      {
        "name": "getManagedObject",
        "parameters": [
          "c8ySourceId"
        ],
        "returnType": "TManagedObject",
        "documentation": "Looks up a device from the inventory cache by internal Cumulocity device ID. The optional type parameter `TManagedObject` lets callers declare the exact shape of the returned object and get full type safety on custom fragments without any manual casting. The default is the base {@link C8yManagedObject}, so existing code that omits the type parameter continues to work unchanged."
      },
      {
        "name": "getManagedObjectByExternalId",
        "parameters": [
          "externalId"
        ],
        "returnType": "TManagedObject",
        "documentation": "Looks up a device from the inventory cache by external ID. This is the recommended way to look up devices by their external identifiers. The optional type parameter `TManagedObject` lets callers declare the exact shape of the returned object and get full type safety on custom fragments without any manual casting. The default is the base {@link C8yManagedObject}, so existing code that omits the type parameter continues to work unchanged."
      },
      {
        "name": "getDTMAsset",
        "parameters": [
          "assetId"
        ],
        "returnType": "TAsset",
        "documentation": "Looks up DTM (Digital Twin Manager) Asset properties by asset ID. The optional type parameter `TAsset` lets callers declare the expected asset shape and get full type safety on custom properties without casting. Defaults to {@link C8yManagedObject} so existing code is unaffected. Returns `null` when the asset is not found."
      },
      {
        "name": "getConfig",
        "parameters": [],
        "returnType": "TConfig",
        "documentation": "Retrieves read-only mapping configuration for the current invocation. Contains mapping metadata such as `mappingId`, `mappingName`, `version`, `tenant`, `topic`, `targetAPI`, `debug`, `clientId`, and optional flags like `createNonExistingDevice` or `eventWithAttachment`. This is populated before the Smart Function is called and does **not** persist across invocations (unlike `getState` / `setState`). The optional type parameter `TConfig` lets callers declare the exact shape of the config object for full type safety on known keys. Defaults to `Record<string, any>` so existing code is unaffected."
      },
      {
        "name": "addWarning",
        "parameters": [
          "warning"
        ],
        "returnType": "void",
        "documentation": "Adds a warning message to the processing context. Warnings are collected and surfaced to users for debugging. Use this for non-fatal issues that should be brought to attention (e.g., fallback logic applied, optional field missing). Stored separately from log messages and visible in the Dynamic Mapper UI."
      },
      {
        "name": "logMessage",
        "parameters": [
          "message"
        ],
        "returnType": "void",
        "documentation": "Logs a message to the processing context. Alias for {@link addLogMessage} — prefer `console.log` for general debugging. Log messages are stored internally and surfaced alongside warnings in the UI."
      },
      {
        "name": "addLogMessage",
        "parameters": [
          "message"
        ],
        "returnType": "void",
        "documentation": "Logs a message to the processing context (canonical form)."
      },
      {
        "name": "getStateKeySet",
        "parameters": [],
        "returnType": "string[]",
        "documentation": "Returns all state keys currently stored in the context. Useful for inspecting or iterating over all persisted state keys."
      },
      {
        "name": "clearState",
        "parameters": [],
        "returnType": "void",
        "documentation": "Removes every state entry held for this mapping. Exists on the runtime context (`SmartFunctionContext.clearState`) but was missing here."
      },
      {
        "name": "getTesting",
        "parameters": [],
        "returnType": "boolean",
        "documentation": "Indicates whether this invocation is running inside a test cycle (i.e., triggered from the mapping test UI rather than a live message). Use this to skip side effects (alarms, external API calls) during tests."
      },
      {
        "name": "getState",
        "parameters": [
          "key",
          "defaultValue"
        ],
        "returnType": "TValue",
        "documentation": "Retrieves a persisted state value by key. State **persists across message invocations** for the same mapping. Values written by a previous message are available when the next message arrives. State is scoped per tenant + mapping — it is not shared across mappings or tenants. State does not survive a service restart (in-memory only). The optional type parameter `TValue` lets callers annotate the expected value type and avoid `as` casts on the result. Defaults to `any` so existing code that omits the type parameter continues to work unchanged. **Limitation:** Because `TValue` is a method-level generic (not class-level), TypeScript cannot enforce that `getState<T>` and `setState<T>` use the *same* type for the same key across calls. Nothing prevents: ```ts context.setState<string>('count', 'hello'); context.getState<number>('count', 0); // compiles, but wrong ``` For cross-call consistency use {@link SmartFunctionContextV2 } (V2), which declares the full state shape once via the class-level `TState` generic. When `defaultValue` is provided and `TValue` is omitted, TypeScript infers `TValue` from the default — e.g. `getState('count', 0)` returns `number`."
      },
      {
        "name": "setState",
        "parameters": [
          "key",
          "value"
        ],
        "returnType": "void",
        "documentation": "Persists a state value by key. The value is stored in memory and made available to subsequent invocations of the same mapping. State is automatically cleared when the mapping is deleted. For concurrent invocations of the same mapping, last-writer-wins. The optional type parameter `TValue` constrains the stored value type at the call site only — see `getState` for the cross-call consistency limitation. Defaults to `any` so existing code is unaffected."
      }
    ],
    "documentation": "Dynamic Mapper's enhanced runtime context. Extends standard IDP DataPrepContext with additional capabilities for: - Persistent state across message invocations (per mapping) - Device enrichment/lookups from inventory cache - DTM (Digital Twin Manager) integration ### Persistent state `setState` / `getState` values survive across messages for the same mapping. They are cleared when the mapping is deleted and do not survive a service restart."
  },
  {
    "name": "DynamicMapperDeviceMessage",
    "isEnum": false,
    "properties": [
      {
        "name": "payload",
        "type": "Record<string, any>",
        "documentation": "Pre-deserialized JSON payload. Note: Differs from IDP standard (Uint8Array). Dynamic Mapper automatically deserializes JSON payloads to objects. Use bracket notation to access properties: payload[\"key\"]."
      },
      {
        "name": "topic",
        "type": "string",
        "documentation": "The topic on the transport (e.g., MQTT topic)"
      },
      {
        "name": "clientId",
        "type": "string",
        "documentation": "Transport client ID (e.g., MQTT client ID). Set for inbound messages; null for outbound."
      },
      {
        "name": "sourceId",
        "type": "string",
        "documentation": "Internal Cumulocity device ID of the originating device. Set for outbound messages; null for inbound."
      },
      {
        "name": "cumulocityType",
        "type": "C8yObjectType",
        "documentation": "Lowercase C8y object type string, matching the {@link C8yObjectType} union. Set by the outbound processor; null for inbound messages. Enables discriminant narrowing: `switch (msg.cumulocityType) { ... }`."
      },
      {
        "name": "time",
        "type": "string",
        "documentation": "ISO-8601 timestamp captured when the message was received by the connector. Use as a reliable receive-time fallback when the payload has no timestamp: `var time = payload[\"time\"] ?? msg.time;`"
      },
      {
        "name": "transportId",
        "type": "string",
        "documentation": "Identifier of the connector that delivered this message (e.g. \"my-mqtt-connector\"). Set for inbound messages; null for outbound (C8Y-originated) messages."
      },
      {
        "name": "transportFields",
        "type": "Record<string, string>",
        "documentation": "Transport-specific key/value pairs (e.g. MQTT 5 user properties). For Kafka this carries the consumed record's **key** under `\"key\"` — the record key, not headers. It is delivered here rather than inside {@link payload}, so it never appears in the mapping's source template: `var deviceId = msg.transportFields?.[\"key\"];` Empty map when no transport fields are available."
      }
    ],
    "methods": [],
    "documentation": "Dynamic Mapper's enhanced device message. Note: In IDP standard, DeviceMessage has `payload: Uint8Array`. In Dynamic Mapper, we pre-deserialize JSON payloads to objects for convenience. This interface represents the input message after deserialization."
  },
  {
    "name": "OutputMessage",
    "isEnum": false,
    "properties": [
      {
        "name": "sinkType",
        "type": "string",
        "documentation": "An unique sink type, example: C8Y Core."
      },
      {
        "name": "deviceIdentifier",
        "type": "Record<string, any>",
        "documentation": "The unique device identifier, example: External Id."
      },
      {
        "name": "payload",
        "type": "any",
        "documentation": "The payload of the message."
      },
      {
        "name": "properties",
        "type": "Record<string, any>",
        "documentation": "A map of properties associated with the message."
      }
    ],
    "methods": [],
    "documentation": "Output message to be sent by the flow function."
  },
  {
    "name": "MappingError",
    "isEnum": false,
    "properties": [
      {
        "name": "errorDetails",
        "type": "string[]",
        "documentation": "Array of error detail strings."
      },
      {
        "name": "payload",
        "type": "any",
        "documentation": "Optional payload that resulted in this error."
      }
    ],
    "methods": [],
    "documentation": "Error information for mapping operations."
  },
  {
    "name": "OutboundMessage",
    "isEnum": false,
    "properties": [
      {
        "name": "payload",
        "type": "C8yReceivedPayloadTypeMap[C8yObjectType]",
        "documentation": "The Cumulocity event/measurement/alarm payload, pre-deserialized. Defaults to `C8yPayloadTypeMap[T]` (e.g. `C8yMeasurement` when `T = 'measurement'`), providing type safety for all well-known fields. Pass a more specific sub-type as the second parameter to get full type safety on custom fragments without any casting. All C8y domain types carry a `[fragment: string]: any` index signature, so arbitrary bracket notation continues to work even when `TPayload` is the base type."
      },
      {
        "name": "cumulocityType",
        "type": "C8yObjectType",
        "documentation": "Cumulocity API type of the triggering event, if available."
      },
      {
        "name": "sourceId",
        "type": "string",
        "documentation": "Internal Cumulocity device ID of the originating device, if available."
      },
      {
        "name": "topic",
        "type": "string",
        "documentation": "The mapping's publish topic, as resolved for this message. The runtime hands outbound functions the same `InputMessage` shape it uses inbound (`FlowOutboundProcessor.createInputMessage`), so this is populated — it was simply missing from this interface."
      },
      {
        "name": "time",
        "type": "string",
        "documentation": "ISO-8601 timestamp of when the runtime processed this event."
      }
    ],
    "methods": [],
    "documentation": "Message received by an outbound Smart Function. At runtime the Java backend wraps the Cumulocity platform event (measurement, operation, alarm, etc.) and provides the payload as a pre-deserialized object supporting direct property access using bracket notation. The optional type parameter `T` narrows the `cumulocityType` of the triggering event — useful when a function is dedicated to a specific event type. Defaults to the full {@link C8yObjectType} union so existing code is unaffected."
  },
  {
    "name": "CumulocityAction",
    "isEnum": true,
    "values": [
      "create",
      "update",
      "delete",
      "patch"
    ],
    "documentation": "HTTP verb to use when calling the Cumulocity API or a tenant-local microservice. Used in {@link CumulocityObject.action} and {@link DeviceMessage.action}. - `\"create\"` – POST - `\"update\"` – PUT - `\"delete\"` – DELETE - `\"patch\"` – PATCH The routing target (C8Y Core API vs. microservice) is determined by {@link CumulocityObject.cumulocityType} / {@link DeviceMessage.cumulocityType}, not by this field."
  },
  {
    "name": "CumulocityType",
    "isEnum": true,
    "values": [
      "measurement",
      "event",
      "alarm",
      "operation",
      "managedObject",
      "custom"
    ],
    "documentation": "Cumulocity API object type. Determines which API endpoint is used when processing the object. Used in {@link CumulocityObject.cumulocityType} and {@link DeviceMessage.cumulocityType}. - `\"measurement\"` – single measurement, or bulk when payload contains a `measurements` array. The mapper injects `source.id` automatically and always uses the bulk endpoint (`POST /measurement/measurements` with `Content-Type: application/vnd.com.nsn.cumulocity.measurementcollection+json`). For bulk: set `payload: { measurements: [...] }` without `source` on each entry. - `\"event\"` – POST/PUT/DELETE to `/event/events` - `\"alarm\"` – POST/PUT/DELETE to `/alarm/alarms` - `\"operation\"` – POST/PUT to `/devicecontrol/operations` - `\"managedObject\"` – POST/PUT/DELETE/PATCH to `/inventory/managedObjects` - `\"custom\"` – call a tenant-local microservice; set {@link CumulocityObject.targetPath} or {@link DeviceMessage.topic} to the `/service/…` path. The HTTP method is controlled by {@link C8yObjectAction}."
  },
  {
    "name": "Destination",
    "isEnum": true,
    "values": [
      "cumulocity",
      "iceflow",
      "streaming-analytics"
    ],
    "documentation": "Where a {@link CumulocityObject} is sent. Default `\"cumulocity\"`. - `\"cumulocity\"` — Cumulocity core - `\"iceflow\"` — IceFlow, for offloading - `\"streaming-analytics\"` — Streaming Analytics Mirrors `Destination.java`. Named rather than inlined on {@link CumulocityObject.destination} so the mapping editor's completion provider can be generated from this file — an inline union has no name to generate an entry from."
  },
  {
    "name": "ChildReference",
    "isEnum": true,
    "values": [
      "device",
      "asset",
      "addition"
    ],
    "documentation": "What kind of child relationship to create when {@link ExternalSource} builds a device beneath a parent. Named for the same reason as {@link C8yDestination}."
  }
];
