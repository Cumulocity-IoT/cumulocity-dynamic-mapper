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
import { InventoryService } from '@c8y/client';
import { BehaviorSubject, map, Observable, Subscription } from 'rxjs';
import { MAPPING_FRAGMENT, MappingStatus, SharedService } from '../../shared';
import {
  ManagedObjectRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';


interface MonitoringState {
  status: MappingStatus[];
  error: string | null;
}

@Injectable({ providedIn: 'root' })
export class MonitoringService {

  private readonly inventory = inject(InventoryService);
  private readonly sharedService = inject(SharedService);
  private readonly realtimeSubjectService = inject(RealtimeSubjectService);

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
  private isMonitoring = false;


  getMappingStatus(): Observable<MappingStatus[]> {
    return this.mappingStatus$;
  }


  async startMonitoring(): Promise<void> {
    if (this.isMonitoring) {
      console.warn('Monitoring is already active');
      return;
    }

    try {
      this.isMonitoring = true;
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
      const realtimeSubscription = this.managedObjectRealtimeService
        .onAll$(agentId)
        .pipe(
          map((update) => {
            if (!update?.data) {
              console.warn('Invalid realtime update received');
              return [];
            }
            const mappingData = update.data[MAPPING_FRAGMENT];
            return Array.isArray(mappingData) ? mappingData : [];
          })
        )
        .subscribe((status) => this.state$.next({ status, error: null }));


      // Continue with monitoring setup...
    } catch (error) {
      console.error('Failed to start monitoring:', error);
      // Handle error appropriately (emit error state, show notification, etc.)
      throw error; // Re-throw to let caller handle
    }
  }

  stopMonitoring(): void {
    if (this.managedObjectRealtimeService) this.managedObjectRealtimeService.stop();
    this.isMonitoring = false;
  }
}
