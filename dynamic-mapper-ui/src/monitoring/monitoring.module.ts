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

import { NgModule } from '@angular/core';
import { hookNavigator, hookRoute, hookTab } from '@c8y/ngx-components';
import { MonitoringComponent } from './statistic/monitoring.component';
import { MonitoringChartComponent } from './chart/chart.component';
import { featureResolver, NODE2 } from '../shared/mapping/util';
import { MappingServiceEventComponent } from './event/mapping-service-event.component';
import { MonitoringNavigationFactory } from './monitoring-navigation.factory';
import { StatisticTabFactory } from './statistic-tab.factory';

@NgModule({
  providers: [
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/statistic/inbound`,
      component: MonitoringComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/statistic/outbound`,
      component: MonitoringComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/chart`,
      component: MonitoringChartComponent
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/serviceEvent`,
      component: MappingServiceEventComponent
    }),
    hookNavigator(MonitoringNavigationFactory),
    hookTab(StatisticTabFactory),
  ]
})
export class MonitoringModule { }
