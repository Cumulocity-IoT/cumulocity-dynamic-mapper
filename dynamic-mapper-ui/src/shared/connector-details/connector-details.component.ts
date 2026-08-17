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
import { ChangeDetectorRef, Component, inject, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { AlertService, BottomDrawerService, CoreModule } from '@c8y/ngx-components';
import { firstValueFrom, Observable, Subject, Subscription, takeUntil, tap } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Direction,
  Feature,
  getSeverityBadgeClass,
  LoggingEventType,
  LoggingEventTypeMap,
  Operation,
  SharedService,
  ConnectorType,
  ALERT_INFO_TIMEOUT
} from '..';
import { ServiceConfiguration } from '../../configuration';
import { ConnectorLogService } from '../service/connector-log.service';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ActivatedRoute } from '@angular/router';
import { HttpStatusCode } from '@angular/common/http';
import { ConnectorConfigurationDrawerComponent } from '../connector-configuration/edit/connector-configuration-drawer.component';
import { gettext } from '@c8y/ngx-components/gettext';
// Imported directly (not via the shared barrel): these are referenced inside the @Component
// decorator's `imports` array, evaluated synchronously at module-load time. The barrel
// (shared/index.ts) exports this very component before it exports shared.module /
// connector-status-history.component, so going through the barrel here would read them back
// as undefined mid-circular-import — see the SharedModule TypeError this replaced.
import { SharedModule } from '../shared.module';
import { ConnectorStatusHistoryComponent } from './connector-status-history.component';

@Component({
  selector: 'd11r-mapping-connector-details',
  styleUrls: ['./connector-details.component.style.css'],
  templateUrl: 'connector-details.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [
    CoreModule,
    SharedModule,
    ConnectorStatusHistoryComponent
  ]
})
export class ConnectorDetailsComponent implements OnInit, OnDestroy {
  version: string = packageJson.version;
  monitoring$: Observable<ConnectorStatus>;
  specifications$: Observable<ConnectorSpecification[]>;
  statusLogs$: Observable<any[]>;
  configuration: ConnectorConfiguration;
  feature: Feature;
  serviceConfiguration: ServiceConfiguration;
  filterStatusLog = {
    connectorIdentifier: 'ALL',
    type: LoggingEventType.CONNECTOR_EVENT_TYPE,
  };
  LoggingEventTypeMap = LoggingEventTypeMap;
  LoggingEventType = LoggingEventType;
  ConnectorType = ConnectorType;
  contextSubscription: Subscription;
  initialStateDrawer: any;

  private readonly destroy$ = new Subject<void>();

  private readonly connectorStatusService = inject(ConnectorLogService);
  private readonly route = inject(ActivatedRoute);
  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly connectorConfigurationService = inject(ConnectorConfigurationService);
  private readonly cdr = inject(ChangeDetectorRef);

  async ngOnInit() {
    this.specifications$ = this.connectorConfigurationService.getSpecifications();
    this.feature = await this.sharedService.getFeatures();
    this.serviceConfiguration = await this.sharedService.getServiceConfiguration();
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
    this.statusLogs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      error: (error) => console.error('Error receiving logs:', error)
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

  getSeverityClass(severity?: string | null): string {
    return getSeverityBadgeClass(severity ?? 'info');
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
    if (response1.status === HttpStatusCode.Created) {
      const wasConnecting = !this.configuration.enabled;
      this.configuration.enabled = !this.configuration.enabled;
      this.alertService.add({
        text: wasConnecting
          ? gettext('Connector is connecting, please wait...')
          : gettext('Connector disconnected.'),
        type: 'info',
        timeout: ALERT_INFO_TIMEOUT
      });
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