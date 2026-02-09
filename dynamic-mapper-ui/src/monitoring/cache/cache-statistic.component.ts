

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
  CoreModule
} from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import {
  Feature,
  Operation,
  SharedService
} from '../../shared';
import { KpiDetails, MonitoringService } from '../shared/monitoring.service';
import { ActivatedRoute } from '@angular/router';
import { gettext } from '@c8y/ngx-components/gettext';
import { KpListComponent } from './kpi/kpi-list.component';
import { HttpStatusCode } from '@angular/common/http';
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

  feature: Feature;
  kpis: KpiDetails[];
  loading = true;

  async ngOnInit(): Promise<void> {
    this.feature = this.route.snapshot.data['feature'];
    await this.reload();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.monitoringService.stopMonitoring();
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

  async reload() {
    this.loading = true;
    try {
      // load KPI details from monitoring service
      const baseKpis = await this.monitoringService.getKpisDetails();

      // Add fillrate KPIs for each cache
      const fillrateKpis: KpiDetails[] = baseKpis.map(kpi => ({
        domain: kpi.domain,
        id: `${kpi.id}Fillrate`,
        name: `${kpi.name} fillrate`,
        itemName: 'Percent',
        value: kpi.limit > 0 ? Math.round((kpi.value / kpi.limit) * 100) : 0,
        limit: 100,
        icon: 'percent',
        domainIcon: kpi.domainIcon,
      }));

      this.kpis = [...baseKpis, ...fillrateKpis];
    } catch (e) {
      this.alertService.addServerFailure(e);
    } finally {
      this.loading = false;
    }
  }
}