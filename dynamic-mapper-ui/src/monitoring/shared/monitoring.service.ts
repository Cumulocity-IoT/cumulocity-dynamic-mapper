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
import { InventoryService, FetchClient } from '@c8y/client';
import { BehaviorSubject, map, Observable, Subject, takeUntil } from 'rxjs';
import { BASE_URL, MAPPING_FRAGMENT, MappingStatus, SharedService } from '../../shared';
import {
  ManagedObjectRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

export type KpiDetails = {
  domain: string;
  id: string;
  name: string;
  itemName: string;
  value?: number;
  limit: number;
  icon: string;
  domainIcon: string;
};

interface MonitoringState {
  status: MappingStatus[];
  error: string | null;
}

@Injectable({ providedIn: 'root' })
export class MonitoringService {

  private readonly inventory = inject(InventoryService);
  private readonly sharedService = inject(SharedService);
  private readonly realtimeSubjectService = inject(RealtimeSubjectService);
  private readonly client = inject(FetchClient);

  constructor() {
    this.managedObjectRealtimeService = new ManagedObjectRealtimeService(
      this.realtimeSubjectService
    );
  }


  private managedObjectRealtimeService: ManagedObjectRealtimeService;
  private state$ = new BehaviorSubject<MonitoringState>({
    status: [],
    error: null,
  });
  private mappingStatus$ = this.state$.pipe(map(state => state.status));
  // Ref-count, not a boolean: this service is `providedIn: 'root'` and multiple components
  // (e.g. the inbound/outbound statistic tabs) independently call start/stopMonitoring from
  // their own ngOnInit/ngOnDestroy. A single "isMonitoring" flag let one component's teardown
  // kill the shared realtime stream out from under another still-mounted consumer; only tear
  // down once the last consumer has stopped.
  private activeConsumers = 0;
  private unsubscribe$ = new Subject<void>();


  getMappingStatus(): Observable<MappingStatus[]> {
    return this.mappingStatus$;
  }

  async getCacheSize(cacheId: string): Promise<number> {
    try {
      const response = await this.client.fetch(`${BASE_URL}/cache?cacheId=${encodeURIComponent(cacheId)}`, {
        method: 'GET'
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch cache size: ${response.status}`);
      }

      const body = await response.json();
      return typeof body === 'number' ? body : Number(body || 0);
    } catch (err) {
      console.error('Error fetching cache size', err);
      throw err;
    }
  }

  async getKpisDetails(_tenantId?: string): Promise<Array<KpiDetails>> {
    try {
      const [inventorySize, inboundSize, outboundSize] = await Promise.all([
        this.getCacheSize('INVENTORY_CACHE'),
        this.getCacheSize('INBOUND_ID_CACHE'),
        this.getCacheSize('OUTBOUND_ID_CACHE')
      ]);
      // include configured limits from service configuration
      const config = await this.sharedService.getServiceConfiguration();
      const inventoryLimit = (config && typeof config.inventoryCacheSize === 'number') ? config.inventoryCacheSize : 0;
      const inboundLimit = (config && typeof config.inboundExternalIdCacheSize === 'number') ? config.inboundExternalIdCacheSize : 0;
      const outboundLimit = (config && typeof config.outboundExternalIdCacheSize === 'number') ? config.outboundExternalIdCacheSize : 0;

      return [
        { domain: 'inventoryCache', id: 'inventoryCacheRaw', name: 'Inventory cache', value: inventorySize ?? 0, itemName: 'Entries', icon: 'hashtag', domainIcon: 'more-details', limit: inventoryLimit },
        { domain: 'inboundIdCache', id: 'inboundIdCacheRaw', name: 'Inbound ID cache', value: inboundSize ?? 0, itemName: 'Entries', icon: 'hashtag', domainIcon: 'pin-code', limit: inboundLimit },
        { domain: 'outboundIdCache', id: 'outboundIdCacheRaw', name: 'Outbound ID cache', value: outboundSize ?? 0, itemName: 'Entries', icon: 'hashtag', domainIcon: 'pin-code', limit: outboundLimit }
      ];
    } catch (err) {
      console.error('Failed to get KPIs details', err);
      throw err;
    }
  }


  async startMonitoring(): Promise<void> {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      // Another consumer already has the stream running; nothing more to do.
      return;
    }

    try {
      const agentId = await this.sharedService.getDynamicMappingServiceAgent();

      if (!agentId) {
        throw new Error('No mapping service agent found');
      }

      const { data } = await this.inventory.detail(agentId);

      if (!data) {
        throw new Error('No data received from inventory service');
      }
      const status: MappingStatus[] = data[MAPPING_FRAGMENT];
      this.state$.next({ status: status, error: null });

      // subscribe to event stream
      this.managedObjectRealtimeService.start();
      this.managedObjectRealtimeService
        .onAll$(agentId)
        .pipe(
          map((update) => {
            if (!update?.data) {
              console.warn('Invalid realtime update received');
              return [];
            }
            const mappingData = update.data[MAPPING_FRAGMENT];
            return Array.isArray(mappingData) ? mappingData : [];
          }),
          takeUntil(this.unsubscribe$)
        )
        .subscribe((status) => this.state$.next({ status, error: null }));
    } catch (error) {
      console.error('Failed to start monitoring:', error);
      this.activeConsumers = Math.max(0, this.activeConsumers - 1);
      throw error;
    }
  }

  stopMonitoring(): void {
    if (this.activeConsumers > 0) {
      this.activeConsumers--;
    }
    if (this.activeConsumers > 0) {
      // Other consumers are still active; keep the shared stream running for them.
      return;
    }
    if (this.managedObjectRealtimeService) this.managedObjectRealtimeService.stop();
    this.unsubscribe$.next();
    this.unsubscribe$.complete();
    this.unsubscribe$ = new Subject<void>();
  }
}
