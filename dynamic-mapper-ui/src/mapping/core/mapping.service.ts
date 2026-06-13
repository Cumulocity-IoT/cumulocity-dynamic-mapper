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
} from '@c8y/client';
import {
  Observable,
  Subject,
  combineLatest,
  map,
  shareReplay,
  switchMap,
  take,
  filter,
  takeUntil
} from 'rxjs';
import {
  BASE_URL,
  Direction,
  SharedService,
  MappingEnriched,
  Operation,
  DeploymentMapEntryDetailed,
  PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT,
  DeploymentMapEntry,
  PATH_DEPLOYMENT_DEFINED_ENDPOINT,
  Mapping,
  PATH_MAPPING_ENDPOINT,
  LoggingEventTypeMap,
  LoggingEventType,
  TransformationType,
} from '../../shared';

import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({
  providedIn: 'root'
})
export class MappingService {
  // Core dependencies
  private readonly eventRealtimeService: EventRealtimeService;

  // Observables and subjects
  private readonly updateMappingEnriched$ = new Subject<MappingEnriched>();
  private unsubscribe$ = new Subject<void>();

  mappingsOutboundEnriched$: Observable<MappingEnriched[]>;
  mappingsInboundEnriched$: Observable<MappingEnriched[]>;
  readonly reloadInbound$: Subject<void>;
  readonly reloadOutbound$: Subject<void>;

  // Cache
  private _agentId: string;
  private readonly JSONATA = require('jsonata');
  private deprecationWarningsShown: Set<Direction> = new Set();
  deprecationModalShown = false;

  constructor(
    private readonly sharedService: SharedService,
    private readonly client: FetchClient
  ) {
    this.eventRealtimeService = new EventRealtimeService(inject(RealtimeSubjectService));
    this.reloadInbound$ = this.sharedService.reloadInbound$;
    this.reloadOutbound$ = this.sharedService.reloadOutbound$;
    this.initializeMappingsEnriched();
  }

  // TODO ngOnDestroy is not called for services, find alternative how to stop the realtime service
  ngOnDestroy(): void {
    this.unsubscribe$.next();
    this.unsubscribe$.complete();
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
    }
  }

  // ===== MAPPING OPERATIONS =====

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

  resetCache(): void {
    // Implementation as needed
  }

  // ===== MAPPING CRUD OPERATIONS =====

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
    if (!response.ok) throw new Error(response.statusText);
    const result: Mapping[] = await response.json();
    return result;
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
    if (!response.ok) throw new Error(response.statusText);
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return response.text();
  }

  async updateMapping(mapping: Mapping): Promise<Mapping> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${mapping.id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(mapping),
        method: 'PUT'
      }
    );
    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.message)!;
    }
    const m = await response.json();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return m;
  }

  async createMapping(mapping: Mapping): Promise<Mapping> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_MAPPING_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(mapping),
      method: 'POST'
    });
    if (!response.ok) {
      const errorTxt = await response.json();
      throw new Error(errorTxt.message ?? 'Could not be imported');
    }
    const m = await response.json();
    this.reloadInbound$.next();
    this.reloadOutbound$.next();
    return m;
  }

  // ===== DEPLOYMENT OPERATIONS =====

  async getEffectiveDeploymentMap(): Promise<DeploymentMapEntryDetailed[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    if (!response.ok) throw new Error(await this.extractErrorMessage(response));
    const mappings: DeploymentMapEntryDetailed[] = await response.json();
    return mappings;
  }

  async getDefinedDeploymentMapEntry(
    mappingIdent: string
  ): Promise<DeploymentMapEntry> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_DEFINED_ENDPOINT}/${mappingIdent}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    if (!response.ok) throw new Error(await this.extractErrorMessage(response));
    const mapEntry: string[] = await response.json();
    const result: DeploymentMapEntry = {
      identifier: mappingIdent,
      connectors: mapEntry
    };
    return result;
  }

  async updateDefinedDeploymentMapEntry(
    entry: DeploymentMapEntry
  ): Promise<string> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_DEPLOYMENT_DEFINED_ENDPOINT}/${entry.identifier}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(entry.connectors ?? []),
        method: 'PUT'
      }
    );
    // The backend validates connector identifiers (400 on unknown/stale connectors)
    // and reconciles subscriptions live, so surface its message rather than a bare status text.
    if (!response.ok) throw new Error(await this.extractErrorMessage(response));
    return response.text();
  }

  /**
   * Extracts a human-readable error message from a failed response. The backend returns a
   * Spring error body with a `message` field (e.g. "Unknown connector identifier(s): [...]");
   * fall back to the status text when the body is empty or not JSON.
   */
  private async extractErrorMessage(response: Response): Promise<string> {
    try {
      const body = await response.json();
      if (body?.message) return body.message;
    } catch {
      // body was not JSON; fall through to status text
    }
    return response.statusText || `Request failed (${response.status})`;
  }


  // ===== UTILITY METHODS =====

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


  refreshMappings(direction: Direction) {
    if (direction == Direction.INBOUND) {
      this.reloadInbound$.next();
    } else {
      this.reloadOutbound$.next();
    }
  }

  async checkAndShowDeprecationWarning(direction: Direction): Promise<void> {
    // Kept for backward compatibility; deprecation notice is now shown
    // as a modal dialog in MappingComponent on first load.
    if (this.deprecationWarningsShown.has(direction)) {
      return;
    }

    const feature = await this.sharedService.getFeatures();
    if (feature?.suppressDeprecationWarning) {
      return;
    }

    const mappings = await this.getMappings(direction);
    const deprecatedMappings = mappings.filter(
      m => m.transformationType === TransformationType.SUBSTITUTION_AS_CODE
    );

    if (deprecatedMappings.length > 0) {
      this.deprecationWarningsShown.add(direction);
    }
  }

  getMappingsObservable(direction: Direction): Observable<MappingEnriched[]> {
    if (direction == Direction.INBOUND) {
      return this.mappingsInboundEnriched$;
    } else {
      return this.mappingsOutboundEnriched$;
    }
  }

  // ===== PRIVATE METHODS =====

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

  async stopChangedMappingEvents() {
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
      this.unsubscribe$.next();
      this.unsubscribe$.complete();
      this.unsubscribe$ = new Subject<void>();
    }
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
            LoggingEventTypeMap[LoggingEventType.MAPPING_CHANGED_EVENT_TYPE].type
        ),
        takeUntil(this.unsubscribe$)
      )
      .subscribe(() => {
        this.reloadInbound$.next();
        this.reloadOutbound$.next();
      });
  }
}