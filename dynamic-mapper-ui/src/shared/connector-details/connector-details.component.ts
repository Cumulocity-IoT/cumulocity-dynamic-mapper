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
import { ChangeDetectorRef, Component, inject, OnDestroy, OnInit } from '@angular/core';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { firstValueFrom, Observable, Subject, Subscription, takeUntil, tap } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Direction,
  Feature,
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
import { ConnectorConfigurationDrawerComponent } from '../connector-configuration/edit/connector-configuration-drawer.component';
import { gettext } from '@c8y/ngx-components/gettext';

@Component({
  selector: 'd11r-mapping-connector-details',
  styleUrls: ['./connector-details.component.style.css'],
  templateUrl: 'connector-details.component.html',
  standalone: false
})
export class ConnectorDetailsComponent implements OnInit, OnDestroy {
  version: string = packageJson.version;
  monitoring$: Observable<ConnectorStatus>;
  specifications$: Observable<ConnectorSpecification[]>;
  statusLogs$: Observable<any[]>;
  configuration: ConnectorConfiguration;
  feature: Feature;
  filterStatusLog = {
    connectorIdentifier: 'ALL',
    type: LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE,
  };
  LoggingEventTypeMap = LoggingEventTypeMap;
  LoggingEventType = LoggingEventType;
  ConnectorType = ConnectorType;
  contextSubscription: Subscription;
  initialStateDrawer: any;

  private destroy$ = new Subject<void>();

  connectorStatusService = inject(ConnectorLogService);
  route = inject(ActivatedRoute);
  alertService = inject(AlertService);
  sharedService = inject(SharedService);
  bsModalService = inject(BsModalService);
  bottomDrawerService = inject(BottomDrawerService);
  connectorConfigurationService = inject(ConnectorConfigurationService);
  cdr = inject(ChangeDetectorRef);

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.specifications$ = this.connectorConfigurationService.getSpecifications();
    this.feature = await this.sharedService.getFeatures();
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
    this.connectorStatusService.startConnectorStatusLogs();
    this.statusLogs$ = this.connectorStatusService.getStatusLogs();
    // Subscribe to logs to verify they're coming through
    this.statusLogs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      // next: (logs) => console.log('Received logs in component:', logs),
      error: (error) => console.error('Error receiving logs:', error),
      // complete: () => console.log('Completed') // optional
    });
    this.updateStatusLogs();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
    this.connectorStatusService.stopConnectorStatusLogs();
  }

  updateStatusLogs() {
    this.connectorStatusService.updateStatusLogs(this.filterStatusLog);
  }

  async onConfigurationUpdate(): Promise<void> {
    const configuration = _.clone(this.configuration);

    const specifications = await firstValueFrom(this.specifications$);
    this.initialStateDrawer = {
      add: false,
      configuration: configuration,
      specifications: specifications,
      readOnly: configuration.enabled
    };
    const drawer = this.bottomDrawerService.openDrawer(ConnectorConfigurationDrawerComponent, { initialState: this.initialStateDrawer });
    const resultOf = await drawer.instance.result;
    if (typeof resultOf === 'object' && resultOf !== null) {
      this.configuration = resultOf as ConnectorConfiguration;
    }

    if (this.initialStateDrawer.add) {
      await this.handleModalResponse(
        resultOf,
        'Added successfully.',
        'Failed to create connector configuration',
        config => this.connectorConfigurationService.createConfiguration(config)
      );
    } else {
      await this.handleModalResponse(
        resultOf,
        'Updated successfully.',
        'Failed to update connector configuration',
        config => this.connectorConfigurationService.updateConfiguration(config)
      );
    }
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

  private async handleModalResponse(
    response: any,
    successMessage: string,
    errorMessage: string,
    action: (config: any) => Promise<any>
  ): Promise<void> {
    if (!response) return;

    const clonedConfiguration = this.prepareConfiguration(response);
    const apiResponse = await action(clonedConfiguration);

    if (apiResponse.status < 300) {
      this.alertService.success(gettext(successMessage));
    } else {
      this.alertService.danger(gettext(errorMessage));
    }
    this.reloadData();
  }

  reloadData(): void {
    this.connectorConfigurationService.refreshConfigurations();
  }

  private prepareConfiguration(config: ConnectorConfiguration): Partial<ConnectorConfiguration> {
    return {
      identifier: config.identifier,
      connectorType: config.connectorType,
      enabled: config.enabled,
      name: config.name,
      properties: config.properties
    };
  }
}