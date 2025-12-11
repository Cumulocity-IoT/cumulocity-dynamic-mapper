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
import { BehaviorSubject } from 'rxjs';
import {
  BASE_URL,
  PATH_RELATION_ENDPOINT,
  SharedService
} from '../../shared';
import {
  NotificationSubscriptionRequest,
  NotificationSubscriptionResponse,
  SubscriptionStatus
} from '../shared/mapping.model';

// Custom error types for better error handling
export class ClientRelationError extends Error {
  constructor(
    message: string,
    public readonly statusCode?: number,
    public readonly originalError?: any
  ) {
    super(message);
    this.name = 'ClientRelationError';
  }
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
export class ClientRelationService {
  // Configuration
  private readonly config: SubscriptionServiceConfig = {
    enableRetry: true,
    retryAttempts: 3,
    requestTimeout: 30000
  };

  // Loading states
  private readonly loadingStates = new Map<string, BehaviorSubject<boolean>>();

  constructor(
    private readonly client: FetchClient,
    private readonly sharedService: SharedService
  ) {}



  /**
  * Gets all clients
  */
  async getAllClients(): Promise<any | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'getSubscriptionDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/clients`,
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
 * Gets all client relations
 */
  async getAllClientRelations(): Promise<any | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
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

  /**
* Gets all devices for a client
*/
  async getDevicesForClient(clientId: string): Promise<any | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'getDevicesForClient',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/client/${clientId}/devices`,
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
  * Delete all client relations
  */
  async deleteAllClientRelations(): Promise<any | null> {
    const features = await this.sharedService.getFeatures();

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'deleteAllClientRelations',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/relations`,
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

        return await response.json();
      }
    );
  }

  /**
* Clear all client relations
*/
  async deleteClientRelationForDevice(device: string | IIdentified): Promise<any | null> {
    const features = await this.sharedService.getFeatures();
    let deviceId = device;
    if (typeof device === 'object') {
      deviceId = device.id as string;
    }

    if (!features?.outputMappingEnabled) {
      return null;
    }

    return this.handleOperation(
      'removeClientRelationForDevice',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/device/${deviceId}`,
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

        return await response.json();
      }
    );
  }

  /**
   * Updates client-device relations 
   */
  async addOrUpdateClientRelations(
    clientId: string, deviceIds: string[]
  ): Promise<any> {

    return this.handleOperation(
      'addOrUpdateClientRelations',
      async () => {
        const response = await this.client.fetch(
          `${BASE_URL}/${PATH_RELATION_ENDPOINT}/client/${clientId}`,
          {
            headers: {
              'content-type': 'application/json'
            },
            body: JSON.stringify(deviceIds),
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


  // ===== PRIVATE HELPER METHODS =====


  /**
   * Handles subscription operations with error handling, loading states, and retry logic
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
      const subscriptionError = this.handleError(error, operationName);
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
  private handleError(error: any, operationName: string): ClientRelationError {
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
    } else {
      message += `: ${error.message || 'Unknown error'}`;
    }

    return new ClientRelationError(message, statusCode, error);
  }
}