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
import { Component, EventEmitter, OnInit, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DisplayOptions, gettext, Pagination, Row, WizardConfig, WizardModalService } from '@c8y/ngx-components';
import { v4 as uuidv4 } from 'uuid';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { API, Direction, Mapping, MappingSubstitution, MappingType, Operation, PayloadWrapper, QOS, SnoopStatus } from '../../shared/mapping.model';
import { isTemplateTopicUnique, SAMPLE_TEMPLATES_C8Y } from '../../shared/util';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { ActiveRendererComponent } from '../renderer/active.renderer.component';
import { MappingService } from '../core/mapping.service';
import { ModalOptions } from 'ngx-bootstrap/modal';
import { takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { EditorMode, StepperConfiguration } from '../stepper/stepper-model';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';

@Component({
  selector: 'mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingComponent implements OnInit {

  isSubstitutionValid: boolean

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  direction: Direction = Direction.INCOMING;
  title: string = `Mapping List ${this.direction}`;

  mappings: Mapping[] = [];
  mappingToUpdate: Mapping;
  stepperConfiguration: StepperConfiguration = {
    showEditorSource: true,
    allowNoDefinedIdentifier: false,
    showProcessorExtensions: false,
    allowTesting: true,
    editorMode: EditorMode.UPDATE
  };

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'id',
      header: '#',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true
    },
    {
      header: 'Subscription Topic',
      name: 'subscriptionTopic',
      path: 'subscriptionTopic',
      filterable: true,
      gridTrackSize: '12.5%'
    },
    {
      header: 'Template Topic',
      name: 'templateTopic',
      path: 'templateTopic',
      filterable: true,
      gridTrackSize: '12.5%'
    },
    {
      name: 'targetAPI',
      header: 'API',
      path: 'targetAPI',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: APIRendererComponent,
      gridTrackSize: '5%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '20%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '20%'
    },
    {
      header: 'Test/Snoop',
      name: 'tested',
      path: 'tested',
      filterable: false,
      sortable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'text-align-center',
      gridTrackSize: '8%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      sortable: false,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: '5%'
    },
    {
      header: 'Active',
      name: 'active',
      path: 'active',
      filterable: true,
      sortable: true,
      cellRendererComponent: ActiveRendererComponent,
      gridTrackSize: '7%'
    },
  ]

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();
  refresh: EventEmitter<any> = new EventEmitter();

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    public alertService: AlertService,
    private wizardModalService: WizardModalService,
    private activatedRoute: ActivatedRoute,
    private router: Router
  ) { 
  }

  ngOnInit() {

    const href = this.router.url;
    href.match
    this.direction = href.match(/mqtt-mapping\/mappings\/incoming/g) ? Direction.INCOMING : Direction.OUTGOING;
    this.title = `Mapping List ${this.direction}`;

    this.loadMappings();
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
        callback: this.deleteMapping.bind(this)
      });

    this.mappingService.listToReload().subscribe(() => {
      this.loadMappings();
    })

  }

  onRowClick(mapping: Row) {
    console.log('Row clicked:');
    this.updateMapping(mapping as Mapping);
  }

  onAddMapping() {
    const wizardConfig: WizardConfig = {
      headerText: 'Add Mapping',
      headerIcon: 'plus-circle',
    };
    const initialState = {
      id: 'addMappingWizard',
      wizardConfig,
    };

    const modalOptions: ModalOptions = { initialState } as any;
    const modalRef = this.wizardModalService.show(modalOptions);
    modalRef.content.onClose.pipe(takeUntil(this.destroy$)).subscribe(result => {
      console.log("Was selected:", result);
      this.mappingType = result.mappingType;
      this.direction = result.direction;
      if (result) {
        this.addMapping();
      }
    });
  }

  async addMapping() {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      editorMode: EditorMode.CREATE
    };

    let ident = uuidv4();
    let sub: MappingSubstitution[] = [];
    let mapping: Mapping = {
      name: "Mapping - " + ident.substring(0, 7),
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
      direction: this.direction,
      lastUpdate: Date.now()
    }
    if (this.mappingType == MappingType.FLAT_FILE) {
      let sampleSource = JSON.stringify({
        message: '10,temp,1666963367'
      } as PayloadWrapper);
      mapping = {
        ...mapping,
        source: sampleSource
      }
    } else if (this.mappingType == MappingType.PROCESSOR_EXTENSION) {
      mapping.extension = {
        event: undefined,
        name: undefined,
        message: undefined
      }
    }
    this.setStepperConfiguration(this.mappingType, this.direction)

    this.mappingToUpdate = mapping;
    console.log("Add mappping", this.mappings)
    this.refresh.emit();
    this.showConfigMapping = true;
  }

  updateMapping(mapping: Mapping) {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      editorMode: EditorMode.UPDATE
    };
    if (mapping.active) {
      this.stepperConfiguration.editorMode = EditorMode.READ_ONLY;
    }
    this.setStepperConfiguration(mapping.mappingType, mapping.direction);
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping));
    console.log("Editing mapping", this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  copyMapping(mapping: Mapping) {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      editorMode: EditorMode.COPY
    };
    this.setStepperConfiguration(mapping.mappingType, mapping.direction)
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;
    this.mappingToUpdate.name = this.mappingToUpdate.name + " - Copy";
    this.mappingToUpdate.ident = uuidv4();
    this.mappingToUpdate.id = this.mappingToUpdate.ident
    console.log("Copying mapping", this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  async deleteMapping(mapping: Mapping) {
    console.log("Deleting mapping:", mapping)
    await this.mappingService.deleteMapping(mapping);
    this.alertService.success(gettext('Mapping deleted successfully'));
    this.isConnectionToMQTTEstablished = true;
    this.loadMappings();
    this.refresh.emit();
    //this.activateMappings();
  }

  async loadMappings(): Promise<void> {
    this.mappings = await this.mappingService.loadMappings(this.direction);
    console.log("Updated mappings", this.mappings);
  }

  async onCommit(mapping: Mapping) {
    // test if new/updated mapping was commited or if cancel
    mapping.lastUpdate = Date.now();

    console.log("Changed mapping:", mapping);

    if (isTemplateTopicUnique(mapping, this.mappings)) {
      if (this.stepperConfiguration.editorMode == EditorMode.UPDATE) {
        console.log("Update existing mapping:", mapping);
        await this.mappingService.updateMapping(mapping);
        this.alertService.success(gettext('Mapping updated successfully'));
        this.loadMappings();
        this.refresh.emit();
        //this.activateMappings();
      } else if (this.stepperConfiguration.editorMode == EditorMode.CREATE 
        || this.stepperConfiguration.editorMode == EditorMode.COPY) {
        // new mapping
        console.log("Push new mapping:", mapping);
        await this.mappingService.createMapping(mapping);
        this.alertService.success(gettext('Mapping created successfully'));
        this.loadMappings();
        this.refresh.emit();
        //this.activateMappings();
      }
      this.isConnectionToMQTTEstablished = true;

    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.subscriptionTopic + ". Please use a different topic."));
    }
    this.showConfigMapping = false;
  }

  async onSaveClicked() {
    this.saveMappings();
  }

  async onActivateClicked() {
    this.activateMappings();
  }

  private async activateMappings() {
    const response2 = await this.configurationService.runOperation(Operation.RELOAD_MAPPINGS);
    console.log("Activate mapping response:", response2)
    if (response2.status < 300) {
      //this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  private async saveMappings() {
    await this.mappingService.saveMappings(this.mappings);
    console.log("Saved mppings:", this.mappings)
    this.alertService.success(gettext('Mappings saved successfully'));
    this.isConnectionToMQTTEstablished = true;
    // if (response1.res.ok) {
    // } else {
    //   this.alertService.danger(gettext('Failed to save mappings'));
    // }
  }

  setStepperConfiguration(mappingType: MappingType, direction: Direction) {
    if (mappingType == MappingType.PROTOBUF_STATIC) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: false,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTesting: false
      }
    } else if (mappingType == MappingType.PROCESSOR_EXTENSION) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: true,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTesting: false
      }
    }
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
  }

}

