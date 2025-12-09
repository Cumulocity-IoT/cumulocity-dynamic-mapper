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
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  ActionControl,
  AlertService,
  BottomDrawerService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  ColumnDataType,
  DataGridComponent,
  DisplayOptions,
  Pagination
} from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import {
  API,
  ConfirmationModalComponent,
  createCustomUuid,
  DeploymentMapEntry,
  Direction,
  ExtensionType,
  Feature,
  getExternalTemplate,
  isSubstitutionsAsCode,
  LabelTaggedRendererComponent,
  Mapping,
  MappingEnriched,
  MappingType,
  MappingTypeDescriptionMap,
  nextIdAndPad,
  Operation,
  Qos,
  SAMPLE_TEMPLATES_C8Y,
  SharedService,
  SnoopStatus,
  StepperConfiguration,
  Substitution,
  TransformationType
} from '../../shared';

import { HttpStatusCode } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { gettext } from '@c8y/ngx-components/gettext';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, filter, finalize, Subject, switchMap, take } from 'rxjs';
import { CodeTemplate } from '../../configuration/shared/configuration.model';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';
import { MappingFilterComponent } from '../filter/mapping-filter.component';
import { ImportMappingsComponent } from '../import/import-modal.component';
import { MappingTypeDrawerComponent } from '../mapping-create/mapping-type-drawer.component';
import { MappingDeploymentRendererComponent } from '../renderer/mapping-deployment.renderer.component';
import { MappingIdCellRendererComponent } from '../renderer/mapping-id.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snooped-template.renderer.component';
import { MappingStatusActivationRendererComponent } from '../renderer/status-activation.renderer.component';
import { StatusRendererComponent } from '../renderer/status.renderer.component';
import {
  PayloadWrapper
} from '../shared/mapping.model';
import { AdvisorAction, EditorMode } from '../shared/stepper.model';
import { AdviceActionComponent } from './advisor/advice-action.component';

@Component({
  selector: 'd11r-mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingComponent implements OnInit, OnDestroy {
  @ViewChild('mappingGrid') mappingGrid: DataGridComponent;

  showConfigMapping = false;
  showSnoopingMapping = false;
  isConnectionToMQTTEstablished: boolean;

  readonly mappingsEnriched$ = new BehaviorSubject<MappingEnriched[]>([]);
  mappingsCount = 0;
  mappingToUpdate: Mapping;
  substitutionsAsCode: boolean;
  devices: IIdentified[] = [];
  snoopStatus: SnoopStatus = SnoopStatus.NONE;
  snoopEnabled = false;
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

  mappingType: MappingType;
  transformationType: TransformationType;
  destroy$ = new Subject<boolean>();

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  actionControls: ActionControl[] = [];
  bulkActionControls: BulkActionControl[] = [];

  feature: Feature;
  codeTemplate: CodeTemplate;

  constructor(
  ) {
    const href = this.router.url;
    this.stepperConfiguration.direction = href.includes('/mappings/inbound')
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    this.columnsMappings = this.getColumnsMappings();
    this.titleMapping = `Mapping ${this.stepperConfiguration.direction.toLowerCase()}`;
  }

  private subscriptionService = inject(SubscriptionService);
  private mappingService = inject(MappingService);
  private sharedService = inject(SharedService);
  private alertService = inject(AlertService);
  private bsModalService = inject(BsModalService);
  private bottomDrawerService = inject(BottomDrawerService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  async ngOnInit() {
    this.setupActionControls();
    this.setupBulkActionControls();

    this.feature = this.route.snapshot.data['feature'];

    // Subscribe to mappings observable
    this.mappingService
      .getMappingsObservable(this.stepperConfiguration.direction)
      .subscribe(mappings => this.mappingsEnriched$.next(mappings));

    // Track mappings count
    this.mappingsEnriched$.subscribe(maps => {
      this.mappingsCount = maps.length;
    });

    // Start listening to mapping changes
    await this.mappingService.startChangedMappingEvents();

    this.mappingService.listenToUpdateMapping().subscribe((m: MappingEnriched) => {
      this.updateMapping(m);
    });

    // Check outbound mapping subscriptions
    // if (this.stepperConfiguration.direction === Direction.OUTBOUND) {
    //   try {
    //     const mappings = await this.mappingService.getMappings(Direction.OUTBOUND);
    //     const numberOutboundMappings = mappings.length;

    //     // Get dynamic devices
    //     const dynamicResult = await this.subscriptionService.getSubscriptionDevice(
    //       this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION
    //     );
    //     const hasDynamicDevices = dynamicResult.devices.length > 0;

    //     // Get static devices
    //     const staticResult = await this.subscriptionService.getSubscriptionDevice(
    //       this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION
    //     );
    //     const hasStaticDevices = staticResult.devices.length > 0;

    //     // Show warning if no devices are subscribed but mappings exist
    //     if (!hasDynamicDevices && !hasStaticDevices && numberOutboundMappings > 0) {
    //       this.alertService.warning(
    //         "No device subscriptions found for your outbound mappings. " +
    //         "You need to subscribe your outbound mappings to at least one device to process data!"
    //       );
    //     }
    //   } catch (error) {
    //     console.error('Error verifying outbound mapping subscriptions:', error);
    //     this.alertService.danger('Failed to verify outbound mapping subscriptions');
    //   }
    // }
  }

  private async validateSubscriptionOutbound(): Promise<boolean> {
    let valid = true;
    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      const result = await Promise.all([this.subscriptionService.getSubscriptionDevice(this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION), this.subscriptionService.getSubscriptionDevice(this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION)])
      if (result[0].devices?.length == 0 && result[1].devices?.length == 0)
        this.alertService.info("To enable the outbound mapping, a subscription is required. Please proceed with creating the necessary 'Subscription outbound'.");
      valid = false;
    }
    return valid;
  }

  private setupActionControls() {
    this.actionControls.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.updateMapping.bind(this),
        showIf: item => (!item['mapping']['active'] && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)),
      },
      {
        type: 'VIEW',
        icon: 'eye',
        callback: this.updateMapping.bind(this),
        showIf: item => item['mapping']['active'] || !(this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        text: 'Duplicate',
        type: 'DUPLICATE',
        icon: 'duplicate',
        callback: this.copyMapping.bind(this),
        showIf: item => (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingWithConfirmation.bind(this),
        showIf: item => (!item['mapping']['active'] && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole))
      },
      {
        type: 'APPLY_MAPPING_FILTER',
        text: 'Apply filter',
        icon: 'filter',
        callback: this.editMessageFilter.bind(this),
        showIf: item => ((item['mapping']['mappingType'] == MappingType.JSON && item['mapping']['direction'] == Direction.INBOUND) ||
          item['mapping']['direction'] == Direction.OUTBOUND) && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'ENABLE_DEBUG',
        text: 'Enable debugging',
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: item => !item['mapping']['debug'] && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'ENABLE_DEBUG',
        text: 'Disable debugging',
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: item => item['mapping']['debug'] && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'ENABLE_SNOOPING',
        text: 'Enable snooping',
        icon: 'mic',
        callback: this.toggleSnoopStatusMapping.bind(this),
        showIf: item =>
          item['snoopSupported'] &&
          (item['mapping']['snoopStatus'] === SnoopStatus.NONE ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED) && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'DISABLE_SNOOPING',
        text: 'Disable snooping',
        icon: 'mic',
        callback: this.toggleSnoopStatusMapping.bind(this),
        showIf: item =>
          item['snoopSupported'] &&
          !(
            item['mapping']['snoopStatus'] === SnoopStatus.NONE ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED
          ) && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'RESET_SNOOP',
        text: 'Reset snoop',
        icon: 'reset',
        callback: this.resetSnoop.bind(this),
        showIf: item =>
          item['snoopSupported'] &&
          (item['mapping']['snoopStatus'] === SnoopStatus.STARTED ||
            item['mapping']['snoopStatus'] === SnoopStatus.ENABLED ||
            item['mapping']['snoopStatus'] === SnoopStatus.STOPPED) && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole)
      },
      {
        type: 'EXPORT',
        text: 'Export mapping',
        icon: 'export',
        callback: this.exportSingle.bind(this)
      }
    );
  }

  private setupBulkActionControls() {
    this.bulkActionControls.push(
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingBulkWithConfirmation.bind(this),
        showIf: (selectedItemIds: string[]) => {
          const activeMappings = this.mappingsEnriched$
            .getValue()
            ?.filter(m => m.mapping.active);
          const result = activeMappings?.some(m =>
            selectedItemIds?.includes(m.mapping.id)
          );
          return !result && (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole);
        }
      },
      {
        type: 'ACTIVATE',
        text: 'Activate',
        icon: 'toggle-on',
        callback: this.activateMappingBulk.bind(this),
        showIf: () => this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole
      },
      {
        type: 'DEACTIVATE',
        text: 'Deactivate',
        icon: 'toggle-off',
        callback: this.deactivateMappingBulk.bind(this),
        showIf: () => this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole
      },
      {
        type: 'EXPORT',
        text: 'Export mapping',
        icon: 'export',
        callback: this.exportMappingBulk.bind(this)
      }
    );
  }

  async editMessageFilter(m: MappingEnriched) {
    const { mapping } = m;
    const sourceSystem =
      mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    const initialState = { mapping, sourceSystem };
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

    await this.sharedService.runOperation({
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
      {
        name: 'identifier',
        header: 'Identifier',
        path: 'mapping.identifier',
        filterable: false,
        dataType: ColumnDataType.TextShort,
        visible: false,
        gridTrackSize: '0%'
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
      {
        name: 'targetAPI',
        header: 'API',
        path: 'mapping.targetAPI',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: LabelTaggedRendererComponent,
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
        name: 'status',
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
        cellRendererComponent: MappingStatusActivationRendererComponent,
        gridTrackSize: '9%'
      }
    ];
    return cols;
  }

  async onAddMapping() {
    this.snoopStatus = SnoopStatus.NONE;
    const initialState = {
      direction: this.stepperConfiguration.direction
    };

    const drawer = this.bottomDrawerService.openDrawer(MappingTypeDrawerComponent, { initialState: initialState });
    const resultOf = await drawer.instance.result;

    if (resultOf && typeof resultOf !== 'string') {
      if (resultOf.snoop) {
        this.snoopStatus = SnoopStatus.ENABLED;
        this.snoopEnabled = true;
      }
      this.transformationType = resultOf.transformationType;
      this.substitutionsAsCode = this.transformationType == TransformationType.SMART_FUNCTION || this.transformationType == TransformationType.SUBSTITUTION_AS_CODE;
      this.mappingType = resultOf.mappingType;
      this.codeTemplate = resultOf.codeTemplate;
      this.addMapping();
    }
  }

  async addMapping() {
    this.setStepperConfiguration(
      this.mappingType,
      this.transformationType,
      this.stepperConfiguration.direction,
      EditorMode.CREATE, this.substitutionsAsCode
    );

    const identifier = createCustomUuid();
    const sub: Substitution[] = [];
    let mapping: Mapping;
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      let code;
      if (this.substitutionsAsCode) code = this.codeTemplate.code;
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
        maxFailureCount: 0,
        qos: Qos.AT_LEAST_ONCE,
        substitutions: sub,
        useExternalId: false,
        createNonExistingDevice: false,
        mappingType: this.mappingType,
        transformationType: this.transformationType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        code,
        snoopStatus: this.snoopStatus,
        snoopedTemplates: [],
        direction: this.stepperConfiguration.direction,
        autoAckOperation: true,
        debug: false,
        lastUpdate: Date.now()
      };
    } else {
      let code;
      if (this.substitutionsAsCode) code = this.codeTemplate.code;
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
        maxFailureCount: 0,
        qos: Qos.AT_LEAST_ONCE,
        filterMapping: this.snoopEnabled ? ' $exists(C8Y_FRAGMENT)' : undefined,
        substitutions: sub,
        useExternalId: false,
        createNonExistingDevice: false,
        mappingType: this.mappingType,
        transformationType: this.transformationType,
        updateExistingDevice: false,
        externalIdType: 'c8y_Serial',
        code,
        snoopStatus: this.snoopStatus,
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
        payload: '10,temp,1666963367'
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

      this.setStepperConfiguration(
        mapping.mappingType,
        mapping.transformationType,
        this.stepperConfiguration.direction,
        mapping.active ? EditorMode.READ_ONLY : EditorMode.UPDATE,
        isSubstitutionsAsCode(mapping)
      );

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
      mapping.transformationType,
      mapping.direction,
      EditorMode.COPY,
      isSubstitutionsAsCode(mapping)
    );
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;

    this.mappingToUpdate = {
      ...this.mappingToUpdate,
      snoopStatus: SnoopStatus.NONE,
      snoopedTemplates: [],
      name: `${this.mappingToUpdate.name} - Copy`,
      identifier: createCustomUuid(),
      id: createCustomUuid(),
      active: false,
    }

    const deploymentMapEntry =
      await this.mappingService.getDefinedDeploymentMapEntry(mapping.identifier);
    this.deploymentMapEntry = {
      identifier: this.mappingToUpdate.identifier,
      connectors: deploymentMapEntry.connectors
    };

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

    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      this.validateSubscriptionOutbound();
    }
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
    }
    this.isConnectionToMQTTEstablished = true;

    this.mappingService.updateDefinedDeploymentMapEntry(
      this.deploymentMapEntry
    );

    this.showConfigMapping = false;
    this.showSnoopingMapping = false;


    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      this.validateSubscriptionOutbound();
    }
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

    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      this.validateSubscriptionOutbound();
    }
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

  async onAddSampleMappings() {
    let response = await this.mappingService.addSampleMappings({ direction: this.stepperConfiguration.direction });
    if (response.status == HttpStatusCode.Created) {
      this.alertService.success(`Added sample mappings for ${this.stepperConfiguration.direction}`);
      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    }
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
    const response2 = await this.sharedService.runOperation(
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
    transformationType: TransformationType,
    direction: Direction,
    editorMode: EditorMode,
    substitutionsAsCode: boolean
  ) {
    // console.log('DEBUG I', MappingTypeDescriptionMap);
    // console.log('DEBUG II', MappingTypeDescriptionMap[mappingType]);
    // console.log('DEBUG III', this.stepperConfiguration);

    this.stepperConfiguration = {
      ...MappingTypeDescriptionMap[mappingType].stepperConfiguration,
      direction,
      editorMode,
      ...(direction === Direction.OUTBOUND && { allowTestSending: false }),
      // if snoop is enabled, then skip the first step selecting an connector
      ...(direction === Direction.OUTBOUND && this.snoopStatus === SnoopStatus.ENABLED && {
        advanceFromStepToEndStep: 0
      }),
      ...((substitutionsAsCode) && {
        advanceFromStepToEndStep: undefined,
        showCodeEditor: true,
        allowTestSending: false,
        allowTestTransformation: true
      }),
      ...((transformationType == TransformationType.SMART_FUNCTION) && {
        showEditorTarget: false,
        allowTestSending: false,
        allowTestTransformation: true
      })
    };

    // Clean up undefined properties
    if (substitutionsAsCode) {
      delete this.stepperConfiguration.advanceFromStepToEndStep;
    }
    //console.log('DEBUG IV', this.stepperConfiguration, substitutionsAsCode);

  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
    this.mappingService.stopChangedMappingEvents();
  }

  refreshMappings() {
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async clickedResetDeploymentMapEndpoint() {
    const response1 = await this.sharedService.runOperation(
      { operation: Operation.RESET_DEPLOYMENT_MAP }
    );

    const response2 = await this.sharedService.runOperation(
      { operation: Operation.RELOAD_MAPPINGS }
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === HttpStatusCode.Created && response2.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Reset deployment cache.'));
    } else {
      this.alertService.danger(gettext('Failed to reset deployment cache!'));
    }

    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

}