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
import { Component, OnInit } from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Feature,
  SharedService,
  StatusEventTypes
} from '../../shared';
import { ConnectorStatusService } from '../connector-status.service';
import { ConnectorConfigurationService } from '../connector-configuration.service';

@Component({
  selector: 'd11r-mapping-connector-status',
  styleUrls: ['./connector-status.component.style.css'],
  templateUrl: 'connector-status.component.html'
})
export class ConnectorStatusComponent implements OnInit {
  version: string = packageJson.version;
  monitorings$: Observable<ConnectorStatus>;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations$: Observable<ConnectorConfiguration[]> = new Observable();
  statusLogs$: Observable<any[]>;
  statusLogs: any[] = [];
  filterStatusLog = {
    // eventType: StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE,
    eventType: 'ALL',
    connectorIdent: 'ALL'
  };
  StatusEventTypes = StatusEventTypes;

  constructor(
    public bsModalService: BsModalService,
    public connectorStatusService: ConnectorStatusService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) {}

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.feature = await this.sharedService.getFeatures();
    await this.connectorStatusService.startConnectorStatusLogs();
    this.configurations$ =
      this.connectorConfigurationService.getConnectorConfigurationsWithLiveStatus();
    this.statusLogs$ = this.connectorStatusService.getStatusLogs();
  }

  updateStatusLogs() {
    this.connectorStatusService.updateStatusLogs(this.filterStatusLog);
  }
}
