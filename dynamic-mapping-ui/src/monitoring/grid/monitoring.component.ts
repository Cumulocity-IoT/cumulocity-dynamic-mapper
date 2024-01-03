/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import {
  ActionControl,
  AlertService,
  Column,
  ColumnDataType,
  DisplayOptions,
  Pagination
} from '@c8y/ngx-components';
import { Observable } from 'rxjs';
import { BrokerConfigurationService, Operation } from '../../configuration';
import { NameRendererComponent } from '../../mapping';
import { MappingStatus } from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';
import { NumberRendererComponent } from '../renderer/number.renderer.component';
import { DirectionRendererComponent } from '../renderer/direction.renderer.component';

@Component({
  selector: 'd11r-mapping-monitoring-grid',
  templateUrl: 'monitoring.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MonitoringComponent implements OnInit, OnDestroy {
  mappingStatus$: Observable<MappingStatus[]>;
  subscription: object;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'name',
      header: 'Name',
      path: 'name',
      filterable: false,
      sortOrder: 'asc',
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: NameRendererComponent,
      visible: true
    },
    {
      name: 'direction',
      header: 'Direction',
      path: 'direction',
      filterable: false,
      dataType: ColumnDataType.Icon,
      cellRendererComponent: DirectionRendererComponent,
      visible: true
    },
    {
      name: 'subscriptionTopic',
      header: 'Subscription Topic',
      path: 'subscriptionTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '15%'
    },
    {
      name: 'publishTopic',
      header: 'Publish Topic',
      path: 'publishTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '15%'
    },
    {
      header: '# Errors',
      name: 'errors',
      path: 'errors',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '12.5%'
    },
    {
      header: '# Messages Received',
      name: 'messagesReceived',
      path: 'messagesReceived',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '12.5%'
    },
    {
      header: '# Snooped Templates Total',
      name: 'snoopedTemplatesTotal',
      path: 'snoopedTemplatesTotal',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '12.5%'
    },
    {
      header: '# Snooped Templates Active',
      name: 'snoopedTemplatesActive',
      path: 'snoopedTemplatesActive',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '12.5%'
    }
  ];

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1
  };

  actionControls: ActionControl[] = [];

  constructor(
    public monitoringService: MonitoringService,
    public brokerConfigurationService: BrokerConfigurationService,
    public alertService: AlertService
  ) {}

  ngOnInit() {
    this.initializeMonitoringService();
  }

  async refreshMappingStatus(): Promise<void> {
    await this.brokerConfigurationService.runOperation(
      Operation.REFRESH_STATUS_MAPPING
    );
  }

  private async initializeMonitoringService() {
    this.subscription =
      await this.monitoringService.subscribeMonitoringChannel();
    this.mappingStatus$ = this.monitoringService.getCurrentMappingStatus();
  }

  ngOnDestroy(): void {
    console.log('Stop subscription');
    this.monitoringService.unsubscribeFromMonitoringChannel(this.subscription);
  }
}
