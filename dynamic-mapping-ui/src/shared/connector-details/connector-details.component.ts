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
import { AlertService, ContextData } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable, Subject, Subscription, takeUntil, tap } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  LoggingEventType,
  LoggingEventTypeMap,
} from '..';
import { ConnectorLogService } from '../service/connector-log.service';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'd11r-mapping-connector-details',
  styleUrls: ['./connector-details.component.style.css'],
  templateUrl: 'connector-details.component.html'
})
export class ConnectorDetailsComponent implements OnInit, OnDestroy {
  version: string = packageJson.version;
  monitorings$: Observable<ConnectorStatus>;
  specifications: ConnectorSpecification[] = [];
  configurations$: Observable<ConnectorConfiguration[]> = new Observable();
  statusLogs$: Observable<any[]> ;
  connector: ConnectorConfiguration;
  filterStatusLog = {
    connectorIdentifier: 'ALL',
    type: LoggingEventType.ALL,
  };
  LoggingEventTypeMap = LoggingEventTypeMap;
  LoggingEventType = LoggingEventType;
  title:string;
  private destroy$ = new Subject<void>();

  constructor(
    public bsModalService: BsModalService,
    public connectorStatusService: ConnectorLogService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private route: ActivatedRoute
  ) {}

  async ngOnInit() {
    // console.log('Running version', this.version);
    const {connector} = this.route.snapshot.data;
    this.filterStatusLog.connectorIdentifier = connector.identifier;
    this.connector = connector;
    console.log('Details for connector', connector);

    this.connectorStatusService.initConnectorLogsRealtime();
    this.configurations$ =
      this.connectorConfigurationService.getConnectorConfigurationsWithLiveStatus();
    this.statusLogs$ = this.connectorStatusService.getStatusLogs();
    // Subscribe to logs to verify they're coming through
    this.statusLogs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      // next: (logs) => console.log('Received logs in component:', logs),
      error: (error) => console.error('Error receiving logs:', error),
      complete: () => console.log('Completed') // optional
    });
    this.updateStatusLogs();
  }

  updateStatusLogs() {
    this.connectorStatusService.updateStatusLogs(this.filterStatusLog);
  }
  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
