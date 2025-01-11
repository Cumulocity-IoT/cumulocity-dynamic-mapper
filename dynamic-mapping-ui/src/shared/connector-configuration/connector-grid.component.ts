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
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  AfterViewInit,
  ViewEncapsulation,
} from '@angular/core';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  Column,
  DataGridComponent,
  gettext,
  Pagination
} from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import {
  BehaviorSubject,
  combineLatest,
  from,
  Observable,
  Subject,
  take
} from 'rxjs';

import * as _ from 'lodash';
import { ConfirmationModalComponent } from '../confirmation/confirmation-modal.component';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import {
  ConnectorStatus,
  LoggingEventType
} from '../connector-log/connector-log.model';
import { DeploymentMapEntry } from '../mapping/mapping.model';
import { uuidCustom } from '../mapping/util';
import { ConnectorConfigurationModalComponent } from './create/connector-configuration-modal.component';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorType
} from './connector.model';
import { StatusEnabledRendererComponent } from './renderer/status-enabled-renderer.component';
import { ConnectorStatusRendererComponent } from './renderer/connector-status.renderer.component';
import { LabelRendererComponent } from '../component/renderer/label.renderer.component';
import { ConnectorDetailCellRendererComponent } from './renderer/connector-link.renderer.component';

@Component({
  selector: 'd11r-mapping-connector-configuration',
  styleUrls: ['./connector-grid.component.style.css'],
  templateUrl: 'connector-grid.component.html',
  encapsulation: ViewEncapsulation.None
})
export class ConnectorGridComponent implements OnInit, AfterViewInit {
  @Input() selectable = true;
  @Input() readOnly = false;
  @Input() deploy: string[];
  @Input() deploymentMapEntry: DeploymentMapEntry;

  @Output() deploymentMapEntryChange = new EventEmitter<any>();
  selected: string[] = [];
  selected$: Subject<string[]>;
  selectedAll: boolean = false;
  monitoring$: Observable<ConnectorStatus>;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];
  customClasses: string;
  configurations$: Observable<ConnectorConfiguration[]>;
  LoggingEventType = LoggingEventType;
  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  columns: Column[] = [];
  actionControls: ActionControl[] = [];

  @ViewChild('connectorGrid', { static: false })
  connectorGrid: DataGridComponent;

  constructor(
    private bsModalService: BsModalService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private alertService: AlertService,
  ) { }

  ngAfterViewInit(): void {
    setTimeout(async () => {
      if (this.selectable) {
        this.connectorGrid.setItemsSelected(this.selected, true);
      }
    }, 0);
  }

  ngOnInit() {
    // console.log('connector-configuration', this._deploymentMapEntry, this.deploymentMapEntry);

    this.actionControls.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.onConfigurationUpdate.bind(this),
        showIf: (item) => !item['enabled'] && !this.readOnly && item['connectorType'] != ConnectorType.HTTP
      },
      {
        type: 'VIEW',
        icon: 'eye',
        callback: this.onConfigurationUpdate.bind(this),
        showIf: (item) => item['enabled'] || item['connectorType'] == ConnectorType.HTTP
      },
      {
        text: 'Duplicate',
        type: 'duplicate',
        icon: 'duplicate',
        callback: this.onConfigurationCopy.bind(this),
        showIf: (item) => !item['enabled'] && !this.readOnly && item['connectorType'] != ConnectorType.HTTP
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.onConfigurationDelete.bind(this),
        showIf: (item) => !item['enabled'] && !this.readOnly && item['connectorType'] != ConnectorType.HTTP
      }
    );

    this.columns.push(
      {
        name: 'identifier',
        header: 'Identifier',
        path: 'identifier',
        filterable: false,
        sortOrder: 'asc',
        visible: false,
        gridTrackSize: '10%'
      },
      {
        name: 'name',
        header: 'Name',
        path: 'name',
        filterable: false,
        sortOrder: 'asc',
        visible: true,
        cellRendererComponent: ConnectorDetailCellRendererComponent,
        gridTrackSize: '30%'
      },
      {
        name: 'connectorType',
        header: 'Type',
        path: 'connectorType',
        filterable: false,
        sortOrder: 'asc',
        visible: true,
        cellRendererComponent: LabelRendererComponent,
        gridTrackSize: '25%'
      },
      {
        header: 'Status',
        name: 'status',
        path: 'status',
        filterable: false,
        sortable: true,
        cellRendererComponent: ConnectorStatusRendererComponent,
        gridTrackSize: (this.selectable) ? '17%' : '21%'
      },
      {
        header: 'Enabled',
        name: 'enabled',
        path: 'enabled',
        filterable: false,
        sortable: true,
        cellRendererComponent: StatusEnabledRendererComponent,
        gridTrackSize: (this.selectable) ? '16%' : '19%'
      }
    );

    this.configurations$ =
      this.connectorConfigurationService.getConnectorConfigurationsWithLiveStatus();

    this.selected = this.deploymentMapEntry?.connectors ?? [];
    this.selected$ = new BehaviorSubject(this.selected);

    combineLatest([this.selected$, this.configurations$]).subscribe(
      ([se, conf]) => {
        this.configurations = conf;
        if (this.selectable) {
          this.deploymentMapEntry.connectors = se;
          this.deploymentMapEntry.connectorsDetailed = conf.filter((con) =>
            se.includes(con.identifier)
          );
          this.deploymentMapEntryChange.emit(this.deploymentMapEntry);
          if (this.readOnly)
            this.configurations?.forEach(
              (conf) => {conf['checked'] = this.selected.includes(conf.identifier);
                conf['readOnly'] = this.readOnly;
              }
            );
        }
      }
    );

    from(this.connectorConfigurationService.getConnectorSpecifications())
      .pipe(take(1))
      .subscribe((specs) => {
        this.specifications = specs;
      });

    this.customClasses = this.shouldHideBulkActionsAndReadOnly ? 'hide-bulk-actions' : '';
  }

  get shouldHideBulkActionsAndReadOnly(): boolean {
    return this.selectable && this.readOnly;
  }

  public onSelectionChanged(selected: any) {
    this.selected = selected;
    this.selected$?.next(this.selected);
  }

  refresh() {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }

  reloadData(): void {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }

  async onConfigurationUpdate(config: ConnectorConfiguration) {
    const index = this.configurations.findIndex(
      (conf) => conf.identifier == config.identifier
    );
    const configuration = _.clone(this.configurations[index]);
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
        this.configurations[index] = editedConfiguration;
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

  async onConfigurationCopy(config: ConnectorConfiguration) {
    const index = this.configurations.findIndex(
      (conf) => conf.identifier == config.identifier
    );
    const configuration = _.clone(this.configurations[index]);
    // const configuration = _.clone(config);
    configuration.identifier = uuidCustom();
    configuration.name = `${configuration.name}_copy`;
    this.alertService.warning(
      gettext(
        'Review properties, e.g. client_id must be different across different client connectors to the same broker.'
      )
    );

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
        this.configurations[index] = editedConfiguration;
        // avoid to include status$
        const clonedConfiguration = {
          identifier: editedConfiguration.identifier,
          connectorType: editedConfiguration.connectorType,
          enabled: editedConfiguration.enabled,
          name: editedConfiguration.name,
          properties: editedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(gettext('Updated successfully.'));
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration!')
          );
        }
      }
      this.reloadData();
    });
  }

  async onConfigurationDelete(config: ConnectorConfiguration) {
    const index = this.configurations.findIndex(
      (conf) => conf.identifier == config.identifier
    );
    const configuration = _.clone(this.configurations[index]);

    const initialState = {
      title: 'Delete connector',
      message: 'You are about to delete a connector. Do you want to proceed?',
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
          const response =
            await this.connectorConfigurationService.deleteConnectorConfiguration(
              configuration.identifier
            );
          if (response.status < 300) {
            this.alertService.success(gettext('Deleted successfully.'));
          } else {
            this.alertService.danger(
              gettext('Failed to delete connector configuration')
            );
          }
          this.refresh();
        }
        confirmDeletionModalRef.hide();
      }
    );
  }

  async onConfigurationAdd() {
    const configuration: Partial<ConnectorConfiguration> = {
      properties: {},
      identifier: uuidCustom()
    };
    const initialState = {
      add: true,
      configuration: configuration,
      specifications: this.specifications,
      configurationsCount: this.configurations?.length,
      readOnly: configuration.enabled
    };
    const modalRef = this.bsModalService.show(
      ConnectorConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (addedConfiguration) => {
      // console.log('Configuration after edit:', addedConfiguration);
      if (addedConfiguration) {
        this.configurations.push(addedConfiguration);
        // avoid to include status$
        const clonedConfiguration = {
          identifier: addedConfiguration.identifier,
          connectorType: addedConfiguration.connectorType,
          enabled: addedConfiguration.enabled,
          name: addedConfiguration.name,
          properties: addedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(
            gettext('Added successfully configuration')
          );
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration')
          );
        }
      }
      this.refresh();
    });
  }

  findNameByIdent(identifier: string): string {
    return this.configurations?.find((conf) => conf.identifier == identifier)?.name;
  }
}
