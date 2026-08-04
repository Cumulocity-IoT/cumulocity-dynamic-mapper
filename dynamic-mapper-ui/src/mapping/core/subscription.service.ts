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
 * @authors Christof Strack
 */

import { Injectable } from '@angular/core';
import { FetchClient, IIdentified } from '@c8y/client';
import { Observable, BehaviorSubject } from 'rxjs';
import {
  BASE_URL,
  PATH_SUBSCRIPTION_ENDPOINT,
  SharedService
} from '../../shared';
import {
  NotificationSubscriptionRequest,
  NotificationSubscriptionResponse,
  SubscriptionStatus
} from '../shared/mapping.model';

// Custom error types for better error handling
export class SubscriptionError extends Error {
  constructor(
    message: string,
    public readonly statusCode?: number,
    public readonly originalError?: any
  ) {
    super(message);
    this.name = 'SubscriptionError';
  }
}

export class ValidationError extends Error {
  constructor(
    message: string,
    public readonly validationErrors: string[] = []
  ) {
    super(message);
    this.name = 'ValidationError';
  }
}

@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {
  // Loading states
  private readonly loadingStates = new Map<string, BehaviorSubject<boolean>>();
  public readonly STATIC_DEVICE_SUBSCRIPTION = "DynamicMapperStaticDeviceSubscription";
  public readonly DYNAMIC_DEVICE_SUBSCRIPTION = "DynamicMapperDynamicDeviceSubscription";

  constructor(
    private readonly client: FetchClient,
    private readonly sharedService: SharedService
  ) { }

  // ===== SUBSCRIPTION CRUD OPERATIONS =====

  /**
   * Updates device-based notification subscription.
   * @param subscription optional subscription name (e.g. STATIC_DEVICE_SUBSCRIPTION or
   *   DYNAMIC_DEVICE_SUBSCRIPTION). When omitted the backend uses its default (static).
   */
  async updateSubscriptionDevice(
    request: NotificationSubscriptionRequest,
    subscription?: string
  ): Promise<NotificationSubscriptionResponse> {
    return this.handleOperation(
      'updateSubscriptionDevice',
      async () => {
        const url = subscription
          ? `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}?subscription=${encodeURIComponent(subscription)}`
          : `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`;
        const response = await this.client.fetch(
          url,
          {
            headers: {
              'content-type': 'application/json'
            },
            body: JSON.stringify(request),
            method: 'PUT'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Updates device group-based notification subscription
   */
  async updateSubscriptionByDeviceGroup(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    return this.handleOperation(
      'updateSubscriptionByDeviceGroup',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/group`,
          {
            headers: {
              'content-type': 'application/json'
            },
            body: JSON.stringify(request),
            method: 'PUT'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Updates device type-based notification subscription
   */
  async updateSubscriptionByDeviceType(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    return this.handleOperation(
      'updateSubscriptionByDeviceType',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/type`,
          {
            headers: {
              'content-type': 'application/json'
            },
            body: JSON.stringify(request),
            method: 'PUT'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Resyncs already-existing devices of a single, already-configured type into the dynamic
   * device subscription. Notification 2.0's tenant-level type filter only fires for devices
   * created after the type was added — this backfills devices that existed before. Runs
   * asynchronously in the background on the server; progress/completion is reported via
   * Service Events, not via this call's response.
   */
  async resyncTypeSubscription(type: string): Promise<void> {
    await this.handleOperation(
      'resyncTypeSubscription',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/type/resync/${encodeURIComponent(type)}`,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'POST'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return;
      }
    );
  }

  /**
   * Creates a new notification subscription
   */
  async createSubscription(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    return this.handleOperation(
      'createSubscription',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
          {
            headers: {
              'content-type': 'application/json'
            },
            body: JSON.stringify(request),
            method: 'POST'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Deletes device notification subscription
   */
  async deleteSubscriptionDevice(device: IIdentified, subscription: string): Promise<void> {
    if (!device?.id) {
      throw new ValidationError('Device ID is required for deletion');
    }

    await this.handleOperation(
      'deleteSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/${device.id}?subscription=${subscription}`,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'DELETE'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return;
      }
    );
  }

  /**
   * Deletes device group notification subscription
   */
  async deleteSubscriptionDeviceGroup(group: IIdentified): Promise<void> {
    if (!group?.id) {
      throw new ValidationError('Group ID is required for deletion');
    }

    await this.handleOperation(
      'deleteSubscriptionDeviceGroup',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/group/${group.id}`,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'DELETE'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return;
      }
    );
  }

  // ===== SUBSCRIPTION READ OPERATIONS =====

  /**
   * Gets device-based notification subscription.
   *
   * Paging is opt-in: when both `currentPage` and `pageSize` are supplied the backend returns
   * only that page (plus paging metadata) — used by the grid's load-more. When they are omitted
   * the backend returns the full list (used by the manage-subscription drawers, which re-commit
   * the complete desired set).
   */
  async getSubscriptionDevice(
    subscription: string,
    currentPage?: number,
    pageSize?: number,
    search?: string
  ): Promise<NotificationSubscriptionResponse | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'getSubscriptionDevice',
      async () => {
        let url = `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}?subscription=${encodeURIComponent(subscription)}`;
        if (currentPage != null && pageSize != null) {
          url += `&currentPage=${currentPage}&pageSize=${pageSize}&withTotalPages=true`;
        }
        if (search) {
          url += `&search=${encodeURIComponent(search)}`;
        }
        const response = await this.client.fetch(
          url,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'GET'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Gets device group-based notification subscription
   */
  async getSubscriptionByDeviceGroup(): Promise<NotificationSubscriptionResponse | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'getSubscriptionByDeviceGroup',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/group`,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'GET'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Gets device type-based notification subscription
   */
  async getSubscriptionByDeviceType(): Promise<NotificationSubscriptionResponse | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'getSubscriptionByDeviceType',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/type`,
          {
            headers: {
              'content-type': 'application/json'
            },
            method: 'GET'
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      }
    );
  }

  /**
   * Gets all subscriptions
   */
  async getAllSubscriptions(): Promise<{
    devices: NotificationSubscriptionResponse | null;
    groups: NotificationSubscriptionResponse | null;
    types: NotificationSubscriptionResponse | null;
  }> {
    const [devices, groups, types] = await Promise.allSettled([
      this.getSubscriptionDevice(this.STATIC_DEVICE_SUBSCRIPTION),
      this.getSubscriptionDevice(this.DYNAMIC_DEVICE_SUBSCRIPTION),
      this.getSubscriptionByDeviceGroup(),
      this.getSubscriptionByDeviceType()
    ]);

    return {
      devices: devices.status === 'fulfilled' ? devices.value : null,
      groups: groups.status === 'fulfilled' ? groups.value : null,
      types: types.status === 'fulfilled' ? types.value : null
    };
  }



  // ===== UTILITY METHODS =====

  /**
   * Creates a subscription response from request (for optimistic updates)
   */
  createOptimisticResponse(
    request: NotificationSubscriptionRequest,
    status: SubscriptionStatus = SubscriptionStatus.PENDING
  ): NotificationSubscriptionResponse {
    return {
      api: request.api,
      subscriptionName: request.subscriptionName,
      devices: request.devices,
      types: request.types,
      status
    };
  }

  /**
   * Checks if specific operation is loading
   */
  isSubscriptionOperationLoading(operationName: string): Observable<boolean> {
    return this.getLoadingSubject(operationName).asObservable();
  }

  // ===== PRIVATE HELPER METHODS =====

  /**
   * Handles subscription operations with error handling and loading states
   */
  private async handleOperation<T>(
    operationName: string,
    operation: () => Promise<T>
  ): Promise<T> {
    const loadingSubject = this.getLoadingSubject(operationName);
    loadingSubject.next(true);

    try {
      const result = await operation();
      return result;
    } catch (error) {
      const subscriptionError = this.handleSubscriptionError(error, operationName);
      throw subscriptionError;
    } finally {
      loadingSubject.next(false);
    }
  }

  /**
   * Gets or creates loading subject for operation
   */
  private getLoadingSubject(operationName: string): BehaviorSubject<boolean> {
    if (!this.loadingStates.has(operationName)) {
      this.loadingStates.set(operationName, new BehaviorSubject<boolean>(false));
    }
    return this.loadingStates.get(operationName)!;
  }

  /**
   * Handles subscription-related errors (updated for FetchClient)
   */
  private handleSubscriptionError(error: any, operationName: string): SubscriptionError {
    let message = `Failed to ${operationName}`;
    let statusCode: number | undefined;

    // Check if it's a fetch response error
    if (error.message && error.message.includes('HTTP')) {
      const match = error.message.match(/HTTP (\d+):/);
      if (match) {
        statusCode = parseInt(match[1]);

        switch (statusCode) {
          case 400:
            message += ': Invalid request data';
            break;
          case 401:
            message += ': Unauthorized access';
            break;
          case 403:
            message += ': Insufficient permissions';
            break;
          case 404:
            message += ': Resource not found or outbound mapping disabled';
            break;
          case 409:
            message += ': Subscription conflict';
            break;
          case 422:
            message += ': Validation failed';
            break;
          case 500:
            message += ': Internal server error';
            break;
          default:
            message += `: HTTP ${statusCode}`;
        }
      }
    } else if (error instanceof ValidationError) {
      throw error; // Re-throw validation errors as-is
    } else {
      message += `: ${error.message || 'Unknown error'}`;
    }

    return new SubscriptionError(message, statusCode, error);
  }
}