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
 * @since 6.2
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
 * @deprecated Use `Record<string, any>` directly. This interface was kept only for
 * backward compatibility. The `.get()` method is a legacy alias for bracket notation.
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
   * Use bracket notation to access properties: payload["key"].
   */
  payload: Record<string, any>;

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
   * Returns the resolved external ID of the source device for outbound mappings.
   *
   * Only populated when the mapping has `useExternalId` enabled and a non-empty
   * `externalIdType` configured. Equivalent to `context.getConfig().externalId`.
   *
   * @returns The resolved external identifier, or undefined if not available
   *
   * @example
   * const externalId = context.getExternalId();
   * return { topic: `measurements/${externalId}`, payload: ... };
   */
  getExternalId(): string | undefined;

  /**
   * Looks up a device from the inventory cache by internal Cumulocity device ID.
   *
   * The optional type parameter `TManagedObject` lets callers declare the exact
   * shape of the returned object and get full type safety on custom fragments
   * without any manual casting. The default is the base {@link C8yManagedObject},
   * so existing code that omits the type parameter continues to work unchanged.
   *
   * @typeParam TManagedObject - Expected managed object shape (defaults to {@link C8yManagedObject})
   * @param c8ySourceId - The internal Cumulocity device ID to look up
   * @returns The managed object from inventory, or null if not found
   *
   * @example Basic (no type parameter — same as before)
   * const device = context.getManagedObject("12345");
   * if (device) {
   *   console.log("Device name:", device.name);
   * }
   *
   * @example Typed — deep properties are fully typed, no casting needed
   * interface MySensor extends C8yManagedObject {
   *   c8y_Sensor: { type: { voltage: boolean; current: boolean } };
   * }
   * const device = context.getManagedObject<MySensor>("12345");
   * const isVoltage: boolean = device?.c8y_Sensor?.type?.voltage ?? false;
   */
  getManagedObject<TManagedObject extends C8yManagedObject = C8yManagedObject>(
    c8ySourceId: string
  ): TManagedObject | null;

  /**
   * Looks up a device from the inventory cache by external ID.
   * This is the recommended way to look up devices by their external identifiers.
   *
   * The optional type parameter `TManagedObject` lets callers declare the exact
   * shape of the returned object and get full type safety on custom fragments
   * without any manual casting. The default is the base {@link C8yManagedObject},
   * so existing code that omits the type parameter continues to work unchanged.
   *
   * @typeParam TManagedObject - Expected managed object shape (defaults to {@link C8yManagedObject})
   * @param externalId - The external ID to look up (with type)
   * @returns The managed object from inventory, or null if not found
   *
   * @example Basic (no type parameter — same as before)
   * const device = context.getManagedObjectByExternalId({
   *   externalId: "SENSOR-001",
   *   type: "c8y_Serial"
   * });
   * if (device) {
   *   console.log("Device:", device.name);
   * }
   *
   * @example Typed — deep properties are fully typed, no casting needed
   * interface MySensor extends C8yManagedObject {
   *   c8y_Sensor: { type: { voltage: boolean; current: boolean } };
   * }
   * const device = context.getManagedObjectByExternalId<MySensor>({
   *   externalId: "SENSOR-001",
   *   type: "c8y_Serial"
   * });
   * const isVoltage: boolean = device?.c8y_Sensor?.type?.voltage ?? false;
   */
  getManagedObjectByExternalId<TManagedObject extends C8yManagedObject = C8yManagedObject>(
    externalId: ExternalId
  ): TManagedObject | null;

  /**
   * Looks up DTM (Digital Twin Manager) Asset properties by asset ID.
   *
   * The optional type parameter `TAsset` lets callers declare the expected asset
   * shape and get full type safety on custom properties without casting.
   * Defaults to {@link C8yManagedObject} so existing code is unaffected.
   * Returns `null` when the asset is not found.
   *
   * @typeParam TAsset - Expected asset shape (defaults to {@link C8yManagedObject})
   * @param assetId - The ID of the asset to look up
   * @returns The asset, or null if not found
   *
   * @example Basic (no type parameter — same as before)
   * const asset = context.getDTMAsset("asset-123");
   * console.log("Asset properties:", asset);
   *
   * @example Typed — custom properties are fully typed, no casting needed
   * interface MyAsset extends C8yManagedObject {
   *   c8y_Location: { lat: number; lng: number };
   * }
   * const asset = context.getDTMAsset<MyAsset>("asset-123");
   * const lat: number = asset?.c8y_Location?.lat ?? 0;
   */
  getDTMAsset<TAsset extends C8yManagedObject = C8yManagedObject>(assetId: string): TAsset | null;

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
   * The optional type parameter `TConfig` lets callers declare the exact shape
   * of the config object for full type safety on known keys. Defaults to
   * `Record<string, any>` so existing code is unaffected.
   *
   * @typeParam TConfig - Expected config shape (defaults to `Record<string, any>`)
   * @returns The mapping configuration object
   *
   * @example Basic (no type parameter — same as before)
   * const config = context.getConfig();
   * console.log("Mapping:", config.mappingName, "Target:", config.targetAPI);
   *
   * @example Typed — known keys are type-safe, no casting needed
   * const config = context.getConfig<{ mappingName: string; externalId: string }>();
   * return { topic: `measurements/${config.externalId}`, ... };
   */
  getConfig<TConfig extends Record<string, any> = Record<string, any>>(): TConfig;

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
 * - `CumulocityObject<'custom'>`      → `payload: Record<string, any>` (arbitrary microservice body)
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
  /** Arbitrary body sent to a tenant-local microservice via {@link CumulocityObject.targetPath}. */
  custom: Record<string, any>;
};

/**
 * HTTP verb to use when calling the Cumulocity API or a tenant-local microservice.
 * Used in {@link CumulocityObject.action} and {@link DeviceMessage.action}.
 *
 * - `"create"` – POST
 * - `"update"` – PUT
 * - `"delete"` – DELETE
 * - `"patch"`  – PATCH
 *
 * The routing target (C8Y Core API vs. microservice) is determined by
 * {@link CumulocityObject.cumulocityType} / {@link DeviceMessage.cumulocityType},
 * not by this field.
 */
export type C8yObjectAction = 'create' | 'update' | 'delete' | 'patch';

/**
 * Cumulocity API object type.
 * Determines which API endpoint is used when processing the object.
 * Used in {@link CumulocityObject.cumulocityType} and {@link DeviceMessage.cumulocityType}.
 *
 * - `"measurement"` – POST/GET to `/measurement/measurements`
 * - `"event"`       – POST/PUT/DELETE to `/event/events`
 * - `"alarm"`       – POST/PUT/DELETE to `/alarm/alarms`
 * - `"operation"`   – POST/PUT to `/devicecontrol/operations`
 * - `"managedObject"` – POST/PUT/DELETE/PATCH to `/inventory/managedObjects`
 * - `"custom"`      – call a tenant-local microservice; set {@link CumulocityObject.targetPath}
 *                     or {@link DeviceMessage.topic} to the `/service/…` path.
 *                     The HTTP method is controlled by {@link C8yObjectAction}.
 */
export type C8yObjectType = 'measurement' | 'event' | 'alarm' | 'operation' | 'managedObject' | 'custom';

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
export interface CumulocityObject<
  T extends C8yObjectType = C8yObjectType,
  TPayload extends C8yPayloadTypeMap[T] = C8yPayloadTypeMap[T]
> {
  /**
   * The Cumulocity API object payload.
   * Should match the structure used in the C8Y REST API.
   *
   * The type is derived from `T` via {@link C8yPayloadTypeMap} by default:
   * - `CumulocityObject<'measurement'>` → `C8yMeasurement`
   * - `CumulocityObject<'event'>`       → `C8yEvent`
   * - `CumulocityObject<'alarm'>`       → `C8yAlarm`
   * - `CumulocityObject<'operation'>`   → `C8yOperation`
   * - `CumulocityObject<'managedObject'>` → `C8yManagedObject`
   *
   * The optional second parameter `TPayload` lets you supply a more specific
   * sub-type (e.g. an interface that extends `C8yAlarm` with custom fragments)
   * for full type safety without casting.  Defaults to `C8yPayloadTypeMap[T]`
   * so existing code that omits it is completely unaffected.
   *
   * @example Custom payload type for an alarm with a threshold fragment
   * interface MyAlarm extends C8yAlarm {
   *   c8y_CustomThreshold: { value: number };
   * }
   * const obj: CumulocityObject<'alarm', MyAlarm> = {
   *   cumulocityType: 'alarm',
   *   action: 'create',
   *   payload: {
   *     type: 'c8y_HighTemp', text: '...', severity: 'MAJOR',
   *     status: 'ACTIVE', time: '...',
   *     c8y_CustomThreshold: { value: 80 },  // ✅ typed, no cast needed
   *   },
   *   externalSource: [{ type: 'c8y_Serial', externalId: 'SENSOR-001' }],
   * };
   *
   * Special notes:
   * - If providing an externalSource, you don't need to provide an "id"
   * - For update APIs, include an "id" field in the payload
   */
  payload: TPayload;

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
   * - "custom" - Tenant-local microservice call; set {@link targetPath} to `/service/…`
   */
  cumulocityType: T;

  /**
   * HTTP method to use for this operation.
   * - "create" - POST (create a new object)
   * - "update" - PUT (replace an existing object)
   * - "delete" - DELETE
   * - "patch"  - PATCH (partial update)
   *
   * When {@link cumulocityType} is `"custom"`, this controls the HTTP verb sent
   * to the tenant-local microservice at {@link targetPath}.
   */
  action: C8yObjectAction;

  /**
   * Target microservice path used when {@link cumulocityType} is `"custom"`.
   * Must start with `/service/` to ensure requests stay within the tenant.
   * The HTTP method is determined by {@link action}.
   *
   * @example "/service/my-microservice/api/process"
   * @since 6.3
   */
  targetPath?: string;

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
   * @since 6.2
   * @example "12345"
   */
  sourceId?: string;
}

/**
 * A device/broker message that can be returned from a Smart Function.
 * Used primarily in outbound scenarios to send data back to devices/brokers.
 *
 * @example
 * // Send a JSON object — no manual serialization needed
 * return {
 *   topic: `measurements/${deviceId}`,
 *   payload: { temperature: 25.5, timestamp: new Date().toISOString() }
 * };
 *
 * @example
 * // Omit topic when the mapping already defines a fixed publish topic
 * return {
 *   payload: { temperature: 25.5 }
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
   * Message payload — either a plain JSON object or a `Uint8Array` of raw bytes.
   *
   * **JSON object (recommended):** Return a plain JavaScript object and the runtime
   * will serialize it to JSON before publishing. No manual serialization needed.
   *
   * **Uint8Array (binary / legacy):** Use when the broker requires raw bytes or a
   * non-JSON encoding (e.g. SparkPlug B protobuf, custom binary protocol).
   * `TextEncoder` / `TextDecoder` are available (GraalJS is started with `js.text-encoding=true`).
   *
   * @example JSON object (preferred)
   * payload: { temperature: 25.5, timestamp: new Date().toISOString() }
   *
   * @example Uint8Array via TextEncoder
   * payload: new TextEncoder().encode(JSON.stringify(myObject))
   */
  payload: Record<string, any> | Uint8Array;

  /**
   * The topic on the transport (e.g., MQTT topic).
   *
   * **Optional when the mapping has a fixed (non-wildcard) publish topic.**
   * If omitted, the runtime falls back to the publish topic configured in the
   * mapping itself — no need to repeat it here.
   *
   * Provide a value when you need to override or dynamically construct the topic
   * (e.g. include the device external ID via `context.getConfig().externalId` or
   * the `_externalId_` placeholder token).
   *
   * Requires the mapping to have `useExternalId` enabled and an `externalIdType`
   * configured when using the external-ID placeholder.
   *
   * @example `measurements/${context.getConfig().externalId}`
   * @example "measurements/12345"
   * @example undefined  // use the topic from the mapping configuration
   */
  topic?: string;

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
   * External identity descriptor for device/topic resolution.
   * Only `type` is required — `externalId` is resolved from context at runtime
   * (e.g. when using the `_externalId_` placeholder in the topic).
   * Provide `externalId` explicitly when the value is known upfront.
   *
   * @example [{ type: "c8y_Serial" }]                            // topic placeholder only
   * @example [{ type: "c8y_Serial", externalId: "DEVICE-001" }]  // explicit lookup
   */
  externalSource?: Array<{ type: string; externalId?: string }>;

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
   * @since 6.2
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
 * The optional second parameter `TPayload` constrains the payload type of the
 * returned objects, enabling full type safety on custom C8y fragments.
 * Defaults to `C8yPayloadTypeMap[T]` so existing code is unaffected.
 *
 * @typeParam T       - Allowed `cumulocityType` values in the returned objects
 * @typeParam TPayload - Expected payload shape (defaults to `C8yPayloadTypeMap[T]`)
 * @param msg - The incoming device message from the broker (pre-deserialized)
 * @param context - Runtime context providing state, config, and device lookups
 * @returns Cumulocity objects (measurements, events, alarms, etc.) or empty array
 *
 * @example
 * // Untyped (any cumulocityType — backward-compatible default)
 * const onMessage: SmartFunctionIn = (msg, context) => { ... };
 *
 * @example
 * // Constrained to type only
 * const onMessage: SmartFunctionIn<'managedObject' | 'event'> = (msg, context) => {
 *   return [{
 *     cumulocityType: "managedObject", // ✅
 *     // cumulocityType: "measurement"  ❌ TypeScript error
 *     action: "create",
 *     payload: { ... },
 *     externalSource: [{ type: "c8y_Serial", externalId: clientId! }]
 *   }];
 * };
 *
 * @example
 * // Constrained to type + custom payload shape
 * interface MyAlarm extends C8yAlarm {
 *   c8y_CustomThreshold: { value: number };
 * }
 * const onMessage: SmartFunctionIn<'alarm', MyAlarm> = (msg, context) => {
 *   return [{
 *     cumulocityType: 'alarm', action: 'create',
 *     payload: { ..., c8y_CustomThreshold: { value: 80 } }, // ✅ typed
 *     externalSource: [{ type: 'c8y_Serial', externalId: 'SENSOR-001' }],
 *   }];
 * };
 */
export type SmartFunctionIn<
  T extends C8yObjectType = C8yObjectType,
  TPayload extends C8yPayloadTypeMap[T] = C8yPayloadTypeMap[T]
> = (
  msg: DynamicMapperDeviceMessage,
  context: SmartFunctionContext
) => Array<CumulocityObject<T, TPayload>> | CumulocityObject<T, TPayload> | [];

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
export interface OutboundMessage<
  T extends C8yObjectType = C8yObjectType,
  TPayload extends C8yPayloadTypeMap[T] = C8yPayloadTypeMap[T]
> {
  /**
   * The Cumulocity event/measurement/alarm payload, pre-deserialized.
   *
   * Defaults to `C8yPayloadTypeMap[T]` (e.g. `C8yMeasurement` when
   * `T = 'measurement'`), providing type safety for all well-known fields.
   * Pass a more specific sub-type as the second parameter to get full type
   * safety on custom fragments without any casting.
   *
   * All C8y domain types carry a `[fragment: string]: any` index signature,
   * so `.get("key")` and arbitrary bracket notation continue to work even
   * when `TPayload` is the base type.
   *
   * @example Typed access to a custom measurement series
   * interface MySteamMeasurement extends C8yMeasurement {
   *   c8y_Steam: { Temperature: { value: number; unit: string } };
   * }
   * const onMessage: SmartFunctionOut<'measurement', MySteamMeasurement> = (msg) => {
   *   const temp: number = msg.payload.c8y_Steam.Temperature.value; // ✅ typed
   * };
   */
  payload: TPayload;

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
 * The optional second parameter `TPayload` narrows `msg.payload` to a specific
 * sub-type of the base C8y domain interface (e.g. a `C8yMeasurement` extension
 * with custom fragments), enabling full type safety without bracket-notation
 * casting.  Defaults to `C8yPayloadTypeMap[T]` so existing code is unaffected.
 *
 * @typeParam T       - Triggering Cumulocity event type(s)
 * @typeParam TPayload - Expected payload shape (defaults to `C8yPayloadTypeMap[T]`)
 * @param msg - The incoming Cumulocity event, wrapped with SmartFunctionPayload access
 * @param context - Runtime context providing state, config, and device lookups
 * @returns Device messages to send to the broker or empty array
 *
 * @example
 * // Untyped (handles any event type — backward-compatible default)
 * const onMessage: SmartFunctionOut = (msg, context) => { ... };
 *
 * @example
 * // Constrained to type only
 * const onMessage: SmartFunctionOut<'measurement'> = (msg, context) => {
 *   // msg.cumulocityType is narrowed to 'measurement'
 *   // msg.payload is C8yMeasurement — known fields are typed, fragments are any
 *   const temp = msg.payload["c8y_TemperatureMeasurement"]?.["T"]?.["value"];
 * };
 *
 * @example
 * // Constrained to type + custom payload shape — no bracket notation needed
 * interface MySteamMeasurement extends C8yMeasurement {
 *   c8y_Steam: { Temperature: { value: number; unit: string } };
 * }
 * const onMessage: SmartFunctionOut<'measurement', MySteamMeasurement> = (msg) => {
 *   const temp: number = msg.payload.c8y_Steam.Temperature.value; // ✅ typed
 *   // JSON object payload — no manual serialization needed
 *   return {
 *     topic: `measurements/${msg.sourceId}`,  // omit topic to use the mapping's fixed topic
 *     payload: { temperature: temp },
 *   };
 * };
 */
export type SmartFunctionOut<
  T extends C8yObjectType = C8yObjectType,
  TPayload extends C8yPayloadTypeMap[T] = C8yPayloadTypeMap[T]
> = (
  msg: OutboundMessage<T, TPayload>,
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
// V2 SMART FUNCTION TYPES
// ============================================================================
//
// V2 introduces three improvements over the V1 positional-generic style:
//
//  1. OutboundMessage as a proper discriminated union — switch (msg.cumulocityType)
//     automatically narrows msg.payload to the matching C8y domain type.
//     This requires the Java runtime to set msg.cumulocityType (done in
//     FlowProcessorOutboundProcessor via InputMessage.cumulocityType).
//
//  2. Object generic on SmartFunctionInV2 / SmartFunctionOutV2 — callers specify
//     only the parts they care about (returns, config, state, input) without
//     worrying about generic parameter order.
//
//  3. SmartFunctionContextV2 — config and state are both typed end-to-end from
//     the object generic, eliminating as-casts on getConfig() and getState().
//
// V1 types are unchanged.  V2 types are purely additive.
// ============================================================================

/**
 * Discriminated union mapping each {@link C8yObjectType} to a concrete outbound
 * message shape.  When `T` is the full union TypeScript distributes the lookup,
 * producing a true discriminated union:
 *
 * ```typescript
 * switch (msg.cumulocityType) {
 *   case 'measurement': msg.payload  // ← C8yMeasurement (auto-narrowed)
 *   case 'alarm':       msg.payload  // ← C8yAlarm (auto-narrowed)
 * }
 * ```
 *
 * The Java runtime populates `msg.cumulocityType` in
 * {@code FlowProcessorOutboundProcessor} so narrowing works at runtime.
 */
export type OutboundMessageByType = {
  [T in C8yObjectType]: {
    /** Pre-deserialized C8y payload, typed to the matching domain interface. */
    payload: C8yPayloadTypeMap[T];
    /**
     * Required in V2 — the Java runtime always sets this for outbound messages.
     * Enables discriminant narrowing without casting.
     */
    cumulocityType: T;
    /** Internal Cumulocity device ID of the originating device, if available. */
    sourceId?: string;
  };
};

/**
 * V2 outbound message type — a proper discriminated union over {@link C8yObjectType}.
 *
 * Defaults to the full union (all C8y types) so existing code is unaffected.
 * Specify `T` to constrain and auto-narrow:
 *
 * @example
 * // msg.payload is narrowed to C8yMeasurement automatically
 * const onMessage: SmartFunctionOutV2<{ input: 'measurement' }> = (msg, context) => {
 *   switch (msg.cumulocityType) {
 *     case 'measurement': {
 *       const temp = msg.payload.c8y_Temperature?.T?.value; // typed
 *     }
 *   }
 * };
 */
export type OutboundMessageV2<T extends C8yObjectType = C8yObjectType> = OutboundMessageByType[T];

/**
 * V2 runtime context — config and state are typed end-to-end via class-level generics.
 *
 * Extends {@link DataPrepContext} so that the base `getState` / `setState` contract
 * is honoured. The type parameters refine those signatures further.
 *
 * @typeParam TConfig - Shape of the mapping config object (defaults to `Record<string, any>`)
 * @typeParam TState  - Shape of the persistent state object (defaults to `Record<string, any>`)
 *
 * @example
 * type MyCtx = SmartFunctionContextV2<
 *   { mappingName: string; externalId: string },
 *   { lastTemperature: number; forwardedCount: number }
 * >;
 * const name: string = context.getConfig().mappingName;     // typed
 * const last: number = context.getState('lastTemperature'); // typed
 */
export interface SmartFunctionContextV2<
  TConfig extends Record<string, any> = Record<string, any>,
  TState extends Record<string, any> = Record<string, any>
> extends DataPrepContext {
  /** Runtime identifier for Dynamic Mapper. */
  readonly runtime: 'dynamic-mapper';

  /**
   * Retrieve a state value by key.
   * Return type is inferred from `TState` — no casting needed.
   */
  getState<TKey extends keyof TState>(key: TKey, defaultValue?: TState[TKey]): TState[TKey];

  /**
   * Persist a state value by key.
   * Value type is enforced by `TState` — wrong types are caught at compile time.
   */
  setState<TKey extends keyof TState>(key: TKey, value: TState[TKey]): void;

  /** Returns the entire state object, typed as `TState`. */
  getStateAll(): TState;

  /** MQTT / transport client identifier. */
  getClientId(): string | undefined;

  /** Resolved external ID of the source device (outbound only). */
  getExternalId(): string | undefined;

  /**
   * Look up a managed object by internal Cumulocity device ID.
   * @typeParam TManagedObject - Expected shape (defaults to {@link C8yManagedObject})
   */
  getManagedObject<TManagedObject extends C8yManagedObject = C8yManagedObject>(
    c8ySourceId: string
  ): TManagedObject | null;

  /**
   * Look up a managed object by external ID.
   * @typeParam TManagedObject - Expected shape (defaults to {@link C8yManagedObject})
   */
  getManagedObjectByExternalId<TManagedObject extends C8yManagedObject = C8yManagedObject>(
    externalId: ExternalId
  ): TManagedObject | null;

  /**
   * Look up a DTM asset by asset ID.
   * @typeParam TAsset - Expected shape (defaults to {@link C8yManagedObject})
   */
  getDTMAsset<TAsset extends C8yManagedObject = C8yManagedObject>(assetId: string): TAsset | null;

  /**
   * Returns the mapping configuration object, typed as `TConfig`.
   * All known keys are type-safe — no casting needed.
   */
  getConfig(): TConfig;

  /** Add a non-fatal warning message visible in the Dynamic Mapper UI. */
  addWarning(warning: string): void;
}

/**
 * V2 inbound Smart Function signature.
 *
 * Uses an object generic so callers can specify only the parts they care about
 * without worrying about parameter order.
 *
 * @typeParam T - Object describing the function contract:
 *   - `input`   — narrows `msg.payload` to a specific type. Two forms are supported:
 *                 1. A {@link C8yObjectType} string (`'operation'`, `'measurement'`, etc.) —
 *                    auto-narrows `msg.payload` to the matching C8y domain type via
 *                    {@link C8yPayloadTypeMap}. Useful when the **Cumulocity API Connector**
 *                    forwards a known C8y event type into the mapper as an inbound message.
 *                 2. A custom `Record<string, any>` interface — use when the broker payload
 *                    has a well-known but non-standard shape (e.g. LoRa uplink objects).
 *                 Defaults to `Record<string, any>` so untyped code is unaffected.
 *   - `returns` — allowed return type(s); can be a tuple for exact order/count enforcement
 *   - `config`  — shape of the mapping config (enables typed `context.getConfig()`)
 *   - `state`   — shape of the persistent state (enables typed `getState`/`setState`)
 *
 * @example Untyped (backward-compatible default)
 * const onMessage: SmartFunctionInV2 = (msg, context) => { ... };
 *
 * @example C8yObjectType string — auto-narrows payload to C8y domain type
 * // Use this when the Cumulocity API Connector forwards a C8y operation as the inbound message.
 * const onMessage: SmartFunctionInV2<{ input: 'operation' }> = (msg, context) => {
 *   // msg.payload is narrowed to C8yOperation — no cast or Zod schema needed
 *   const deviceId = msg.payload.deviceId;   // ✅ typed
 *   const text     = msg.payload.c8y_Command?.text; // ✅ typed via index signature
 *   return [{ cumulocityType: 'operation', action: 'update', payload: { id: msg.payload.id, status: 'SUCCESSFUL' } }];
 * };
 *
 * @example Custom interface — typed broker payload (e.g. LoRa uplink)
 * const onMessage: SmartFunctionInV2<{
 *   input: {
 *     source: { id: string };
 *     c8y_LoriotUplinkRequest: { port: number; data: string; freq: number; EUI: string; dr: string };
 *     time: string;
 *   };
 * }> = (msg, context) => {
 *   const id  = msg.payload.source.id;                        // ✅ typed
 *   const req = msg.payload.c8y_LoriotUplinkRequest;          // ✅ typed
 *   // ...
 * };
 *
 * @example Config only
 * const onMessage: SmartFunctionInV2<{ config: { mappingName: string } }> = (msg, context) => {
 *   console.log(context.getConfig().mappingName); // ✅ typed
 * };
 *
 * @example Tuple return — enforces exactly [measurement, managedObject] in that order
 * const onMessage: SmartFunctionInV2<{
 *   returns: [CumulocityObject<'measurement'>, CumulocityObject<'managedObject'>];
 *   config:  { mappingName: string };
 *   state:   { messageCount: number };
 * }> = (msg, context) => {
 *   context.setState('messageCount', context.getState('messageCount', 0) + 1);
 *   return [ measurementObj, managedObjectObj ]; // TypeScript enforces order + count
 * };
 */
export type SmartFunctionInV2<
  T extends {
    input?: C8yObjectType | Record<string, any>;
    returns?: CumulocityObject | CumulocityObject[];
    config?: Record<string, any>;
    state?: Record<string, any>;
  } = {}
> = (
  msg: Omit<DynamicMapperDeviceMessage, 'payload'> & {
    /**
     * Pre-deserialized message payload, typed via `T['input']`:
     * - `C8yObjectType` string → auto-mapped to the C8y domain interface via {@link C8yPayloadTypeMap}
     * - Custom interface     → used directly
     * - Omitted             → `Record<string, any>` (backward-compatible default)
     */
    payload: T extends { input: infer TInput }
      ? TInput extends C8yObjectType
        ? C8yPayloadTypeMap[TInput]
        : TInput extends Record<string, any>
          ? TInput
          : Record<string, any>
      : Record<string, any>;
  },
  context: SmartFunctionContextV2<
    T extends { config: infer TConfig extends Record<string, any> } ? TConfig : Record<string, any>,
    T extends { state: infer TState extends Record<string, any> } ? TState : Record<string, any>
  >
) => T extends { returns: infer TReturns extends CumulocityObject | CumulocityObject[] }
  ? TReturns
  : CumulocityObject | CumulocityObject[];

/**
 * V2 outbound Smart Function signature.
 *
 * Uses an object generic so callers can specify only the parts they care about
 * without worrying about parameter order.
 *
 * @typeParam T - Object describing the function contract:
 *   - `input`   — the C8y event type triggering this function; narrows `msg.cumulocityType`
 *                 and auto-narrows `msg.payload` to the matching domain type
 *   - `message` — allowed return type(s)
 *   - `config`  — shape of the mapping config
 *   - `state`   — shape of the persistent state
 *
 * @example Untyped (backward-compatible default)
 * const onMessage: SmartFunctionOutV2 = (msg, context) => { ... };
 *
 * @example Fully typed — payload is auto-narrowed, config + state are typed
 * const onMessage: SmartFunctionOutV2<{
 *   input:   'measurement';
 *   config:  { externalId: string };
 *   state:   { forwardedCount: number };
 *   message: DeviceMessage;
 * }> = (msg, context) => {
 *   // msg.cumulocityType is narrowed to 'measurement'
 *   // msg.payload        is narrowed to C8yMeasurement
 *   const count = context.getState('forwardedCount', 0) + 1;
 *   context.setState('forwardedCount', count);
 *   // JSON object payload — no manual serialization needed
 *   // topic is optional: omit it when the mapping defines a fixed publish topic
 *   return {
 *     topic: `measurements/${context.getConfig().externalId}`,
 *     payload: { count },
 *   };
 * };
 */
export type SmartFunctionOutV2<
  T extends {
    message?: DeviceMessage | DeviceMessage[];
    config?: Record<string, any>;
    state?: Record<string, any>;
    input?: C8yObjectType;
  } = {}
> = (
  msg: OutboundMessageV2<
    T extends { input: infer TInput extends C8yObjectType } ? TInput : C8yObjectType
  >,
  context: SmartFunctionContextV2<
    T extends { config: infer TConfig extends Record<string, any> } ? TConfig : Record<string, any>,
    T extends { state: infer TState extends Record<string, any> } ? TState : Record<string, any>
  >
) => T extends { message: infer TMessage extends DeviceMessage | DeviceMessage[] }
  ? TMessage
  : DeviceMessage | DeviceMessage[];

/**
 * V2 Smart Function union — either inbound or outbound.
 * Use the specific V2 types when the direction is known.
 */
export type SmartFunctionV2 = SmartFunctionInV2 | SmartFunctionOutV2;

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
export function createMockPayload(data: Record<string, any>): Record<string, any> {
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
 * Mock outbound message for testing V1 outbound Smart Functions.
 * Creates an OutboundMessage with a pre-deserialized payload
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
 * Mock outbound message for testing V2 outbound Smart Functions.
 *
 * Unlike {@link createMockOutboundMessage}, this helper returns a fully typed
 * {@link OutboundMessageV2} where `cumulocityType` is **required** — mirroring
 * the Java runtime which always sets `msg.cumulocityType` for outbound messages.
 *
 * The type parameter `T` constrains which `C8yObjectType` value is accepted,
 * so the returned object is the exact discriminant branch of the union.
 *
 * @example
 * const mockMsg = createMockOutboundMessageV2(
 *   { source: { id: '12345' }, c8y_TemperatureMeasurement: { T: { value: 25.5, unit: 'C' } } },
 *   'measurement'
 * );
 * // mockMsg.cumulocityType === 'measurement'  (required, not optional)
 * // mockMsg.payload is typed as C8yMeasurement
 */
export function createMockOutboundMessageV2<T extends C8yObjectType>(
  payloadData: C8yPayloadTypeMap[T],
  cumulocityType: T,
  sourceId?: string
): OutboundMessageV2<T> {
  // Type assertion is required: when T is still generic TypeScript evaluates
  // OutboundMessageByType[T] as the intersection of all branches (→ never).
  // The runtime value is correct — it satisfies exactly the T branch.
  return { payload: payloadData, cumulocityType, sourceId } as OutboundMessageV2<T>;
}

/**
 * Mock V2 runtime context for testing V2 Smart Functions.
 * Returns a {@link SmartFunctionContextV2} with fully typed config and state.
 *
 * @typeParam TConfig - Shape of the config object
 * @typeParam TState  - Shape of the state object
 *
 * @example
 * const ctx = createMockRuntimeContextV2<
 *   { externalId: string },
 *   { forwardedCount: number }
 * >({ config: { externalId: 'SENSOR-001' } });
 *
 * const id: string = ctx.getConfig().externalId;          // typed
 * const n:  number = ctx.getState('forwardedCount', 0);   // typed
 */
export function createMockRuntimeContextV2<
  TConfig extends Record<string, any> = Record<string, any>,
  TState extends Record<string, any> = Record<string, any>
>(options: {
  clientId?: string;
  config?: TConfig;
  devices?: Record<string, C8yManagedObject>;
  externalIdMap?: Record<string, C8yManagedObject>;
  dtmAssets?: Record<string, C8yManagedObject>;
}): SmartFunctionContextV2<TConfig, TState> {
  const state = {} as TState;

  return {
    runtime: 'dynamic-mapper',
    setState<TKey extends keyof TState>(key: TKey, value: TState[TKey]) {
      state[key] = value;
    },
    getState<TKey extends keyof TState>(key: TKey, defaultValue?: TState[TKey]): TState[TKey] {
      return (state[key] !== undefined ? state[key] : defaultValue) as TState[TKey];
    },
    getStateAll(): TState {
      return { ...state };
    },
    getConfig(): TConfig {
      return (options.config || {}) as TConfig;
    },
    getClientId() {
      return options.clientId;
    },
    getExternalId() {
      return (options.config as Record<string, any>)?.['externalId'];
    },
    getManagedObject<TManagedObject extends C8yManagedObject = C8yManagedObject>(c8ySourceId: string) {
      return (options.devices?.[c8ySourceId] ?? null) as TManagedObject | null;
    },
    getManagedObjectByExternalId<TManagedObject extends C8yManagedObject = C8yManagedObject>(externalId: ExternalId) {
      const key = `${externalId.externalId}:${externalId.type}`;
      return (options.externalIdMap?.[key] ?? null) as TManagedObject | null;
    },
    getDTMAsset<TAsset extends C8yManagedObject = C8yManagedObject>(assetId: string) {
      return (options.dtmAssets?.[assetId] ?? null) as TAsset | null;
    },
    addWarning(warning: string) {
      console.warn('[MockContextV2]', warning);
    }
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
    getConfig<TConfig extends Record<string, any> = Record<string, any>>() {
      return (options.config || {}) as TConfig;
    },
    getClientId() {
      return options.clientId;
    },
    getExternalId() {
      return options.config?.['externalId'];
    },
    getManagedObject(c8ySourceId: string) {
      return options.devices?.[c8ySourceId] || null;
    },
    getManagedObjectByExternalId(externalId: ExternalId) {
      const key = `${externalId.externalId}:${externalId.type}`;
      return options.externalIdMap?.[key] || null;
    },
    getDTMAsset<TAsset extends C8yManagedObject = C8yManagedObject>(assetId: string) {
      return (options.dtmAssets?.[assetId] ?? null) as TAsset | null;
    },
    addWarning(warning: string) {
      console.warn('[MockContext]', warning);
    }
  };
}
