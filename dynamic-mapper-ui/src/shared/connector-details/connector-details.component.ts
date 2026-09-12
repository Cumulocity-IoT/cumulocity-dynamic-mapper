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
import { firstValueFrom, Observable, Subject, takeUntil, tap } from 'rxjs';
import packageJson from '../../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  Direction,
  Feature,
  getSeverityBadgeClass,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedService,
  ConnectorType
} from '..';
import { ServiceConfiguration } from '../../configuration';
import { ConnectorLogService } from '../service/connector-log.service';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ActivatedRoute } from '@angular/router';
import { ConnectorConfigurationDrawerComponent } from '../connector-configuration/edit/connector-configuration-drawer.component';
import { applyConnectorConfigurationChange, awaitDrawerResult, ConnectorConfigurationApiPayload, toggleConnectorConnection } from '../connector-configuration/connector.model';
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
  initialStateDrawer: any;
  isTogglingConnection = false;

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
    this.route.data.pipe(
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

    // Keep `configuration` (enabled/status) live via the same polling stream the grid uses,
    // instead of only the one-shot route-resolver snapshot: onConfigurationToggle()'s optimistic
    // flip of `enabled` is otherwise never corrected if the connect/reconnect attempt it
    // triggered ultimately fails or is still backing off.
    const identifier = this.route.snapshot.paramMap.get('identifier');
    this.connectorConfigurationService.getConfigurationsWithStatus().pipe(
      takeUntil(this.destroy$)
    ).subscribe(configurations => {
      const match = configurations.find(config => config.identifier === identifier);
      if (match) {
        this.configuration = match;
        this.cdr.detectChanges();
      }
    });
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
    // Mirrors the html's own gating for which of the two buttons ("Update configuration" /
    // "View configuration") is shown — both call this same method, so it must independently
    // work out which of the two the user actually clicked. Without this, `action` stayed
    // undefined, which the drawer's `mode`/`readOnly` derivation silently treats as 'view' for
    // the header/title but NOT for the actual editable-vs-read-only state (that's driven only
    // by `configuration.enabled`) — so a non-admin viewing a disabled connector saw an
    // editable form under a title that still claimed "View connector".
    const action = !configuration.enabled && this.feature?.userHasMappingAdminRole ? 'update' : 'view';
    this.initialStateDrawer = {
      add: false,
      action,
      configuration: configuration,
      specifications: specifications
    };
    const drawer = this.bottomDrawerService.openDrawer(ConnectorConfigurationDrawerComponent, { initialState: this.initialStateDrawer });
    const resultOf = await awaitDrawerResult(drawer.instance.result);
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
    // Guards against a double-click firing two connect/disconnect operations back-to-back —
    // the actual `enabled` state is then corrected by the live getConfigurationsWithStatus()
    // subscription in ngOnInit() once the operation completes, rather than an optimistic flip.
    if (this.isTogglingConnection) return;
    this.isTogglingConnection = true;
    try {
      const queued = await toggleConnectorConnection(this.sharedService, this.alertService, this.configuration);
      if (queued) {
        // Optimistic, for instant feedback — the live getConfigurationsWithStatus() subscription
        // in ngOnInit() will correct this afterward if the connect/reconnect attempt it queued
        // ultimately fails or is still backing off.
        this.configuration.enabled = !this.configuration.enabled;
      }
    } finally {
      this.isTogglingConnection = false;
    }
    this.reloadData();
    this.sharedService.refreshMappings(Direction.INBOUND);
    this.sharedService.refreshMappings(Direction.OUTBOUND);
  }

  private async handleModalResponse(
    response: ConnectorConfiguration | undefined,
    successMessage: string,
    errorMessage: string,
    action: (config: ConnectorConfigurationApiPayload) => Promise<any>
  ): Promise<void> {
    await applyConnectorConfigurationChange(this.alertService, this.connectorConfigurationService, response, successMessage, errorMessage, action);
  }

  reloadData(): void {
    this.connectorConfigurationService.refreshConfigurations();
  }
}