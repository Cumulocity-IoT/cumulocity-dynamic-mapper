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

/**
 * TypeScript type definitions for the Smart Function Runtime API.
 *
 * This module provides Dynamic Mapper extensions on top of the IDP DataPrep standard.
 * Base IDP types (`DataPrepContext`, `ExternalId`) are in {@link ./dataprep.types}.
 *
 * These types provide:
 * - IntelliSense/autocomplete support in IDEs
 * - Static type checking for Smart Functions
 * - Proper types for unit testing and mocking
 * - Documentation of the complete Smart Function API surface
 *
 * @module SmartFunctionRuntime
 * @since 6.1.6
 */

import { DataPrepContext, ExternalId } from './dataprep.types';
export { DataPrepContext, ExternalId };

// ============================================================================
// DYNAMIC MAPPER EXTENDED TYPES
// ============================================================================

/**
 * Represents the payload of a Smart Function message.
 * Supports both object-style access (bracket notation) and Map-like API (.get()).
 *
 * In Dynamic Mapper, payloads are pre-deserialized from JSON for convenience.
 *
 * @example
 * // Object-style access (preferred)
 * const temp = payload["sensorData"]["temp_val"];
 * const messageId = payload["messageId"];
 */
export interface SmartFunctionPayload {
  /**
   * Object-style property access.
   * Allows accessing nested properties using bracket notation.
   */
  [key: string]: any;

  /**
   * Map-like API for accessing payload properties.
   * @deprecated Prefer bracket notation: payload["key"] instead of payload.get("key")
   * @param key - The property key to retrieve
   * @returns The value associated with the key, or undefined if not found
   *
   * @example
   * const messageId = payload["messageId"];
   * const clientId = payload["clientId"];
   */
  get(key: string): any;
}

/**
 * Dynamic Mapper's enhanced device message.
 *
 * Note: In IDP standard, DeviceMessage has `payload: Uint8Array`.
 * In Dynamic Mapper, we pre-deserialize JSON payloads to objects for convenience.
 * This interface represents the input message after deserialization.
 *
 * @example
 * function onMessage(msg: DynamicMapperDeviceMessage, context: SmartFunctionContext) {
 *   const temp = msg.payload.temperature;  // Already parsed!
 *   const topic = msg.topic;
 *   const clientId = msg.clientId;
 * }
 */
export interface DynamicMapperDeviceMessage {
  /**
   * Pre-deserialized JSON payload.
   *
   * Note: Differs from IDP standard (Uint8Array).
   * Dynamic Mapper automatically deserializes JSON payloads to objects.
   */
  payload: SmartFunctionPayload;

  /** The topic on the transport (e.g., MQTT topic) */
  topic: string;

  /** Transport client ID (e.g., MQTT client ID) */
  clientId?: string;

  /** Identifier for the source/destination transport (e.g., "mqtt", "kafka") */
  transportId?: string;

  /** Transport-specific fields/properties/headers */
  transportFields?: { [key: string]: any };

  /** Timestamp of the incoming message */
  time?: Date;
}

/**
 * Dynamic Mapper's enhanced runtime context.
 * Extends standard IDP DataPrepContext with additional capabilities for:
 * - Persistent state across message invocations (per mapping)
 * - Device enrichment/lookups from inventory cache
 * - DTM (Digital Twin Manager) integration
 *
 * ### Persistent state
 * `setState` / `getState` values survive across messages for the same mapping.
 * They are cleared when the mapping is deleted and do not survive a service restart.
 *
 * @example
 * function onMessage(msg: DynamicMapperDeviceMessage, context: SmartFunctionContext) {
 *   // State persists across invocations — messageCount grows with each message
 *   const count = (context.getState("messageCount") as number | undefined) || 0;
 *   context.setState("messageCount", count + 1);
 *
 *   const clientId = context.getClientId();
 *
 *   // Device enrichment (enhanced)
 *   const device = context.getManagedObjectByExternalId({
 *     externalId: clientId!,
 *     type: "c8y_Serial"
 *   });
 * }
 */
export interface SmartFunctionContext extends DataPrepContext {
  /** Runtime identifier for Dynamic Mapper */
  readonly runtime: "dynamic-mapper";

  /**
   * Retrieves all state as a single object.
   * Useful for debugging or logging all state at once.
   *
   * @returns An object containing all state key-value pairs
   *
   * @example
   * console.log("All state:", context.getStateAll());
   */
  getStateAll(): Record<string, any>;

  /**
   * Retrieves the MQTT client ID or transport client identifier.
   *
   * @returns The client ID, or undefined if not available
   *
   * @example
   * const clientId = context.getClientId();
   */
  getClientId(): string | undefined;

  /**
   * Looks up a device from the inventory cache by internal Cumulocity device ID.
   *
   * @param c8ySourceId - The internal Cumulocity device ID to look up
   * @returns The managed object from inventory, or null if not found
   *
   * @example
   * const device = context.getManagedObject("12345");
   * if (device) {
   *   console.log("Device name:", device.name);
   *   console.log("Device type:", device.type);
   * }
   */
  getManagedObject(c8ySourceId: string): C8yManagedObject | null;

  /**
   * Looks up a device from the inventory cache by external ID.
   * This is the recommended way to look up devices by their external identifiers.
   *
   * @param externalId - The external ID to look up (with type)
   * @returns The managed object from inventory, or null if not found
   *
   * @example
   * const device = context.getManagedObjectByExternalId({
   *   externalId: "SENSOR-001",
   *   type: "c8y_Serial"
   * });
   *
   * if (device) {
   *   console.log("Device:", device.name);
   *   // Access custom fragments
   *   const sensorType = device?.c8y_Sensor?.type;
   * }
   */
  getManagedObjectByExternalId(externalId: ExternalId): C8yManagedObject | null;

  /**
   * Looks up DTM (Digital Twin Manager) Asset properties by asset ID.
   *
   * @param assetId - The ID of the asset to look up
   * @returns A record containing the asset properties
   *
   * @example
   * const asset = context.getDTMAsset("asset-123");
   * console.log("Asset properties:", asset);
   */
  getDTMAsset(assetId: string): Record<string, any>;

  /**
   * Retrieves read-only mapping configuration for the current invocation.
   *
   * Contains mapping metadata such as `mappingId`, `mappingName`, `tenant`,
   * `topic`, `targetAPI`, `debug`, `clientId`, and optional flags like
   * `createNonExistingDevice` or `eventWithAttachment`.
   *
   * This is populated before the Smart Function is called and does **not**
   * persist across invocations (unlike `getState` / `setState`).
   *
   * @returns A record containing the mapping configuration
   *
   * @example
   * const config = context.getConfig();
   * console.log("Mapping:", config.mappingName, "Target:", config.targetAPI);
   * if (config.debug) {
   *   console.log("Debug mode active for mapping", config.mappingId);
   * }
   */
  getConfig(): Record<string, any>;

  /**
   * Adds a warning message to the processing context.
   *
   * Warnings are collected and surfaced to users for debugging.
   * Use this for non-fatal issues that should be brought to attention
   * (e.g., fallback logic applied, optional field missing).
   *
   * @param warning - The warning message
   *
   * @example
   * if (!device) {
   *   context.addWarning("Device not found in cache, using implicit creation");
   * }
   */
  addWarning(warning: string): void;
}

// ============================================================================
// CUMULOCITY DOMAIN OBJECT TYPES
// ============================================================================

/**
 * Base interface for Cumulocity objects that have a source device.
 */
export interface C8ySourceReference {
  /** Reference to the source device */
  source?: {
    /** The internal Cumulocity device ID */
    id: string;
    /** Optional self URL */
    self?: string;
  };
}

/**
 * Cumulocity Measurement object.
 * Represents time-series measurement data from devices.
 *
 * @example
 * {
 *   type: "c8y_TemperatureMeasurement",
 *   time: "2025-02-17T10:30:00Z",
 *   c8y_Temperature: {
 *     T: {
 *       value: 25.5,
 *       unit: "C"
 *     }
 *   }
 * }
 */
export interface C8yMeasurement extends C8ySourceReference {
  /** Internal Cumulocity ID (only for updates) */
  id?: string;

  /** Measurement type (e.g., "c8y_TemperatureMeasurement") */
  type: string;

  /** ISO 8601 timestamp of the measurement */
  time: string;

  /**
   * Custom measurement fragments.
   * Each fragment contains series with value and unit.
   *
   * @example
   * c8y_Temperature: {
   *   T: { value: 25.5, unit: "C" }
   * }
   */
  [fragment: string]: any;
}

/**
 * Cumulocity Event object.
 * Represents discrete events from devices.
 *
 * @example
 * {
 *   type: "c8y_LocationUpdate",
 *   text: "Device location updated",
 *   time: "2025-02-17T10:30:00Z",
 *   c8y_Position: {
 *     lat: 51.5074,
 *     lng: -0.1278
 *   }
 * }
 */
export interface C8yEvent extends C8ySourceReference {
  /** Internal Cumulocity ID (only for updates) */
  id?: string;

  /** Event type (e.g., "c8y_LocationUpdate") */
  type: string;

  /** Human-readable event description */
  text: string;

  /** ISO 8601 timestamp of the event */
  time: string;

  /**
   * Custom event fragments.
   * Can include any additional event-specific data.
   */
  [fragment: string]: any;
}

/**
 * Alarm severity levels.
 */
export type C8yAlarmSeverity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING';

/**
 * Alarm status values.
 */
export type C8yAlarmStatus = 'ACTIVE' | 'ACKNOWLEDGED' | 'CLEARED';

/**
 * Cumulocity Alarm object.
 * Represents alarm notifications from devices.
 *
 * @example
 * {
 *   type: "c8y_HighTemperatureAlarm",
 *   text: "Temperature exceeded threshold",
 *   severity: "MAJOR",
 *   status: "ACTIVE",
 *   time: "2025-02-17T10:30:00Z"
 * }
 */
export interface C8yAlarm extends C8ySourceReference {
  /** Internal Cumulocity ID (only for updates) */
  id?: string;

  /** Alarm type (e.g., "c8y_HighTemperatureAlarm") */
  type: string;

  /** Human-readable alarm description */
  text: string;

  /** Alarm severity level */
  severity: C8yAlarmSeverity;

  /** Current alarm status */
  status: C8yAlarmStatus;

  /** ISO 8601 timestamp when the alarm was raised */
  time: string;

  /**
   * Custom alarm fragments.
   * Can include any additional alarm-specific data.
   */
  [fragment: string]: any;
}

/**
 * Operation status values.
 */
export type C8yOperationStatus = 'PENDING' | 'EXECUTING' | 'SUCCESSFUL' | 'FAILED';

/**
 * Cumulocity Operation object.
 * Represents device operations/commands.
 *
 * @example
 * {
 *   deviceId: "12345",
 *   status: "PENDING",
 *   c8y_Restart: {}
 * }
 */
export interface C8yOperation {
  /** Internal Cumulocity ID (only for updates) */
  id?: string;

  /** Target device ID */
  deviceId: string;

  /** Current operation status */
  status: C8yOperationStatus;

  /** Optional description */
  description?: string;

  /**
   * Custom operation fragments.
   * The fragment name indicates the operation type (e.g., c8y_Restart).
   */
  [fragment: string]: any;
}

/**
 * Cumulocity Managed Object.
 * Represents devices, assets, or other inventory objects.
 *
 * @example
 * {
 *   id: "12345",
 *   type: "c8y_Device",
 *   name: "Temperature Sensor",
 *   c8y_IsDevice: {},
 *   c8y_Sensor: {
 *     type: { temperature: true }
 *   }
 * }
 */
export interface C8yManagedObject {
  /** Internal Cumulocity ID */
  id?: string;

  /** Object type (e.g., "c8y_Device") */
  type?: string;

  /** Human-readable object name */
  name?: string;

  /**
   * Custom object fragments.
   * Can include device-specific data, configurations, etc.
   */
  [fragment: string]: any;
}

// ============================================================================
// SMART FUNCTION OUTPUT TYPES
// ============================================================================

/**
 * Maps each {@link C8yObjectType} value to its corresponding C8y domain interface.
 *
 * Used as a lookup table in {@link CumulocityObject} so that the `payload` field
 * is automatically typed when `T` is constrained to a specific object type:
 *
 * - `CumulocityObject<'measurement'>` → `payload: C8yMeasurement`
 * - `CumulocityObject<'event'>`       → `payload: C8yEvent`
 * - `CumulocityObject<'alarm'>`       → `payload: C8yAlarm`
 * - `CumulocityObject<'operation'>`   → `payload: C8yOperation`
 * - `CumulocityObject<'managedObject'>` → `payload: C8yManagedObject`
 *
 * When `T` is the full {@link C8yObjectType} union (the default), TypeScript
 * distributes the conditional type, resulting in the union of all domain interfaces.
 */
export type C8yPayloadTypeMap = {
  measurement: C8yMeasurement;
  event: C8yEvent;
  alarm: C8yAlarm;
  operation: C8yOperation;
  managedObject: C8yManagedObject;
};

/**
 * CRUD action to perform on a Cumulocity object.
 * Used in {@link CumulocityObject.action} and {@link DeviceMessage.action}.
 */
export type C8yObjectAction = 'create' | 'update' | 'delete' | 'patch';

/**
 * Cumulocity API object type.
 * Determines which API endpoint is used when processing the object.
 * Used in {@link CumulocityObject.cumulocityType} and {@link DeviceMessage.cumulocityType}.
 */
export type C8yObjectType = 'measurement' | 'event' | 'alarm' | 'operation' | 'managedObject';

/**
 * Details of external Id for advanced device creation scenarios.
 * For simple lookups, use {@link ExternalId} instead.
 */
export interface ExternalSource {
  /** External Id to be looked up and/or created to get C8Y "id" */
  externalId: string;

  /** External ID type (e.g., "c8y_Serial") */
  type: string;

  /**
   * Whether to automatically create the device managed object if it doesn't exist.
   * Default: true
   */
  autoCreateDeviceMO?: boolean;

  /**
   * Parent device ID for creating child devices.
   * Used when creating hierarchical device structures.
   */
  parentId?: string;

  /**
   * Type of child reference when creating a child device.
   * - "device": Child device
   * - "asset": Child asset
   * - "addition": Addition to parent
   */
  childReference?: 'device' | 'asset' | 'addition';

  /**
   * Transport/MQTT client ID.
   * Stored on the managed object for use in outbound messages.
   */
  clientId?: string;
}

/**
 * A Cumulocity action object that can be returned from a Smart Function.
 * Represents a request to create/update/delete data in Cumulocity.
 *
 * The optional type parameter `T` constrains which `cumulocityType` values are
 * allowed, enabling per-function return-type documentation and type checking.
 * Defaults to the full {@link C8yObjectType} union so existing code is unaffected.
 *
 * @example
 * // Untyped (accepts any cumulocityType — backward-compatible default)
 * const obj: CumulocityObject = { cumulocityType: "measurement", ... };
 *
 * @example
 * // Typed to a specific subset
 * const obj: CumulocityObject<'managedObject' | 'event'> = {
 *   cumulocityType: "managedObject", // ✅
 *   // cumulocityType: "measurement"  ❌ TypeScript error
 *   ...
 * };
 *
 * @example
 * // Create a measurement
 * return [{
 *   cumulocityType: "measurement",
 *   action: "create",
 *   payload: {
 *     type: "c8y_TemperatureMeasurement",
 *     time: new Date().toISOString(),
 *     c8y_Temperature: {
 *       T: { value: 25.5, unit: "C" }
 *     }
 *   },
 *   externalSource: [{ type: "c8y_Serial", externalId: "SENSOR-001" }]
 * }];
 */
export interface CumulocityObject<T extends C8yObjectType = C8yObjectType> {
  /**
   * The Cumulocity API object payload.
   * Should match the structure used in the C8Y REST API.
   *
   * The type is derived from the `T` parameter via {@link C8yPayloadTypeMap}:
   * - `CumulocityObject<'measurement'>` → `C8yMeasurement`
   * - `CumulocityObject<'event'>`       → `C8yEvent`
   * - `CumulocityObject<'alarm'>`       → `C8yAlarm`
   * - `CumulocityObject<'operation'>`   → `C8yOperation`
   * - `CumulocityObject<'managedObject'>` → `C8yManagedObject`
   *
   * When `T` is the default union, the payload accepts any of the domain types.
   *
   * Special notes:
   * - If providing an externalSource, you don't need to provide an "id"
   * - For update APIs, include an "id" field in the payload
   */
  payload: C8yPayloadTypeMap[T];

  /**
   * Which Cumulocity API type is being modified.
   * This determines which API endpoint will be used.
   * Must match the shape of {@link payload}.
   *
   * Available values:
   * - "measurement" - Time-series measurement data
   * - "event" - Events from devices
   * - "alarm" - Alarm notifications
   * - "operation" - Device operations/commands
   * - "managedObject" - Inventory/device objects
   */
  cumulocityType: T;

  /**
   * What kind of operation to perform on this type.
   * - "create" - Create a new object
   * - "update" - Update an existing object
   * - "delete" - Delete an object
   * - "patch" - Partially update an object
   */
  action: C8yObjectAction;

  /**
   * External ID configuration for device resolution.
   *
   * - Use ExternalId[] for simple lookups
   * - Use ExternalSource for advanced device creation scenarios
   *
   * When a Cumulocity message (e.g., operation) is received,
   * this will contain all external IDs for the Cumulocity ID.
   */
  externalSource?: ExternalId[] | ExternalId | ExternalSource[];

  /**
   * Destination for the message.
   * Default: "cumulocity"
   *
   * - "cumulocity" - Send to Cumulocity core
   * - "iceflow" - Send to IceFlow for offloading
   * - "streaming-analytics" - Send to Streaming Analytics
   */
  destination?: 'cumulocity' | 'iceflow' | 'streaming-analytics';

  /**
   * Context data for device creation.
   * Used when automatically creating new devices.
   *
   * Common fields:
   * - deviceName: Name for the new device
   * - deviceType: Type for the new device
   * - processingMode: "PERSISTENT" or "TRANSIENT"
   * - attachmentName/Type/Data: for EVENT attachments
   *
   * @example
   * contextData: {
   *   deviceName: "Temperature Sensor 01",
   *   deviceType: "c8y_Sensor"
   * }
   */
  contextData?: Record<string, string>;

  /**
   * Explicitly set the Cumulocity device ID (sourceId) for this object.
   * When set, this overrides automatic device resolution from externalSource.
   *
   * Useful for routing data to a different device than the one that originated it.
   *
   * @since 6.1.6
   * @example "12345"
   */
  sourceId?: string;
}

/**
 * A device/broker message that can be returned from a Smart Function.
 * Used primarily in outbound scenarios to send data back to devices/brokers.
 *
 * @example
 * // Send a message to a device topic
 * return {
 *   topic: `measurements/${deviceId}`,
 *   payload: new TextEncoder().encode(JSON.stringify({
 *     temperature: 25.5,
 *     timestamp: new Date().toISOString()
 *   }))
 * };
 */

/**
 * The optional type parameter `T` narrows the {@link DeviceMessage.cumulocityType}
 * field, documenting which Cumulocity event type this outbound message is derived
 * from. Defaults to the full {@link C8yObjectType} union so existing code is unaffected.
 *
 * @example
 * // Untyped — any cumulocityType (backward-compatible default)
 * const msg: DeviceMessage = { topic: "out/temp", payload: bytes };
 *
 * @example
 * // Constrained to measurement
 * const msg: DeviceMessage<'measurement'> = {
 *   topic: "out/temp",
 *   payload: bytes,
 *   cumulocityType: "measurement"  // ✅ — "alarm" would be a TypeScript error
 * };
 */
export interface DeviceMessage<T extends C8yObjectType = C8yObjectType> {
  /**
   * Message payload as a Uint8Array.
   * For outbound messages, serialize your data to Uint8Array.
   *
   * @example
   * // Convert string to Uint8Array
   * payload: new TextEncoder().encode(JSON.stringify(myObject))
   */
  payload: Uint8Array;

  /**
   * The topic on the transport (e.g., MQTT topic).
   *
   * Special placeholder: Use `_externalId_` in the topic to automatically
   * reference the external ID of the device. The placeholder will be resolved
   * using the externalId type specified in the `externalSource` field.
   *
   * @example "measurements/_externalId_"
   * @example "measurements/12345"
   */
  topic: string;

  /**
   * Identifier for the source/destination transport.
   * Examples: "mqtt", "kafka", "opc-ua"
   *
   * Mandatory unless in thin-edge (when it can be inferred from context).
   */
  transportId?: string;

  /**
   * Transport/MQTT client ID.
   * Mandatory unless in thin-edge (when it can be inferred from context).
   */
  clientId?: string;

  /**
   * Set the MQTT retain flag on the outgoing message.
   * When true, the broker retains the last message on the topic for new subscribers.
   *
   * @example true   // retain last message on topic
   * @example false  // do not retain (default)
   */
  retain?: boolean;

  /**
   * Dictionary of transport-specific fields/properties/headers.
   *
   * Values must be strings. For Kafka, use "key" to define the record key.
   *
   * @example { "key": "device-123" }
   * @example { "qos": "1", "messageExpiryInterval": "3600" }
   */
  transportFields?: { [key: string]: string };

  /**
   * Timestamp of the message.
   * For incoming messages, this is set automatically.
   * For outgoing messages, this is optional.
   */
  time?: Date;

  /**
   * External source configuration for resolving the `_externalId_` placeholder.
   * Defines which external ID type should be used to lookup the device.
   *
   * @example [{ type: "c8y_Serial" }]
   */
  externalSource?: Array<{ type: string }>;

  /**
   * What kind of operation is being performed.
   * Similar to CumulocityObject action field.
   */
  action?: C8yObjectAction;

  /**
   * Specifies which Cumulocity API type this device message maps to.
   * Helps determine the target API endpoint.
   * Narrowed by the type parameter `T`.
   *
   * If not specified, the target API is derived from the topic or mapping.
   */
  cumulocityType?: T;

  /**
   * Explicitly set the Cumulocity device ID for this message.
   * Overrides automatic device resolution when set.
   *
   * @since 6.1.6
   * @example "12345"
   */
  sourceId?: string;
}

// ============================================================================
// SMART FUNCTION TYPES
// ============================================================================

/**
 * Inbound Smart Function signature.
 *
 * Processes incoming device messages from the broker and returns Cumulocity objects
 * to be sent to the Cumulocity platform.
 *
 * The optional type parameter `T` constrains which `cumulocityType` values the
 * returned {@link CumulocityObject} array may contain. Defaults to the full
 * {@link C8yObjectType} union so existing code is unaffected.
 *
 * @param msg - The incoming device message from the broker (pre-deserialized)
 * @param context - Runtime context providing state, config, and device lookups
 * @returns Cumulocity objects (measurements, events, alarms, etc.) or empty array
 *
 * @example
 * // Untyped (any cumulocityType — backward-compatible default)
 * const onMessage: SmartFunctionIn = (msg, context) => { ... };
 *
 * @example
 * // Constrained: only 'managedObject' and 'event' are valid return types
 * const onMessage: SmartFunctionIn<'managedObject' | 'event'> = (msg, context) => {
 *   return [{
 *     cumulocityType: "managedObject", // ✅
 *     // cumulocityType: "measurement"  ❌ TypeScript error
 *     action: "create",
 *     payload: { ... },
 *     externalSource: [{ type: "c8y_Serial", externalId: clientId! }]
 *   }];
 * };
 */
export type SmartFunctionIn<T extends C8yObjectType = C8yObjectType> = (
  msg: DynamicMapperDeviceMessage,
  context: SmartFunctionContext
) => Array<CumulocityObject<T>> | CumulocityObject<T> | [];

/**
 * Message received by an outbound Smart Function.
 *
 * At runtime the Java backend wraps the Cumulocity platform event (measurement,
 * operation, alarm, etc.) in the same `DeviceMessage` Java class used for inbound
 * messages. This means `payload` is always a {@link SmartFunctionPayload} that supports
 * direct property access using bracket notation.
 *
 * The optional type parameter `T` narrows the `cumulocityType` of the triggering
 * event — useful when a function is dedicated to a specific event type.
 * Defaults to the full {@link C8yObjectType} union so existing code is unaffected.
 *
 * @example
 * // Constrained to measurement events only
 * const onMessage: SmartFunctionOut<'measurement'> = (msg, context) => {
 *   // msg.cumulocityType is narrowed to 'measurement'
 *   const temp = msg.payload["c8y_TemperatureMeasurement"]?.T?.value;
 *   ...
 * };
 */
export interface OutboundMessage<T extends C8yObjectType = C8yObjectType> {
  /**
   * The Cumulocity event/measurement/alarm payload, pre-deserialized.
   * Access properties using bracket notation: payload["key"].
   */
  payload: SmartFunctionPayload;

  /** Cumulocity API type of the triggering event, if available. */
  cumulocityType?: T;

  /** Internal Cumulocity device ID of the originating device, if available. */
  sourceId?: string;
}

/**
 * Outbound Smart Function signature.
 *
 * Processes a Cumulocity platform event and returns device messages
 * to be sent to the broker.
 *
 * The `msg.payload` is a {@link SmartFunctionPayload} — the same accessor type used
 * in inbound functions — so both property access and `.get()` work without casting.
 *
 * The optional type parameter `T` narrows `msg.cumulocityType` to the specified
 * event type(s), documenting which Cumulocity events this function handles.
 * Defaults to the full {@link C8yObjectType} union so existing code is unaffected.
 *
 * @param msg - The incoming Cumulocity event, wrapped with SmartFunctionPayload access
 * @param context - Runtime context providing state, config, and device lookups
 * @returns Device messages to send to the broker or empty array
 *
 * @example
 * // Untyped (handles any event type — backward-compatible default)
 * const onMessage: SmartFunctionOut = (msg, context) => { ... };
 *
 * @example
 * // Constrained: only handles 'measurement' outbound events
 * const onMessage: SmartFunctionOut<'measurement'> = (msg, context) => {
 *   // msg.cumulocityType is narrowed to 'measurement'
 *   const sourceId = msg.payload["source"]["id"];
 *
 *   return {
 *     topic: `measurements/${sourceId}`,
 *     payload: new TextEncoder().encode(JSON.stringify({
 *       temp: msg.payload["c8y_TemperatureMeasurement"]?.["T"]?.["value"],
 *       timestamp: new Date().toISOString()
 *     }))
 *   };
 * };
 */
export type SmartFunctionOut<T extends C8yObjectType = C8yObjectType> = (
  msg: OutboundMessage<T>,
  context: SmartFunctionContext
) => Array<DeviceMessage> | DeviceMessage | [];

/**
 * Smart Function signature (union of inbound and outbound).
 *
 * A Smart Function can be either:
 * - **SmartFunctionIn**: Processes device messages from broker → Cumulocity objects
 * - **SmartFunctionOut**: Processes Cumulocity objects → device messages to broker
 *
 * Use the specific types (SmartFunctionIn or SmartFunctionOut) for better type safety
 * when the direction is known.
 */
export type SmartFunction = SmartFunctionIn | SmartFunctionOut;

// ============================================================================
// FLOW FUNCTION TYPES
// ============================================================================

/**
 * Input message received by the flow function.
 */
export interface InputMessage {
  /** An unique source path, example: MQTT Topic. */
  sourcePath: string;

  /** The source id, example: MQTT client id. */
  sourceId: string;

  /** The payload of the message. */
  payload: any;

  /** A map of properties associated with the message. */
  properties: Record<string, any>;
}

/**
 * Output message to be sent by the flow function.
 */
export interface OutputMessage {
  /** An unique sink type, example: C8Y Core. */
  sinkType: string;

  /** The unique device identifier, example: External Id. */
  deviceIdentifier?: Record<string, any>;

  /** The payload of the message. */
  payload: any;

  /** A map of properties associated with the message. */
  properties: Record<string, any>;
}

/**
 * Error information for mapping operations.
 */
export interface MappingError {
  /** Array of error detail strings. */
  errorDetails: string[];

  /** Optional payload that resulted in this error. */
  payload?: any;
}

// ============================================================================
// HELPER TYPES FOR TESTING
// ============================================================================

/**
 * Mock payload for testing Smart Functions.
 * Implements both object-style and Map-like access.
 *
 * @example
 * const mockPayload = createMockPayload({
 *   messageId: "msg-123",
 *   temperature: 25.5,
 *   sensorData: { temp_val: 30.0 }
 * });
 */
export function createMockPayload(data: Record<string, any>): SmartFunctionPayload {
  return {
    ...data,
    get(key: string) {
      return data[key];
    }
  };
}

/**
 * Mock input message for testing Smart Functions.
 * Creates a DynamicMapperDeviceMessage with pre-deserialized payload.
 *
 * @example
 * const mockMsg = createMockInputMessage({
 *   messageId: "msg-123",
 *   temperature: 25.5
 * }, "device/temp/data", "client-123");
 */
export function createMockInputMessage(
  payloadData: Record<string, any>,
  topic: string = "test/topic",
  clientId?: string
): DynamicMapperDeviceMessage {
  const payload = createMockPayload(payloadData);

  return {
    payload,
    topic,
    clientId,
    transportId: "mqtt",
    time: new Date()
  };
}

/**
 * Mock outbound message for testing outbound Smart Functions.
 * Creates an OutboundMessage with a pre-deserialized SmartFunctionPayload
 * that supports both bracket access and .get().
 *
 * @example
 * const mockMsg = createMockOutboundMessage({
 *   source: { id: '12345' },
 *   c8y_TemperatureMeasurement: { T: { value: 25.5, unit: 'C' } }
 * }, 'measurement');
 */
export function createMockOutboundMessage(
  payloadData: Record<string, any>,
  cumulocityType?: C8yObjectType,
  sourceId?: string
): OutboundMessage {
  return {
    payload: createMockPayload(payloadData),
    cumulocityType,
    sourceId
  };
}

/**
 * Mock runtime context for testing Smart Functions.
 * Creates a SmartFunctionContext with all enhanced capabilities.
 *
 * @example
 * const mockContext = createMockRuntimeContext({
 *   clientId: "client-123",
 *   devices: {
 *     "12345": { id: "12345", name: "Test Device", type: "c8y_Device" }
 *   },
 *   externalIdMap: {
 *     "SENSOR-001:c8y_Serial": { id: "12345", name: "Test Device" }
 *   }
 * });
 */
export function createMockRuntimeContext(options: {
  clientId?: string;
  config?: Record<string, any>;
  devices?: Record<string, any>;
  externalIdMap?: Record<string, any>;
  dtmAssets?: Record<string, any>;
}): SmartFunctionContext {
  const state: Record<string, any> = {};

  return {
    runtime: "dynamic-mapper",
    setState(key: string, value: any) {
      state[key] = value;
    },
    getState(key: string, defaultValue?: any) {
      return state[key] ?? defaultValue;
    },
    getStateAll() {
      return { ...state };
    },
    getConfig() {
      return options.config || {};
    },
    getClientId() {
      return options.clientId;
    },
    getManagedObject(c8ySourceId: string) {
      return options.devices?.[c8ySourceId] || null;
    },
    getManagedObjectByExternalId(externalId: ExternalId) {
      const key = `${externalId.externalId}:${externalId.type}`;
      return options.externalIdMap?.[key] || null;
    },
    getDTMAsset(assetId: string) {
      return options.dtmAssets?.[assetId] || {};
    },
    addWarning(warning: string) {
      console.warn('[MockContext]', warning);
    }
  };
}
