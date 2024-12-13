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
import { CdkStep } from '@angular/cdk/stepper';
import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import * as _ from 'lodash';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject } from 'rxjs';
import { Content, JSONEditorPropsOptional, Mode } from 'vanilla-jsoneditor';
import { ExtensionService } from '../../extension';
import {
  API,
  COLOR_HIGHLIGHTED,
  ConnectorType,
  DeploymentMapEntry,
  Direction,
  Extension,
  ExtensionEntry,
  Mapping,
  MappingSubstitution,
  RepairStrategy,
  SAMPLE_TEMPLATES_C8Y,
  SnoopStatus,
  StepperConfiguration,
  getExternalTemplate,
  getSchema,
  whatIsIt
} from '../../shared';
import { JsonEditorComponent } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { ValidationError } from '../shared/mapping.model';
import { EditorMode } from '../shared/stepper-model';
import {
  countDeviceIdentifiers,
  definesDeviceIdentifier,
  expandC8YTemplate,
  expandExternalTemplate,
  isDisabled,
  reduceSourceTemplate,
  reduceTargetTemplate,
  splitTopicExcludingSeparator
} from '../shared/util';
import { EditSubstitutionComponent } from '../substitution/edit/edit-substitution-modal.component';
import { SubstitutionRendererComponent } from '../substitution/substitution-grid.component';

@Component({
  selector: 'd11r-mapping-stepper',
  templateUrl: 'mapping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<Mapping>();
  private _deploymentMapEntry: DeploymentMapEntry;

  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
  }

  ValidationError = ValidationError;
  Direction = Direction;
  COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;
  isDisabled = isDisabled;

  editorTestingPayloadTemplateEmitter = new EventEmitter<any>();
  updateSourceEditor: EventEmitter<any> = new EventEmitter<any>();
  updateTargetEditor: EventEmitter<any> = new EventEmitter<any>();

  templateForm: FormGroup;
  templateModel: any = {};
  substitutionFormly: FormGroup = new FormGroup({});
  substitutionFormlyFields: FormlyFieldConfig[];
  substitutionModel: any = {};
  propertyFormly: FormGroup = new FormGroup({});

  sourceTemplate: any;
  targetTemplate: any;
  sourceSystem: string;
  targetSystem: string;

  countDeviceIdentifiers$: BehaviorSubject<number> =
    new BehaviorSubject<number>(0);
  isSubstitutionValid$: BehaviorSubject<boolean> =
    new BehaviorSubject<boolean>(false);
  labels: any = {
    next: 'Next',
    cancel: 'Cancel'
  };

  editorOptionsSourceTemplate = {
    mode: Mode.tree,
    removeModes: ['table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: false,
    name: 'message'
  };

  editorOptionsTargetTemplate = {
    mode: Mode.tree,
    removeModes: ['table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: true
  };

  editorOptionsSourceSubstitution = {
    mode: Mode.tree,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true,
    name: 'message'
  };

  editorOptionsTargetSubstitution = {
    mode: Mode.tree,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    readOnly: true,
    statusBar: true
  };

  selectedSubstitution: number = -1;

  snoopedTemplateCounter: number = -1;
  step: any;
  expertMode: boolean = false;
  templatesInitialized: boolean = false;
  extensions: Map<string, Extension> = new Map();
  extensionEvents$: BehaviorSubject<ExtensionEntry[]> = new BehaviorSubject([]);
  onDestroy$ = new Subject<void>();
  supportsMessageContext: boolean;
  isButtonDisabled$: BehaviorSubject<boolean> = new BehaviorSubject(true);

  sourceCustomMessage$: Subject<string> = new BehaviorSubject(undefined);
  targetCustomMessage$: Subject<string> = new BehaviorSubject(undefined);

  @ViewChild('editorSourceStepTemplate', { static: false })
  editorSourceStepTemplate: JsonEditorComponent;
  @ViewChild('editorTargetStepTemplate', { static: false })
  editorTargetStepTemplate: JsonEditorComponent;

  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild: SubstitutionRendererComponent;

  @ViewChild('stepper', { static: false })
  stepper: C8yStepper;

  currentStepIndex: number;

  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public extensionService: ExtensionService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) { }

  ngOnInit() {
    // console.log('mapping-stepper', this._deploymentMapEntry, this.deploymentMapEntry);
    if (
      this.mapping.snoopStatus === SnoopStatus.NONE ||
      this.mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.labels = {
        ...this.labels,
        custom: 'Start snooping'
      };
    }

    this.targetSystem =
      this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    this.templateModel = {
      stepperConfiguration: this.stepperConfiguration,
      mapping: this.mapping
    };

    this.substitutionModel = {
      stepperConfiguration: this.stepperConfiguration,
      pathSource: '',
      pathTarget: '',
      repairStrategy: RepairStrategy.DEFAULT,
      resolve2ExternalId: false,
      expandArray: false,
      targetExpression: {
        result: '',
        resultType: 'empty',
        valid: false,
      },
      sourceExpression: {
        result: '',
        resultType: 'empty',
        valid: false,
      }
    };

    this.substitutionFormlyFields = [
      {
        fieldGroup: [
          {
            className: 'col-lg-5 col-lg-offset-1',
            key: 'pathSource',
            type: 'input-custom',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Source Expression',
              class: 'input-sm',
              customWrapperClass: 'm-b-24',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              placeholder: '$join([$substring(txt,5), id]) or $number(id)/10',
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
              required: true,
              customMessage: this.sourceCustomMessage$
            },
            expressionProperties: {
              'templateOptions.class': (model) => {
                if (
                  model.pathSource == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return 'input-sm';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe(path => {
                  this.updateSourceExpressionResult(path);
                });
              }
            }
          },
          {
            className: 'col-lg-5',
            key: 'pathTarget',
            type: 'input-custom',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Target Expression',
              customWrapperClass: 'm-b-24',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions,
              description: `Use the same <a href="https://jsonata.org" target="_blank">JSONata</a>
              expressions as for the source template. In addition you can use <code>$</code> to merge the 
              result of the source expression with the existing target template. Special care is 
              required since this can overwrite mandatory Cumulocity attributes, e.g. <code>source.id</code>.  This can result in API calls that are rejected by the Cumulocity backend!`,
              required: true,
              customMessage: this.targetCustomMessage$
            },
            expressionProperties: {
              'templateOptions.class': (model) => {
                // console.log("Logging class:", t)
                if (
                  model.pathTarget == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return 'input-sm';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe(path => {
                  this.updateTargetExpressionResult(path);
                });
              }
            }
          }
        ]
      }
    ];

    this.setTemplateForm();
    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
    this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);
  }

  private setTemplateForm(): void {
    this.templateForm = new FormGroup({
      extensionName: new FormControl({
        value: this.mapping?.extension?.extensionName,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
      }),
      eventName: new FormControl({
        value: this.mapping?.extension?.eventName,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
      }),
      snoopedTemplateIndex: new FormControl({
        value: -1,
        disabled:
          !this.stepperConfiguration.showEditorSource ||
          this.mapping.snoopedTemplates.length === 0 ||
          this.stepperConfiguration.editorMode ===
          EditorMode.READ_ONLY

      }),
      sampleTargetTemplatesButton: new FormControl(
        {
          value: !this.stepperConfiguration.showEditorSource ||
            this.stepperConfiguration.editorMode ===
            EditorMode.READ_ONLY,
          disabled: undefined
        }
      )
    });
    this.isSubstitutionValid$.subscribe(valid => {
      if (valid) {
        this.templateForm.setErrors(null);
      } else {
        this.templateForm.setErrors({ 'incorrect': true });
      }
    })
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  deploymentMapEntryChange(e) {
    this.isButtonDisabled$.next(
      !this._deploymentMapEntry?.connectors ||
      this._deploymentMapEntry?.connectors?.length == 0
    );

    setTimeout(() => {
      this.supportsMessageContext =
        this._deploymentMapEntry.connectorsDetailed?.some(
          (con) => con.connectorType == ConnectorType.KAFKA
        );
    });
  }

  onEditorSourceInitialized() {
    this.updateSourceEditor.emit(
      { schema: getSchema(this.mapping.targetAPI, this.mapping.direction, false, false), identifier: API[this.mapping.targetAPI].identifier }
    );
  }

  onEditorTargetInitialized() {
    this.updateTargetEditor.emit(
      { schema: getSchema(this.mapping.targetAPI, this.mapping.direction, true, false), identifier: API[this.mapping.targetAPI].identifier }
    );
  }

  async updateSourceExpressionResult(path) {
    this.sourceCustomMessage$.next(undefined);
    try {
      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorSourceStepTemplate?.get(),
        path
      );
      this.substitutionModel.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
        valid: true
      };
      if (this.expertMode) {
        this.substitutionFormly.get('pathSource').setErrors(null);
        if (
          this.substitutionModel.sourceExpression.resultType == 'Array' &&
          !this.substitutionModel.expandArray
        ) {
          const txt =
            'Current expression extracts an array. Consider to use the option "Expand as array" if you want to create multiple measurements, alarms, events or devices, i.e. "multi-device" or "multi-value"';
          this.alertService.info(txt);
          // this.sourceCustomMessage$.next(txt);
        }
      }
    } catch (error) {
      this.substitutionModel.sourceExpression.valid = false;
      this.substitutionFormly
        .get('pathSource')
        .setErrors({ validationError: { message: error.message } });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }


  async updateTargetExpressionResult(path) {
    try {
      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorTargetStepTemplate?.get(),
        path
      );
      this.substitutionModel.targetExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
        valid: true
      };

      const definesDI = definesDeviceIdentifier(
        this.mapping.targetAPI,
        this.mapping.externalIdType,
        this.mapping.direction,
        this.substitutionModel
      );
      if (this.expertMode) {
        this.substitutionFormly.get('pathTarget').setErrors(null);
        if (definesDI && this.mapping.mapDeviceIdentifier) {
          const txt = `${API[this.mapping.targetAPI].identifier
            } is resolved using the external Id ${this.mapping.externalIdType
            } defined in the previous step.`;
          this.targetCustomMessage$.next(txt);
        } else if (path == '$') {
          const txt = `By specifying "$" you selected the root of the target 
          template and this result in merging the source expression with the target template.`;
          this.targetCustomMessage$.next(txt);
        }
      }
    } catch (error) {
      this.substitutionModel.targetExpression.valid = false;
      this.substitutionFormly
        .get('pathTarget')
        .setErrors({ validationError: { message: error.message } });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  isSubstitutionValid() {
    const r1 =
      this.substitutionModel.sourceExpression?.valid;
    const r2 =
      this.substitutionModel.targetExpression?.valid;
    const r3 = this.substitutionModel.pathSource != '';
    const r4 = this.substitutionModel.pathTarget != '';
    const result = r1 && r2 && r3 && r4;
    return result;
  }

  onSelectedPathSourceChanged(path: string) {
    if (this.expertMode) {
      this.substitutionFormly.get('pathSource').setValue(path);
      this.substitutionModel.pathSource = path;
    } else {
      this.substitutionModel.pathSource = path;
      this.updateSourceExpressionResult(path);
    }
  }

  onSelectedPathTargetChanged(path: string) {
    if (this.expertMode) {
      this.substitutionFormly.get('pathTarget').setValue(path);
      this.substitutionModel.pathTarget = path;
    } else {
      this.substitutionModel.pathTarget = path;
      this.updateTargetExpressionResult(path);
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onSourceTemplateChanged(content: Content) {
    if (_.has(content, 'text') && content['text']) {
      this.mapping.sourceTemplate = reduceSourceTemplate(JSON.parse(content['text']), false);
    } else {
      this.mapping.sourceTemplate = reduceSourceTemplate(content['json'], false);
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onTargetTemplateChanged(content: Content) {
    if (_.has(content, 'text') && content['text']) {
      this.mapping.targetTemplate = reduceSourceTemplate(JSON.parse(content['text']), false);
    } else {
      this.mapping.targetTemplate = reduceSourceTemplate(content['json'], false);
    }
  }

  async onCommitButton() {
    this.commit.emit(this.mapping);
  }

  async onSampleTargetTemplatesButton() {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.targetTemplate = expandC8YTemplate(
        JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
        this.mapping
      );
    } else {
      const levels: string[] = splitTopicExcludingSeparator(
        this.mapping.mappingTopicSample
      );
      this.targetTemplate = expandExternalTemplate(
        JSON.parse(getExternalTemplate(this.mapping)),
        this.mapping,
        levels
      );
    }
    this.editorTargetStepTemplate.set(this.targetTemplate);
  }

  async onCancelButton() {
    this.cancel.emit();
  }

  onSelectExtensionName(extensionName) {
    this.mapping.extension.extensionName = extensionName;
    this.extensionEvents$.next(
      Object.values(this.extensions[extensionName].extensionEntries as Map<string, ExtensionEntry>).filter(entry => entry.extensionType == this.mapping.extension.extensionType)
    );
    console.log("Selected events", Object.values(this.extensions[extensionName].extensionEntries))
  }

  onSelectExtensionEvent(extensionEvent) {
    this.mapping.extension.eventName = extensionEvent;
  }

  async onStepChange(index: number) {
    // console.log("StepChange:", index);
    // this.step == 'Add and select connector'
    this.currentStepIndex = index;
    if (index == 1) {
      this.templateModel.mapping = this.mapping;

      this.extensions =
        (await this.extensionService.getProcessorExtensions()) as Map<string, Extension>;
      if (this.mapping?.extension?.extensionName) {
        if (!this.extensions[this.mapping.extension.extensionName]) {
          const msg = `The extension ${this.mapping.extension.extensionName} with event ${this.mapping.extension.eventName} is not loaded. Please load the extension or choose a different one.`;
          this.alertService.warning(msg);
        } else {
          this.extensionEvents$.next(
            Object.values(
              this.extensions[this.mapping.extension.extensionName].extensionEntries
            )
          );
          console.log("Selected events", Object.values(this.extensions[this.mapping.extension.extensionName].extensionEntries), this.mapping, this.extensions)

        }
      }
    } else if (index == 2) {
      // this.step == 'Select templates'
      // console.log("Step index 1 - before", this.targetTemplate);
      this.expandTemplates();
      // console.log("Step index 1 - afer", this.targetTemplate);
    } else if (index == 3) {
      this.editorTestingPayloadTemplateEmitter.emit({ mapping: this.mapping, sourceTemplate: this.sourceTemplate, targetTemplate: this.targetTemplate });
      this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);
      this.onSelectSubstitution(0);
      // this.step == 'Select templates'
    } else if (index == 4) {
      this.sourceTemplate = this.editorSourceStepTemplate?.get();
      this.targetTemplate = this.editorTargetStepTemplate?.get();
    }

  }

  onNextStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): void {
    if (this.stepperConfiguration.advanceFromStepToEndStep && this.stepperConfiguration.advanceFromStepToEndStep == this.currentStepIndex) {
      this.goToLastStep();
      this.alertService.info('The other steps have been skipped for this mapping type!');
    } else {
      event.stepper.next();
    }
  }

  private goToLastStep() {
    // Mark all previous steps as completed
    this.stepper.steps.forEach((step, index) => {
      if (index < this.stepper.steps.length - 1) {
        step.completed = true;
      }
    });
    // Select the last step
    this.stepper.selectedIndex = this.stepper.steps.length - 1;
  }

  async onBackStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    this.step = event.step.label;
    if (this.step == 'Test mapping') {
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector('#editorTestingRequest');
      if (editorTestingRequestRef != null) {
        editorTestingRequestRef.setAttribute('schema', undefined);
      }
    } else if (this.step == 'Select templates') {
      this.templatesInitialized = false;
    }
    event.stepper.previous();
  }

  private expandTemplates() {
    const levels: string[] = splitTopicExcludingSeparator(
      this.mapping.direction == Direction.INBOUND
        ? this.mapping.mappingTopicSample
        : this.mapping.publishTopicSample
    );

    if (
      this.stepperConfiguration.editorMode == EditorMode.CREATE &&
      this.templatesInitialized == false
    ) {
      this.templatesInitialized = true;
      if (this.stepperConfiguration.direction == Direction.INBOUND) {
        this.sourceTemplate = expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          this.mapping,
          levels
        );
        this.targetTemplate = expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
          this.mapping
        );
      } else {
        this.sourceTemplate = expandC8YTemplate(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
          this.mapping
        );
        this.targetTemplate = expandExternalTemplate(
          JSON.parse(getExternalTemplate(this.mapping)),
          this.mapping,
          levels
        );
      }
    } else {
      if (this.stepperConfiguration.direction == Direction.INBOUND) {
        this.sourceTemplate = expandExternalTemplate(
          JSON.parse(this.mapping.sourceTemplate),
          this.mapping,
          levels
        );
        this.targetTemplate = expandC8YTemplate(
          JSON.parse(this.mapping.targetTemplate),
          this.mapping
        );
      } else {
        this.sourceTemplate = expandC8YTemplate(
          JSON.parse(this.mapping.sourceTemplate),
          this.mapping
        );
        this.targetTemplate = expandExternalTemplate(
          JSON.parse(this.mapping.targetTemplate),
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
      this.sourceTemplate = JSON.parse(
        this.mapping.snoopedTemplates[this.snoopedTemplateCounter]
      );
    } catch (error) {
      this.sourceTemplate = {
        message: this.mapping.snoopedTemplates[this.snoopedTemplateCounter]
      };
      console.warn(
        'The payload was not in JSON format, now wrap it:',
        this.sourceTemplate
      );
    }
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.sourceTemplate = expandExternalTemplate(
        this.sourceTemplate,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample)
      );
    } else {
      this.sourceTemplate = expandC8YTemplate(
        this.sourceTemplate,
        this.mapping
      );
    }
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onSelectSnoopedSourceTemplate(index: any) {
    try {
      this.sourceTemplate = JSON.parse(this.mapping.snoopedTemplates[index]);
    } catch (error) {
      this.sourceTemplate = {
        message: this.mapping.snoopedTemplates[index]
      };
      console.warn(
        'The payload was not in JSON format, now wrap it:',
        this.sourceTemplate
      );
    }
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.sourceTemplate = expandExternalTemplate(
        this.sourceTemplate,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample)
      );
    } else {
      this.sourceTemplate = expandC8YTemplate(
        this.sourceTemplate,
        this.mapping
      );
    }
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
  }

  async onTargetAPIChanged(changedTargetAPI) {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.mapping.targetTemplate = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.sourceTemplate = getExternalTemplate(this.mapping);
      this.updateTargetEditor.emit(
        getSchema(this.mapping.targetAPI, this.mapping.direction, true, false)
      );
    } else {
      this.mapping.sourceTemplate = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.targetTemplate = getExternalTemplate(this.mapping);
      this.updateSourceEditor.emit(
        getSchema(this.mapping.targetAPI, this.mapping.direction, false, false)
      );
    }
  }

  async updateTestResult(result) {
    this.mapping.tested = result;
  }

  onAddSubstitution() {
    if (this.isSubstitutionValid()) {
      this.substitutionModel.expandArray = false;
      this.substitutionModel.repairStrategy = RepairStrategy.DEFAULT;
      this.substitutionModel.resolve2ExternalId = false;
      this.addSubstitution(this.substitutionModel);
      this.selectedSubstitution = -1;
    } else {
      this.alertService.warning(
        'Please select two nodes: one node in the template source, one node in the template target to define a substitution.'
      );
    }
  }

  onDeleteSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(selected, 1);
    }
    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
    this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);

  }

  toggleExpertMode() {
    this.expertMode = !this.expertMode;
  }

  onUpdateSubstitution() {
    if (this.selectedSubstitution != -1) {
      const selected = this.selectedSubstitution;
      const initialState = {
        substitution: _.clone(this.mapping.substitutions[selected]),
        mapping: this.mapping,
        stepperConfiguration: this.stepperConfiguration,
        isUpdate: true
      };
      if (
        this.substitutionModel.sourceExpression.valid &&
        this.substitutionModel.targetExpression.valid
      ) {
        initialState.substitution.pathSource =
          this.substitutionModel.pathSource;
        initialState.substitution.pathTarget =
          this.substitutionModel.pathTarget;
      }
      const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
        initialState
      });
      modalRef.content.closeSubject.subscribe((editedSub) => {
        if (editedSub) {
          this.mapping.substitutions[selected] = editedSub;
          this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
          this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);
          //this.substitutionModel = editedSub; not needed
        }
      });


      // console.log("Updated subs I:", this.mapping.substitutions);
    }
  }

  private addSubstitution(ns: MappingSubstitution) {
    const sub: MappingSubstitution = _.clone(ns);
    let duplicateSubstitutionIndex = -1;
    let duplicate;
    this.mapping.substitutions.forEach((s, index) => {
      if (sub.pathTarget == s.pathTarget) {
        duplicateSubstitutionIndex = index;
        duplicate = this.mapping.substitutions[index];
      }
    });
    const isDuplicate = duplicateSubstitutionIndex != -1;
    const initialState = {
      isDuplicate,
      duplicate,
      duplicateSubstitutionIndex,
      substitution: sub,
      mapping: this.mapping,
      stepperConfiguration: this.stepperConfiguration
    };
    if (this.expertMode || isDuplicate) {
      const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
        initialState
      });

      modalRef.content.closeSubject.subscribe((newSub: MappingSubstitution) => {
        if (newSub && !isDuplicate) {
          this.mapping.substitutions.push(newSub);
        } else if (newSub && isDuplicate) {
          this.mapping.substitutions[duplicateSubstitutionIndex] = newSub;
        }
        this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
        this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);
      });
    } else {
      this.mapping.substitutions.push(sub);
      this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
      this.isSubstitutionValid$.next(countDeviceIdentifiers(this.mapping) == 1 || this.stepperConfiguration.allowNoDefinedIdentifier);
    }
  }

  async onSelectSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length && selected > -1) {
      this.selectedSubstitution = selected;
      this.substitutionModel = _.clone(this.mapping.substitutions[selected]);
      this.substitutionModel.stepperConfiguration = this.stepperConfiguration;
      await this.editorSourceStepTemplate?.setSelectionToPath(
        this.substitutionModel.pathSource
      );
      await this.editorTargetStepTemplate.setSelectionToPath(
        this.substitutionModel.pathTarget
      );
    }
    // console.log("Updated subs II:", this.mapping.substitutions);
  }

  ngOnDestroy() {
    this.countDeviceIdentifiers$.complete();
    this.isSubstitutionValid$.complete();
    this.extensionEvents$.complete();
    this.onDestroy$.complete();
  }
}
