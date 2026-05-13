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
import { CoreModule } from '@c8y/ngx-components';
import { Observable, Subject } from 'rxjs';
import {
  ConnectorConfiguration,
  ConnectorStatusEvent,
  LoggingEventType,
  LoggingEventTypeMap,
} from '..';
import { FormatStringPipe } from '../misc/format-string.pipe';
import { ConnectorLogService } from '../service/connector-log.service';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'd11r-mapping-connector-log',
  styleUrls: ['./connector-log.component.style.css'],
  templateUrl: 'connector-log.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, FormatStringPipe]
})
export class ConnectorStatusComponent implements OnInit, OnDestroy {
  configurations$: Observable<ConnectorConfiguration[]>;
  statusLogs$: Observable<ConnectorStatusEvent[]>;
  filterStatusLog = {
    connectorIdentifier: 'ALL',
    type: LoggingEventType.ALL,
  };
  readonly LoggingEventTypeMap = LoggingEventTypeMap;
  readonly LoggingEventType = LoggingEventType;
  private readonly destroy$ = new Subject<void>();

  private readonly connectorStatusService = inject(ConnectorLogService);
  private readonly connectorConfigurationService = inject(ConnectorConfigurationService);

  ngOnInit(): void {
    this.connectorStatusService.startConnectorStatusLogs();
    this.configurations$ = this.connectorConfigurationService.getConfigurationsWithStatus();
    this.statusLogs$ = this.connectorStatusService.getStatusLogs();
  }

  updateStatusLogs(): void {
    this.connectorStatusService.updateStatusLogs(this.filterStatusLog);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.connectorStatusService.stopConnectorStatusLogs();
  }
}
