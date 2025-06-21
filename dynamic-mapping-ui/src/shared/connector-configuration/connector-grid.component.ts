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
import { Component, EventEmitter, Input, OnInit, Output, ViewChild, AfterViewInit, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, Column, DataGridComponent, gettext, Pagination } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, combineLatest, from, Observable, } from 'rxjs';
import { map, take } from 'rxjs/operators';
import { cloneDeep } from 'lodash';

import { ConfirmationModalComponent } from '../confirmation/confirmation-modal.component';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ConnectorStatus, LoggingEventType } from '../connector-log/connector-log.model';
import { DeploymentMapEntry, Direction, Feature } from '../mapping/mapping.model';
import { createCustomUuid } from '../mapping/util';
import { ConnectorConfigurationModalComponent } from './edit/connector-configuration-modal.component';
import { ConnectorConfiguration, ConnectorSpecification, ConnectorType } from './connector.model';
import { ACTION_CONTROLS, GRID_COLUMNS } from './action-controls';
import { ActionVisibilityRule } from './types';
import { SharedService } from '..';

@Component({
  selector: 'd11r-mapping-connector-grid',
  styleUrls: ['./connector-grid.component.style.css'],
  templateUrl: 'connector-grid.component.html',
  encapsulation: ViewEncapsulation.None
})
export class ConnectorGridComponent implements OnInit, AfterViewInit {
  @Input() selectable = true;
  @Input() directions: Direction[] = [Direction.INBOUND, Direction.OUTBOUND];
  @Input() readOnly = false;
  @Input() deploy: string[];
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() deploymentMapEntryChange = new EventEmitter<DeploymentMapEntry>();

  @ViewChild('connectorGrid') connectorGrid: DataGridComponent;

  selected: string[] = [];
  selected$ = new BehaviorSubject<string[]>([]);
  monitoring$: Observable<ConnectorStatus>;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[] = [];
  configurations$: Observable<ConnectorConfiguration[]>;
  customClasses: string;

  readonly LoggingEventType = LoggingEventType;
  readonly pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };

  columns: Column[];
  actionControls: ActionControl[];
  feature: Feature;

  constructor(
    private bsModalService: BsModalService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private alertService: AlertService,
    public sharedService: SharedService,
  ) {

  }

  async ngOnInit(): Promise<void> {
    this.initializeColumns();
    this.initializeActionControls();
    this.initializeSelection();
    this.initializeConfigurations();
    this.initializeSpecifications();
    this.customClasses = this.shouldHideBulkActionsAndReadOnly ? 'hide-bulk-actions' : '';
    this.feature = await this.sharedService.getFeatures();
  }

  ngAfterViewInit(): void {
    if (this.selectable) {
      setTimeout(() => this.connectorGrid.setItemsSelected(this.selected, true), 0);
    }
  }
  private initializeActionControls(): void {
    this.actionControls = ACTION_CONTROLS.map(control => ({
      ...control,
      callback: this[control.callbackName].bind(this),
      showIf: (item: ConnectorConfiguration) =>
        this.checkActionVisibility(item, control.visibilityRules)
    }));
  }


  private initializeColumns(): void {
    this.columns = GRID_COLUMNS.map(column => ({
      ...column,
      gridTrackSize: column.name === 'status' || column.name === 'enabled'
        ? this.selectable ? column.gridTrackSize : `${parseInt(column.gridTrackSize) + 4}%`
        : column.gridTrackSize,
      cellRendererComponent: this.selectable && column.name === 'name' ? undefined : column.cellRendererComponent,
    }));
  }

  private initializeConfigurations(): void {
    this.configurations$ = this.connectorConfigurationService.getConfigurationsWithStatus().pipe(
      map(configs => configs.filter(config =>
        config.supportedDirections?.some(dir => this.directions.includes(dir))
      ))
    )
    this.setupConfigurationsSubscription();
  }

  private initializeSpecifications(): void {
    from(this.connectorConfigurationService.getSpecifications())
      .pipe(take(1))
      .subscribe(specs => this.specifications = specs);
  }

  private initializeSelection(): void {
    this.selected = this.deploymentMapEntry?.connectors ?? [];
    this.selected$.next(this.selected);
  }

  private setupConfigurationsSubscription(): void {
    combineLatest([this.selected$, this.configurations$]).subscribe(([selected, configurations]) => {
      this.configurations = configurations;
      if (this.selectable) {
        this.updateDeploymentMapEntry(selected, configurations);
      }
    });
  }

  private updateDeploymentMapEntry(selected: string[], configurations: ConnectorConfiguration[]): void {
    if (!this.deploymentMapEntry) return;

    this.deploymentMapEntry.connectors = selected;
    this.deploymentMapEntry.connectorsDetailed = configurations.filter(con => selected.includes(con.identifier));
    this.deploymentMapEntryChange.emit(this.deploymentMapEntry);

    if (this.readOnly) {
      this.updateReadOnlyConfigurations(selected);
    }
  }

  private updateReadOnlyConfigurations(selected: string[]): void {
    this.configurations?.forEach(conf => {
      conf['checked'] = selected.includes(conf.identifier);
      conf['readOnly'] = this.readOnly;
    });
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
    this.refresh();
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

  // Public methods
  onSelectionChanged(selected: string[]): void {
    this.selected = selected;
    this.selected$.next(selected);
  }

  refresh(): void {
    this.connectorConfigurationService.refreshConfigurations();
  }

  async onConfigurationUpdate(config: ConnectorConfiguration): Promise<void> {
    const modalRef = this.showConfigurationModal(config, false);
    modalRef.content.closeSubject.subscribe(async editedConfiguration => {
      await this.handleModalResponse(
        editedConfiguration,
        'Updated successfully.',
        'Failed to update connector configuration',
        config => this.connectorConfigurationService.updateConfiguration(config)
      );
    });
  }

  async onConfigurationCopy(config: ConnectorConfiguration): Promise<void> {
    const copiedConfig = this.prepareCopyConfiguration(config);
    const modalRef = this.showConfigurationModal(copiedConfig, false);
    modalRef.content.closeSubject.subscribe(async editedConfiguration => {
      await this.handleModalResponse(
        editedConfiguration,
        'Created successfully.',
        'Failed to create connector configuration',
        config => this.connectorConfigurationService.createConfiguration(config)
      );
    });
  }

  async onConfigurationDelete(config: ConnectorConfiguration): Promise<void> {
    const modalRef = this.showConfirmationModal();
    modalRef.content.closeSubject.subscribe(async (result: boolean) => {
      if (result) {
        await this.handleModalResponse(
          config,
          'Deleted successfully.',
          'Failed to delete connector configuration',
          config => this.connectorConfigurationService.deleteConfiguration(config.identifier)
        );
      }
      modalRef.hide();
    });
  }

  async onConfigurationAdd(): Promise<void> {
    const newConfig: Partial<ConnectorConfiguration> = {
      properties: {},
      identifier: createCustomUuid()
    };
    const modalRef = this.showConfigurationModal(newConfig, true);
    modalRef.content.closeSubject.subscribe(async addedConfiguration => {
      await this.handleModalResponse(
        addedConfiguration,
        'Added successfully.',
        'Failed to create connector configuration',
        config => this.connectorConfigurationService.createConfiguration(config)
      );
    });
  }

  findNameByIdent(identifier: string): string {
    return this.configurations?.find(conf => conf.identifier === identifier)?.name;
  }

  get shouldHideBulkActionsAndReadOnly(): boolean {
    //return this.selectable && this.readOnly;
    return this.readOnly;
  }

  // Helper methods for showing modals
  private showConfigurationModal(config: Partial<ConnectorConfiguration>, isAdd: boolean): BsModalRef {
    return this.bsModalService.show(ConnectorConfigurationModalComponent, {
      initialState: {
        add: isAdd,
        configuration: cloneDeep(config),
        specifications: this.specifications,
        configurationsCount: this.configurations?.length,
      }
    });
  }

  private showConfirmationModal(): BsModalRef {
    return this.bsModalService.show(ConfirmationModalComponent, {
      initialState: {
        title: 'Delete connector',
        message: 'You are about to delete a connector. Do you want to proceed?',
        labels: { ok: 'Delete', cancel: 'Cancel' }
      }
    });
  }

  // Missing methods for the component:
  private prepareCopyConfiguration(config: ConnectorConfiguration): Partial<ConnectorConfiguration> {
    const copiedConfig = cloneDeep(config);
    copiedConfig.identifier = createCustomUuid();
    copiedConfig.name = `${copiedConfig.name}_copy`;

    this.alertService.warning(
      gettext('Review properties, e.g. client_id must be different across different client connectors to the same broker.')
    );

    return copiedConfig;
  }

  private checkActionVisibility(
    item: ConnectorConfiguration,
    rules: ActionVisibilityRule[]
  ): boolean {
    return rules.every(rule => {
      switch (rule.type) {
        case 'enabled':
          return (item.enabled === rule.value);
        case 'readOnly':
          return (this.readOnly === rule.value);
        case 'connectorType':
          return item.connectorType !== ConnectorType.HTTP;
        case 'userRole':
          const userHasAdminRole = this.feature?.userHasMappingAdminRole;
          if (rule.value === 'viewLogic') {
            // Show VIEW if: (admin + enabled) OR (non-admin + any status)
            return (userHasAdminRole && item.enabled) || !userHasAdminRole;
          }
          return rule.value ? userHasAdminRole : !userHasAdminRole;
        default:
          return true;
      }
    });
  }
}
