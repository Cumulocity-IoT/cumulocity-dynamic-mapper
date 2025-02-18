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
import { CoreModule, hookNavigator, hookRoute, hookTab } from '@c8y/ngx-components';
import { MonitoringComponent } from './grid/monitoring.component';
import { IdRendererComponent } from './renderer/id-cell.renderer.component';
import { ServiceConfigurationModule } from '../configuration';
import { NumberRendererComponent } from './renderer/number.renderer.component';
import { DirectionRendererComponent } from './renderer/direction.renderer.component';
import { MonitoringChartComponent } from './chart/chart.component';
import { NODE2 } from '../shared/mapping/util';
import { NgxEchartsModule } from 'ngx-echarts';
import { MappingServiceEventComponent } from './event/mapping-service-event.component';
import { BsDatepickerModule } from 'ngx-bootstrap/datepicker';
import { SharedModule } from '../shared';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { MonitoringNavigationFactory } from './monitoring-navigation.factory';

@NgModule({
  declarations: [
    MonitoringComponent,
    MappingServiceEventComponent,
    IdRendererComponent,
    NumberRendererComponent,
    DirectionRendererComponent,
    MonitoringChartComponent
  ],
  imports: [
    CoreModule,
    ServiceConfigurationModule,
    BsDatepickerModule,
    NgxEchartsModule.forRoot({
      echarts: () => import('echarts')
    }),
    CollapseModule,
    SharedModule
  ],
  exports: [],
  providers: [
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
      component: MonitoringComponent
    }),
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/chart`,
      component: MonitoringChartComponent
    }),
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/serviceEvent`,
      component: MappingServiceEventComponent
    }),
    hookNavigator(MonitoringNavigationFactory),
  ]
})
export class MonitoringModule {}
