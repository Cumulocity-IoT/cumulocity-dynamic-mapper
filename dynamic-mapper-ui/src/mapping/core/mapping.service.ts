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

import { inject, Injectable, OnDestroy } from '@angular/core';
import {
  FetchClient,
  IFetchResponse,
  IIdentified,
  InventoryService,
  QueriesUtil
} from '@c8y/client';
import {
  Observable,
  Subject,
  combineLatest,
  map,
  shareReplay,
  switchMap,
  take,
  BehaviorSubject,
  filter,
  takeUntil
} from 'rxjs';
import {
  BASE_URL,
  PATH_SUBSCRIPTIONS_ENDPOINT,
  PATH_SUBSCRIPTION_ENDPOINT,
  Direction,
  SharedService,
  MappingEnriched,
  MappingTypeDescriptionMap,
  Operation,
  DeploymentMapEntryDetailed,
  PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT,
  DeploymentMapEntry,
  PATH_DEPLOYMENT_DEFINED_ENDPOINT,
  Mapping,
  PATH_MAPPING_ENDPOINT,
  MappingType,
  LoggingEventTypeMap,
  LoggingEventType
} from '../../shared';
import { JSONProcessorInbound } from './processor/impl/json-processor-inbound.service';
import { JSONProcessorOutbound } from './processor/impl/json-processor-outbound.service';
import { CodeBasedProcessorOutbound } from './processor/impl/code-based-processor-outbound.service';
import { CodeBasedProcessorInbound } from './processor/impl/code-based-processor-inbound.service';
import {
  NotificationSubscriptionRequest,
  NotificationSubscriptionResponse,
  SubscriptionStatus,
  Device
} from '../shared/mapping.model';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';
import { ProcessingContext, ProcessingType, SubstituteValue } from './processor/processor.model';

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
export class MappingService implements OnDestroy {
  // Configuration
  private readonly config: SubscriptionServiceConfig = {
    enableRetry: true,
    retryAttempts: 3,
    requestTimeout: 30000
  };

  // Loading states
  private readonly loadingStates = new Map<string, BehaviorSubject<boolean>>();

  // Core dependencies
  private readonly eventRealtimeService: EventRealtimeService;
  private readonly queriesUtil: QueriesUtil;

  // Observables and subjects
  private readonly updateMappingEnriched$ = new Subject<MappingEnriched>();
  private readonly unsubscribe$ = new Subject<void>();

  mappingsOutboundEnriched$: Observable<MappingEnriched[]>;
  mappingsInboundEnriched$: Observable<MappingEnriched[]>;
  readonly reloadInbound$: Subject<void>;
  readonly reloadOutbound$: Subject<void>;

  // Cache
  private _agentId: string;
  private readonly JSONATA = require('jsonata');

  constructor(
    private readonly inventory: InventoryService,
    private readonly jsonProcessorInbound: JSONProcessorInbound,
    private readonly jsonProcessorOutbound: JSONProcessorOutbound,
    private readonly codeBasedProcessorOutbound: CodeBasedProcessorOutbound,
    private readonly codeBasedProcessorInbound: CodeBasedProcessorInbound,
    private readonly sharedService: SharedService,
    private readonly client: FetchClient
  ) {
    this.eventRealtimeService = new EventRealtimeService(inject(RealtimeSubjectService));
    this.queriesUtil = new QueriesUtil();
    this.reloadInbound$ = this.sharedService.reloadInbound$;
    this.reloadOutbound$ = this.sharedService.reloadOutbound$;
    this.initializeMappingsEnriched();
  }

  ngOnDestroy(): void {
    this.unsubscribe$.next();
    this.unsubscribe$.complete();
    this.loadingStates.clear();
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
    }
  }

  // ===== SUBSCRIPTION METHODS (CONVERTED TO FETCHCLIENT) =====

  /**
   * Updates device-based notification subscription
   */
  async updateSubscriptionDevice(
    request: NotificationSubscriptionRequest
  ): Promise<NotificationSubscriptionResponse> {
    this.validateSubscriptionRequest(request, SubscriptionType.DEVICE);

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
    this.validateSubscriptionRequest(request, SubscriptionType.GROUP);

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
    this.validateSubscriptionRequest(request, SubscriptionType.TYPE);

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
    this.validateSubscriptionRequest(request);

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
          `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}`,
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
          `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}/group`,
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
          `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}/type`,
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

  // ===== UTILITY METHODS =====

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
   * Checks if specific operation is loading
   */
  isSubscriptionOperationLoading(operationName: string): Observable<boolean> {
    return this.getLoadingSubject(operationName).asObservable();
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
      status,
      createdAt: new Date(),
      updatedAt: new Date()
    };
  }

  // ===== EXISTING METHODS (keeping the rest of your methods unchanged) =====

  async changeActivationMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.ACTIVATE_MAPPING,
      parameter
    });
  }

  async addSampleMappings(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.ADD_SAMPLE_MAPPINGS,
      parameter
    });
  }

  listenToUpdateMapping(): Observable<MappingEnriched> {
    return this.updateMappingEnriched$;
  }

  initiateUpdateMapping(mapping: MappingEnriched): void {
    this.updateMappingEnriched$.next(mapping);
  }

  async changeDebuggingMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.DEBUG_MAPPING,
      parameter
    });
  }

  async changeSnoopStatusMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.SNOOP_MAPPING,
      parameter
    });
  }

  async resetSnoop(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.SNOOP_RESET,
      parameter
    });
  }

  async updateTemplate(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation({
      operation: Operation.COPY_SNOOPED_SOURCE_TEMPLATE,
      parameter
    });
  }

  resetCache(): void {
    // Implementation as needed
  }

  private initializeMappingsEnriched(): void {
    this.mappingsInboundEnriched$ = this.reloadInbound$.pipe(
      switchMap(() =>
        combineLatest([
          this.getMappings(Direction.INBOUND),
          this.getEffectiveDeploymentMap()
        ])
      ),
      map(([mappings, mappingsDeployed]) => {
        return mappings.map(mapping => ({
          id: mapping.id,
          mapping,
          snoopSupported:
            MappingTypeDescriptionMap[mapping.mappingType]?.properties[
              Direction.INBOUND
            ].snoopSupported,
          connectors: mappingsDeployed[mapping.identifier]
        }));
      }),
      shareReplay(1)
    );

    this.mappingsOutboundEnriched$ = this.reloadOutbound$.pipe(
      switchMap(() =>
        combineLatest([
          this.getMappings(Direction.OUTBOUND),
          this.getEffectiveDeploymentMap()
        ])
      ),
      map(([mappings, mappingsDeployed]) => {
        return mappings?.map(mapping => ({
          id: mapping.id,
          mapping,
          snoopSupported:
            MappingTypeDescriptionMap[mapping.mappingType]?.properties[
              Direction.OUTBOUND
            ].snoopSupported,
          connectors: mappingsDeployed[mapping.identifier]
        })) || [];
      }),
      shareReplay(1)
    );

    // Initialize subscriptions
    this.mappingsInboundEnriched$.pipe(take(1)).subscribe();
    this.mappingsOutboundEnriched$.pipe(take(1)).subscribe();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
  }

  async getEffectiveDeploymentMap(): Promise<DeploymentMapEntryDetailed[]> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const mappings: Promise<DeploymentMapEntryDetailed[]> = await data.json();
    return mappings;
  }

  async getDefinedDeploymentMapEntry(
    mappingIdent: string
  ): Promise<DeploymentMapEntry> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_DEFINED_ENDPOINT}/${mappingIdent}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const mapEntry: string[] = await data.json();
    const result: DeploymentMapEntry = {
      identifier: mappingIdent,
      connectors: mapEntry
    };
    return result;
  }

  async getMappings(direction: Direction): Promise<Mapping[]> {
    const path = direction ? `${BASE_URL}/${PATH_MAPPING_ENDPOINT}?direction=${direction}` : `${BASE_URL}/${PATH_MAPPING_ENDPOINT}`;
    const response = await this.client.fetch(path,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    const result: Mapping[] = await response.json();
    return result;
  }

  initializeCache(dir: Direction): void {
    if (dir == Direction.INBOUND) {
      this.jsonProcessorInbound.initializeCache();
    }
  }

  refreshMappings(direction: Direction) {
    if (direction == Direction.INBOUND) {
      this.reloadInbound$.next();
    } else {
      this.reloadOutbound$.next();
    }
  }

  async stopChangedMappingEvents() {
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
      this.unsubscribe$.next();
      this.unsubscribe$.complete();
    }
  }

  async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }

  async validateExpression(json: JSON, path: string): Promise<boolean> {
    let result = true;
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      try {
        expression.evaluate(json) as JSON;
      } catch (error) {
        return false;
      }
    }
    return result;
  }

  public initializeContext(
    mapping: Mapping,
  ): ProcessingContext {
    const ctx: ProcessingContext = {
      mapping: mapping,
      topic:
        mapping.direction == Direction.INBOUND
          ? mapping.mappingTopicSample
          : mapping.publishTopicSample,
      processingType: ProcessingType.UNDEFINED,
      errors: [],
      mappingType: mapping.mappingType,
      processingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: false,
      requests: []
    };
    return ctx;
  }

  async testResult(
    context: ProcessingContext,
    message: any
  ): Promise<ProcessingContext> {
    const { mapping } = context;
    if (mapping.direction == Direction.INBOUND) {
      if (mapping.mappingType !== MappingType.CODE_BASED) {
        this.jsonProcessorInbound.deserializePayload(mapping, message, context);
        this.jsonProcessorInbound.enrichPayload(context);
        await this.jsonProcessorInbound.extractFromSource(context);
        this.jsonProcessorInbound.validateProcessingCache(context);
        await this.jsonProcessorInbound.substituteInTargetAndSend(context);
      } else {
        this.codeBasedProcessorInbound.deserializePayload(mapping, message, context);
        this.codeBasedProcessorInbound.enrichPayload(context);
        await this.codeBasedProcessorInbound.extractFromSource(context);
        this.codeBasedProcessorInbound.validateProcessingCache(context);
        await this.codeBasedProcessorInbound.substituteInTargetAndSend(context);
      }
    } else {
      if (mapping.mappingType !== MappingType.CODE_BASED) {
        this.jsonProcessorOutbound.deserializePayload(mapping, message, context);
        await this.jsonProcessorOutbound.extractFromSource(context);
        await this.jsonProcessorOutbound.substituteInTargetAndSend(context);
      } else {
        this.codeBasedProcessorOutbound.deserializePayload(mapping, message, context);
        await this.codeBasedProcessorOutbound.extractFromSource(context);
        await this.codeBasedProcessorOutbound.substituteInTargetAndSend(context);
      }
    }

    return context;
  }

  async deleteMapping(id: string): Promise<string> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return data.text();
  }

  async updateMapping(mapping: Mapping): Promise<Mapping> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${mapping.id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(mapping),
        method: 'PUT'
      }
    );
    const data = await response;
    if (!data.ok) {
      const error = await data.json();
      throw new Error(error.message)!;
    }
    const m = await data.json();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return m;
  }

  async createMapping(mapping: Mapping): Promise<Mapping> {
    const response = this.client.fetch(`${BASE_URL}/${PATH_MAPPING_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(mapping),
      method: 'POST'
    });
    const data = await response;
    if (!data.ok) {
      const errorTxt = await data.json();
      throw new Error(errorTxt.message ?? 'Could not be imported');
    }
    const m = await data.json();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return m;
  }

  async startChangedMappingEvents(): Promise<void> {
    if (!this._agentId) {
      this._agentId = await this.sharedService.getDynamicMappingServiceAgent();
    }

    this.eventRealtimeService.start();
    this.eventRealtimeService
      .onAll$(this._agentId)
      .pipe(
        map((p) => p['data']),
        filter(
          (payload) =>
            payload['type'] ==
            LoggingEventTypeMap[LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE].type
        ),
        takeUntil(this.unsubscribe$)
      )
      .subscribe(() => {
        this.reloadInbound$.next();
        this.reloadOutbound$.next();
      });
  }

  async updateDefinedDeploymentMapEntry(
    entry: DeploymentMapEntry
  ): Promise<any> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_DEFINED_ENDPOINT}/${entry.identifier}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(entry.connectors),
        method: 'PUT'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.text();
    return m;
  }

  getMappingsObservable(direction: Direction): Observable<MappingEnriched[]> {
    if (direction == Direction.INBOUND) {
      return this.mappingsInboundEnriched$;
    } else {
      return this.mappingsOutboundEnriched$;
    }
  }
}