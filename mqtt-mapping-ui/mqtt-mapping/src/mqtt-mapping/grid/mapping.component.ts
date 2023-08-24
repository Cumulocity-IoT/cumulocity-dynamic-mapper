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
  OnInit,
  ViewEncapsulation,
} from "@angular/core";
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  Column,
  ColumnDataType,
  DisplayOptions,
  gettext,
  Pagination,
  Row,
} from "@c8y/ngx-components";
import { v4 as uuidv4 } from "uuid";
import { BrokerConfigurationService } from "../../mqtt-configuration/broker-configuration.service";
import {
  API,
  C8YAPISubscription,
  Direction,
  Mapping,
  MappingSubstitution,
  MappingType,
  Operation,
  PayloadWrapper,
  QOS,
  SnoopStatus,
} from "../../shared/mapping.model";
import {
  getExternalTemplate,
  isFilterOutboundUnique,
  isTemplateTopicUnique,
  SAMPLE_TEMPLATES_C8Y,
} from "../../shared/util";
import { APIRendererComponent } from "../renderer/api.renderer.component";
import { QOSRendererComponent } from "../renderer/qos-cell.renderer.component";
import { StatusRendererComponent } from "../renderer/status-cell.renderer.component";
import { TemplateRendererComponent } from "../renderer/template.renderer.component";
import { ActiveRendererComponent } from "../renderer/active.renderer.component";
import { MappingService } from "../core/mapping.service";
import { BsModalService } from "ngx-bootstrap/modal";
import { Observable, Subject, from } from "rxjs";
import { EditorMode, StepperConfiguration } from "../stepper/stepper-model";
import { Router } from "@angular/router";
import { IIdentified } from "@c8y/client";
import { MappingTypeComponent } from "../mapping-type/mapping-type.component";
import { Dir } from "@angular/cdk/bidi";

@Component({
  selector: "mapping-mapping-grid",
  templateUrl: "mapping.component.html",
  styleUrls: ["../shared/mapping.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class MappingComponent implements OnInit {
  isSubstitutionValid: boolean;

  showConfigMapping: boolean = false;
  showConfigSubscription: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  mappings: Mapping[] = [];
  mappingToUpdate: Mapping;
  subscription: C8YAPISubscription;
  devices: IIdentified[] = [];
  Direction = Direction;

  param = { name: "world" };

  stepperConfiguration: StepperConfiguration = {
    showEditorSource: true,
    allowNoDefinedIdentifier: false,
    allowDefiningSubstitutions: true,
    showProcessorExtensions: false,
    allowTestTransformation: true,
    allowTestSending: true,
    editorMode: EditorMode.UPDATE,
    direction: Direction.INBOUND,
  };
  titleMapping: string;
  titleSubsription: string = `Subscription on devices for mapping OUTBOUND`;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
  };

  columnsMappings: Column[] = [
    {
      name: "id",
      header: "System ID",
      path: "id",
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true,
    },
    {
      header: "Subscription Topic",
      name: "subscriptionTopic",
      path: "subscriptionTopic",
      filterable: true,
      gridTrackSize: "12.5%",
    },
    {
      header: "Template Topic",
      name: "templateTopic",
      path: "templateTopic",
      filterable: true,
      gridTrackSize: "12.5%",
    },
    {
      name: "targetAPI",
      header: "API",
      path: "targetAPI",
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: APIRendererComponent,
      gridTrackSize: "5%",
    },
    {
      header: "Sample payload",
      name: "source",
      path: "source",
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: "20%",
    },
    {
      header: "Target",
      name: "target",
      path: "target",
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: "20%",
    },
    {
      header: "Test/Snoop",
      name: "tested",
      path: "tested",
      filterable: false,
      sortable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: "text-align-center",
      gridTrackSize: "8%",
    },
    {
      header: "QOS",
      name: "qos",
      path: "qos",
      filterable: true,
      sortable: false,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: "5%",
    },
    {
      header: "Active",
      name: "active",
      path: "active",
      filterable: true,
      sortable: true,
      cellRendererComponent: ActiveRendererComponent,
      gridTrackSize: "7%",
    },
  ];

  columnsSubscriptions: Column[] = [
    {
      name: "id",
      header: "System ID",
      path: "id",
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true,
    },
    {
      header: "Name",
      name: "name",
      path: "name",
      filterable: true,
    },
  ];

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();
  refresh: EventEmitter<any> = new EventEmitter();

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };
  actionControlMapping: ActionControl[] = [];
  actionControlSubscription: ActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    public alertService: AlertService,
    private bsModalService: BsModalService,
    private router: Router
  ) {
    const href = this.router.url;
    this.stepperConfiguration.direction = href.match(
      /sag-ps-pkg-mqtt-mapping\/mappings\/inbound/g
    )
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    if (this.stepperConfiguration.direction == Direction.OUTBOUND) {
      this.columnsMappings[1] = {
        header: "Publish Topic",
        name: "publishTopic",
        path: "publishTopic",
        filterable: true,
        gridTrackSize: "12.5%",
      };
      this.columnsMappings[2] = {
        header: "Template Topic Sample",
        name: "templateTopicSample",
        path: "templateTopicSample",
        filterable: true,
        gridTrackSize: "12.5%",
      };
    }
    this.titleMapping = `Mapping ${this.stepperConfiguration.direction}`;
    this.loadSubscriptions();
  }

  async loadSubscriptions() {
    this.subscription = await this.mappingService.getSubscriptions();
  }

  ngOnInit() {
    this.loadMappings();
    this.actionControlMapping.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.updateMapping.bind(this),
      },
      {
        text: "Copy",
        type: "COPY",
        icon: "copy",
        callback: this.copyMapping.bind(this),
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMapping.bind(this),
      }
    );
    this.actionControlSubscription.push({
      type: BuiltInActionType.Delete,
      callback: this.deleteSubscription.bind(this),
    });

    this.mappingService.listToReload().subscribe(() => {
      this.loadMappings();
    });
  }

  onRowClick(mapping: Row) {
    console.log("Row clicked:");
    this.updateMapping(mapping as Mapping);
  }

  onAddMapping() {
    const initialState = {
      direction: this.stepperConfiguration.direction,
    };
    const modalRef = this.bsModalService.show(MappingTypeComponent, {
      initialState,
    });
    modalRef.content.closeSubject.subscribe((result) => {
      console.log("Was selected:", result);
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
      editorMode: EditorMode.CREATE,
    };

    let ident = uuidv4();
    let sub: MappingSubstitution[] = [];
    let mapping: Mapping = {
      name: "Mapping - " + ident.substring(0, 7),
      id: ident,
      ident: ident,
      subscriptionTopic: "",
      templateTopic: "",
      templateTopicSample: "",
      targetAPI: API.MEASUREMENT.name,
      source: "{}",
      target: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
      active: false,
      tested: false,
      qos: QOS.AT_LEAST_ONCE,
      substitutions: sub,
      mapDeviceIdentifier: false,
      createNonExistingDevice: false,
      mappingType: this.mappingType,
      updateExistingDevice: false,
      externalIdType: "c8y_Serial",
      snoopStatus: SnoopStatus.NONE,
      snoopedTemplates: [],
      direction: this.stepperConfiguration.direction,
      autoAckOperation: true,
      lastUpdate: Date.now(),
    };
    mapping.target = getExternalTemplate(mapping);
    if (this.mappingType == MappingType.FLAT_FILE) {
      let sampleSource = JSON.stringify({
        message: "10,temp,1666963367",
      } as PayloadWrapper);
      mapping = {
        ...mapping,
        source: sampleSource,
      };
    } else if (this.mappingType == MappingType.PROCESSOR_EXTENSION) {
      mapping.extension = {
        event: undefined,
        name: undefined,
        message: undefined,
      };
    }
    this.setStepperConfiguration(
      this.mappingType,
      this.stepperConfiguration.direction
    );

    this.mappingToUpdate = mapping;
    console.log("Add mappping", this.mappings);
    this.refresh.emit();
    this.showConfigMapping = true;
  }

  async deleteSubscription(device: IIdentified) {
    console.log("Delete device", device);
    try {
      await this.mappingService.deleteSubscriptions(device);
      this.alertService.success(
        gettext("Subscription for this device deleted successfully")
      );
      this.loadSubscriptions();
    } catch (error) {
      this.alertService.danger(
        gettext("Failed to delete subscription:") + error
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
      editorMode: EditorMode.UPDATE,
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
    console.log("Editing mapping", this.mappingToUpdate);
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
      editorMode: EditorMode.COPY,
    };
    this.setStepperConfiguration(mapping.mappingType, mapping.direction);
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;
    this.mappingToUpdate.name = this.mappingToUpdate.name + " - Copy";
    this.mappingToUpdate.ident = uuidv4();
    this.mappingToUpdate.id = this.mappingToUpdate.ident;
    console.log("Copying mapping", this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  async deleteMapping(mapping: Mapping) {
    console.log("Deleting mapping:", mapping);
    try {
      await this.mappingService.deleteMapping(mapping);
      this.alertService.success(gettext("Mapping deleted successfully"));
      this.isConnectionToMQTTEstablished = true;
      this.loadMappings();
      this.refresh.emit();
      //this.activateMappings();
    } catch (error) {
      this.alertService.danger(gettext("Failed to delete mapping:") + error);
    }
  }

  async loadMappings(): Promise<void> {
    this.mappings = await this.mappingService.loadMappings(
      this.stepperConfiguration.direction
    );
    console.log("Updated mappings", this.mappings);
  }

  async onCommitMapping(mapping: Mapping) {
    // test if new/updated mapping was commited or if cancel
    mapping.lastUpdate = Date.now();

    console.log("Changed mapping:", mapping);

    if (
      (mapping.direction == Direction.INBOUND &&
        isTemplateTopicUnique(mapping, this.mappings)) 
        // test if we can attach multiple outbound mappings to the same filterOutbound
        || (mapping.direction == Direction.OUTBOUND 
        //  && isFilterOutboundUnique(mapping, this.mappings)
        )
    ) {
      if (this.stepperConfiguration.editorMode == EditorMode.UPDATE) {
        console.log("Update existing mapping:", mapping);
        try {
          await this.mappingService.updateMapping(mapping);
          this.alertService.success(gettext("Mapping updated successfully"));
          this.loadMappings();
          this.refresh.emit();
        } catch (error) {
          this.alertService.danger(
            gettext("Failed to updated mapping:") + error
          );
        }
        //this.activateMappings();
      } else if (
        this.stepperConfiguration.editorMode == EditorMode.CREATE ||
        this.stepperConfiguration.editorMode == EditorMode.COPY
      ) {
        // new mapping
        console.log("Push new mapping:", mapping);
        try {
          await this.mappingService.createMapping(mapping);
          this.alertService.success(gettext("Mapping created successfully"));
          this.loadMappings();
          this.refresh.emit();
        } catch (error) {
          this.alertService.danger(
            gettext("Failed to create mapping:") + error
          );
        }
        //this.activateMappings();
      }
      this.isConnectionToMQTTEstablished = true;
    } else {
      if (mapping.direction == Direction.INBOUND) {
        this.alertService.danger(
          gettext(
            "Topic is already used: " +
              mapping.subscriptionTopic +
              ". Please use a different topic."
          )
        );
      } else {
        this.alertService.danger(
          gettext(
            "FilterOutbound is already used: " +
              mapping.filterOutbound +
              ". Please use a different filter."
          )
        );
      }
    }
    this.showConfigMapping = false;
  }

  async onCommitSubscriptions(deviceList: IIdentified[]) {
    this.subscription = {
      api: API.ALL.name,
      devices: deviceList,
    };
    console.log("Changed deviceList:", this.subscription.devices);
    try {
      await this.mappingService.updateSubscriptions(this.subscription);
      this.alertService.success(gettext("Subscriptions updated successfully"));
    } catch (error) {
      this.alertService.danger(
        gettext("Failed to update subscriptions:") + error
      );
    }
    this.showConfigSubscription = false;
  }

  async onReloadClicked() {
    this.reloadMappings();
  }

  private async reloadMappings() {
    const response2 = await this.configurationService.runOperation(
      Operation.RELOAD_MAPPINGS
    );
    console.log("Activate mapping response:", response2);
    if (response2.status < 300) {
      //this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext("Failed to activate mappings"));
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
        allowTestSending: true,
      };
    } else if (mappingType == MappingType.PROCESSOR_EXTENSION) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: true,
        allowDefiningSubstitutions: false,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTestTransformation: false,
        allowTestSending: true,
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
