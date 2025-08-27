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

import { Injectable, OnDestroy } from '@angular/core';
import { FetchClient, IIdentified } from '@c8y/client';
import { Observable, BehaviorSubject, Subject } from 'rxjs';
import {
  BASE_URL,
  PATH_SUBSCRIPTION_ENDPOINT,
  PATH_RELATION_ENDPOINT,
  SharedService
} from '../../shared';
import {
  NotificationSubscriptionRequest,
  NotificationSubscriptionResponse,
  SubscriptionStatus,
  Device
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

// Subscription types enum
export enum SubscriptionType {
  DEVICE = 'device',
  GROUP = 'group',
  TYPE = 'type'
}

// Service configuration interface
interface SubscriptionServiceConfig {
  enableRetry: boolean;
  retryAttempts: number;
  requestTimeout: number;
}

@Injectable({
  providedIn: 'root'
})
export class SubscriptionService implements OnDestroy {
  // Configuration
  private readonly config: SubscriptionServiceConfig = {
    enableRetry: true,
    retryAttempts: 3,
    requestTimeout: 30000
  };

  // Loading states
  private readonly loadingStates = new Map<string, BehaviorSubject<boolean>>();
  private readonly unsubscribe$ = new Subject<void>();

  constructor(
    private readonly client: FetchClient,
    private readonly sharedService: SharedService
  ) { }

  ngOnDestroy(): void {
    this.unsubscribe$.next();
    this.unsubscribe$.complete();
    this.loadingStates.clear();
  }

  // ===== SUBSCRIPTION CRUD OPERATIONS =====

  /**
   * Updates device-based notification subscription
   */
  async updateSubscriptionDevice(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    // this.validateSubscriptionRequest(request, SubscriptionType.DEVICE);

    return this.handleSubscriptionOperation(
      'updateSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
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
    // this.validateSubscriptionRequest(request, SubscriptionType.GROUP);

    return this.handleSubscriptionOperation(
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
    // this.validateSubscriptionRequest(request, SubscriptionType.TYPE);

    return this.handleSubscriptionOperation(
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
   * Creates a new notification subscription
   */
  async createSubscription(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    // this.validateSubscriptionRequest(request);

    return this.handleSubscriptionOperation(
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
  async deleteSubscriptionDevice(device: IIdentified): Promise<void> {
    if (!device?.id) {
      throw new ValidationError('Device ID is required for deletion');
    }

    await this.handleSubscriptionOperation(
      'deleteSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/${device.id}`,
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

    await this.handleSubscriptionOperation(
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
   * Gets device-based notification subscription
   */
  async getSubscriptionDevice(): Promise<NotificationSubscriptionResponse | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleSubscriptionOperation(
      'getSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
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

    return this.handleSubscriptionOperation(
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

    return this.handleSubscriptionOperation(
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
      this.getSubscriptionDevice(),
      this.getSubscriptionByDeviceGroup(),
      this.getSubscriptionByDeviceType()
    ]);

    return {
      devices: devices.status === 'fulfilled' ? devices.value : null,
      groups: groups.status === 'fulfilled' ? groups.value : null,
      types: types.status === 'fulfilled' ? types.value : null
    };
  }


    /**
   * Gets all client mappings
   */
  async getAllClientRelations(): Promise<any | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleSubscriptionOperation(
      'getSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/client`,
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
   * Validates subscription request based on type
   */
  private validateSubscriptionRequest(
    request: NotificationSubscriptionRequest,
    type?: SubscriptionType
  ): void {
    const errors: string[] = [];

    if (!request) {
      throw new ValidationError('Subscription request is required');
    }

    if (!request.api) {
      errors.push('API type is required');
    }

    if (request.subscriptionName && request.subscriptionName.length > 100) {
      errors.push('Subscription name must not exceed 100 characters');
    }

    // Validate based on subscription type
    switch (type) {
      case SubscriptionType.DEVICE:
        if (!request.devices || request.devices.length === 0) {
          errors.push('At least one device must be specified for device subscription');
        }
        if (request.devices && request.devices.length > 1000) {
          errors.push('Cannot subscribe to more than 1000 devices at once');
        }
        this.validateDevices(request.devices, errors);
        break;

      case SubscriptionType.TYPE:
        if (!request.types || request.types.length === 0) {
          errors.push('At least one device type must be specified for type subscription');
        }
        if (request.types && request.types.length > 50) {
          errors.push('Cannot subscribe to more than 50 device types at once');
        }
        this.validateTypes(request.types, errors);
        break;

      case SubscriptionType.GROUP:
        if (!request.devices || request.devices.length === 0) {
          errors.push('At least one device group must be specified');
        }
        this.validateDevices(request.devices, errors);
        break;

      default:
        // General validation - must have either devices or types
        const hasDevices = request.devices && request.devices.length > 0;
        const hasTypes = request.types && request.types.length > 0;

        if (!hasDevices && !hasTypes) {
          errors.push('Either devices or types must be specified');
        }

        if (hasDevices && hasTypes) {
          errors.push('Cannot specify both devices and types in the same subscription');
        }
        break;
    }

    if (errors.length > 0) {
      throw new ValidationError('Invalid subscription request', errors);
    }
  }

  /**
   * Validates device list
   */
  private validateDevices(devices: Device[] | undefined, errors: string[]): void {
    if (!devices) return;

    devices.forEach((device, index) => {
      if (!device.id || device.id.trim() === '') {
        errors.push(`Device at index ${index} must have a valid ID`);
      }
    });
  }

  /**
   * Validates device types list
   */
  private validateTypes(types: string[] | undefined, errors: string[]): void {
    if (!types) return;

    types.forEach((type, index) => {
      if (!type || type.trim() === '') {
        errors.push(`Type at index ${index} cannot be empty`);
      }
    });
  }

  /**
   * Handles subscription operations with error handling, loading states, and retry logic
   */
  private async handleSubscriptionOperation<T>(
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