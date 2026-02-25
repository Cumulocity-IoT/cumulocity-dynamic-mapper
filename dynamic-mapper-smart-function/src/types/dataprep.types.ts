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
 * IDP DataPrep base type definitions (Cumulocity Standard).
 *
 * These types are compatible with the Cumulocity IDP (IoT Data Plane) DataPrep standard.
 * Dynamic Mapper extends these base types with additional capabilities — see
 * {@link ./smart-function-dynamic-mapper.types} for the extended versions.
 *
 * @module DataPrepTypes
 * @since 6.1.6
 */

// ============================================================================
// IDP DATAPREP BASE TYPES (Cumulocity Standard)
// ============================================================================

/**
 * Standard IDP DataPrep context interface.
 * Minimal context with state management only.
 *
 * Dynamic Mapper extends this with additional capabilities.
 * See {@link SmartFunctionContext} for the extended version.
 */
export interface DataPrepContext {
  /** Runtime identifier - "dynamic-mapper" for Dynamic Mapper */
  readonly runtime: string;

  /**
   * Retrieves a persisted state value by key.
   *
   * State **persists across message invocations** for the same mapping. Values
   * written by a previous message are available when the next message arrives.
   * State is scoped per tenant + mapping — it is not shared across mappings or
   * tenants.
   *
   * State does not survive a service restart (in-memory only).
   *
   * @param key - The state key
   * @param defaultValue - Optional default value if key doesn't exist
   * @returns The state value or default
   *
   * @example
   * // On first message: returns undefined; on subsequent messages: returns prior value
   * const count = (context.getState('messageCount') as number | undefined) || 0;
   */
  getState(key: string, defaultValue?: any): any;

  /**
   * Persists a state value by key.
   *
   * The value is stored in memory and made available to subsequent invocations
   * of the same mapping. State is automatically cleared when the mapping is
   * deleted. For concurrent invocations of the same mapping, last-writer-wins.
   *
   * @param key - The state key
   * @param value - The value to store (primitives, objects, and arrays are supported)
   *
   * @example
   * context.setState('messageCount', count + 1);
   * context.setState('lastTemperature', temperature);
   */
  setState(key: string, value: any): void;
}

// ============================================================================
// EXTERNAL ID TYPES
// ============================================================================

/**
 * External identifier used for device lookup.
 * Used to reference devices by their external ID and type.
 *
 * @example
 * { externalId: "DEVICE-001", type: "c8y_Serial" }
 */
export interface ExternalId {
  /** External ID to be looked up (e.g., device serial number) */
  externalId: string;

  /** External ID type (e.g., "c8y_Serial", "c8y_DeviceId") */
  type: string;
}
