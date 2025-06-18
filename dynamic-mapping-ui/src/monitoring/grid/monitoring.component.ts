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
import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import {
  ActionControl,
  AlertService,
  Column,
  ColumnDataType,
  DisplayOptions,
  gettext,
  Pagination
} from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import {
  ConfirmationModalComponent,
  Feature,
  MappingStatus,
  Operation,
  SharedService
} from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';
import { NumberRendererComponent } from '../renderer/number.renderer.component';
import { DirectionRendererComponent } from '../renderer/direction.renderer.component';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { ConnectorConfigurationService } from '../../connector';
import { NameRendererComponent } from '../../mapping/renderer/name.renderer.component';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'd11r-mapping-monitoring-grid',
  templateUrl: 'monitoring.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MonitoringComponent implements OnInit, OnDestroy {
  mappingStatus$: Subject<MappingStatus[]> = new Subject<MappingStatus[]>();

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
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
      name: 'mappingTopic',
      header: 'Mapping topic',
      path: 'mappingTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '15%'
    },
    {
      name: 'publishTopic',
      header: 'Publish topic',
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
      gridTrackSize: '10%'
    },
    {
      header: '# Messages received',
      name: 'messagesReceived',
      path: 'messagesReceived',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Snooped templates total',
      name: 'snoopedTemplatesTotal',
      path: 'snoopedTemplatesTotal',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Snooped templates active',
      name: 'snoopedTemplatesActive',
      path: 'snoopedTemplatesActive',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Current failure count',
      name: 'currentFailureCount',
      path: 'currentFailureCount',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    }
  ];

  pagination: Pagination = {
    pageSize: 5,
    currentPage: 1
  };
  feature: Feature;

  actionControls: ActionControl[] = [];

  constructor(
    public monitoringService: MonitoringService,
    public brokerConnectorService: ConnectorConfigurationService,
    public alertService: AlertService,
    public bsModalService: BsModalService,
    private sharedService: SharedService,
    private route: ActivatedRoute
  ) { }

  async ngOnInit() {
    this.initializeMonitoringService();
    this.feature = this.route.snapshot.data['feature'];
  }

  async refreshMappingStatus(): Promise<void> {
    await this.sharedService.runOperation({ operation: Operation.REFRESH_STATUS_MAPPING });
  }

  private async initializeMonitoringService() {
    await this.monitoringService.startMonitoring();
    this.monitoringService
      .getCurrentMappingStatus()
      .subscribe((status) => this.mappingStatus$.next(status));
  }

  async resetStatusMapping() {
    const initialState = {
      title: 'Reset mapping statistic',
      message:
        'You are about to delete the mapping statistic. Do you want to proceed?',
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );

    confirmDeletionModalRef.content.closeSubject.subscribe(
      async (result: boolean) => {
        // console.log('Confirmation result:', result);
        if (result) {
          const res = await this.sharedService.runOperation(
            { operation: Operation.RESET_STATUS_MAPPING }
          );
          if (res.status < 300) {
            this.alertService.success(
              gettext('Mapping statistic reset successfully.')
            );
          } else {
            this.alertService.danger(gettext('Failed to reset statistic.'));
          }
        }
        confirmDeletionModalRef.hide();
      }
    );
  }

  ngOnDestroy(): void {
    this.monitoringService.stopMonitoring();
  }
}
