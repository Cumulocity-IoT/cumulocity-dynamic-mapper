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
import { CdkStep } from "@angular/cdk/stepper";
import {
  AfterContentChecked,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation,
} from "@angular/core";
import { FormControl, FormGroup } from "@angular/forms";
import { AlertService, C8yStepper } from "@c8y/ngx-components";
import { FormlyFieldConfig } from "@ngx-formly/core";
import * as _ from "lodash";
import { BsModalRef, BsModalService } from "ngx-bootstrap/modal";
import { BehaviorSubject, Subject } from "rxjs";
import { BrokerConfigurationService } from "../../mqtt-configuration/broker-configuration.service";
import {
  API,
  Direction,
  Extension,
  Mapping,
  MappingSubstitution,
  QOS,
  RepairStrategy,
  SnoopStatus,
  ValidationError,
} from "../../shared/mapping.model";
import {
  cloneSubstitution,
  COLOR_HIGHLIGHTED,
  countDeviceIdentifiers,
  definesDeviceIdentifier,
  deriveTemplateTopicFromTopic,
  getExternalTemplate,
  getSchema,
  isWildcardTopic,
  SAMPLE_TEMPLATES_C8Y,
  splitTopicExcludingSeparator,
  TOKEN_DEVICE_TOPIC,
  TOKEN_TOPIC_LEVEL,
  whatIsIt,
} from "../../shared/util";
import { MappingService } from "../core/mapping.service";
import { C8YRequest } from "../processor/prosessor.model";
import { SnoopingModalComponent } from "../snooping/snooping-modal.component";
import { EditorMode, StepperConfiguration } from "./stepper-model";
import { SubstitutionRendererComponent } from "./substitution/substitution-renderer.component";
import { isDisabled } from "./util";
import { JsonEditor2Component } from "../../shared/editor2/jsoneditor2.component";
import { EditSubstitutionComponent } from "../edit/edit-substitution-modal.component";

@Component({
  selector: "mapping-stepper",
  templateUrl: "mapping-stepper.component.html",
  styleUrls: ["../shared/mapping.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class MappingStepperComponent implements OnInit, AfterContentChecked {
  @Input() mapping: Mapping;
  @Input() mappings: Mapping[];
  @Input() stepperConfiguration: StepperConfiguration;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<Mapping>();

  ValidationError = ValidationError;
  Direction = Direction;
  COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  EditorMode = EditorMode;
  isDisabled = isDisabled;

  propertyFormly: FormGroup = new FormGroup({});
  propertyFormlyFields: FormlyFieldConfig[];
  templateFormly: FormGroup = new FormGroup({});
  templateForm: FormGroup;
  templateFormlyFields: FormlyFieldConfig[];
  testForm: FormGroup;

  templateModel: any = {};
  templateSource: any;
  templateTarget: any;

  testingModel: {
    results: C8YRequest[];
    errorMsg?: string;
    request?: any;
    response?: any;
    selectedResult: number;
  } = {
    results: [],
    selectedResult: -1,
  };

  countDeviceIdentifers$: BehaviorSubject<number> = new BehaviorSubject<number>(
    0
  );
  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;

  editorOptionsSource: any = {};
  editorOptionsTarget: any = {};
  editorOptionsTesting: any = {};

  selectedSubstitution: number = -1;

  snoopedTemplateCounter: number = 0;
  step: any;

  @ViewChild("editorSource", { static: false })
  editorSource: JsonEditor2Component;
  @ViewChild("editorTarget", { static: false })
  editorTarget: JsonEditor2Component;
  @ViewChild("editorTestingRequest", { static: false })
  editorTestingRequest: JsonEditor2Component;
  @ViewChild("editorTestingResponse", { static: false })
  editorTestingResponse: JsonEditor2Component;
  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild: SubstitutionRendererComponent;
  @ViewChild(C8yStepper, { static: false }) stepper: C8yStepper;

  extensions: Map<string, Extension> = new Map();
  extensionEvents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  onDestroy$ = new Subject<void>();
  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) {}

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND
        ? "Cumulocity"
        : "MQTT Broker";
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND
        ? "Cumulocity"
        : "MQTT Broker";
    this.templateModel = {
      mapping: this.mapping,
      currentSubstitution: {
        pathSource: "",
        pathTarget: "",
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false,
        targetExpression: {
          path: "",
          result: "",
          resultType: "empty",
          errrorMsg: "",
          infoMsg: "",
          infoShow: "",
        },
        sourceExpression: {
          path: "",
          result: "",
          resultType: "empty",
          errrorMsg: "",
          infoMsg: "",
          infoShow: "",
        },
      },
    };
    console.log(
      "Mapping to be updated:",
      this.mapping,
      this.stepperConfiguration
    );
    let numberSnooped = this.mapping.snoopedTemplates
      ? this.mapping.snoopedTemplates.length
      : 0;
    if (this.mapping.snoopStatus == SnoopStatus.STARTED && numberSnooped > 0) {
      this.alertService.success(
        "Already " +
          numberSnooped +
          " templates exist. In the next step you an stop the snooping process and use the templates. Click on Next"
      );
    }

    this.propertyFormlyFields = [
      {
        validators: {
          validation: [
            {
              name:
                this.stepperConfiguration.direction == Direction.INBOUND
                  ? "checkTopicsInboundAreValid"
                  : "checkTopicsOutboundAreValid",
            },
          ],
        },
        fieldGroupClassName: "row",
        fieldGroup: [
          {
            className: "col-lg-6",
            key: "name",
            wrappers: ["c8y-form-field"],
            type: "input",
            templateOptions: {
              label: "Mapping Name",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              required: true,
            },
          },
          {
            className: "col-lg-6",
            key: "templateTopic",
            type: "input",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Template Topic",
              placeholder: "Template Topic ...",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                "The TemplateTopic defines the topic to which this mapping is bound to. Name must begin with the Topic name.",
              required:
                this.stepperConfiguration.direction == Direction.INBOUND,
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND,
          },
          // filler when template topic is not shown
          {
            className: "col-lg-6",
            type: "filler",
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND,
          },
          {
            className: "col-lg-6",
            key: "subscriptionTopic",
            wrappers: ["c8y-form-field"],
            type: "input",
            templateOptions: {
              label: "Subscription Topic",
              placeholder: "Subscription Topic ...",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: "Subscription Topic",
              change: (field: FormlyFieldConfig, event?: any) => {
                this.mapping.templateTopic = deriveTemplateTopicFromTopic(
                  this.propertyFormly.get("subscriptionTopic").value
                );
                this.mapping.templateTopicSample = this.mapping.templateTopic;
                this.mapping = {
                  ...this.mapping,
                };
              },
              required:
                this.stepperConfiguration.direction == Direction.INBOUND,
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND,
          },
          {
            className: "col-lg-6",
            key: "publishTopic",
            type: "input",
            templateOptions: {
              label: "Publish Topic",
              placeholder: "Publish Topic ...",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              change: (field: FormlyFieldConfig, event?: any) => {
                const derived = deriveTemplateTopicFromTopic(
                  this.propertyFormly.get("publishTopic").value
                );
                this.mapping.templateTopicSample = derived;
                this.mapping = {
                  ...this.mapping,
                };
              },
              required:
                this.stepperConfiguration.direction == Direction.OUTBOUND,
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND,
          },
          {
            className: "col-lg-6",
            key: "templateTopicSample",
            type: "input",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Template Topic Sample",
              placeholder: "e.g. device/110",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: `The TemplateTopicSample name
              must have the same number of
              levels and must match the TemplateTopic.`,
              required: true,
            },
          },
          {
            className: "col-lg-12",
            key: "filterOutbound",
            type: "input",
            templateOptions: {
              label: "Filter Outbound",
              placeholder: "e.g. custom_OperationFragment",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                "The Filter Outbound can contain one fragment name to associate a mapping to a Cumulocity MEAO. If the Cumulocity MEAO contains this fragment, the mapping is applied. Specify nested elements as follows: custom_OperationFragment.value",
              required:
                this.stepperConfiguration.direction == Direction.OUTBOUND,
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND,
          },
        ],
      },
      {
        fieldGroupClassName: "row",
        fieldGroup: [
          {
            className: "col-lg-6",
            key: "targetAPI",
            type: "select",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Target API",
              options: Object.keys(API).map((key) => {
                return { label: key, value: key };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              change: (field: FormlyFieldConfig, event?: any) => {
                console.log("Changes:", field, event, this.mapping);
                this.onTargetAPIChanged(
                  this.propertyFormly.get("targetAPI").value
                );
              },
              required: true,
            },
          },
          {
            className: "col-lg-6",
            key: "createNonExistingDevice",
            type: "switch",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Create Non Existing Device",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                "In case a MEAO (Measuremente, Event, Alarm, Operation) is received and the referenced device does not yet exist, it can be created automatically.",
              required: false,
              switchMode: true,
              indeterminate: false,
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              this.mapping.targetAPI == API.INVENTORY.name,
          },
          {
            className: "col-lg-6",
            key: "updateExistingDevice",
            type: "switch",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Update Existing Device",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: "Update Existing Device.",
              required: false,
              switchMode: true,
              indeterminate: false,
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              (this.stepperConfiguration.direction == Direction.INBOUND &&
                this.mapping.targetAPI != API.INVENTORY.name),
          },
          {
            className: "col-lg-6",
            key: "autoAckOperation",
            type: "switch",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Auto acknowledge",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: "Auto acknowledge outbound operation.",
              required: false,
              switchMode: true,
              indeterminate: false,
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.INBOUND ||
              (this.stepperConfiguration.direction == Direction.OUTBOUND &&
                this.mapping.targetAPI != API.OPERATION.name),
          },
        ],
      },
      {
        fieldGroupClassName: "row",
        fieldGroup: [
          {
            className: "col-lg-6",
            key: "qos",
            type: "select",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "QOS",
              options: Object.values(QOS).map((key) => {
                return { label: key, value: key };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              required: true,
            },
          },
          {
            className: "col-lg-6",
            key: "snoopStatus",
            type: "select",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Snoop payload",
              options: Object.keys(SnoopStatus).map((key) => {
                return {
                  label: key,
                  value: key,
                  disabled: key != "ENABLED" && key != "NONE",
                };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                "Snooping records the payloads and saves them for later usage. Once the snooping starts and payloads are recorded, they can be used as templates for defining the source format of the MQTT mapping.",
              required: true,
            },
          },
        ],
      },
      {
        fieldGroupClassName: "row",
        fieldGroup: [
          {
            className: "col-lg-6",
            key: "mapDeviceIdentifier",
            type: "switch",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Map Device Identifier",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              switchMode: true,
              description: `If this is enabled then the device id is treated as an external id which is looked up and translated using th externalIdType.`,
              indeterminate: false,
            },
          },
          {
            className: "col-lg-6",
            key: "externalIdType",
            type: "input",
            templateOptions: {
              label: "External Id type",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
            },
            hideExpression: (model) => !model.mapDeviceIdentifier,
          },
        ],
      },
    ];

    this.templateFormlyFields = [
      {
        fieldGroup: [
          {
            className:
              "col-lg-5 col-lg-offset-1 text-monospace column-right-border",
            key: "pathSource",
            type: "input-sm",
            wrappers: ["custom-form-field"],
            templateOptions: {
              label: "Evaluate Expression on Source",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              placeholder:
                "e.g. $join([$substring(txt,5), _DEVICE_IDENT_]) or $number(_DEVICE_IDENT_)/10",
              description: `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>to convert a UNIX timestamp to ISO date format use:
                  <code>$fromMillis($number(deviceTimestamp))</code>
                </li>
                <li>to join substring starting at position 5 of property <code>txt</code> with
                  device
                  identifier use: <code>$join([$substring(txt,5), "-", _DEVICE_IDENT_])</code></li>
                <li>function chaining using <code>~</code> is not supported, instead use function
                  notation. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              change: (field: FormlyFieldConfig, event?: any) => {
                this.updateSourceExpressionResult(
                  this.templateFormly.get("pathSource").value
                );
              },
              required: false,
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe((value) => {
                  this.updateSourceExpressionResult(value);
                });
              },
            },
          },
          {
            className: "col-lg-5 text-monospace column-left-border",
            key: "pathTarget",
            type: "input-sm",
            wrappers: ["custom-form-field"],
            templateOptions: {
              label: "Evaluate Expression on Target",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              change: (field: FormlyFieldConfig, event?: any) => {
                this.updateTargetExpressionResult(
                  this.templateFormly.get("pathTarget").value
                );
              },
              required: false,
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe((value) => {
                  this.updateTargetExpressionResult(value);
                });
              },
            },
          },
        ],
      },
      {
        fieldGroup: [
          {
            className:
              "col-lg-5 reduced-top col-lg-offset-1 column-right-border not-p-b-24",
            type: "message-field",
            expressionProperties: {
              "templateOptions.content": (model) =>
                model.sourceExpression?.msgTxt,
              "templateOptions.textClass": (model) =>
                model.sourceExpression?.severity,
              "templateOptions.enabled": (model) => true,
            },
          },
          {
            // message field target
            className: "col-lg-5 reduced-top column-left-border not-p-b-24",
            type: "message-field",
            expressionProperties: {
              "templateOptions.content": (model) =>
                model.targetExpression?.msgTxt,
              "templateOptions.textClass": (model) =>
                model.targetExpression?.severity,
              "templateOptions.enabled": (model) => true,
            },
          },
        ],
      },

      {
        fieldGroup: [
          {
            //dummy row to start new row
            className: "row",
            key: "textField",
            type: "text",
          },
          {
            className:
              "col-lg-5 col-lg-offset-1 text-monospace font-smaller column-right-border",
            key: "sourceExpression.result",
            type: "input-sm",
            templateOptions: {
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              readonly: true,
            },
            expressionProperties: {
              "templateOptions.label": (label) =>
                `Result Type [${this.templateModel.sourceExpression?.resultType}]`,
            },
          },
          {
            className:
              "col-lg-5 text-monospace font-smaller column-left-border",
            key: "targetExpression.result",
            type: "input-sm",
            templateOptions: {
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              readonly: true,
            },
            expressionProperties: {
              "templateOptions.label": (label) =>
                `Result Type [${this.templateModel.targetExpression?.resultType}]`,
            },
          },
        ],
      },
    ];

    this.setTemplateForm();
    this.editorOptionsSource = {
      ...this.editorOptionsSource,
      mode: "tree",
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      name: "message",
    };

    this.editorOptionsTarget = {
      ...this.editorOptionsTarget,
      mode: "tree",
      mainMenuBar: true,
      navigationBar: false,
      statusBar: true,
    };

    this.editorOptionsTesting = {
      ...this.editorOptionsTesting,
      mode: "tree",
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: true,
    };

    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));

    this.extensionEvents$.subscribe((events) => {
      console.log("New events from extension", events);
    });
  }

  private setTemplateForm(): void {
    this.templateForm = new FormGroup({
      exName: new FormControl({
        value: this.mapping?.extension?.name,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
      }),
      exEvent: new FormControl({
        value: this.mapping?.extension?.event,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
      }),
    });
  }

  private getTemplateForm(): void {
    if (this.mapping.extension) {
      this.mapping.extension.name = this.templateForm.controls["exName"].value;
      this.mapping.extension.event =
        this.templateForm.controls["exEvent"].value;
    }
  }

  ngAfterContentChecked(): void {
    // if json source editor is displayed then choose the first selection
    const editorSourceRef =
      this.elementRef.nativeElement.querySelector("#editorSource");
    if (editorSourceRef != null && !editorSourceRef.getAttribute("schema")) {
      //set schema for editors
      if (this.stepperConfiguration.showEditorSource) {
        this.editorSource.setSchema(
          getSchema(this.mapping.targetAPI, this.mapping.direction, false)
        );
        editorSourceRef.setAttribute("schema", "true");
      }
    }
    const editorTargetRef =
      this.elementRef.nativeElement.querySelector("#editorTarget");
    if (editorTargetRef != null && !editorTargetRef.getAttribute("schema")) {
      //set schema for editors
      this.editorTarget.setSchema(
        getSchema(API.MEASUREMENT.name, Direction.INBOUND, true)
      );
      editorTargetRef.setAttribute("schema", "true");
    }

    const editorTestingResponseRef =
      this.elementRef.nativeElement.querySelector("#editorTestingResponse");
    if (
      editorTestingResponseRef != null &&
      !editorTestingResponseRef.getAttribute("schema")
    ) {
      //set schema for editors
      this.editorTestingResponse.setSchema(
        getSchema(this.mapping.targetAPI, this.mapping.direction, true)
      );
      editorTestingResponseRef.setAttribute("schema", "true");
    }

    const editorTestingRequestRef = this.elementRef.nativeElement.querySelector(
      "#editorTestingRequestRef"
    );
    if (
      editorTestingRequestRef != null &&
      !editorTestingRequestRef.getAttribute("schema")
    ) {
      //set schema for editors
      this.editorTestingRequest.setSchema(
        getSchema(this.mapping.targetAPI, this.mapping.direction, true)
      );
      editorTestingRequestRef.setAttribute("schema", "true");
    }
  }

  public onSelectedSourcePathChanged(path: string) {
    this.templateFormly.get("pathSource").patchValue(path);
  }

  public async updateSourceExpressionResult(path: string) {
    try {
      this.templateModel.sourceExpression = {
        msgTxt: "",
        severity: "text-info",
      };
      this.templateFormly.get("pathSource").setErrors(null);

      let r: JSON = await this.mappingService.evaluateExpression(
        this.editorSource?.get(),
        path
      );
      this.templateModel.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
      };

      if (
        this.templateModel.sourceExpression == "Array" &&
        !this.templateModel.sourceExpression.expandArray
      ) {
        this.templateModel.sourceExpression.msgTxt =
          'Current expression extracts an array. Consider to use the option "Expand as array" if you want to create multiple measurements, alarms, events or devices, i.e. "multi-device" or "multi-value"';
        this.templateModel.sourceExpression.severity = "text-warning";
      }
    } catch (error) {
      console.log("Error evaluating source expression: ", error);
      this.templateModel.sourceExpression = {
        msgTxt: error.message,
        severity: "text-danger",
      };
      this.templateFormly.get("pathSource").setErrors({ error: error.message });
    }
    this.templateModel = {
      ...this.templateModel,
    };
  }

  public onSelectedTargetPathChanged(path: string) {
    this.templateModel.pathTarget = path;
  }

  public async updateTargetExpressionResult(path: string) {
    try {
      this.templateModel.targetExpression = {
        msgTxt: "",
        severity: "text-info",
      };
      this.templateFormly.get("pathTarget").setErrors(null);
      let r: JSON = await this.mappingService.evaluateExpression(
        this.editorTarget?.get(),
        path
      );
      this.templateModel.targetExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
      };

      const definesDI = definesDeviceIdentifier(
        this.mapping.targetAPI,
        this.templateModel,
        this.mapping.direction
      );
      if (definesDI) {
        this.templateModel.targetExpression.msgTxt =
          API[this.mapping.targetAPI].identifier +
          ` is resolved using the external Id ` +
          this.mapping.externalIdType +
          ` defined s in the previous step.`;
        this.templateModel.targetExpression.severity = "text-info";
      }
    } catch (error) {
      console.log("Error evaluating target expression: ", error);
      this.templateModel.targetExpression = {
        msgTxt: error.message,
        severity: "text-danger",
      };
      this.templateFormly.get("pathTarget").setErrors({ error: error.message });
    }
    this.templateModel = {
      ...this.templateModel,
    };
  }

  private getCurrentMapping(patched: boolean): Mapping {
    return {
      ...this.mapping,
      source: this.reduceSourceTemplate(
        this.editorSource ? this.editorSource.get() : {},
        patched
      ), //remove dummy field "_DEVICE_IDENT_", array "_TOPIC_LEVEL_" since it should not be stored
      target: this.reduceTargetTemplate(this.editorTarget.get(), patched), //remove dummy field "_DEVICE_IDENT_", since it should not be stored
      lastUpdate: Date.now(),
    };
  }

  async onCommitButton() {
    this.onCommit.emit(this.getCurrentMapping(false));
  }

  async onTestTransformation() {
    let testProcessingContext = await this.mappingService.testResult(
      this.getCurrentMapping(true),
      false
    );
    this.testingModel.results = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successful!");
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.success("Testing tranformation was successful!");
    }
    this.onNextTestResult();
  }

  async onSendTest() {
    let testProcessingContext = await this.mappingService.testResult(
      this.getCurrentMapping(true),
      true
    );
    this.testingModel.results = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successful!");
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.info(
        `Sending tranformation was successful: ${testProcessingContext.requests[0].response.id}`
      );
      //console.log("RES", testProcessingContext.requests[0].response);
    }
    this.onNextTestResult();
  }

  public onNextTestResult() {
    if (
      this.testingModel.selectedResult >=
      this.testingModel.results.length - 1
    ) {
      this.testingModel.selectedResult = -1;
    }
    this.testingModel.selectedResult++;
    this.selectedResult$.next(this.testingModel.selectedResult);
    if (
      this.testingModel.selectedResult >= 0 &&
      this.testingModel.selectedResult < this.testingModel.results.length
    ) {
      this.testingModel.request =
        this.testingModel.results[this.testingModel.selectedResult].request;
      this.testingModel.response =
        this.testingModel.results[this.testingModel.selectedResult].response;
      this.editorTestingRequest.setSchema(
        getSchema(
          this.testingModel.results[this.testingModel.selectedResult].targetAPI,
          this.mapping.direction,
          true
        )
      );
      this.testingModel.errorMsg =
        this.testingModel.results[this.testingModel.selectedResult].error;
    } else {
      this.testingModel.request = JSON.parse("{}");
      this.testingModel.response = JSON.parse("{}");
      this.testingModel.errorMsg = undefined;
    }
  }

  async onSampleTargetTemplatesButton() {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateTarget = this.expandC8YTemplate(
        JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI])
      );
    } else {
      let levels: String[] = splitTopicExcludingSeparator(
        this.mapping.templateTopicSample
      );
      this.templateTarget = this.expandExternalTemplate(
        JSON.parse(getExternalTemplate(this.mapping)),
        levels
      );
    }
    this.editorTarget.set(this.templateTarget);
  }

  async onCancelButton() {
    this.onCancel.emit();
  }

  onSelectExtension(extension) {
    console.log("onSelectExtension", extension);
    this.mapping.extension.name = extension;
    this.extensionEvents$.next(
      Object.keys(this.extensions[extension].extensionEntries)
    );
  }

  public async onNextStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    console.log("OnNextStep", event.step.label, this.mapping);
    this.step = event.step.label;

    if (this.step == "Define topic") {
      this.templateModel.mapping = this.mapping;
      console.log(
        "Populate jsonPath if wildcard:",
        isWildcardTopic(this.mapping.subscriptionTopic),
        this.mapping.substitutions.length
      );
      console.log(
        "Templates from mapping:",
        this.mapping.target,
        this.mapping.source
      );
      this.enrichTemplates();
      this.extensions =
        (await this.configurationService.getProcessorExtensions()) as any;
      if (this.mapping?.extension?.name) {
        this.extensionEvents$.next(
          Object.keys(
            this.extensions[this.mapping?.extension?.name].extensionEntries
          )
        );
      }

      let numberSnooped = this.mapping.snoopedTemplates
        ? this.mapping.snoopedTemplates.length
        : 0;
      const initialState = {
        snoopStatus: this.mapping.snoopStatus,
        numberSnooped: numberSnooped,
      };
      if (
        this.mapping.snoopStatus == SnoopStatus.ENABLED &&
        this.mapping.snoopedTemplates.length == 0
      ) {
        console.log("Ready to snoop ...");
        const modalRef: BsModalRef = this.bsModalService.show(
          SnoopingModalComponent,
          { initialState }
        );
        modalRef.content.closeSubject.subscribe((confirm: boolean) => {
          if (confirm) {
            this.onCommit.emit(this.getCurrentMapping(false));
          } else {
            this.mapping.snoopStatus = SnoopStatus.NONE;
            event.stepper.next();
          }
          modalRef.hide();
        });
      } else if (this.mapping.snoopStatus == SnoopStatus.STARTED) {
        console.log("Continue snoop ...?");
        const modalRef: BsModalRef = this.bsModalService.show(
          SnoopingModalComponent,
          { initialState }
        );
        modalRef.content.closeSubject.subscribe((confirm: boolean) => {
          if (confirm) {
            this.mapping.snoopStatus = SnoopStatus.STOPPED;
            if (numberSnooped > 0) {
              this.templateSource = JSON.parse(
                this.mapping.snoopedTemplates[0]
              );
              let levels: String[] = splitTopicExcludingSeparator(
                this.mapping.templateTopicSample
              );
              if (this.stepperConfiguration.direction == Direction.INBOUND) {
                this.templateSource = this.expandExternalTemplate(
                  this.templateSource,
                  levels
                );
              } else {
                this.templateSource = this.expandC8YTemplate(
                  this.templateSource
                );
              }
              this.onSampleTargetTemplatesButton();
            }
            event.stepper.next();
          } else {
            this.onCancel.emit();
          }
          modalRef.hide();
        });
      } else {
        event.stepper.next();
      }
    } else if (this.step == "Define templates and substitutions") {
      this.getTemplateForm();
      this.editorTestingRequest.set(
        this.editorSource ? this.editorSource.get() : ({} as JSON)
      );
      this.editorTestingResponse.set({} as JSON);
      this.onSelectSubstitution(0);
      event.stepper.next();
    }
  }

  private enrichTemplates() {
    let levels: String[] = splitTopicExcludingSeparator(
      this.mapping.templateTopicSample
    );

    if (this.stepperConfiguration.editorMode == EditorMode.CREATE) {
      if (this.stepperConfiguration.direction == Direction.INBOUND) {
        this.templateSource = this.expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          levels
        );
        this.templateTarget = this.expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI])
        );
      } else {
        this.templateSource = this.expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI])
        );
        this.templateTarget = this.expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          levels
        );
      }
      console.log(
        "Sample template",
        this.templateTarget,
        getSchema(this.mapping.targetAPI, this.mapping.direction, true)
      );
    } else {
      if (this.stepperConfiguration.direction == Direction.INBOUND) {
        this.templateSource = this.expandExternalTemplate(
          JSON.parse(this.mapping.source),
          levels
        );
        this.templateTarget = this.expandC8YTemplate(
          JSON.parse(this.mapping.target)
        );
      } else {
        this.templateSource = this.expandC8YTemplate(
          JSON.parse(this.mapping.source)
        );
        this.templateTarget = this.expandExternalTemplate(
          JSON.parse(this.mapping.target),
          levels
        );
      }
    }
  }

  async onSnoopedSourceTemplates() {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }
    try {
      this.templateSource = JSON.parse(
        this.mapping.snoopedTemplates[this.snoopedTemplateCounter]
      );
    } catch (error) {
      this.templateSource = {
        message: this.mapping.snoopedTemplates[this.snoopedTemplateCounter],
      };
      console.warn(
        "The payload was not in JSON format, now wrap it:",
        this.templateSource
      );
    }
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateSource = this.expandExternalTemplate(
        this.templateSource,
        splitTopicExcludingSeparator(this.mapping.templateTopicSample)
      );
    } else {
      this.templateSource = this.expandC8YTemplate(this.templateSource);
    }
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onTargetAPIChanged(targetAPI) {
    this.mapping.targetAPI = targetAPI;
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateTarget = SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI];
    } else {
      this.templateTarget = getExternalTemplate(this.mapping);
    }
  }

  public onAddSubstitution() {
    if (
      this.templateModel.pathSource != "" &&
      this.templateModel.pathTarget != ""
    ) {
      this.templateModel.resolve2ExternalId = false;
      this.templateModel.expandArray = false;
      this.templateModel.repairStrategy = RepairStrategy.DEFAULT;
      this.addSubstitution(this.templateModel);
      this.selectedSubstitution = -1;
      console.log(
        "New substitution",
        this.templateModel,
        this.mapping.substitutions
      );
      this.templateModel = {
        pathSource: "",
        pathTarget: "",
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false,
      };
    } else {
      this.alertService.warning(
        "Please select two nodes: one node in the template source, one node in the template target to define a substitution."
      );
    }
  }

  public onDeleteAllSubstitution() {
    this.mapping.substitutions = [];
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));

    console.log("Cleared substitutions!");
  }

  public onDeleteSelectedSubstitution() {
    console.log("Delete selected substitution", this.selectedSubstitution);
    if (this.selectedSubstitution < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(this.selectedSubstitution, 1);
      this.selectedSubstitution = -1;
    }
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
    console.log("Deleted substitution", this.mapping.substitutions.length);
  }

  public onDeleteSubstitution(selected: number) {
    console.log("Delete selected substitution", selected);
    if (selected < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(selected, 1);
      selected = -1;
    }
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
    console.log("Deleted substitution", this.mapping.substitutions.length);
  }

  public onUpdateSubstitution() {
    if (this.selectedSubstitution != -1) {
      const selected = this.selectedSubstitution;
      console.log("Edit selected substitution", selected);
      const initialState = {
        substitution: _.clone(this.mapping.substitutions[selected]),
        mapping: this.mapping,
        stepperConfiguration: this.stepperConfiguration,
      };
      if (
        this.templateModel.sourceExpression?.severity != "text-danger" &&
        this.templateModel.targetExpression?.severity != "text-danger"
      ) {
        initialState.substitution.pathSource = this.templateModel.pathSource;
        initialState.substitution.pathTarget = this.templateModel.pathTarget;
      }
      const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
        initialState,
      });
      modalRef.content.closeSubject.subscribe((editedSub) => {
        console.log("Mapping after edit:", editedSub);
        if (editedSub) {
          this.mapping.substitutions[selected] = editedSub;
          this.templateModel.pathSource = editedSub.pathSource;
          this.templateModel.pathTarget = editedSub.pathTarget;
        }
      });
      this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
      console.log("Edited substitution", this.mapping.substitutions.length);
    }
  }

  private addSubstitution(st: MappingSubstitution) {
    let sub: MappingSubstitution = _.clone(st);
    let existingSubstitution = -1;
    this.mapping.substitutions.forEach((s, index) => {
      if (sub.pathTarget == s.pathTarget) {
        existingSubstitution = index;
      }
    });
    const initialState = {
      duplicate: existingSubstitution != -1,
      existingSubstitution: existingSubstitution,
      substitution: sub,
      mapping: this.mapping,
      stepperConfiguration: this.stepperConfiguration,
    };
    const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
      initialState,
    });
    modalRef.content.closeSubject.subscribe((result) => {
      console.log("results:", result);
    });
    modalRef.content.closeSubject.subscribe((newSub: MappingSubstitution) => {
      console.log("About to add new substitution:", newSub);
      if (newSub) {
        this.mapping.substitutions.push(newSub);
        this.templateModel.pathSource = newSub.pathSource;
        this.templateModel.pathTarget = newSub.pathTarget;
      }
    });
  }

  public onNextSubstitution() {
    this.substitutionChild.scrollToSubstitution(this.selectedSubstitution);
    if (this.selectedSubstitution >= this.mapping.substitutions.length - 1) {
      this.selectedSubstitution = -1;
    }
    this.selectedSubstitution++;
    this.onSelectSubstitution(this.selectedSubstitution);
  }

  public onSelectSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length && selected > -1) {
      this.selectedSubstitution = selected;
      this.templateFormly
        .get("pathSource")
        .setValue(this.mapping.substitutions[selected].pathSource);
      this.templateFormly
        .get("pathTarget")
        .setValue(this.mapping.substitutions[selected].pathTarget);
      this.editorSource?.setSelectionToPath(this.templateModel.pathSource);
      this.editorTarget.setSelectionToPath(this.templateModel.pathTarget);
      this.templateModel = { ...this.templateModel };
    }
  }

  private expandExternalTemplate(t: object, levels: String[]): object {
    if (Array.isArray(t)) {
      return t;
    } else {
      return {
        ...t,
        _TOPIC_LEVEL_: levels,
      };
    }
  }

  private expandC8YTemplate(t: object): object {
    if (this.mapping.targetAPI == API.INVENTORY.name) {
      return {
        ...t,
        _DEVICE_IDENT_: "909090",
      };
    } else {
      return t;
    }
  }

  private reduceSourceTemplate(t: object, patched: boolean): string {
    if (!patched) delete t[TOKEN_TOPIC_LEVEL];
    let tt = JSON.stringify(t);
    return tt;
  }

  private reduceTargetTemplate(t: object, patched: boolean): string {
    if (!patched) delete t[TOKEN_DEVICE_TOPIC];
    let tt = JSON.stringify(t);
    return tt;
  }
}
