/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 * @authors Christof Strack
 */

/**
 * Device information for subscription
 */
export interface Device {
  /** Unique device identifier */
  id: string;
  /** Human-readable device name */
  name?: string;
  /** Device type classification */
  type?: string;
  /** Names of device groups this device belongs to */
  groups?: string[];
}

/**
 * Request payload for creating or updating notification subscriptions.
 * Either devices OR types should be provided, but not both.
 */
export interface NotificationSubscriptionRequest {
  /** 
   * Cumulocity IoT API type to subscribe to for notifications 
   * @example NotificationAPI.MEASUREMENT
   */
  api: string;

  /** 
   * Optional name for the subscription to help identify it
   * @maxLength 100
   * @example "temperature-sensors"
   */
  subscriptionName?: string;

  /** 
   * List of specific devices to include in the subscription.
   * Child devices will be automatically discovered and included.
   * @maxItems 1000
   * @example [{ id: "12345", name: "Temperature Sensor 01" }]
   */
  devices?: Device[];

  /** 
   * List of device types to monitor dynamically.
   * All devices of these types will be automatically included.
   * @maxItems 50
   * @example ["c8y_TemperatureSensor", "c8y_HumiditySensor"]
   */
  types?: string[];

}


/**
 * Subscription status enumeration
 */
export enum SubscriptionStatus {
  ACTIVE = 'ACTIVE',
  INACTIVE = 'INACTIVE',
  ERROR = 'ERROR',
  PENDING = 'PENDING'
}

/**
 * Response containing notification subscription details and status
 */
export interface NotificationSubscriptionResponse {
  /** Cumulocity IoT API type */
  api?: string;

  /** Name of the subscription */
  subscriptionName?: string;

  /** List of subscribed devices */
  devices?: Device[];

  /** List of subscribed device types */
  types?: string[];

  /** Unique subscription identifier */
  subscriptionId?: string;

  /** Current subscription status */
  status?: SubscriptionStatus;

  /** Ids of devices whose subscription could not be created (status ERROR) */
  failedDeviceIds?: string[];

  /** Pagination metadata; only present when the request opted into paging */
  paging?: NotificationSubscriptionPaging;

}

/**
 * Pagination metadata for a paged device subscription response.
 */
export interface NotificationSubscriptionPaging {
  /** Current page (1-based) */
  currentPage: number;
  /** Items requested per page */
  pageSize: number;
  /** Total pages; only set when withTotalPages was requested */
  totalPages?: number;
  /** Total subscriptions; only set when withTotalPages was requested */
  totalElements?: number;
  /** True when another page can be loaded */
  hasNext: boolean;
}
