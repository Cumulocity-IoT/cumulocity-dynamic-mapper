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
  OnDestroy,
  ViewChild,
  AfterViewInit,
  ElementRef,
  Renderer2,
  AfterViewChecked
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
  from,
  Observable,
  ReplaySubject,
  Subject
} from 'rxjs';

import * as _ from 'lodash';
import { ConfirmationModalComponent } from '../confirmation/confirmation-modal.component';
import { ConnectorConfigurationService } from '../connector-configuration.service';
import {
  ConnectorStatus,
  StatusEventTypes
} from '../connector-log/connector-status.model';
import { DeploymentMapEntry } from '../model/shared.model';
import { uuidCustom } from '../model/util';
import { ConfigurationConfigurationModalComponent } from './connector-configuration-modal.component';
import {
  ConnectorConfiguration,
  ConnectorSpecification
} from './connector.model';
import { StatusEnabledRendererComponent } from './status-enabled-renderer.component';
import { ConnectorStatusRendererComponent } from './connector-status.renderer.component';
import { CheckedRendererComponent } from './checked-renderer.component';

@Component({
  selector: 'd11r-mapping-connector-configuration',
  styleUrls: ['./connector-grid.component.style.css'],
  templateUrl: 'connector-grid.component.html'
})
export class ConnectorConfigurationComponent implements OnInit, AfterViewInit {
  @Input() selectable = true;
  @Input() readOnly = false;
  @Input() deploy: string[];
  private _deploymentMapEntry: DeploymentMapEntry;
  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
    this.deploymentMapEntryChange.emit(value);
  }
  @Output() deploymentMapEntryChange = new EventEmitter<any>();
  selected: string[] = [];
  selected$: Subject<string[]> = new BehaviorSubject([]);
  selectedAll: boolean = false;
  monitoring$: Observable<ConnectorStatus>;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];
  configurations$: Subject<ConnectorConfiguration[]> = new ReplaySubject(1);
  StatusEventTypes = StatusEventTypes;
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
    private el: ElementRef,
    private renderer: Renderer2
  ) {}

  ngAfterViewInit(): void {
    setTimeout(async () => {
      if (this.selectable && !this.readOnly) {
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
        showIf: (item) => !item['enabled'] && !this.readOnly
      },
      {
        type: 'VIEW',
        icon: 'eye',
        callback: this.onConfigurationUpdate.bind(this),
        showIf: (item) => item['enabled']
      },
      {
        text: 'Duplicate',
        type: 'duplicate',
        icon: 'duplicate',
        callback: this.onConfigurationCopy.bind(this),
        showIf: (item) => !item['enabled'] && !this.readOnly
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.onConfigurationDelete.bind(this),
        showIf: (item) => !item['enabled'] && !this.readOnly
      }
    );

    this.columns.push(
      {
        name: ' ',
        header: '',
        path: 'checked',
        filterable: false,
        sortOrder: 'asc',
        visible: this.selectable && this.readOnly,
        gridTrackSize: '7%',
        cellRendererComponent: CheckedRendererComponent
      },
      {
        name: 'ident',
        header: 'Ident',
        path: 'ident',
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
        visible: true
      },
      {
        name: 'connectorType',
        header: 'Type',
        path: 'connectorType',
        filterable: false,
        sortOrder: 'asc',
        visible: true,
        gridTrackSize: '20%'
      },
      {
        header: 'Status',
        name: 'status',
        path: 'status',
        filterable: false,
        sortable: true,
        cellRendererComponent: ConnectorStatusRendererComponent,
        gridTrackSize: '20%'
      },
      {
        header: 'Enabled',
        name: 'enabled',
        path: 'enabled',
        filterable: false,
        sortable: true,
        cellRendererComponent: StatusEnabledRendererComponent,
        gridTrackSize: '15%'
      }
    );
    this.selected = this.deploymentMapEntry?.connectors ?? [];

    this.selected$.next(this.selected);
    this.selected$.subscribe((se) => {
      if (this.selectable) {
        this.deploymentMapEntry.connectors = se;
      }
    });

    from(
      this.connectorConfigurationService.getConnectorSpecifications()
    ).subscribe((specs) => {
      this.specifications = specs;
    });

    this.connectorConfigurationService
      .getRealtimeConnectorConfigurations()
      .subscribe((confs) => {
        this.configurations$.next(confs);
      });

    this.configurations$.subscribe((confs) => {
      this.configurations = confs;
      if (this.selectable && this.readOnly)
        this.configurations?.forEach(
          (conf) => (conf['checked'] = this.selected.includes(conf.ident))
        );
    });
  }

  public onSelectToggle(id: string) {
    if (this.isSelected(id)) {
      this.selected = this.selected.filter((ident) => id !== ident);
    } else {
      this.selected.push(id);
    }
    this.selected$.next(this.selected);
  }

  public isSelected(id: string): boolean {
    return this.selected.includes(id);
  }

  public onSelectToggleAll() {
    if (this.isSelectedAll()) {
      this.selectedAll = false;
      this.selected = [];
    } else {
      this.selectedAll = true;
      this.configurations.forEach((con) => this.selected.push(con.ident));
    }
    this.selected$.next(this.selected);
  }
  public onSelectionChanged(selected: any) {
    this.selected = selected;
    this.selected$.next(this.selected);
  }

  public isSelectedAll(): boolean {
    return this.selectedAll;
  }

  refresh() {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }

  reloadData(): void {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }

  async onConfigurationUpdate(config: ConnectorConfiguration) {
    const index = this.configurations.findIndex(
      (conf) => conf.ident == config.ident
    );
    const configuration = _.clone(this.configurations[index]);
    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications,
      readOnly: this.readOnly
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
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
          ident: editedConfiguration.ident,
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
      (conf) => conf.ident == config.ident
    );
    const configuration = _.clone(this.configurations[index]);
    // const configuration = _.clone(config);
    configuration.ident = uuidCustom();
    configuration.name = `${configuration.name}_copy`;
    this.alertService.warning(
      gettext(
        'Review properties, e.g. client_id must be different across different client connectors to the same broker.'
      )
    );

    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
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
          ident: editedConfiguration.ident,
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
      (conf) => conf.ident == config.ident
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
              configuration.ident
            );
          if (response.status < 300) {
            this.alertService.success(gettext('Deleted successfully.'));
          } else {
            this.alertService.danger(
              gettext('Failed to delete connector configuration')
            );
          }
          await this.reloadData();
        }
        confirmDeletionModalRef.hide();
      }
    );
  }

  async onConfigurationAdd() {
    const configuration: Partial<ConnectorConfiguration> = {
      properties: {},
      ident: uuidCustom()
    };
    const initialState = {
      add: true,
      configuration: configuration,
      specifications: this.specifications,
      configurationsCount: this.configurations?.length
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
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
          ident: addedConfiguration.ident,
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

  findNameByIdent(ident: string): string {
    return this.configurations?.find((conf) => conf.ident == ident)?.name;
  }
}
