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
import { FetchClient, InventoryService, Realtime } from '@c8y/client';
import { BehaviorSubject, map, Observable, Subscription } from 'rxjs';
import { MAPPING_FRAGMENT, MappingStatus, SharedService } from '../../shared';
import {
  ManagedObjectRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class MonitoringService {
  constructor(
    private client: FetchClient,
    private inventory: InventoryService,
    private sharedService: SharedService
  ) {
    this.managedObjectRealtimeService = new ManagedObjectRealtimeService(
      inject(RealtimeSubjectService)
    );
  }

  private realtime: Realtime;
  private subscription: Subscription;
  private managedObjectRealtimeService: ManagedObjectRealtimeService;
  private mappingStatus$ = new BehaviorSubject<MappingStatus[]>([]);

  getCurrentMappingStatus(): Observable<MappingStatus[]> {
    return this.mappingStatus$;
  }

  async startMonitoring(): Promise<void> {
    const agentId = await this.sharedService.getDynamicMappingServiceAgent();
    // console.log('Start subscription for monitoring:', agentId);

    const { data } = await this.inventory.detail(agentId);
    const monitoring: MappingStatus[] = data[MAPPING_FRAGMENT];
    this.mappingStatus$.next(monitoring);

    // subscribe to event stream
    this.managedObjectRealtimeService.start();
    this.subscription = this.managedObjectRealtimeService
      .onAll$(agentId)
      .pipe(map((p) => p['data'][MAPPING_FRAGMENT]))
      .subscribe((m) => this.mappingStatus$.next(m));
  }
  stopMonitoring() {
    if (this.subscription) this.subscription.unsubscribe();
  }
}
