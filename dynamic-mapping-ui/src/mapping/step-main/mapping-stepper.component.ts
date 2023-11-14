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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * Unless required by applicable law or agreed to in writing, software
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { CdkStep } from "@angular/cdk/stepper";
import {
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
import { BrokerConfigurationService } from "../../configuration/broker-configuration.service";
import {
  API,
  Direction,
  Extension,
  Mapping,
  MappingSubstitution,
  RepairStrategy,
  SnoopStatus,
  ValidationError,
} from "../../shared/mapping.model";
import {
  COLOR_HIGHLIGHTED,
  countDeviceIdentifiers,
  definesDeviceIdentifier,
  expandC8YTemplate,
  expandExternalTemplate,
  getExternalTemplate,
  getSchema,
  isWildcardTopic,
  reduceSourceTemplate,
  reduceTargetTemplate,
  SAMPLE_TEMPLATES_C8Y,
  splitTopicExcludingSeparator,
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
  selector: "d11r-mapping-stepper",
  templateUrl: "mapping-stepper.component.html",
  styleUrls: ["../shared/mapping.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class MappingStepperComponent implements OnInit {
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

  templateFormly: FormGroup = new FormGroup({});
  templateForm: FormGroup;
  templateFormlyFields: FormlyFieldConfig[];
  editorTestingPayloadTemplateEmitter = new EventEmitter<any>();
  schemaUpdateSource: EventEmitter<string> = new EventEmitter<any>();
  schemaUpdateTarget: EventEmitter<string> = new EventEmitter<any>();

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
  propertyFormly: FormGroup = new FormGroup({});
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
  editorTestingResponse: JsonEditor2Component;
  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild: SubstitutionRendererComponent;

  extensions: Map<string, Extension> = new Map();
  extensionEvents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  onDestroy$ = new Subject<void>();
  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public brokerConfigurationService: BrokerConfigurationService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) {}

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND
        ? "Cumulocity"
        : "Broker";
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND
        ? "Cumulocity"
        : "Broker";
    this.templateModel = {
      stepperConfiguration: this.stepperConfiguration,
      mapping: this.mapping,
      currentSubstitution: {
        pathSource: "",
        pathTarget: "",
        repairStrategy: RepairStrategy.DEFAULT,
        resolve2ExternalId: false,
        expandArray: false,
        targetExpression: {
          result: "",
          resultType: "empty",
          msgTxt: "",
          severity: "text-info",
        },
        sourceExpression: {
          result: "",
          resultType: "empty",
          msgTxt: "",
          severity: "text-info",
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

    this.templateFormlyFields = [
      {
        fieldGroup: [
          {
            className:
              "col-lg-5 col-lg-offset-1 text-monospace column-right-border",
            key: "currentSubstitution.pathSource",
            type: "input-custom",
            wrappers: ["custom-form-field"],
            templateOptions: {
              label: "Evaluate Expression on Source",
              class: "input-sm animate-background",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              placeholder: "$join([$substring(txt,5), id]) or $number(id)/10",
              description: `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>to convert a UNIX timestamp to ISO date format use:
                  <code>$fromMillis($number(deviceTimestamp))</code>
                </li>
                <li>to join substring starting at position 5 of property <code>txt</code> with
                  device
                  identifier use: <code>$join([$substring(txt,5), "-", id])</code></li>
                <li>function chaining using <code>~</code> is not supported, instead use function
                  notation. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              change: (field: FormlyFieldConfig, event?: any) => {
                this.updateSourceExpressionResult(
                  this.templateFormly.get("currentSubstitution.pathSource")
                    .value
                );
              },
              required: false,
            },
            expressionProperties: {
              "templateOptions.class": (model) => {
                if (
                  model.currentSubstitution.pathSource == "" &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return "input-sm animate-background";
                } else {
                  return "input-sm";
                }
              },
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
            key: "currentSubstitution.pathTarget",
            type: "input-custom",
            wrappers: ["custom-form-field"],
            templateOptions: {
              label: "Evaluate Expression on Target",
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              change: (field: FormlyFieldConfig, event?: any) => {
                this.updateTargetExpressionResult(
                  this.templateFormly.get("currentSubstitution.pathTarget")
                    .value
                );
              },
              description: `Use the same <a href="https://jsonata.org" target="_blank">JSONata</a>
              expressions as in the source template. In addition you can use <code>$</code> to merge the 
              result of the source expression with the existing target template. Special care is 
              required since this can overwrite mandatory Cumulocity attributes, e.g. <code>source.id</code>.  This can result in API calls that are rejected by the Cumulocity backend!`,
              required: false,
            },
            expressionProperties: {
              "templateOptions.class": (model) => {
                //console.log("Logging class:", t)
                if (
                  model.currentSubstitution.pathTarget == "" &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return "input-sm animate-background";
                } else {
                  return "input-sm";
                }
              },
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
                model.currentSubstitution.sourceExpression.msgTxt,
              "templateOptions.textClass": (model) =>
                model.currentSubstitution.sourceExpression.severity,
              "templateOptions.enabled": (model) => true,
            },
          },
          {
            // message field target
            className: "col-lg-5 reduced-top column-left-border not-p-b-24",
            type: "message-field",
            expressionProperties: {
              "templateOptions.content": (model) =>
                model.currentSubstitution.targetExpression.msgTxt,
              "templateOptions.textClass": (model) =>
                model.currentSubstitution.targetExpression.severity,
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
            key: "currentSubstitution.sourceExpression.result",
            type: "textarea-custom",
            wrappers: ["custom-form-field"],
            templateOptions: {
              class: "input-sm",
              disabled: true,
              readonly: true,
            },
            expressionProperties: {
              "templateOptions.label": (model) =>
                `Result Type [${this.templateModel.currentSubstitution.sourceExpression.resultType}]`,
              "templateOptions.value": (model) => {
                return `${this.templateModel.currentSubstitution.sourceExpression.result}`;
              },
            },
          },
          {
            className:
              "col-lg-5 text-monospace font-smaller column-left-border",
            key: "currentSubstitution.targetExpression.result",
            type: "textarea-custom",
            wrappers: ["custom-form-field"],
            templateOptions: {
              class: "input-sm",
              disabled: true,
              readonly: true,
            },
            expressionProperties: {
              "templateOptions.label": (model) =>
                `Result Type [${this.templateModel.currentSubstitution.targetExpression.resultType}]`,
              "templateOptions.value": (model) => {
                return `${this.templateModel.currentSubstitution.targetExpression.result}`;
              },
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

  public onSelectedPathSourceChanged(path: string) {
    this.templateFormly.get("currentSubstitution.pathSource").setValue(path);
  }

  public onEditorSourceInitialized(state: string) {
    this.schemaUpdateSource.emit(
      getSchema(this.mapping.targetAPI, this.mapping.direction, false)
    );
  }

  public onEditorTargetInitialized(state: string) {
    this.schemaUpdateTarget.emit(
      getSchema(this.mapping.targetAPI, this.mapping.direction, true)
    );
  }

  public async updateSourceExpressionResult(path: string) {
    this.editorSource.schemaUpdate;
    try {
      this.templateModel.currentSubstitution.sourceExpression = {
        msgTxt: "",
        severity: "text-info",
      };
      this.templateFormly.get("currentSubstitution.pathSource").setErrors(null);

      let r: JSON = await this.mappingService.evaluateExpression(
        this.editorSource?.get(),
        path
      );
      this.templateModel.currentSubstitution.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
      };

      if (
        this.templateModel.currentSubstitution.sourceExpression.resultType ==
          "Array" &&
        !this.templateModel.currentSubstitution.expandArray
      ) {
        this.templateModel.currentSubstitution.sourceExpression.msgTxt =
          'Current expression extracts an array. Consider to use the option "Expand as array" if you want to create multiple measurements, alarms, events or devices, i.e. "multi-device" or "multi-value"';
        this.templateModel.currentSubstitution.sourceExpression.severity =
          "text-warning";
      }
    } catch (error) {
      console.log("Error evaluating source expression: ", error);
      this.templateModel.currentSubstitution.sourceExpression = {
        msgTxt: error.message,
        severity: "text-danger",
      };
      this.templateFormly
        .get("currentSubstitution.pathSource")
        .setErrors({ error: error.message });
    }
    this.templateModel = { ...this.templateModel };
  }

  isSubstitutionValid() {
    const r1 =
      this.templateModel.currentSubstitution.sourceExpression.severity !=
      "text-danger";
    const r2 =
      this.templateModel.currentSubstitution.targetExpression.severity !=
      "text-danger";
    const r3 = this.templateModel.currentSubstitution.pathSource != "";
    const r4 = this.templateModel.currentSubstitution.pathTarget != "";
    let result = r1 && r2 && r3 && r4;
    return result;
  }

  public onSelectedPathTargetChanged(path: string) {
    this.templateFormly.get("currentSubstitution.pathTarget").setValue(path);
  }

  public async updateTargetExpressionResult(path: string) {
    try {
      this.templateModel.currentSubstitution.targetExpression = {
        msgTxt: "",
        severity: "text-info",
      };
      this.templateFormly.get("currentSubstitution.pathTarget").setErrors(null);
      let r: JSON = await this.mappingService.evaluateExpression(
        this.editorTarget?.get(),
        path
      );
      this.templateModel.currentSubstitution.targetExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
      };

      const definesDI = definesDeviceIdentifier(
        this.mapping.targetAPI,
        this.templateModel.currentSubstitution,
        this.mapping.direction
      );
      if (definesDI) {
        this.templateModel.currentSubstitution.targetExpression.msgTxt =
          API[this.mapping.targetAPI].identifier +
          ` is resolved using the external Id ` +
          this.mapping.externalIdType +
          ` defined s in the previous step.`;
        this.templateModel.currentSubstitution.targetExpression.severity =
          "text-info";
      } else if (path == "$") {
        this.templateModel.currentSubstitution.targetExpression.msgTxt = `By specifying "$" you selected the root of the target 
        template and this rersults in merging the source expression with the target template.`;
        this.templateModel.currentSubstitution.targetExpression.severity =
          "text-warning";
      }
    } catch (error) {
      console.log("Error evaluating target expression: ", error);
      this.templateModel.currentSubstitution.targetExpression = {
        msgTxt: error.message,
        severity: "text-danger",
      };
      this.templateFormly
        .get("currentSubstitution.pathTarget")
        .setErrors({ error: error.message });
    }
    this.templateModel = { ...this.templateModel };
  }

  public getCurrentMapping(patched: boolean): Mapping {
    return {
      ...this.mapping,
      source: reduceSourceTemplate(
        this.editorSource ? this.editorSource.get() : {},
        patched
      ), //remove array "_TOPIC_LEVEL_" since it should not be stored
      target: reduceTargetTemplate(this.editorTarget?.get(), patched), //remove pachted attributes, since it should not be stored
      lastUpdate: Date.now(),
    };
  }

  async onCommitButton() {
    this.onCommit.emit(this.getCurrentMapping(false));
  }

  async onSampleTargetTemplatesButton() {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateTarget = expandC8YTemplate(
        JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
        this.mapping
      );
    } else {
      let levels: String[] = splitTopicExcludingSeparator(
        this.mapping.templateTopicSample
      );
      this.templateTarget = expandExternalTemplate(
        JSON.parse(getExternalTemplate(this.mapping)),
        this.mapping,
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

  public async onStepChange(event): Promise<void> {
    console.log("OnStepChange", event);
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
        (await this.brokerConfigurationService.getProcessorExtensions()) as any;
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
                this.templateSource = expandExternalTemplate(
                  this.templateSource,
                  this.mapping,
                  levels
                );
              } else {
                this.templateSource = expandC8YTemplate(
                  this.templateSource,
                  this.mapping
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
      const testSourceTemplate = this.editorSource
        ? this.editorSource.get()
        : {};
      this.editorTestingPayloadTemplateEmitter.emit(testSourceTemplate);
      this.onSelectSubstitution(0);
      event.stepper.next();
    }
  }

  public async onBackStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    console.log("onBackStep", event.step.label, this.mapping);
    this.step = event.step.label;
    if (this.step == "Test mapping") {
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector("#editorTestingRequest");
      if (editorTestingRequestRef != null) {
        editorTestingRequestRef.setAttribute("schema", undefined);
      }
    }
    event.stepper.previous();
  }

  private enrichTemplates() {
    let levels: String[] = splitTopicExcludingSeparator(
      this.mapping.templateTopicSample
    );

    if (this.stepperConfiguration.editorMode == EditorMode.CREATE) {
      if (this.stepperConfiguration.direction == Direction.INBOUND) {
        this.templateSource = expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          this.mapping,
          levels
        );
        this.templateTarget = expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
          this.mapping
        );
      } else {
        this.templateSource = expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
          this.mapping
        );
        this.templateTarget = expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          this.mapping,
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
        this.templateSource = expandExternalTemplate(
          JSON.parse(this.mapping.source),
          this.mapping,
          levels
        );
        this.templateTarget = expandC8YTemplate(
          JSON.parse(this.mapping.target),
          this.mapping
        );
      } else {
        this.templateSource = expandC8YTemplate(
          JSON.parse(this.mapping.source),
          this.mapping
        );
        this.templateTarget = expandExternalTemplate(
          JSON.parse(this.mapping.target),
          this.mapping,
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
      this.templateSource = expandExternalTemplate(
        this.templateSource,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.templateTopicSample)
      );
    } else {
      this.templateSource = expandC8YTemplate(
        this.templateSource,
        this.mapping
      );
    }
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onTargetTemplateChanged(templateTarget) {
    this.templateTarget = templateTarget;
  }

  async updateTestResult(result) {
    this.mapping.tested = result;
  }

  public onAddSubstitution() {
    if (this.isSubstitutionValid()) {
      this.templateModel.currentSubstitution.expandArray = false;
      this.templateModel.currentSubstitution.repairStrategy =
        RepairStrategy.DEFAULT;
      this.templateModel.currentSubstitution.resolve2ExternalId = false;
      this.addSubstitution(this.templateModel.currentSubstitution);
      this.selectedSubstitution = -1;
      console.log(
        "New substitution",
        this.templateModel,
        this.mapping.substitutions
      );
    } else {
      this.alertService.warning(
        "Please select two nodes: one node in the template source, one node in the template target to define a substitution."
      );
    }
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
        this.templateModel.currentSubstitution.sourceExpression?.severity !=
          "text-danger" &&
        this.templateModel.currentSubstitution.targetExpression?.severity !=
          "text-danger"
      ) {
        initialState.substitution.pathSource =
          this.templateModel.currentSubstitution.pathSource;
        initialState.substitution.pathTarget =
          this.templateModel.currentSubstitution.pathTarget;
      }
      const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
        initialState,
      });
      modalRef.content.closeSubject.subscribe((editedSub) => {
        console.log("Mapping after edit:", editedSub);
        if (editedSub) {
          this.mapping.substitutions[selected] = editedSub;
          this.templateModel.currentSubstitution.pathSource =
            editedSub.pathSource;
          this.templateModel.currentSubstitution.pathTarget =
            editedSub.pathTarget;
        }
      });
      this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
      console.log("Edited substitution", this.mapping.substitutions.length);
    }
  }

  private addSubstitution(ns: MappingSubstitution) {
    let sub: MappingSubstitution = _.clone(ns);
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
      }
    });
  }

  public onSelectSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length && selected > -1) {
      this.selectedSubstitution = selected;
      this.templateModel.currentSubstitution = _.clone(
        this.mapping.substitutions[selected]
      );
      this.editorSource?.setSelectionToPath(
        this.templateModel.currentSubstitution.pathSource
      );
      this.editorTarget.setSelectionToPath(
        this.templateModel.currentSubstitution.pathTarget
      );
    }
  }

  public onTemplateChanged(templateTarget: any): void {
    this.editorTarget.set(templateTarget);
  }
}
