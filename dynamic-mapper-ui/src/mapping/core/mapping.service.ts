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
  MappingVersion,
  MappingVersionCount,
  PATH_MAPPING_ENDPOINT,
  LoggingEventTypeMap,
  LoggingEventType,
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
  deprecationModalShown = false;
  private readonly versionsCache = new Map<string, MappingVersion[]>();

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

  async getMapping(id: string): Promise<Mapping> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
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

  // ===== VERSION & DRAFT OPERATIONS =====

  /**
   * Returns the unpublished draft (working copy) for a mapping line, or null when
   * there is no draft (HTTP 204).
   */
  async getDraft(id: string): Promise<Mapping | null> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/draft`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (response.status === 204) return null;
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
  }

  /**
   * Saves edits into the mapping line's draft without changing the running/active
   * configuration. Throws on a 409 (the draft was modified concurrently).
   */
  async saveDraft(id: string, mapping: Mapping): Promise<Mapping> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/draft`,
      {
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(mapping),
        method: 'PUT'
      }
    );
    if (response.status === 409) {
      throw new Error('The draft was modified concurrently. Please reload before saving.');
    }
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message ?? response.statusText);
    }
    return response.json();
  }

  /** Discards the mapping line's current draft. No-op when there is no draft. */
  async deleteDraft(id: string): Promise<void> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/draft`,
      { method: 'DELETE' }
    );
    if (!response.ok && response.status !== 204) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message ?? response.statusText);
    }
  }

  /**
   * Publishes the mapping line's current draft as a new immutable version. Does not
   * activate it.
   */
  async publishDraft(id: string, version: string, note?: string): Promise<MappingVersion> {
    const params = new URLSearchParams({ version });
    if (note) params.set('note', note);
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/publish?${params}`,
      { headers: { 'content-type': 'application/json' }, method: 'POST' }
    );
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message ?? response.statusText);
    }
    const mv = await response.json();
    this.clearVersionsCache(id);
    return mv;
  }

  /** Returns suggested semver bumps (patch / minor / major) based on the highest published version. */
  async suggestNextVersions(id: string): Promise<{ patch: string; minor: string; major: string }> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/version/suggest`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
  }

  /** Returns the published version count for every mapping matching the direction in one backend call. */
  async getVersionCounts(direction?: Direction): Promise<MappingVersionCount[]> {
    const query = direction ? `?direction=${direction}` : '';
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/version-counts${query}`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
  }

  /** Lists all published versions of a mapping line. Results are cached until clearVersionsCache() is called. */
  async getVersions(id: string): Promise<MappingVersion[]> {
    if (this.versionsCache.has(id)) {
      return this.versionsCache.get(id)!;
    }
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/version`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (!response.ok) throw new Error(response.statusText);
    const versions: MappingVersion[] = await response.json();
    this.versionsCache.set(id, versions);
    return versions;
  }

  /** Clears the versions cache. Pass an id to evict a single mapping; omit to clear all. */
  clearVersionsCache(id?: string): void {
    if (id) {
      this.versionsCache.delete(id);
    } else {
      this.versionsCache.clear();
    }
  }

  /** Returns a single published version of a mapping line. */
  async getVersion(id: string, version: string): Promise<MappingVersion> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/version/${encodeURIComponent(version)}`,
      { headers: { 'content-type': 'application/json' }, method: 'GET' }
    );
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
  }

  /** Updates the change note of a published version (the only mutable field). */
  async updateVersionNote(id: string, version: string, note: string): Promise<MappingVersion> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/version/${encodeURIComponent(version)}?note=${encodeURIComponent(note ?? '')}`,
      { headers: { 'content-type': 'application/json' }, method: 'PATCH' }
    );
    if (!response.ok) throw new Error(response.statusText);
    const mv = await response.json();
    this.clearVersionsCache(id);
    return mv;
  }

  /** Deletes an inactive published version. The active version cannot be deleted. */
  async deleteVersion(id: string, version: string): Promise<void> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}/version/${encodeURIComponent(version)}`,
      { headers: { 'content-type': 'application/json' }, method: 'DELETE' }
    );
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message ?? response.statusText);
    }
    this.clearVersionsCache(id);
  }

  /**
   * Activates a specific version of a mapping line (rollback / roll-forward). The
   * backend swaps the version's snapshot into the runnable mapping (single active
   * version, C-1).
   */
  async activateVersion(id: string, version: string): Promise<IFetchResponse> {
    return this.changeActivationMapping({ id, active: true, version });
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