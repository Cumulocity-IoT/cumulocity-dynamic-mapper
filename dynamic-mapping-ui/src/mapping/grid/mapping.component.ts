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
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  ColumnDataType,
  DataGridComponent,
  DisplayOptions,
  Pagination,
  gettext
} from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import {
  API,
  ConfirmationModalComponent,
  Direction,
  Mapping,
  MappingEnriched,
  MappingSubstitution,
  MappingType,
  Operation,
  QOS,
  SAMPLE_TEMPLATES_C8Y,
  SnoopStatus,
  getExternalTemplate,
  nextIdAndPad,
  uuidCustom
} from '../../shared';

import { Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Observable, Subject, take } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { ImportMappingsComponent } from '../import/import-modal.component';
import { MappingTypeComponent } from '../mapping-type/mapping-type.component';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { NameRendererComponent } from '../renderer/name.renderer.component';
import { StatusActivationRendererComponent } from '../renderer/status-activation-renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
// import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { MAPPING_TYPE_DESCRIPTION, StepperConfiguration } from '../../shared';
import { DeploymentMapEntry } from '../../shared/model/shared.model';
import { SharedService } from '../../shared/shared.service';
import { MappingDeploymentRendererComponent } from '../renderer/mappingDeployment.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snoopedTemplate.renderer.component';
import {
  C8YNotificationSubscription,
  PayloadWrapper
} from '../shared/mapping.model';
import { EditorMode } from '../shared/stepper-model';
import { HttpStatusCode } from '@angular/common/http';

@Component({
  selector: 'd11r-mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingComponent implements OnInit, OnDestroy {
  @ViewChild('mappingGrid') mappingGrid: DataGridComponent;
  isSubstitutionValid: boolean;

  showConfigMapping: boolean = false;
  showSnoopingMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  mappingsEnriched$: Observable<MappingEnriched[]>;
  mappingsCount: number = 0;
  mappingToUpdate: Mapping;
  subscription: C8YNotificationSubscription;
  devices: IIdentified[] = [];
  snoopStatus: SnoopStatus = SnoopStatus.NONE;
  Direction = Direction;

  stepperConfiguration: StepperConfiguration = {};
  titleMapping: string;
  deploymentMapEntry: DeploymentMapEntry;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  columnsMappings: Column[];
  columnsSubscriptions: Column[] = [
    {
      name: 'id',
      header: 'System ID',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true
    },
    {
      header: 'Name',
      name: 'name',
      path: 'name',
      filterable: true
    }
  ];

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  actionControls: ActionControl[] = [];
  bulkActionControls: BulkActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public shareService: SharedService,
    public alertService: AlertService,
    private bsModalService: BsModalService,
    private router: Router
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.stepperConfiguration.direction = href.match(
      /sag-ps-pkg-dynamic-mapping\/node1\/mappings\/inbound/g
    )
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    this.columnsMappings = this.getColumnsMappings();
    this.titleMapping = `Mapping ${this.stepperConfiguration.direction.toLowerCase()}`;
    this.loadSubscriptions();
  }

  async loadSubscriptions() {
    this.subscription = await this.mappingService.getSubscriptions();
  }

  async ngOnInit() {
    // console.log('ngOnInit');
    this.actionControls.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.updateMapping.bind(this),
        showIf: (item) => !item['mapping']['active']
      },
      {
        type: 'VIEW',
        icon: 'eye',
        callback: this.updateMapping.bind(this),
        showIf: (item) => item['mapping']['active']
      },
      {
        text: 'Duplicate',
        type: 'DUPLICATE',
        icon: 'duplicate',
        callback: this.copyMapping.bind(this)
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingWithConfirmation.bind(this),
        showIf: (item) => !item['mapping']['active']
      },
      {
        type: 'ACTIVATE_MAPPING',
        text: 'Activate',
        icon: 'toggle-on',
        callback: this.activateMapping.bind(this),
        showIf: (item) => !item['mapping']['active']
      },
      {
        type: 'DEACTIVATE_MAPPING',
        text: 'Deactivate',
        icon: 'toggle-off',
        callback: this.activateMapping.bind(this),
        showIf: (item) => item['mapping']['active']
      },
      {
        type: 'ENABLE_DEBUG',
        text: 'Enable debugging',
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: (item) => !item['mapping']['debug']
      },
      {
        type: 'ENABLE_DEBUG',
        text: 'Disable debugging',
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: (item) => item['mapping']['debug']
      },
      {
        type: 'ENABLE_SNOOPING',
        text: 'Enable snooping',
        icon: 'mic',
        callback: this.toggleSnoopStatusMapping.bind(this),
        showIf: (item) =>
          item['mapping']['direction'] === Direction.INBOUND &&
          item['snoopSupported'] &&
          (item['mapping']['snoopStatus'] === SnoopStatus.NONE ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED)
      },
      {
        type: 'DISABLE_SNOOPING',
        text: 'Disable snooping',
        icon: 'mic',
        callback: this.toggleSnoopStatusMapping.bind(this),
        showIf: (item) =>
          item['mapping']['direction'] === Direction.INBOUND &&
          item['snoopSupported'] &&
          !(
            item['mapping']['snoopStatus'] === SnoopStatus.NONE ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED
          )
      },
      {
        type: 'RESET_SNOOP',
        text: 'Reset snoop',
        icon: 'reset',
        callback: this.resetSnoop.bind(this),
        showIf: (item) =>
          item['mapping']['direction'] === Direction.INBOUND &&
          item['snoopSupported'] &&
          (item['mapping']['snoopStatus'] === SnoopStatus.NONE ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED)
      },
      {
        type: 'EXPORT',
        text: 'Export mapping',
        icon: 'export',
        callback: this.exportSingle.bind(this)
      }
    );
    this.bulkActionControls.push(
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingBulkWithConfirmation.bind(this),
        showIf: (selectedItemIds) => {
          const result = true;
          // depending on seleted id hide the bulkDelete
          console.log('Selected mappings (showIf):', selectedItemIds);
          return result;
        }
      },
      {
        type: 'ACTIVATE',
        text: 'Activate',
        icon: 'toggle-on',
        callback: this.activateMappingBulk.bind(this)
      },
      {
        type: 'DEACTIVATE',
        text: 'Deactivate',
        icon: 'toggle-off',
        callback: this.deactivateMappingBulk.bind(this)
      },
      {
        type: 'EXPORT',
        text: 'Export mapping',
        icon: 'export',
        callback: this.exportMappingBulk.bind(this)
      }
    );
    this.mappingsEnriched$ = this.mappingService.getMappingsObservable(
      this.stepperConfiguration.direction
    );

    this.mappingsEnriched$.subscribe((maps) => {
      this.mappingsCount = maps.length;
    });
    await this.mappingService.startChangedMappingEvents();
  }

  getColumnsMappings(): Column[] {
    const cols: Column[] = [
      {
        name: 'name',
        header: 'Name',
        path: 'mapping.name',
        filterable: false,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: NameRendererComponent,
        sortOrder: 'asc',
        visible: true,
        gridTrackSize: '10%'
      },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            header: 'Subscription topic',
            name: 'subscriptionTopic',
            path: 'mapping.subscriptionTopic',
            filterable: true
          }
        : {
            header: 'Publish topic',
            name: 'publishTopic',
            path: 'mapping.publishTopic',
            filterable: true
          },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            header: 'Mapping topic',
            name: 'mappingTopic',
            path: 'mapping.mappingTopic',
            filterable: true
          }
        : {
            header: 'Publish topic sample',
            name: 'publishTopicSample',
            path: 'mapping.publishTopicSample',
            filterable: true
          },
      {
        name: 'targetAPI',
        header: 'API',
        path: 'mapping.targetAPI',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: APIRendererComponent,
        gridTrackSize: '7%'
      },
      {
        header: 'For connectors',
        name: 'connectors',
        path: 'connectors',
        filterable: true,
        sortable: false,
        cellRendererComponent: MappingDeploymentRendererComponent
      },
      {
        header: 'Status',
        name: 'tested',
        path: 'mapping',
        filterable: false,
        sortable: false,
        cellRendererComponent: StatusRendererComponent,
        gridTrackSize: '10%'
      },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            // header: 'Test/Debug/Snoop',
            header: 'Templates snooped',
            name: 'snoopedTemplates',
            path: 'mapping',
            filterable: false,
            sortable: false,
            cellCSSClassName: 'text-align-center',
            cellRendererComponent: SnoopedTemplateRendererComponent,
            gridTrackSize: '8%'
          }
        : undefined,
      {
        header: 'Activate',
        name: 'active',
        path: 'mapping.active',
        filterable: false,
        sortable: true,
        cellRendererComponent: StatusActivationRendererComponent,
        gridTrackSize: '9%'
      }
    ];
    return cols;
  }

  onAddMapping() {
    this.snoopStatus = SnoopStatus.NONE;
    const initialState = {
      direction: this.stepperConfiguration.direction
    };
    const modalRef = this.bsModalService.show(MappingTypeComponent, {
      initialState
    });
    modalRef.content.closeSubject.subscribe((result) => {
      // console.log('Was selected:', result);
      if (result) {
        if (result.snoop) {
          this.snoopStatus = SnoopStatus.ENABLED;
        }
        this.mappingType = result.mappingType;
        this.addMapping();
      }
      modalRef.hide();
    });
  }

  async addMapping() {
    // console.log('Snoop status:', this.snoopStatus);
    this.setStepperConfiguration(
      this.mappingType,
      this.stepperConfiguration.direction,
      EditorMode.CREATE
    );

    const ident = uuidCustom();
    const sub: MappingSubstitution[] = [];
    let mapping: Mapping;
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      mapping = {
        // name: `Mapping - ${ident.substring(0, 7)}`,
        name: `Mapping - ${nextIdAndPad(this.mappingsCount, 2)}`,
        id: ident,
        ident: ident,
        subscriptionTopic: '',
        mappingTopic: '',
        mappingTopicSample: '',
        targetAPI: API.MEASUREMENT.name,
        source: '{}',
        target: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
        active: false,
        tested: false,
        qos: QOS.AT_LEAST_ONCE,
        substitutions: sub,
        mapDeviceIdentifier: false,
        createNonExistingDevice: false,
        mappingType: this.mappingType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        snoopStatus: this.snoopStatus,
        snoopedTemplates: [],
        direction: this.stepperConfiguration.direction,
        autoAckOperation: true,
        debug: false,
        lastUpdate: Date.now()
      };
    } else {
      mapping = {
        name: `Mapping - ${ident.substring(0, 7)}`,
        id: ident,
        ident: ident,
        publishTopic: '',
        publishTopicSample: '',
        targetAPI: API.MEASUREMENT.name,
        source: '{}',
        target: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
        active: false,
        tested: false,
        qos: QOS.AT_LEAST_ONCE,
        substitutions: sub,
        mapDeviceIdentifier: false,
        createNonExistingDevice: false,
        mappingType: this.mappingType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        snoopStatus: this.snoopStatus,
        snoopedTemplates: [],
        direction: this.stepperConfiguration.direction,
        autoAckOperation: true,
        debug: false,
        lastUpdate: Date.now()
      };
    }
    mapping.target = getExternalTemplate(mapping);
    if (this.mappingType == MappingType.FLAT_FILE) {
      const sampleSource = JSON.stringify({
        message: '10,temp,1666963367'
      } as PayloadWrapper);
      mapping = {
        ...mapping,
        source: sampleSource
      };
    } else if (this.mappingType == MappingType.PROCESSOR_EXTENSION) {
      mapping.extension = {
        event: undefined,
        name: undefined,
        message: undefined
      };
    }

    this.mappingToUpdate = mapping;
    this.deploymentMapEntry = { ident: mapping.ident, connectors: [] };
    if (
      mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.showConfigMapping = true;
    } else {
      this.showSnoopingMapping = true;
    }
  }

  async deleteSubscription(device: IIdentified) {
    // console.log('Delete device', device);
    try {
      await this.mappingService.deleteSubscriptions(device);
      this.alertService.success(
        gettext('Subscription for this device deleted successfully')
      );
      this.loadSubscriptions();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to delete subscription:') + error
      );
    }
  }

  async updateMapping(m: MappingEnriched) {
    const { mapping } = m;

    if (mapping.active) {
      this.setStepperConfiguration(
        mapping.mappingType,
        this.stepperConfiguration.direction,
        EditorMode.READ_ONLY
      );
    } else {
      this.setStepperConfiguration(
        mapping.mappingType,
        this.stepperConfiguration.direction,
        EditorMode.UPDATE
      );
    }
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping));

    // for backward compatibility set direction of mapping to inbound
    if (
      !this.mappingToUpdate.direction ||
      this.mappingToUpdate.direction == null
    )
      this.mappingToUpdate.direction = Direction.INBOUND;
    const deploymentMapEntry =
      await this.mappingService.getDefinedDeploymentMapEntry(mapping.ident);
    this.deploymentMapEntry = {
      ident: this.mappingToUpdate.ident,
      connectors: deploymentMapEntry.connectors
    };
    // console.log('Editing mapping', this.mappingToUpdate);
    if (
      mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.showConfigMapping = true;
    } else {
      this.showSnoopingMapping = true;
    }
  }

  async copyMapping(m: MappingEnriched) {
    const { mapping } = m;
    this.setStepperConfiguration(
      mapping.mappingType,
      mapping.direction,
      EditorMode.COPY
    );
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;
    this.mappingToUpdate.snoopStatus = SnoopStatus.NONE;
    this.mappingToUpdate.snoopedTemplates = [];
    this.mappingToUpdate.name = `${this.mappingToUpdate.name} - Copy`;
    this.mappingToUpdate.ident = uuidCustom();
    this.mappingToUpdate.id = this.mappingToUpdate.ident;
    this.mappingToUpdate.active = false;
    const deploymentMapEntry =
      await this.mappingService.getDefinedDeploymentMapEntry(mapping.ident);
    this.deploymentMapEntry = {
      ident: this.mappingToUpdate.ident,
      connectors: deploymentMapEntry.connectors
    };
    // console.log('Copying mapping', this.mappingToUpdate);
    if (
      mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.showConfigMapping = true;
    } else {
      this.showSnoopingMapping = true;
    }
  }

  async activateMapping(m: MappingEnriched) {
    const { mapping } = m;
    const newActive = !mapping.active;
    const action = newActive ? 'Activated' : 'Deactivated';
    const parameter = { id: mapping.id, active: newActive };
    const response =
      await this.mappingService.changeActivationMapping(parameter);
    if (response.status != HttpStatusCode.Ok) {
      const failedMap = await response.json();
      const failedList = Object.values(failedMap).join(',');
      this.alertService.warning(
        `Mapping could only activate partially. It failed for the following connectors: ${failedList}`
      );
    } else {
      this.alertService.success(`${action} mapping: ${mapping.id}`);
    }
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async toggleDebugMapping(m: MappingEnriched) {
    const { mapping } = m;
    const newDebug = !mapping.debug;
    const action = newDebug ? 'Activated' : 'Deactivated';
    this.alertService.success(`Debugging ${action} for mapping: ${mapping.id}`);
    const parameter = { id: mapping.id, debug: newDebug };
    await this.mappingService.changeDebuggingMapping(parameter);
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async toggleSnoopStatusMapping(m: MappingEnriched) {
    const { mapping } = m;
    let newSnoop, action;
    // toggle snoopStatus
    if (
      mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      newSnoop = SnoopStatus.ENABLED;
      action = 'Activated';
    } else {
      newSnoop = SnoopStatus.NONE;
      action = 'Deactivated';
    }
    this.alertService.success(`Snooping ${action} for mapping: ${mapping.id}`);
    const parameter = { id: mapping.id, snoopStatus: newSnoop };
    await this.mappingService.changeSnoopStatusMapping(parameter);
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async resetSnoop(m: MappingEnriched) {
    const { mapping } = m;
    this.alertService.success(
      `Reset snooped messages for mapping: ${mapping.id}`
    );
    const parameter = { id: mapping.id };
    await this.mappingService.resetSnoop(parameter);
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async deleteMappingWithConfirmation(
    m: MappingEnriched,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean> {
    let result: boolean = false;
    // const { mapping } = m;
    // console.log('Deleting mapping before confirmation:', mapping);
    if (confirmation) {
      const initialState = {
        title: multiple ? 'Delete mappings' : 'Delete mapping',
        message: multiple
          ? 'You are about to delete mappings. Do you want to proceed to delete ALL?'
          : 'You are about to delete a mapping. Do you want to proceed?',
        labels: {
          ok: 'Delete',
          cancel: 'Cancel'
        }
      };
      const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
        ConfirmationModalComponent,
        { initialState }
      );

      result = await confirmDeletionModalRef.content.closeSubject.toPromise();
      if (result) {
        // console.log('DELETE mapping:', mapping, result);
        await this.deleteMapping(m);
      } else {
        // console.log('Canceled DELETE mapping', mapping, result);
      }
    } else {
      // await this.deleteMapping(mapping);
    }
    return result;
  }

  async deleteMapping(m: MappingEnriched) {
    const { mapping } = m;
    try {
      await this.mappingService.deleteMapping(mapping.id);
      this.alertService.success(gettext('Mapping deleted successfully'));
      this.isConnectionToMQTTEstablished = true;
    } catch (error) {
      this.alertService.danger(gettext('Failed to delete mapping:') + error);
    }
  }

  async onCommitMapping(mapping: Mapping) {
    // test if new/updated mapping was committed or if cancel
    mapping.lastUpdate = Date.now();
    // ('Changed mapping:', mapping);
    if (
      mapping.direction == Direction.INBOUND ||
      // test if we can attach multiple outbound mappings to the same filterOutbound
      mapping.direction == Direction.OUTBOUND
      //  && isFilterOutboundUnique(mapping, this.mappings)
    ) {
      if (this.stepperConfiguration.editorMode == EditorMode.UPDATE) {
        // console.log('Update existing mapping:', mapping);
        try {
          await this.mappingService.updateMapping(mapping);
          this.alertService.success(gettext('Mapping updated successfully'));
        } catch (error) {
          this.alertService.danger(
            gettext('Failed to updated mapping: ') + error.message
          );
        }
        // this.activateMappings();
      } else if (
        this.stepperConfiguration.editorMode == EditorMode.CREATE ||
        this.stepperConfiguration.editorMode == EditorMode.COPY
      ) {
        // new mapping
        // console.log('Push new mapping:', mapping);
        try {
          await this.mappingService.createMapping(mapping);
          this.alertService.success(gettext('Mapping created successfully'));
        } catch (error) {
          this.alertService.danger(
            gettext('Failed to create mapping:') + error
          );
        }
        // this.activateMappings();
      }
      this.isConnectionToMQTTEstablished = true;
    } else {
      if (mapping.direction == Direction.INBOUND) {
        this.alertService.danger(
          gettext(
            `Topic is already used: ${mapping.subscriptionTopic}. Please use a different topic.`
          )
        );
      } else {
        this.alertService.danger(
          gettext(
            `FilterOutbound is already used: ${mapping.filterOutbound}. Please use a different filter.`
          )
        );
      }
    }

    this.mappingService.updateDefinedDeploymentMapEntry(
      this.deploymentMapEntry
    );

    this.showConfigMapping = false;
    this.showSnoopingMapping = false;
  }

  async onReload() {
    this.reloadMappingsInBackend();
  }

  async exportMappings(mappings2Export: Mapping[]): Promise<void> {
    const json = JSON.stringify(mappings2Export, undefined, 2);
    const blob = new Blob([json]);
    saveAs(blob, `mappings-${this.stepperConfiguration.direction}.json`);
  }

  private exportMappingBulk(ids: string[]) {
    this.mappingsEnriched$.pipe(take(1)).subscribe((ms) => {
      const mappings2Export = ms
        .filter((m) => ids.includes(m.id))
        .map((me) => me.mapping);
      this.exportMappings(mappings2Export);
    });

    this.mappingGrid.setAllItemsSelected(false);
  }

  private activateMappingBulk(ids: string[]) {
    this.mappingsEnriched$.pipe(take(1)).subscribe(async (ms) => {
      const mappings2Activate = ms
        .filter((m) => ids.includes(m.id))
        .map((me) => me.mapping);
      for (let index = 0; index < mappings2Activate.length; index++) {
        const m = mappings2Activate[index];
        const action = 'Activated';
        const parameter = { id: m.id, active: true };
        await this.mappingService.changeActivationMapping(parameter);
        this.alertService.success(`${action} mapping: ${m.id}`);
      }
      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    });
    this.mappingGrid.setAllItemsSelected(false);
  }

  private deactivateMappingBulk(ids: string[]) {
    this.mappingsEnriched$.pipe(take(1)).subscribe(async (ms) => {
      const mappings2Activate = ms
        .filter((m) => ids.includes(m.id))
        .map((me) => me.mapping);
      for (let index = 0; index < mappings2Activate.length; index++) {
        const m = mappings2Activate[index];
        const action = 'Deactivated';
        const parameter = { id: m.id, active: false };
        await this.mappingService.changeActivationMapping(parameter);
        this.alertService.success(`${action} mapping: ${m.id}`);
      }
      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    });
    this.mappingGrid.setAllItemsSelected(false);
  }

  private async deleteMappingBulkWithConfirmation(ids: string[]) {
    let continueDelete: boolean = false;
    this.mappingsEnriched$.pipe(take(1)).subscribe(async (ms) => {
      const mappings2Delete = ms
        .filter((m) => ids.includes(m.id))
        .map((me) => me.mapping);
      for (let index = 0; index < mappings2Delete.length; index++) {
        const m = mappings2Delete[index];
        const me: MappingEnriched = {
          id: m.id,
          mapping: m
        };
        if (index == 0) {
          continueDelete = await this.deleteMappingWithConfirmation(
            me,
            true,
            true
          );
        } else if (continueDelete) {
          this.deleteMapping(me);
        }
      }
    });
    this.isConnectionToMQTTEstablished = true;
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    this.mappingGrid.setAllItemsSelected(false);
  }

  async onExportAll() {
    this.mappingsEnriched$.pipe(take(1)).subscribe((ms) => {
      const mappings2Export = ms.map((me) => me.mapping);
      this.exportMappings(mappings2Export);
    });
  }

  async exportSingle(m: MappingEnriched) {
    const { mapping } = m;
    const mappings2Export = [mapping];
    this.exportMappings(mappings2Export);
  }

  async onImport() {
    const initialState = {};
    const modalRef = this.bsModalService.show(ImportMappingsComponent, {
      initialState
    });
    modalRef.content.closeSubject.subscribe(() => {
      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
      modalRef.hide();
    });
  }

  private async reloadMappingsInBackend() {
    const response2 = await this.shareService.runOperation(
      Operation.RELOAD_MAPPINGS
    );
    // console.log('Activate mapping response:', response2);
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings reloaded'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  setStepperConfiguration(
    mappingType: MappingType,
    direction: Direction,
    editorMode: EditorMode
  ) {
    // console.log('DEBUG I', MAPPING_TYPE_DESCRIPTION);
    // console.log('DEBUG II', MAPPING_TYPE_DESCRIPTION[mappingType]);
    this.stepperConfiguration =
      MAPPING_TYPE_DESCRIPTION[mappingType].stepperConfiguration;
    this.stepperConfiguration.direction = direction;
    this.stepperConfiguration.editorMode = editorMode;
    if (direction == Direction.OUTBOUND)
      this.stepperConfiguration.allowTestSending = false;
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
    this.mappingService.stopChangedMappingEvents();
  }
  refreshMappings() {
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }
}
