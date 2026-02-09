

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
import { Component, inject, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import {
  AlertService,
  CommonModule,
  CoreModule,
  DisplayOptions,
  Pagination
} from '@c8y/ngx-components';
import { BehaviorSubject, map, Subject } from 'rxjs';
import {
  Feature,
  MappingStatus,
  Operation,
  SharedService
} from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';
import { ActivatedRoute } from '@angular/router';
import { gettext } from '@c8y/ngx-components/gettext';
import { KpListComponent } from './kpi/kpi-list.component';
import { HttpStatusCode } from '@angular/common/http';

interface MonitoringComponentState {
  mappingStatuses: MappingStatus[];
  isLoading: boolean;
  error: string | null;
}
@Component({
  selector: 'd11r-cache-grid',
  templateUrl: 'cache-statistic.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, KpListComponent]
})
export class CacheStatisticComponent implements OnInit, OnDestroy {
  // Modern Angular dependency injection
  private readonly monitoringService = inject(MonitoringService);
  private readonly alertService = inject(AlertService);
  private readonly route = inject(ActivatedRoute);
  private sharedService = inject(SharedService);

  // Subscription management
  private readonly destroy$ = new Subject<void>();

  // State management
  readonly state$ = new BehaviorSubject<MonitoringComponentState>({
    mappingStatuses: [],
    isLoading: false,
    error: null
  });


  readonly isLoading$ = this.state$.pipe(map(state => state.isLoading));
  readonly error$ = this.state$.pipe(map(state => state.error));

  readonly displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  readonly pagination: Pagination = {
    pageSize: 5,
    currentPage: 1
  };

  feature: Feature;
  titleStatistic: string;

  // Cache sizes
  inboundCacheSize = 0;
  inventoryCacheSize = 0;


  async ngOnInit(): Promise<void> {
    try {
      await this.loadCacheSizes();
      this.feature = this.route.snapshot.data['feature'];

    } catch (error) {
      this.handleError('Failed to initialize monitoring', error);
    }
  }

  private async loadCacheSizes(): Promise<void> {
    try {
      // use the MonitoringService KPI helper
      const kpis = await this.monitoringService.getKpisDetails();
      const inventory = kpis.find(k => k.id === 'inventoryCache');
      const inbound = kpis.find(k => k.id === 'inboundIdCache');
      this.inventoryCacheSize = inventory?.value ?? 0;
      this.inboundCacheSize = inbound?.value ?? 0;
    } catch (error) {
      this.handleError('Failed to load cache sizes', error);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.monitoringService.stopMonitoring();
  }

  private handleError(message: string, error: unknown): void {
    console.error(message, error);

    const errorMessage = error instanceof Error
      ? error.message
      : 'Unknown error occurred';

    this.alertService.danger(gettext(message));
  }

  async clickedClearInboundExternalIdCache() {
    await this.clearCache('INBOUND_ID_CACHE');
  }

  async clickedClearInventoryCache() {
    await this.clearCache('INVENTORY_CACHE');
  }

  private async clearCache(cacheId: string): Promise<void> {
    const response = await this.sharedService.runOperation({
      operation: Operation.CLEAR_CACHE,
      parameter: { cacheId }
    });

    if (response.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

}