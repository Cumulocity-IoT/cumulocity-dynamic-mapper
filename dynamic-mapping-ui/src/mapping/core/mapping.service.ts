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
import { inject, Injectable } from '@angular/core';
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
  Subscription,
  combineLatest,
  filter,
  map,
  shareReplay,
  switchMap,
  take
} from 'rxjs';
import {
  BASE_URL,
  PATH_MAPPING_ENDPOINT,
  PATH_SUBSCRIPTIONS_ENDPOINT,
  PATH_SUBSCRIPTION_ENDPOINT,
  Direction,
  Mapping,
  SharedService,
  DeploymentMapEntryDetailed,
  PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT,
  MappingEnriched,
  MappingTypeDescriptionMap,
  DeploymentMapEntry,
  DeploymentMap,
  PATH_DEPLOYMENT_DEFINED_ENDPOINT,
  Operation,
  LoggingEventTypeMap,
  LoggingEventType,
  MappingType
} from '../../shared';
import { JSONProcessorInbound } from './processor/impl/json-processor-inbound.service';
import { JSONProcessorOutbound } from './processor/impl/json-processor-outbound.service';
import {
  ProcessingContext,
  ProcessingType,
  SubstituteValue
} from './processor/processor.model';
import { C8YNotificationSubscription } from '../shared/mapping.model';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';
import { CodeBasedProcessorOutbound } from './processor/impl/code-based-processor-outbound.service';
import { CodeBasedProcessorInbound } from './processor/impl/code-based-processor-inbound.service';

@Injectable({
  providedIn: 'root'
})
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private jsonProcessorInbound: JSONProcessorInbound,
    private jsonProcessorOutbound: JSONProcessorOutbound,
    private codeBasedProcessorOutbound: CodeBasedProcessorOutbound,
    private codeBasedProcessorInbound: CodeBasedProcessorInbound,
    private sharedService: SharedService,
    private client: FetchClient,
  ) {
    this.eventRealtimeService = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    this.queriesUtil = new QueriesUtil();
    this.reloadInbound$ = this.sharedService.reloadInbound$;
    this.reloadOutbound$ = this.sharedService.reloadOutbound$;
    this.initializeMappingsEnriched();
  }
  private eventRealtimeService: EventRealtimeService;
  private subscription: Subscription;
  private updateMappingEnriched$: Subject<MappingEnriched> = new Subject();
  queriesUtil: QueriesUtil;
  private _agentId: string;
  protected JSONATA = require('jsonata');

  //   private _mappingsInbound: Promise<Mapping[]>;
  //   private _mappingsOutbound: Promise<Mapping[]>;

  mappingsOutboundEnriched$: Observable<MappingEnriched[]>;
  mappingsInboundEnriched$: Observable<MappingEnriched[]>;

  reloadInbound$: Subject<void>; // = new Subject<void>();
  reloadOutbound$: Subject<void>; // = new Subject<void>();

  async changeActivationMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.ACTIVATE_MAPPING,
        parameter
      }
    );
  }

  async addSampleMappings(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.ADD_SAMPLE_MAPPINGS,
        parameter
      }
    );
  }

  listenToUpdateMapping(): Observable<MappingEnriched> {
    return this.updateMappingEnriched$;
  }
  initiateUpdateMapping(m: MappingEnriched): void {
    this.updateMappingEnriched$.next(m);
  }

  async changeDebuggingMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.DEBUG_MAPPING,
        parameter
      }
    );
  }

  async changeSnoopStatusMapping(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.SNOOP_MAPPING,
        parameter
      }
    );
  }

  async resetSnoop(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.SNOOP_RESET,
        parameter
      }
    );
  }

  async updateTemplate(parameter: any): Promise<IFetchResponse> {
    return await this.sharedService.runOperation(
      {
        operation: Operation.COPY_SNOOPED_SOURCE_TEMPLATE,
        parameter
      }
    );
  }

  resetCache() {
    // this._mappingsInbound = undefined;
    // this._mappingsOutbound = undefined;
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

  async getDefinedDeploymentMap(): Promise<DeploymentMap> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_DEFINED_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const map: Promise<DeploymentMap> = await data.json();
    return map;
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

  initializeMappingsEnriched() {
    this.mappingsInboundEnriched$ = this.reloadInbound$.pipe(
      switchMap(() =>
        combineLatest([
          this.getMappings(Direction.INBOUND),
          this.getEffectiveDeploymentMap()
        ])
      ),
      map(([mappings, mappingsDeployed]) => {
        const mappingsEnriched = [];
        mappings.forEach((m) => {
          mappingsEnriched.push({
            id: m.id,
            mapping: m,
            snoopSupported:
              MappingTypeDescriptionMap[m.mappingType]?.properties[
                Direction.INBOUND
              ].snoopSupported,
            connectors: mappingsDeployed[m.identifier]
          });
        });
        return mappingsEnriched;
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
        const mappingsEnriched = [];
        mappings?.forEach((m) => {
          mappingsEnriched.push({
            id: m.id,
            mapping: m,
            snoopSupported:
              MappingTypeDescriptionMap[m.mappingType]?.properties[
                Direction.OUTBOUND
              ].snoopSupported,
            connectors: mappingsDeployed[m.identifier]
          });
        });
        return mappingsEnriched;
      }),
      shareReplay(1)
    );
    this.mappingsInboundEnriched$.pipe(take(1)).subscribe();
    this.mappingsOutboundEnriched$.pipe(take(1)).subscribe();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
  }

  getMappingsObservable(direction: Direction): Observable<MappingEnriched[]> {
    if (direction == Direction.INBOUND) {
      return this.mappingsInboundEnriched$;
    } else {
      return this.mappingsOutboundEnriched$;
    }
  }

  refreshMappings(direction: Direction) {
    if (direction == Direction.INBOUND) {
      this.reloadInbound$.next();
    } else {
      this.reloadOutbound$.next();
    }
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

  async updateSubscriptions(
    sub: C8YNotificationSubscription
  ): Promise<C8YNotificationSubscription> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(sub),
        method: 'PUT'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.json();
    return m;
  }

  async deleteSubscriptions(device: IIdentified): Promise<any> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/${device.id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.text();
    return m;
  }

  async getSubscriptions(): Promise<C8YNotificationSubscription> {
    const feature = await this.sharedService.getFeatures();

    if (feature?.outputMappingEnabled) {
      const res: IFetchResponse = await this.client.fetch(
        `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}`,
        {
          headers: {
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );
      const data = await res.json();
      return data;
    } else {
      return null;
    }
  }

  async saveMappings(mappings: Mapping[]): Promise<void> {
    mappings.forEach((m) => {
      this.inventory.update({
        d11r_mapping: m,
        id: m.id
      });
    });
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
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

  async deleteMapping(id: string): Promise<string> {
    // let result = this.inventory.delete(mapping.id)
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
    // since the Cumulocity identifiers are not included in the sourceTemplate, we add them for local testing
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
      } /* The above code is written in TypeScript and it is an `else` block that contains three
      asynchronous operations: */
      else {
        this.codeBasedProcessorOutbound.deserializePayload(mapping, message, context);
        await this.codeBasedProcessorOutbound.extractFromSource(context);
        await this.codeBasedProcessorOutbound.substituteInTargetAndSend(context);
      }
    }

    // The producing code (this may take some time)
    return context;
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

  async startChangedMappingEvents(): Promise<void> {
    if (!this._agentId) {
      this._agentId = await this.sharedService.getDynamicMappingServiceAgent();
    }
    // console.log('Started subscriptions:', this._agentId);

    // subscribe to event stream
    this.eventRealtimeService.start();
    this.subscription = this.eventRealtimeService
      .onAll$(this._agentId)
      .pipe(
        map((p) => p['data']),
        // tap((p) => {
        //   console.log('New event', p);
        // }),
        filter(
          (payload) =>
            payload['type'] ==
            LoggingEventTypeMap[LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE].type
        )
      )
      .subscribe(() => {
        this.reloadInbound$.next();
        this.reloadOutbound$.next();
      });
  }

  async stopChangedMappingEvents() { 
    if (this.subscription) {
      this.subscription.unsubscribe();
      this.subscription = null;
    }
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
    }
    // console.log('Stopped subscriptions:', this._agentId);
  }
}
