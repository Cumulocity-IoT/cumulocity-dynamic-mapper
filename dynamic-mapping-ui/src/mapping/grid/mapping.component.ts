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

import { HttpStatusCode } from '@angular/common/http';
import { Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject, filter, finalize, switchMap, take } from 'rxjs';
import { DeploymentMapEntry, ExtensionType, LabelRendererComponent, MappingTypeDescriptionMap, SharedService, StepperConfiguration } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { MappingFilterComponent } from '../filter/mapping-filter.component';
import { ImportMappingsComponent } from '../import/import-modal.component';
import { MappingTypeComponent } from '../mapping-type/mapping-type.component';
import { MappingDeploymentRendererComponent } from '../renderer/mapping-deployment.renderer.component';
import { MappingIdCellRendererComponent } from '../renderer/mapping-id.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snooped-template.renderer.component';
import { StatusActivationRendererComponent } from '../renderer/status-activation.renderer.component';
import { StatusRendererComponent } from '../renderer/status.renderer.component';
import {
  PayloadWrapper
} from '../shared/mapping.model';
import { AdvisorAction, EditorMode } from '../shared/stepper.model';
import { AdviceActionComponent } from './advisor/advice-action.component';
import { TemplateType } from '../../configuration';

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

  mappingsEnriched$: BehaviorSubject<MappingEnriched[]> = new BehaviorSubject(
    []
  );
  mappingsCount: number = 0;
  mappingToUpdate: Mapping;
  substitutionsAsCode: boolean;
  devices: IIdentified[] = [];
  snoopStatus: SnoopStatus = SnoopStatus.NONE;
  snoopEnabled: boolean = false;
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
  codeTemplateInbound: string;
  codeTemplateOutbound: string;

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
        type: 'APPLY_MAPPING_FILTER',
        text: 'Apply filter',
        icon: 'filter',
        callback: this.editMessageFilter.bind(this),
        showIf: (item) => (item['mapping']['mappingType'] == MappingType.JSON && item['mapping']['direction'] == Direction.INBOUND) || item['mapping']['direction'] == Direction.OUTBOUND
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
          item['snoopSupported'] &&
          (item['mapping']['snoopStatus'] === SnoopStatus.STARTED ||
            item['mapping']['snoopStatus'] === SnoopStatus.ENABLED ||
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
        showIf: (selectedItemIds: string[]) => {
          // hide bulkDelete if any selected mapping is enabled
          const activeMappings = this.mappingsEnriched$
            .getValue()
            ?.filter((m) => m.mapping.active);
          const result = activeMappings?.some((m) =>
            selectedItemIds?.includes(m.mapping.id)
          );
          // console.log('Selected mappings (showIf):', selectedItemIds);
          return !result;
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
    this.mappingService
      .getMappingsObservable(this.stepperConfiguration.direction)
      .subscribe((mappings) => this.mappingsEnriched$.next(mappings));

    this.mappingsEnriched$.subscribe((maps) => {
      this.mappingsCount = maps.length;
    });
    await this.mappingService.startChangedMappingEvents();
    this.mappingService
      .listenToUpdateMapping()
      .subscribe((m: MappingEnriched) => {
        console.log('Triggered updating mapping', m);
        this.updateMapping(m);
      });
    this.codeTemplateInbound = (await this.shareService.getCodeTemplate(TemplateType.INBOUND.toString())).code;
    this.codeTemplateOutbound = (await this.shareService.getCodeTemplate(TemplateType.OUTBOUND.toString())).code;
  }

  async editMessageFilter(m: MappingEnriched) {
    const { mapping } = m;
    const initialState = { mapping };
    try {
      const modalRef = this.bsModalService.show(MappingFilterComponent, {
        initialState
      });
      await new Promise((resolve) => {
        modalRef.content.closeSubject
          .pipe(
            take(1),
            filter(filterMapping => !!filterMapping),
            switchMap(filterMapping => this.applyMappingFilter(filterMapping, mapping.id)),
            finalize(() => {
              modalRef.hide();
              resolve(undefined);
            })
          )
          .subscribe({
            next: (filterMapping) => {
              this.alertService.success(`Applied filter ${filterMapping} to mapping ${mapping.name}`);
            },
            error: (error) => {
              this.alertService.danger('Failed to apply mapping filter', error);
              resolve(undefined);
            }
          });
      });
    } catch (error) {
      this.alertService.danger(`'Failed to apply mapping filter': ${error.message}`);
    }
  }

  private async applyMappingFilter(filterMapping: string, mappingId: string): Promise<string> {
    const params = {
      filterMapping,
      id: mappingId
    };

    await this.shareService.runOperation({
      operation: Operation.APPLY_MAPPING_FILTER,
      parameter: params
    });

    await this.mappingService.refreshMappings(Direction.INBOUND);
    return filterMapping;
  }

  getColumnsMappings(): Column[] {
    const cols: Column[] = [
      {
        name: 'name',
        header: 'Name',
        path: 'mapping.name',
        filterable: false,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: MappingIdCellRendererComponent,
        sortOrder: 'asc',
        visible: true,
        gridTrackSize: '10%'
      },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? undefined
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
        : undefined,
      //  {
      //   header: 'Publish topic sample',
      //   name: 'publishTopicSample',
      //   path: 'mapping.publishTopicSample',
      //   filterable: true
      // },
      {
        name: 'targetAPI',
        header: 'API',
        path: 'mapping.targetAPI',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: LabelRendererComponent,
        gridTrackSize: '8%'
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
      {
        header: 'Templates snooped',
        name: 'snoopedTemplates',
        path: 'mapping',
        filterable: false,
        sortable: false,
        cellCSSClassName: 'text-align-center',
        cellRendererComponent: SnoopedTemplateRendererComponent,
        gridTrackSize: '8%'
      },
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
          this.snoopEnabled = true;
        }
        this.substitutionsAsCode = result.substitutionsAsCode;
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
      EditorMode.CREATE, this.substitutionsAsCode
    );

    const identifier = uuidCustom();
    const sub: MappingSubstitution[] = [];
    let mapping: Mapping;
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      mapping = {
        // name: `Mapping - ${identifier.substring(0, 7)}`,
        name: `Mapping - ${nextIdAndPad(this.mappingsCount, 2)}`,
        id: 'any',
        identifier: identifier,
        mappingTopic: '',
        mappingTopicSample: '',
        targetAPI: API.MEASUREMENT.name,
        sourceTemplate: '{}',
        targetTemplate: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
        active: false,
        tested: false,
        qos: QOS.AT_LEAST_ONCE,
        substitutions: sub,
        useExternalId: false,
        createNonExistingDevice: false,
        mappingType: this.substitutionsAsCode ? MappingType.CODE_BASED : this.mappingType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        code: this.substitutionsAsCode ? this.codeTemplateInbound : undefined,
        snoopStatus: this.snoopStatus,
        supportsMessageContext: this.substitutionsAsCode,
        snoopedTemplates: [],
        direction: this.stepperConfiguration.direction,
        autoAckOperation: true,
        debug: false,
        lastUpdate: Date.now()
      };
    } else {
      mapping = {
        name: `Mapping - ${identifier.substring(0, 7)}`,
        id: identifier,
        identifier: identifier,
        // publishTopic: '',
        // publishTopicSample: '',
        targetAPI: API.MEASUREMENT.name,
        sourceTemplate: '{}',
        targetTemplate: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
        active: false,
        tested: false,
        qos: QOS.AT_LEAST_ONCE,
        filterMapping: this.snoopEnabled ? ' $exists(C8Y_FRAGMENT)' : undefined,
        substitutions: sub,
        useExternalId: false,
        createNonExistingDevice: false,
        mappingType: this.substitutionsAsCode ? MappingType.CODE_BASED : this.mappingType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        code: this.substitutionsAsCode ? this.codeTemplateOutbound : undefined,
        snoopStatus: this.snoopStatus,
        supportsMessageContext: this.substitutionsAsCode,
        snoopedTemplates: [],
        direction: this.stepperConfiguration.direction,
        autoAckOperation: true,
        debug: false,
        lastUpdate: Date.now()
      };
    }
    mapping.targetTemplate = getExternalTemplate(mapping);
    if (this.mappingType == MappingType.FLAT_FILE) {
      const sampleSource = JSON.stringify({
        message: '10,temp,1666963367'
      } as PayloadWrapper);
      mapping = {
        ...mapping,
        sourceTemplate: sampleSource
      };
    } else if (this.mappingType == MappingType.EXTENSION_SOURCE) {
      mapping.extension = {
        extensionName: undefined,
        eventName: undefined,
        extensionType: ExtensionType.EXTENSION_SOURCE,
      };
    } else if (this.mappingType == MappingType.EXTENSION_SOURCE_TARGET) {
      mapping.extension = {
        extensionName: undefined,
        eventName: undefined,
        extensionType: ExtensionType.EXTENSION_SOURCE_TARGET,
      };
    }

    this.mappingToUpdate = mapping;
    this.deploymentMapEntry = { identifier: mapping.identifier, connectors: [] };
    if (
      mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.showConfigMapping = true;
    } else {
      this.showSnoopingMapping = true;
    }
  }

  async updateMapping(m: MappingEnriched) {
    let action = AdvisorAction.CONTINUE;
    const { mapping } = m;
    const { snoopSupported } =
      MappingTypeDescriptionMap[mapping.mappingType].properties[
      mapping.direction
      ];
    mapping.lastUpdate = Date.now();
    if (
      (mapping.snoopStatus == SnoopStatus.ENABLED ||
        mapping.snoopStatus == SnoopStatus.STARTED) &&
      snoopSupported
    ) {
      const initialState = {
        mapping,
        labels: {
          ok: 'Ok',
          cancel: 'Cancel'
        }
      };
      const confirmAdviceActionModalRef: BsModalRef = this.bsModalService.show(
        AdviceActionComponent,
        { initialState }
      );

      action =
        await confirmAdviceActionModalRef.content.closeSubject.toPromise();
      // console.log('Result from next step:', mapping, action);
    }

    if (action != AdvisorAction.CANCEL && action != AdvisorAction.CONTINUE_SNOOPING) {
      // stop snooping
      if (action == AdvisorAction.STOP_SNOOPING_AND_EDIT) {
        mapping.snoopStatus = SnoopStatus.STOPPED;
        if (mapping.active) {
          await this.activateMapping(m);
          mapping.active = false;
        }
      } else if (action == AdvisorAction.EDIT) {
        if (mapping.active) {
          await this.activateMapping(m);
          mapping.active = false;
        }
      }
      if (mapping.active) {
        this.setStepperConfiguration(
          mapping.mappingType,
          this.stepperConfiguration.direction,
          EditorMode.READ_ONLY,
          mapping.mappingType == MappingType.CODE_BASED
        );
      } else {
        this.setStepperConfiguration(
          mapping.mappingType,
          this.stepperConfiguration.direction,
          EditorMode.UPDATE,
          mapping.mappingType == MappingType.CODE_BASED
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
        await this.mappingService.getDefinedDeploymentMapEntry(mapping.identifier);
      this.deploymentMapEntry = {
        identifier: this.mappingToUpdate.identifier,
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
  }

  async copyMapping(m: MappingEnriched) {
    const { mapping } = m;
    this.setStepperConfiguration(
      mapping.mappingType,
      mapping.direction,
      EditorMode.COPY,
      mapping?.extension?.eventName == "GraalsCodeExtension"
    );
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;

    this.mappingToUpdate = {
      ...this.mappingToUpdate,
      snoopStatus: SnoopStatus.NONE,
      snoopedTemplates: [],
      name: `${this.mappingToUpdate.name} - Copy`,
      identifier: uuidCustom(),
      id: uuidCustom(),
      active: false,
    }

    const deploymentMapEntry =
      await this.mappingService.getDefinedDeploymentMapEntry(mapping.identifier);
    this.deploymentMapEntry = {
      identifier: this.mappingToUpdate.identifier,
      connectors: deploymentMapEntry.connectors
    };
    // console.log('Copying mapping', this.mappingToUpdate);

    // update view state
    const isInactiveSnoop = mapping.snoopStatus === SnoopStatus.NONE ||
      mapping.snoopStatus === SnoopStatus.STOPPED;

    this.showConfigMapping = isInactiveSnoop;
    this.showSnoopingMapping = !isInactiveSnoop;

  }

  async activateMapping(m: MappingEnriched) {
    const { mapping } = m;
    const newActive = !mapping.active;
    const action = newActive ? 'Activated' : 'Deactivated';
    const parameter = { id: mapping.id, active: newActive };
    const response =
      await this.mappingService.changeActivationMapping(parameter);
    if (response.status != HttpStatusCode.Created) {
      const failedMap = await response.json();
      const failedList = Object.values(failedMap).join(',');
      this.alertService.warning(
        `Mapping ${mapping.name} could only activate partially. It failed for the following connectors: ${failedList}`
      );
    } else {
      this.alertService.success(`${action} for mapping: ${mapping.name} was successful`);
    }
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    // return this.mappingService.
  }

  async toggleDebugMapping(m: MappingEnriched) {
    const { mapping } = m;
    const newDebug = !mapping.debug;
    const action = newDebug ? 'Activated' : 'Deactivated';
    this.alertService.success(`Debugging ${action} for mapping: ${mapping.id} was successful`);
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
    this.alertService.success(`Snooping ${action} for mapping: ${mapping.name}`);
    const parameter = { id: mapping.id, snoopStatus: newSnoop };
    await this.mappingService.changeSnoopStatusMapping(parameter);
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async resetSnoop(m: MappingEnriched) {
    const { mapping } = m;
    this.alertService.success(
      `Reset snooped messages for mapping: ${mapping.name}`
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

  async deleteMapping(m: MappingEnriched): Promise<boolean> {
    const { mapping } = m;
    try {
      await this.mappingService.deleteMapping(mapping.id);
      this.alertService.success(gettext(`Mapping ${mapping.name} deleted successfully'`));
      this.isConnectionToMQTTEstablished = true;
      return true;
    } catch (error) {
      this.alertService.danger(gettext(`Failed to delete mapping ${mapping.name}:`) + error);
      return false;
    }
  }

  async onCommitMapping(mapping: Mapping) {
    // test if new/updated mapping was committed or if cancel
    mapping.lastUpdate = Date.now();
    // ('Changed mapping:', mapping);
    if (
      mapping.direction == Direction.INBOUND ||
      // test if we can attach multiple outbound mappings to the same filterMapping
      mapping.direction == Direction.OUTBOUND
      //  && isFilterOutboundUnique(mapping, this.mappings)
    ) {
      if (this.stepperConfiguration.editorMode == EditorMode.UPDATE) {
        // console.log('Update existing mapping:', mapping);
        try {
          await this.mappingService.updateMapping(mapping);
          this.alertService.success(gettext(`Mapping ${mapping.name} updated successfully`));
        } catch (error) {
          this.alertService.danger(
            gettext(`Failed to updated mapping ${mapping.name}: `) + error.message
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
          this.alertService.success(gettext(`Mapping ${mapping.name} created successfully`));
        } catch (error) {
          this.alertService.danger(
            gettext(`Failed to updated mapping ${mapping.name}: `) + error
          );
        }
        // this.activateMappings();
      }
      this.isConnectionToMQTTEstablished = true;
    } else {
      if (mapping.direction == Direction.INBOUND) {
        this.alertService.danger(
          gettext(
            `Topic is already used: ${mapping.mappingTopic}. Please use a different topic.`
          )
        );
      } else {
        this.alertService.danger(
          gettext(
            `FilterMapping is already used: ${mapping.filterMapping}. Please use a different filter.`
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
        this.alertService.success(`${action} mapping: ${m.name} was successful`);
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
        this.alertService.success(`${action} mapping: ${m.name} was successful`);
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
      { operation: Operation.RELOAD_MAPPINGS }
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
    editorMode: EditorMode,
    substitutionsAsCode: boolean
  ) {
    // console.log('DEBUG I', MAPPING_TYPE_DESCRIPTION);
    // console.log('DEBUG II', MAPPING_TYPE_DESCRIPTION[mappingType]);
    this.stepperConfiguration =
      MappingTypeDescriptionMap[mappingType].stepperConfiguration;
    this.stepperConfiguration.direction = direction;
    this.stepperConfiguration.editorMode = editorMode;
    if (direction == Direction.OUTBOUND)
      this.stepperConfiguration.allowTestSending = false;

    if (substitutionsAsCode) {
      delete this.stepperConfiguration.advanceFromStepToEndStep;
      this.stepperConfiguration.showCodeEditor = true;
      this.stepperConfiguration.allowTestSending = false;
      this.stepperConfiguration.allowTestTransformation = false;
    }
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