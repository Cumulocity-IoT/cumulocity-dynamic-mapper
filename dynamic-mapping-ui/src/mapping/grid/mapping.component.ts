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
  ViewEncapsulation
} from '@angular/core';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  ColumnDataType,
  DisplayOptions,
  Pagination,
  gettext
} from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import { BrokerConfigurationService, Operation } from '../../configuration';
import {
  API,
  ConfirmationModalComponent,
  Direction,
  Mapping,
  MappingSubstitution,
  MappingType,
  QOS,
  SAMPLE_TEMPLATES_C8Y,
  SnoopStatus,
  getExternalTemplate,
  uuidCustom
} from '../../shared';

import { Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { ImportMappingsComponent } from '../import-modal/import.component';
import { MappingTypeComponent } from '../mapping-type/mapping-type.component';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { NameRendererComponent } from '../renderer/name.renderer.component';
import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { StatusActivationRendererComponent } from '../renderer/status-activation-renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { EditorMode, StepperConfiguration } from '../step-main/stepper-model';
import { C8YAPISubscription, PayloadWrapper } from '../shared/mapping.model';

@Component({
  selector: 'd11r-mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingComponent implements OnInit, OnDestroy {
  isSubstitutionValid: boolean;

  showConfigMapping: boolean = false;
  showConfigSubscription: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  mappings: Mapping[] = [];
  mappingToUpdate: Mapping;
  subscription: C8YAPISubscription;
  devices: IIdentified[] = [];
  Direction = Direction;

  param = { name: 'world' };

  stepperConfiguration: StepperConfiguration = {
    showEditorSource: true,
    allowNoDefinedIdentifier: false,
    allowDefiningSubstitutions: true,
    showProcessorExtensions: false,
    allowTestTransformation: true,
    allowTestSending: true,
    editorMode: EditorMode.UPDATE,
    direction: Direction.INBOUND
  };
  titleMapping: string;
  titleSubsription: string = 'Subscription on devices for mapping OUTBOUND';

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columnsMappings: Column[] = [
    {
      name: 'name',
      header: 'Name',
      path: 'name',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: NameRendererComponent,
      sortOrder: 'asc',
      visible: true
    },
    {
      header: 'Subscription Topic',
      name: 'subscriptionTopic',
      path: 'subscriptionTopic',
      filterable: true
    },
    {
      header: 'Template Topic',
      name: 'templateTopic',
      path: 'templateTopic',
      filterable: true
    },
    {
      name: 'targetAPI',
      header: 'API',
      path: 'targetAPI',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: APIRendererComponent,
      gridTrackSize: '7%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      sortable: false,
      cellRendererComponent: TemplateRendererComponent
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      sortable: false,
      cellRendererComponent: TemplateRendererComponent
    },
    {
      header: 'Test/Snoop',
      name: 'tested',
      path: 'tested',
      filterable: false,
      sortable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'text-align-center',
      gridTrackSize: '7%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      sortable: false,
      cellRendererComponent: QOSRendererComponent
    },
    {
      header: 'Active',
      name: 'active',
      path: 'active',
      filterable: false,
      sortable: true,
      cellRendererComponent: StatusActivationRendererComponent,
      gridTrackSize: '7%'
    }
  ];

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
  // refresh: EventEmitter<any> = new EventEmitter();

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  actionControls: ActionControl[] = [];
  actionControlSubscription: ActionControl[] = [];
  bulkActionControls: BulkActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public brokerConfigurationService: BrokerConfigurationService,
    public alertService: AlertService,
    private bsModalService: BsModalService,
    private router: Router
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.stepperConfiguration.direction = href.match(
      /sag-ps-pkg-dynamic-mapping\/mappings\/inbound/g
    )
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      this.columnsMappings[1] = {
        header: 'Publish Topic',
        name: 'publishTopic',
        path: 'publishTopic',
        filterable: true
        // gridTrackSize: "12.5%",
      };
      this.columnsMappings[2] = {
        header: 'Template Topic Sample',
        name: 'templateTopicSample',
        path: 'templateTopicSample',
        filterable: true
        // gridTrackSize: "12.5%",
      };
    }
    this.titleMapping = `Mapping ${this.stepperConfiguration.direction}`;
    this.loadSubscriptions();
  }

  async loadSubscriptions() {
    this.subscription = await this.mappingService.getSubscriptions();
  }

  async ngOnInit() {
    // console.log('ngOnInit');
    this.getMappings();
    this.actionControls.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.updateMapping.bind(this)
      },
      {
        text: 'Copy',
        type: 'COPY',
        icon: 'copy',
        callback: this.copyMapping.bind(this)
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingWithConfirmation.bind(this),
        showIf: (mapping) => !mapping.active
      },
      {
        type: 'ACTIVATE',
        text: 'Toogle Activation',
        icon: 'toggle-on',
        callback: this.activateMapping.bind(this)
      },
      {
        type: 'EXPORT',
        text: 'Export Mapping',
        icon: 'export',
        callback: this.exportSingle.bind(this)
      }
    );
    this.bulkActionControls.push(
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingBulkWithConfirmation.bind(this)
      },
      {
        type: 'ACTIVATE',
        text: 'Toogle Activation',
        icon: 'toggle-on',
        callback: this.activateMappingBulk.bind(this)
      },
      {
        type: 'EXPORT',
        text: 'Export Mapping',
        icon: 'export',
        callback: this.exportMappingBulk.bind(this)
      }
    );
    this.actionControlSubscription.push({
      type: BuiltInActionType.Delete,
      callback: this.deleteSubscription.bind(this)
    });
  }

  onAddMapping() {
    const initialState = {
      direction: this.stepperConfiguration.direction
    };
    const modalRef = this.bsModalService.show(MappingTypeComponent, {
      initialState
    });
    modalRef.content.closeSubject.subscribe((result) => {
      console.log('Was selected:', result);
      if (result) {
        this.mappingType = result;
        this.addMapping();
      }
      modalRef.hide();
    });
  }

  onDefineSubscription() {
    this.showConfigSubscription = !this.showConfigSubscription;
  }

  async addMapping() {
    this.stepperConfiguration = {
      ...this.stepperConfiguration,
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensions: false,
      allowTestTransformation: true,
      allowTestSending: true,
      editorMode: EditorMode.CREATE
    };

    const ident = uuidCustom();
    const sub: MappingSubstitution[] = [];
    let mapping: Mapping = {
      name: `Mapping - ${ident.substring(0, 7)}`,
      id: ident,
      ident: ident,
      subscriptionTopic: '',
      templateTopic: '',
      templateTopicSample: '',
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
      snoopStatus: SnoopStatus.NONE,
      snoopedTemplates: [],
      direction: this.stepperConfiguration.direction,
      autoAckOperation: true,
      lastUpdate: Date.now()
    };
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
    this.setStepperConfiguration(
      this.mappingType,
      this.stepperConfiguration.direction
    );

    this.mappingToUpdate = mapping;
    console.log('Add mappping', this.mappings);
    // this.refresh.emit();
    this.showConfigMapping = true;
  }

  async deleteSubscription(device: IIdentified) {
    console.log('Delete device', device);
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

  updateMapping(mapping: Mapping) {
    if (!mapping.direction)
      this.stepperConfiguration.direction = Direction.INBOUND;
    this.stepperConfiguration = {
      ...this.stepperConfiguration,
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensions: false,
      allowTestTransformation: true,
      allowTestSending: true,
      editorMode: EditorMode.UPDATE
    };
    if (mapping.active) {
      this.stepperConfiguration.editorMode = EditorMode.READ_ONLY;
    }
    this.setStepperConfiguration(mapping.mappingType, mapping.direction);
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping));

    // for backward compatability set direction of mapping to inbound
    if (
      !this.mappingToUpdate.direction ||
      this.mappingToUpdate.direction == null
    )
      this.mappingToUpdate.direction = Direction.INBOUND;
    console.log('Editing mapping', this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  copyMapping(mapping: Mapping) {
    this.stepperConfiguration = {
      ...this.stepperConfiguration,
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensions: false,
      allowTestTransformation: true,
      allowTestSending: true,
      editorMode: EditorMode.COPY
    };
    this.setStepperConfiguration(mapping.mappingType, mapping.direction);
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;
    this.mappingToUpdate.snoopStatus = SnoopStatus.NONE;
    this.mappingToUpdate.snoopedTemplates = [];
    this.mappingToUpdate.name = `${this.mappingToUpdate.name} - Copy`;
    this.mappingToUpdate.ident = uuidCustom();
    this.mappingToUpdate.id = this.mappingToUpdate.ident;
    console.log('Copying mapping', this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  async activateMapping(mapping: Mapping) {
    const newActive = !mapping.active;
    const action = newActive ? 'Activate' : 'Deactivate';
    this.alertService.success(`${action} mapping: ${mapping.id}!`);
    const parameter = { id: mapping.id, active: newActive };
    await this.mappingService.changeActivationMapping(parameter);
    this.reloadMappings();
  }

  async deleteMappingWithConfirmation(
    mapping: Mapping,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean> {
    let result: boolean = false;
    console.log('Deleting mapping before confirmation:', mapping);
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
        console.log('DELETE mapping:', mapping, result);
        await this.deleteMapping(mapping);
      } else {
        console.log('Canceled DELETE mapping', mapping, result);
      }
    } else {
      // await this.deleteMapping(mapping);
    }
    return result;
  }

  async deleteMapping(mapping: Mapping) {
    try {
      await this.mappingService.deleteMapping(mapping.id);
      this.alertService.success(gettext('Mapping deleted successfully'));
      this.isConnectionToMQTTEstablished = true;
      this.reloadMappings();
    } catch (error) {
      this.alertService.danger(gettext('Failed to delete mapping:') + error);
    }
  }

  async getMappings(): Promise<void> {
    this.mappings = await this.mappingService.getMappings(
      this.stepperConfiguration.direction
    );
  }

  async reloadMappings(): Promise<void> {
    this.mappingService.resetCache();
    this.getMappings();
  }

  async onCommitMapping(mapping: Mapping) {
    // test if new/updated mapping was commited or if cancel
    mapping.lastUpdate = Date.now();

    console.log('Changed mapping:', mapping);

    if (
      mapping.direction == Direction.INBOUND ||
      // test if we can attach multiple outbound mappings to the same filterOutbound
      mapping.direction == Direction.OUTBOUND
      //  && isFilterOutboundUnique(mapping, this.mappings)
    ) {
      if (this.stepperConfiguration.editorMode == EditorMode.UPDATE) {
        console.log('Update existing mapping:', mapping);
        try {
          await this.mappingService.updateMapping(mapping);
          this.alertService.success(gettext('Mapping updated successfully'));
          this.reloadMappings();
          // this.refresh.emit();
        } catch (error) {
          this.alertService.danger(
            gettext('Failed to updated mapping:') + error
          );
        }
        // this.activateMappings();
      } else if (
        this.stepperConfiguration.editorMode == EditorMode.CREATE ||
        this.stepperConfiguration.editorMode == EditorMode.COPY
      ) {
        // new mapping
        console.log('Push new mapping:', mapping);
        try {
          await this.mappingService.createMapping(mapping);
          this.alertService.success(gettext('Mapping created successfully'));
          this.reloadMappings();
          // this.refresh.emit();
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
    this.showConfigMapping = false;
    this.reloadMappings();
  }

  async onCommitSubscriptions(deviceList: IIdentified[]) {
    this.subscription = {
      api: API.ALL.name,
      devices: deviceList
    };
    console.log('Changed deviceList:', this.subscription.devices);
    try {
      await this.mappingService.updateSubscriptions(this.subscription);
      this.alertService.success(gettext('Subscriptions updated successfully'));
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription = false;
  }

  async onReload() {
    this.reloadMappingsInBackend();
  }

  private exportMappings(mappings2Export: Mapping[]) {
    const json = JSON.stringify(mappings2Export, undefined, 2);
    const blob = new Blob([json]);
    saveAs(blob, `mappings-${this.stepperConfiguration.direction}.json`);
  }

  private exportMappingBulk(ids: string[]) {
    const mappings2Export = this.mappings.filter((m) => ids.includes(m.id));
    this.exportMappings(mappings2Export);
  }

  private async activateMappingBulk(ids: string[]) {
    for (let i = 0; i < this.mappings.length; i++) {
      if (ids.includes(this.mappings[i].id)) {
        const newActive = !this.mappings[i].active;
        const action = newActive ? 'Activate' : 'Deactivate';
        const parameter = { id: this.mappings[i].id, active: newActive };
        await this.mappingService.changeActivationMapping(parameter);
        this.alertService.success(`${action} mapping: ${this.mappings[i].id}!`);
      }
    }
    this.reloadMappings();
  }

  private async deleteMappingBulkWithConfirmation(ids: string[]) {
    let continueDelete: boolean = false;
    for (let i = 0; i < this.mappings.length; i++) {
      if (ids.includes(this.mappings[i].id)) {
        if (i == 0) {
          continueDelete = await this.deleteMappingWithConfirmation(
            this.mappings[i],
            true,
            true
          );
        } else if (continueDelete) {
          await this.deleteMapping(this.mappings[i]);
        }
      }
    }
    this.isConnectionToMQTTEstablished = true;
    this.reloadMappings();
  }

  async onExportAll() {
    const mappings2Export = this.mappings.filter(
      (m) => m.direction == this.stepperConfiguration.direction
    );
    this.exportMappings(mappings2Export);
  }

  async exportSingle(mappping: Mapping) {
    const mappings2Export = [mappping];
    this.exportMappings(mappings2Export);
  }

  async onImport() {
    const initialState = {};
    const modalRef = this.bsModalService.show(ImportMappingsComponent, {
      initialState
    });
    modalRef.content.closeSubject.subscribe(() => {
      this.reloadMappings();
      modalRef.hide();
    });
  }

  private async reloadMappingsInBackend() {
    const response2 = await this.brokerConfigurationService.runOperation(
      Operation.RELOAD_MAPPINGS
    );
    console.log('Activate mapping response:', response2);
    if (response2.status < 300) {
      // this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  setStepperConfiguration(mappingType: MappingType, direction: Direction) {
    if (mappingType == MappingType.PROTOBUF_STATIC) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: false,
        allowDefiningSubstitutions: false,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTestTransformation: false,
        allowTestSending: true
      };
    } else if (mappingType == MappingType.PROCESSOR_EXTENSION) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: true,
        allowDefiningSubstitutions: false,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTestTransformation: false,
        allowTestSending: true
      };
    }
    if (direction == Direction.OUTBOUND)
      this.stepperConfiguration.allowTestSending = false;
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
  }
}
