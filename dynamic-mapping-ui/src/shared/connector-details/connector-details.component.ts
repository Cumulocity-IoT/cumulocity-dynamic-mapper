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
import * as _ from 'lodash';
import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable, Subject, Subscription, takeUntil, tap } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorConfigurationModalComponent,
  ConnectorSpecification,
  ConnectorStatus,
  Direction,
  LoggingEventType,
  LoggingEventTypeMap,
  Operation,
  SharedService,
  ConnectorType
} from '..';
import { ConnectorLogService } from '../service/connector-log.service';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ActivatedRoute } from '@angular/router';
import { HttpStatusCode } from '@angular/common/http';

@Component({
  selector: 'd11r-mapping-connector-details',
  styleUrls: ['./connector-details.component.style.css'],
  templateUrl: 'connector-details.component.html'
})
export class ConnectorDetailsComponent implements OnInit, OnDestroy {
  version: string = packageJson.version;
  monitorings$: Observable<ConnectorStatus>;
  specifications: ConnectorSpecification[] = [];
  statusLogs$: Observable<any[]>;
  configuration: ConnectorConfiguration;
  filterStatusLog = {
    connectorIdentifier: 'ALL',
    type: LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE,
  };
  LoggingEventTypeMap = LoggingEventTypeMap;
  LoggingEventType = LoggingEventType;
  ConnectorType = ConnectorType;
  contextSubscription: Subscription;
  private destroy$ = new Subject<void>();

  constructor(
    public bsModalService: BsModalService,
    public connectorStatusService: ConnectorLogService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private route: ActivatedRoute,
    public sharedService: SharedService,

    private cdr: ChangeDetectorRef
  ) { }

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.specifications = await this.connectorConfigurationService.getConnectorSpecifications();
    this.contextSubscription = this.route.data.pipe(
      takeUntil(this.destroy$),
      tap(({ connector }) => {
        this.configuration = connector;
        this.cdr.detectChanges();
      }))
      .subscribe(async data => {
        const { connector } = data;
        this.filterStatusLog.connectorIdentifier = connector.identifier;
        this.updateStatusLogs();
      });
    this.connectorStatusService.initConnectorLogsRealtime();
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

  async onConfigurationUpdate() {

    const configuration = _.clone(this.configuration);
    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications,
      readOnly: configuration.enabled
    };
    const modalRef = this.bsModalService.show(
      ConnectorConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (editedConfiguration) => {
      // console.log('Configuration after edit:', editedConfiguration);
      if (editedConfiguration) {
        this.configuration = editedConfiguration;
        // avoid to include status$
        const clonedConfiguration = {
          identifier: editedConfiguration.identifier,
          connectorType: editedConfiguration.connectorType,
          enabled: editedConfiguration.enabled,
          name: editedConfiguration.name,
          properties: editedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.updateConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(gettext('Updated successfully.'));
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration')
          );
        }
      }
      this.reloadData();
    });
  }

  async onConfigurationToggle() {
    const configuration = this.configuration;
    const response1 = await this.sharedService.runOperation(
      configuration.enabled ? { operation: Operation.DISCONNECT, parameter: { connectorIdentifier: configuration.identifier } } : {
        operation: Operation.CONNECT,
        parameter: { connectorIdentifier: configuration.identifier }
      }
    );
    // console.log('Details toggle activation to broker', response1);
    if (response1.status === HttpStatusCode.Created) {
      // if (response1.status === HttpStatusCode.Created && response2.status === HttpStatusCode.Created) {
        Promise.resolve().then(() => {
          this.configuration.enabled = !this.configuration.enabled;
        });
      this.alertService.success(gettext('Connection updated successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to establish connection!'));
    }
    this.reloadData();
    this.sharedService.refreshMappings(Direction.INBOUND);
    this.sharedService.refreshMappings(Direction.OUTBOUND);
  }

  reloadData(): void {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
