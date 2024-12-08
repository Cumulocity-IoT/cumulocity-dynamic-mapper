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
import { Component, OnDestroy, OnInit } from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable, Subject, takeUntil } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Feature,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedService,
} from '../../shared';
import { ConnectorStatusService } from '../connector-status.service';
import { ConnectorConfigurationService } from '../connector-configuration.service';

@Component({
  selector: 'd11r-mapping-connector-status',
  styleUrls: ['./connector-status.component.style.css'],
  templateUrl: 'connector-status.component.html'
})
export class ConnectorStatusComponent implements OnInit, OnDestroy {
  version: string = packageJson.version;
  monitorings$: Observable<ConnectorStatus>;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations$: Observable<ConnectorConfiguration[]> = new Observable();
  statusLogs$: Observable<any[]> ;
  private readonly ALL: string = 'ALL';
  filterStatusLog = {
    connectorIdent: this.ALL,
    type: LoggingEventType.ALL,
  };
  LoggingEventTypeMap = LoggingEventTypeMap;
  LoggingEventType = LoggingEventType;
  private destroy$ = new Subject<void>();

  constructor(
    public bsModalService: BsModalService,
    public connectorStatusService: ConnectorStatusService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) {}

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.connectorStatusService.initConnectorLogsRealtime();
    this.feature = await this.sharedService.getFeatures();
    this.configurations$ =
      this.connectorConfigurationService.getConnectorConfigurationsWithLiveStatus();
    this.statusLogs$ = this.connectorStatusService.getStatusLogs();
    // Subscribe to logs to verify they're coming through
    this.statusLogs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(
      logs => console.log('Received logs in component:', logs),
      error => console.error('Error receiving logs:', error)
    );
  }

  updateStatusLogs() {
    this.connectorStatusService.updateStatusLogs(this.filterStatusLog);
  }
  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
