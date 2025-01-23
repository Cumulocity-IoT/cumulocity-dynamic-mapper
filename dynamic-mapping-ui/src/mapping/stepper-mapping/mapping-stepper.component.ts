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
import { BehaviorSubject, filter, Subject, take } from 'rxjs';
import { Content, Mode } from 'vanilla-jsoneditor';
import { ExtensionService } from '../../extension';
import {
  API,
  COLOR_HIGHLIGHTED,
  ConnectorType,
  countDeviceIdentifiers,
  DeploymentMapEntry,
  Direction,
  Extension,
  ExtensionEntry,
  getExternalTemplate,
  getGenericDeviceIdentifier,
  getSchema,
  JsonEditorComponent,
  Mapping,
  MappingSubstitution,
  RepairStrategy,
  SAMPLE_TEMPLATES_C8Y,
  SnoopStatus,
  StepperConfiguration
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { ValidationError } from '../shared/mapping.model';
import { EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
import {
  expandC8YTemplate,
  expandExternalTemplate,
  isTypeOf,
  reduceSourceTemplate,
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
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<Mapping>();

  ValidationError = ValidationError;
  Direction = Direction;
  COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;

  updateTestingTemplate = new EventEmitter<any>();
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
  sourceTemplateUpdated: any;
  targetTemplateUpdated: any;
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
  @ViewChild('editorSourceStepSubstitution', { static: false })
  editorSourceStepSubstitution: JsonEditorComponent;
  @ViewChild('editorTargetStepSubstitution', { static: false })
  editorTargetStepSubstitution: JsonEditorComponent;

  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild: SubstitutionRendererComponent;

  @ViewChild('stepper', { static: false })
  stepper: C8yStepper;

  stepperForward: boolean = true;
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
  }

  ngOnDestroy() {
    this.countDeviceIdentifiers$.complete();
    this.isSubstitutionValid$.complete();
    this.extensionEvents$.complete();
    this.onDestroy$.complete();
  }

  private updateSubstitutionValid() {
    const ni = countDeviceIdentifiers(this.mapping);
    // console.log('Updated number identifiers', ni, (ni == 1 && this.mapping.direction == Direction.INBOUND) , ni >= 1 && this.mapping.direction == Direction.OUTBOUND, (ni == 1 && this.mapping.direction == Direction.INBOUND) ||
    // (ni >= 1 && this.mapping.direction == Direction.OUTBOUND) || this.stepperConfiguration.allowNoDefinedIdentifier);
    this.countDeviceIdentifiers$.next(ni);
    this.isSubstitutionValid$.next((ni == 1 && this.mapping.direction == Direction.INBOUND) ||
      (ni >= 1 && this.mapping.direction == Direction.OUTBOUND) || this.stepperConfiguration.allowNoDefinedIdentifier || this.currentStepIndex < 3);
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
      !this.deploymentMapEntry?.connectors ||
      this.deploymentMapEntry?.connectors?.length == 0
    );

    setTimeout(() => {
      this.supportsMessageContext =
        this.deploymentMapEntry.connectorsDetailed?.some(
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
        resultType: isTypeOf(r),
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
        resultType: isTypeOf(r),
        result: JSON.stringify(r, null, 4),
        valid: true
      };

      if (this.expertMode) {
        this.substitutionFormly.get('pathTarget').setErrors(null);
        // if (definesDI && this.mapping.useExternalId) {
        //   const txt = `${API[this.mapping.targetAPI].identifier
        //     } is resolved using the external Id ${this.mapping.externalIdType
        //     } defined in the previous step.`;
        //   this.targetCustomMessage$.next(txt);
        // } else 
        if (path == '$') {
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

  async onSelectedPathSourceChanged(path: string) {
    if (this.expertMode) {
      this.substitutionFormly.get('pathSource').setValue(path);
      this.substitutionModel.pathSource = path;
    } else {
      this.substitutionModel.pathSource = path;
      this.updateSourceExpressionResult(path);
    }
    if (path == API[this.mapping.targetAPI].identifier) {
      const gi = getGenericDeviceIdentifier(this.mapping);
      await this.editorSourceStepSubstitution.setSelectionToPath(
        getGenericDeviceIdentifier(this.mapping)
      );
      this.alertService.info(`Please use the selected node ${gi} to map the identity from the source`);
    }

  }

  async onSelectedPathTargetChanged(path: string) {
    if (this.expertMode) {
      this.substitutionFormly.get('pathTarget').setValue(path);
      this.substitutionModel.pathTarget = path;
    } else {
      this.substitutionModel.pathTarget = path;
      this.updateTargetExpressionResult(path);
    }
    if (path == API[this.mapping.targetAPI].identifier) {
      const gi = getGenericDeviceIdentifier(this.mapping);
      await this.editorTargetStepSubstitution.setSelectionToPath(
        gi
      );
      this.alertService.info(`Please use the selected node ${gi} to map the identity from the source`);
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onSourceTemplateChanged(content: Content) {
    let contentAsJson;
    if (_.has(content, 'text') && content['text']) {
      contentAsJson = JSON.parse(content['text']);
    } else {
      contentAsJson = content['json'];
    }
    this.sourceTemplateUpdated = contentAsJson;

    // console.log("Step onSourceTemplateChanged", this.mapping.sourceTemplate, this.mapping.targetTemplate);
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onTargetTemplateChanged(content: Content) {
    let contentAsJson;
    if (_.has(content, 'text') && content['text']) {
      contentAsJson = JSON.parse(content['text']);
    } else {
      contentAsJson = content['json'];
    }
    this.targetTemplateUpdated = contentAsJson;

    // console.log("Step onTargetTemplateChanged",this.mapping.sourceTemplate,  this.mapping.targetTemplate);
  }

  async onCommitButton() {
    this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
    this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);
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
        this.mapping.mappingTopicSample, false
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
    this.updateSubstitutionValid();
    if (index == STEP_GENERAL_SETTINGS) {
      this.templateModel.mapping = this.mapping;
      this.templatesInitialized = false;
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
    } else if (index == STEP_SELECT_TEMPLATES) {
      // this.step == 'Select templates'
      // console.log("Step index 1 - before", this.targetTemplate);
      if (this.stepperForward) {
        this.expandTemplates();
      }
      // console.log("Step index 1 - after", this.targetTemplate);
    } else if (index == STEP_DEFINE_SUBSTITUTIONS) {
      // console.log("Step 3: onStepChange targetTemplate ", this.mapping.targetTemplate);
      this.updateTemplatesInEditors();
      this.updateSubstitutionValid();
      this.onSelectSubstitution(0);
      const testMapping = _.clone(this.mapping);
      testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
      testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
      this.updateTestingTemplate.emit(testMapping);
      // this.step == 'Select templates'
    } else if (index == STEP_TEST_MAPPING) {
      // console.log("Step 4: onStepChange targetTemplate ", this.mapping.targetTemplate);
    }

  }

  private updateTemplatesInEditors() {
    this.sourceTemplate = this.sourceTemplateUpdated ? this.sourceTemplateUpdated : this.sourceTemplate;
    this.targetTemplate = this.targetTemplateUpdated ? this.targetTemplateUpdated : this.targetTemplate;
    this.editorSourceStepSubstitution?.set(this.sourceTemplate);
    this.editorTargetStepSubstitution?.set(this.targetTemplate);
  }

  onNextStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): void {
    this.stepperForward = true;
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
    this.updateTemplatesInEditors();
    this.stepper.selectedIndex = this.stepper.steps.length - 1;
  }

  async onBackStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    this.step = event.step.label;
    this.stepperForward = false;
    if (this.step == 'Test mapping') {
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector('#editorTestingRequest');
      if (editorTestingRequestRef != null) {
        editorTestingRequestRef.setAttribute('schema', undefined);
      }
    } else if (this.step == 'General sesstings') {
      this.templatesInitialized = false;
    } else if (this.step == 'Select templates') {
      this.templatesInitialized = false;
    }
    event.stepper.previous();
  }

  private expandTemplates() {
    const levels: string[] = splitTopicExcludingSeparator(
      this.mapping.direction == Direction.INBOUND
        ? this.mapping.mappingTopicSample
        : this.mapping.publishTopicSample, false
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
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
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
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
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
      const schemaTarget = getSchema(this.mapping.targetAPI, this.mapping.direction, true, false);
      this.updateTargetEditor.emit({ schema: schemaTarget });
    } else {
      this.mapping.sourceTemplate = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.targetTemplate = getExternalTemplate(this.mapping);
      const schemaSource = getSchema(this.mapping.targetAPI, this.mapping.direction, false, false);
      this.updateSourceEditor.emit({ schema: schemaSource });
    }
  }

  async updateTestResult(result) {
    this.mapping.tested = result;
  }

  onAddSubstitution() {
    if (this.isSubstitutionValid()) {
      this.substitutionModel.expandArray = false;
      this.substitutionModel.repairStrategy = RepairStrategy.DEFAULT;
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
    this.updateSubstitutionValid();
  }

  toggleExpertMode() {
    this.expertMode = !this.expertMode;
  }

  onUpdateSubstitution(): void {
    const { selectedSubstitution, mapping, stepperConfiguration, substitutionModel } = this;

    // Early return if no substitution is selected
    if (selectedSubstitution === -1) {
      return;
    }

    // Prepare initial state
    const initialState = {
      substitution: { ...mapping.substitutions[selectedSubstitution] },
      mapping,
      stepperConfiguration,
      isUpdate: true
    };

    // Update paths if expressions are valid
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    if (sourceExpression.valid && targetExpression.valid) {
      initialState.substitution = {
        ...initialState.substitution,
        pathSource,
        pathTarget
      };
    }

    // Show modal and handle response
    const modalRef = this.bsModalService.show(EditSubstitutionComponent, { initialState });

    modalRef.content.closeSubject
      .pipe(
        take(1), // Automatically unsubscribe after first emission
        filter(Boolean) // Only proceed if we have valid data
      )
      .subscribe({
        next: (editedSubstitution: MappingSubstitution) => {
          try {
            mapping.substitutions[selectedSubstitution] = editedSubstitution;
            this.updateSubstitutionValid();
          } catch (error) {
            console.log('Failed to update substitution', error);
          }
        },
        error: (error) => console.log('Error in modal operation', error)
      });
  }

  private addSubstitution(newSubstitution: MappingSubstitution): void {
    const substitution = { ...newSubstitution };
    const { mapping, stepperConfiguration, expertMode } = this;

    // Find duplicate substitution
    const duplicateIndex = mapping.substitutions.findIndex(
      sub => sub.pathTarget === substitution.pathTarget
    );

    const isDuplicate = duplicateIndex !== -1;
    const duplicate = isDuplicate ? mapping.substitutions[duplicateIndex] : undefined;

    const initialState = {
      isDuplicate,
      duplicate,
      duplicateSubstitutionIndex: duplicateIndex,
      substitution,
      mapping,
      stepperConfiguration
    };

    // Handle simple case first (non-expert mode, no duplicates)
    if (!expertMode && !isDuplicate) {
      mapping.substitutions.push(substitution);
      this.updateSubstitutionValid();
      return;
    }

    // Handle expert mode or duplicates
    const modalRef = this.bsModalService.show(EditSubstitutionComponent, {
      initialState
    });

    modalRef.content.closeSubject
      .pipe(
        take(1) // Automatically unsubscribe after first emission
      )
      .subscribe((updatedSubstitution: MappingSubstitution) => {
        if (!updatedSubstitution) return;

        if (isDuplicate) {
          mapping.substitutions[duplicateIndex] = updatedSubstitution;
        } else {
          mapping.substitutions.push(updatedSubstitution);
        }

        this.updateSubstitutionValid();
      });
  }

  async onSelectSubstitution(selected: number) {
    const { mapping, stepperConfiguration } = this;
    const { substitutions } = mapping;

    // Early return if selection is out of bounds
    if (selected < 0 || selected >= substitutions.length) {
      return;
    }

    this.selectedSubstitution = selected;

    // Create substitution model
    this.substitutionModel = {
      ...substitutions[selected],
      stepperConfiguration
    };

    // Parallel execution of path selections
    await Promise.all([
      this.editorSourceStepSubstitution.setSelectionToPath(
        this.substitutionModel.pathSource
      ),
      this.editorTargetStepSubstitution.setSelectionToPath(
        this.substitutionModel.pathTarget
      )
    ]);
  }

  addSubstitutionDisabled(): boolean {
    return !this.stepperConfiguration.showEditorSource ||
      this.stepperConfiguration.editorMode ===
      EditorMode.READ_ONLY ||
      !this.isSubstitutionValid()
  }


  updateSubstitutionDisabled(): boolean {
    return  !this.stepperConfiguration.showEditorSource ||
    this.stepperConfiguration.editorMode ===
      EditorMode.READ_ONLY ||
    this.selectedSubstitution === -1 ||
    !this.isSubstitutionValid()
  }

}
