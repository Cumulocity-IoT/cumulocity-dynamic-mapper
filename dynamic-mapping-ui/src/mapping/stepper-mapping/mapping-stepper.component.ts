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
import { Content } from 'vanilla-jsoneditor';
import { ExtensionService } from '../../extension';
import {
  API,
  COLOR_HIGHLIGHTED,
  DeploymentMapEntry,
  Direction,
  Extension,
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
import { JsonEditor2Component } from '../../shared/editor2/jsoneditor2.component';
import { MappingService } from '../core/mapping.service';
import { EditSubstitutionComponent } from '../substitution/edit/edit-substitution-modal.component';
import { C8YRequest } from '../processor/processor.model';
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
  isButtonDisabled$: BehaviorSubject<boolean> = new BehaviorSubject(true);

  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
    // console.log('New setDeploymentMap', this._deploymentMapEntry);
  }

  ValidationError = ValidationError;
  Direction = Direction;
  COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;
  isDisabled = isDisabled;

  substitutionFormly: FormGroup = new FormGroup({});
  templateForm: FormGroup;
  substitutionFormlyFields: FormlyFieldConfig[];
  editorTestingPayloadTemplateEmitter = new EventEmitter<any>();
  schemaUpdateSource: EventEmitter<string> = new EventEmitter<any>();
  schemaUpdateTarget: EventEmitter<string> = new EventEmitter<any>();

  templateModel: any = {};
  substitutionModel: any = {};
  templateSource: any;
  pathSource$: Subject<string> = new BehaviorSubject<string>('');
  pathTarget$: Subject<string> = new BehaviorSubject<string>('');
  templateTarget: any;

  testingModel: {
    results: C8YRequest[];
    errorMsg?: string;
    request?: any;
    response?: any;
    selectedResult: number;
  } = {
    results: [],
    selectedResult: -1
  };

  countDeviceIdentifiers$: BehaviorSubject<number> =
    new BehaviorSubject<number>(0);
  labels: any = {
    next: 'Next',
    cancel: 'Cancel'
  };
  propertyFormly: FormGroup = new FormGroup({});
  sourceSystem: string;
  targetSystem: string;

  editorOptionsSourceStep3: any = {};
  editorOptionsSourceStep4: any = {};
  editorOptionsTargetStep3: any = {};
  editorOptionsTargetStep4: any = {};
  editorOptionsTesting: any = {};

  selectedSubstitution: number = -1;

  snoopedTemplateCounter: number = -1;
  step: any;
  expertMode: boolean = false;
  templatesInitialized: boolean = false;

  @ViewChild('editorSourceStep4', { static: false })
  editorSourceStep4: JsonEditor2Component;
  @ViewChild('editorTargetStep4', { static: false })
  editorTargetStep4: JsonEditor2Component;
  @ViewChild('editorSourceStep3', { static: false })
  editorSourceStep3: JsonEditor2Component;
  @ViewChild('editorTargetStep3', { static: false })
  editorTargetStep3: JsonEditor2Component;
  editorTestingResponse: JsonEditor2Component;
  @ViewChild(SubstitutionRendererComponent, { static: false })
  substitutionChild: SubstitutionRendererComponent;

  extensions: Map<string, Extension> = new Map();
  extensionEvents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  onDestroy$ = new Subject<void>();

  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public extensionService: ExtensionService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) {}

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  deploymentMapEntryChange(e) {
    // console.log(
    //   'New getDeploymentMap',
    //   this._deploymentMapEntry,
    //   !this._deploymentMapEntry?.connectors
    // );
    this.isButtonDisabled$.next(
      !this._deploymentMapEntry?.connectors ||
        this._deploymentMapEntry?.connectors?.length == 0
    );
    // console.log('New setDeploymentMap from grid', e);
  }

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
    // console.log('Formly to be updated:', this.configService);
    // set value for backward compatibility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
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
        msgTxt: '',
        severity: 'text-info'
      },
      sourceExpression: {
        result: '',
        resultType: 'empty',
        msgTxt: '',
        severity: 'text-info'
      }
    };
    // console.log(
    //  'Mapping to be updated:',
    //  this.mapping,
    //  this.stepperConfiguration
    // );

    this.substitutionFormlyFields = [
      {
        fieldGroup: [
          {
            className: 'col-lg-5 col-lg-offset-1 text-monospace',
            key: 'pathSource',
            type: 'input-custom',
            wrappers: ['custom-form-field'],
            templateOptions: {
              label: 'Source Expression',
              class: 'input-sm disabled-animate-background',
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
              required: true
            },
            expressionProperties: {
              'templateOptions.class': (model) => {
                if (
                  model.pathSource == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return 'input-sm disabled-animate-background';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe(() => {
                  this.updateSourceExpressionResult();
                });
              }
            }
          },
          {
            className: 'col-lg-5 text-monospace',
            key: 'pathTarget',
            type: 'input-custom',
            wrappers: ['custom-form-wrapper'],
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
              required: true
            },
            expressionProperties: {
              'templateOptions.class': (model) => {
                // console.log("Logging class:", t)
                if (
                  model.pathTarget == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions
                ) {
                  return 'input-sm disabled-animate-background';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe(() => {
                  this.updateTargetExpressionResult();
                });
              }
            }
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-5 reduced-top col-lg-offset-1 not-p-b-24',
            type: 'message-field',
            expressionProperties: {
              'templateOptions.content': (model) =>
                model.sourceExpression?.msgTxt,
              'templateOptions.textClass': (model) =>
                model.sourceExpression?.severity,
              'templateOptions.enabled': () => true
            }
          },
          {
            // message field target
            className: 'col-lg-5 reduced-top not-p-b-24',
            type: 'message-field',
            expressionProperties: {
              'templateOptions.content': (model) =>
                model.targetExpression?.msgTxt,
              'templateOptions.textClass': (model) =>
                model.targetExpression?.severity,
              'templateOptions.enabled': () => true
            }
          }
        ]
      }

      //   {
      //     fieldGroup: [
      //       {
      //         className: 'col-lg-5 col-lg-offset-1 text-monospace font-smaller',
      //         key: 'sourceExpression.result',
      //         type: 'textarea-custom',
      //         wrappers: ['custom-form-wrapper'],
      //         templateOptions: {
      //           class: 'no-resize',
      //           disabled: false,
      //           readonly: false,
      //           customWrapperClass: 'm-b-4'
      //         },
      //         expressionProperties: {
      //           'templateOptions.label': () =>
      //             `Source Result [${this.substitutionModel.sourceExpression?.resultType}]`,
      //           'templateOptions.value': () => {
      //             return `${this.substitutionModel.sourceExpression?.result}`;
      //           }
      //         }
      //       },
      //       {
      //         className: 'col-lg-5 text-monospace font-smaller',
      //         key: 'targetExpression.result',
      //         type: 'textarea-custom',
      //         wrappers: ['custom-form-wrapper'],
      //         templateOptions: {
      //           class: 'input',
      //           disabled: true,
      //           readonly: true,
      //           customWrapperClass: 'm-b-4'
      //         },
      //         expressionProperties: {
      //           'templateOptions.label': () =>
      //             `Target Result [${this.substitutionModel.targetExpression?.resultType}]`,
      //           'templateOptions.value': () => {
      //             return `${this.substitutionModel.targetExpression?.result}`;
      //           }
      //         }
      //       }
      //     ]
      //   }
    ];

    this.setTemplateForm();
    this.editorOptionsSourceStep3 = {
      ...this.editorOptionsSourceStep3,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: false,
      name: 'message'
    };

    this.editorOptionsTargetStep3 = {
      ...this.editorOptionsTargetStep3,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: true
    };

    this.editorOptionsSourceStep4 = {
      ...this.editorOptionsSourceStep4,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: true,
      name: 'message'
    };

    this.editorOptionsTargetStep4 = {
      ...this.editorOptionsTargetStep4,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      readOnly: true,
      statusBar: true
    };

    this.editorOptionsTesting = {
      ...this.editorOptionsTesting,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: true
    };

    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));

    // this.extensionEvents$.subscribe((events) => {
    //   console.log('New events from extension', events);
    // });
  }

  private setTemplateForm(): void {
    this.templateForm = new FormGroup({
      exName: new FormControl({
        value: this.mapping?.extension?.name,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
      }),
      exEvent: new FormControl({
        value: this.mapping?.extension?.event,
        disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
      })
    });
  }

  private getTemplateForm(): void {
    if (this.mapping.extension) {
      this.mapping.extension.name = this.templateForm.controls['exName'].value;
      this.mapping.extension.event =
        this.templateForm.controls['exEvent'].value;
    }
  }

  onSelectedPathSourceChanged(path: string) {
    if (this.expertMode)
      this.substitutionFormly.get('pathSource').setValue(path);
    this.substitutionModel.pathSource = path;
    this.pathSource$.next(path);
  }

  onEditorSourceInitialized() {
    this.schemaUpdateSource.emit(
      getSchema(this.mapping.targetAPI, this.mapping.direction, false)
    );
  }

  onEditorTargetInitialized() {
    this.schemaUpdateTarget.emit(
      getSchema(this.mapping.targetAPI, this.mapping.direction, true)
    );
  }

  async updateSourceExpressionResult() {
    try {
      this.substitutionModel.sourceExpression = {
        msgTxt: '',
        severity: 'text-info'
      };
      this.substitutionFormly.get('pathSource').setErrors(null);

      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorSourceStep4?.get(),
        this.substitutionFormly.get('pathSource').value
      );
      this.substitutionModel.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4)
      };

      if (
        this.substitutionModel.sourceExpression.resultType == 'Array' &&
        !this.substitutionModel.expandArray
      ) {
        this.substitutionModel.sourceExpression.msgTxt =
          'Current expression extracts an array. Consider to use the option "Expand as array" if you want to create multiple measurements, alarms, events or devices, i.e. "multi-device" or "multi-value"';
        this.substitutionModel.sourceExpression.severity = 'text-warning';
      }
    } catch (error) {
      // console.log('Error evaluating source expression: ', error);
      this.substitutionModel.sourceExpression = {
        msgTxt: error.message,
        severity: 'text-danger'
      };
      this.substitutionFormly
        .get('pathSource')
        .setErrors({ error: error.message });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  isSubstitutionValid() {
    const r1 =
      this.substitutionModel.sourceExpression?.severity != 'text-danger';
    const r2 =
      this.substitutionModel.targetExpression?.severity != 'text-danger';
    const r3 = this.substitutionModel.pathSource != '';
    const r4 = this.substitutionModel.pathTarget != '';
    const result = r1 && r2 && r3 && r4;
    return result;
  }

  onSelectedPathTargetChanged(path: string) {
    if (this.expertMode)
      this.substitutionFormly.get('pathTarget').setValue(path);
    this.substitutionModel.pathTarget = path;
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onTemplateSourceChanged(content: Content) {
    // if (_.has(content, 'text') && content['text']) {
    //   this.templateSource = JSON.parse(content['text']);
    //   // this.mapping.source = content['text'];‚
    // } else {
    //   this.templateSource = content['json'];
    //   // this.mapping.source = JSON.stringify(content['json']);
    // }
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onTemplateTargetChanged(content: Content) {
    // if (_.has(content, 'text') && content['text']) {
    //   this.templateTarget = JSON.parse(content['text']);
    //   // this.mapping.target = content['text'];
    // } else {
    //   this.templateTarget = content['json'];
    //   // this.mapping.target = JSON.stringify(content['json']);
    // }
  }

  async updateTargetExpressionResult() {
    const path = this.substitutionFormly.get('pathTarget').value;
    try {
      this.substitutionModel.targetExpression = {
        msgTxt: '',
        severity: 'text-info'
      };
      this.substitutionFormly.get('pathTarget').setErrors(null);
      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorTargetStep4?.get(),
        path
      );
      this.substitutionModel.targetExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4)
      };

      const definesDI = definesDeviceIdentifier(
        this.mapping.targetAPI,
        this.substitutionModel,
        this.mapping.direction
      );
      if (definesDI && this.mapping.mapDeviceIdentifier) {
        this.substitutionModel.targetExpression.msgTxt = `${
          API[this.mapping.targetAPI].identifier
        } is resolved using the external Id ${
          this.mapping.externalIdType
        } defined in the previous step.`;
        this.substitutionModel.targetExpression.severity = 'text-info';
      } else if (path == '$') {
        this.substitutionModel.targetExpression.msgTxt = `By specifying "$" you selected the root of the target 
        template and this result in merging the source expression with the target template.`;
        this.substitutionModel.targetExpression.severity = 'text-warning';
      }
    } catch (error) {
      console.error('Error evaluating target expression: ', error);
      this.substitutionModel.targetExpression = {
        msgTxt: error.message,
        severity: 'text-danger'
      };
      this.substitutionFormly
        .get('pathTarget')
        .setErrors({ error: error.message });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  getCurrentMapping(patched: boolean): Mapping {
    return {
      ...this.mapping,
      source: reduceSourceTemplate(
        this.editorSourceStep4 ? this.editorSourceStep4?.get() : {},
        patched
      ), // remove array "_TOPIC_LEVEL_" since it should not be stored
      target: reduceTargetTemplate(this.editorTargetStep4?.get(), patched), // remove patched attributes, since it should not be stored
      lastUpdate: Date.now()
    };
  }

  async onCommitButton() {
    this.commit.emit(this.getCurrentMapping(false));
  }

  async onSampleTargetTemplatesButton() {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateTarget = expandC8YTemplate(
        JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]),
        this.mapping
      );
    } else {
      const levels: string[] = splitTopicExcludingSeparator(
        this.mapping.mappingTopicSample
      );
      this.templateTarget = expandExternalTemplate(
        JSON.parse(getExternalTemplate(this.mapping)),
        this.mapping,
        levels
      );
    }
    this.editorTargetStep4.set(this.templateTarget);
  }

  async onCancelButton() {
    this.cancel.emit();
  }

  onSelectExtension(extension) {
    // ('onSelectExtension', extension);
    this.mapping.extension.name = extension;
    this.extensionEvents$.next(
      Object.keys(this.extensions[extension].extensionEntries)
    );
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  async onStepChange(event): Promise<void> {
    // ('OnStepChange', event);
  }

  async onNextStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    // ('OnNextStep', event.step.label, this.mapping);
    this.step = event.step.label;
    if (this.step == 'Add and select connector') {
      if (
        this.deploymentMapEntry.connectors &&
        this.deploymentMapEntry.connectors.length == 0
      ) {
        // const initialState = {
        //   title: 'No connector selected',
        //   message:
        //     'To apply the mapping to messages you should select at least one connector, unless you want to change this later! Do you want to continue?',
        //   labels: {
        //     ok: 'Continue',
        //     cancel: 'Close'
        //   }
        // };
        // const confirmContinuingModalRef: BsModalRef = this.bsModalService.show(
        //   ConfirmationModalComponent,
        //   { initialState }
        // );
        // confirmContinuingModalRef.content.closeSubject.subscribe(
        //   async (confirmation: boolean) => {
        //     // console.log('Confirmation result:', confirmation);
        //     if (confirmation) {
        //       event.stepper.next();
        //     }
        //     confirmContinuingModalRef.hide();
        //   }
        // );
        // this.alertService.warning(
        //   'To apply the mapping to messages you have to select at least one connector. Go back, unless you only want to assign a connector later!'
        // );
      } else {
        event.stepper.next();
      }
    } else if (this.step == 'General settings') {
      this.templateModel.mapping = this.mapping;
      // console.log(
      //  'Populate jsonPath if wildcard:',
      //  isWildcardTopic(this.mapping.direction == Direction.INBOUND? this.mapping.subscriptionTopic :this.mapping.publishTopic ),
      //  this.mapping.substitutions.length
      // );
      //   console.log(
      //     'Templates from mapping:',
      //     this.mapping.target,
      //     this.mapping.source,
      //     this.mapping
      //   );
      this.expandTemplates();
      this.extensions =
        (await this.extensionService.getProcessorExtensions()) as any;
      if (this.mapping?.extension?.name) {
        if (!this.extensions[this.mapping.extension.name]) {
          const msg = `The extension ${this.mapping.extension.name} with event ${this.mapping.extension.event} is not loaded. Please load the extension or choose a different one.`;
          this.alertService.warning(msg);
        } else {
          this.extensionEvents$.next(
            Object.keys(
              this.extensions[this.mapping.extension.name].extensionEntries
            )
          );
        }
      }
      event.stepper.next();
    } else if (this.step == 'Define substitutions') {
      this.expandTemplates();
      this.getTemplateForm();
      //   const testSourceTemplate = this.editorSourceStep4
      //     ? this.editorSourceStep4.get()
      //     : {};
      // this.editorTestingPayloadTemplateEmitter.emit(testSourceTemplate);
      this.editorTestingPayloadTemplateEmitter.emit(this.templateSource);
      this.onSelectSubstitution(0);
      event.stepper.next();
    } else if (this.step == 'Select templates') {
      this.templateSource = this.editorSourceStep3.get();
      this.templateTarget = this.editorTargetStep3.get();
      console.log(
        'onNextStep before',
        event.step.label,
        this.mapping,
        this.editorSourceStep3.get(),
        this.getCurrentMapping(true),
        this.templateSource,
        this.templateTarget
      );
      event.stepper.next();
    } else {
      event.stepper.next();
    }
  }

  async onBackStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    // console.log(
    //   'onBackStep before',
    //   event.step.label,
    //   this.mapping,
    //   this.getCurrentMapping(false),
    //   this.getCurrentMapping(true),
    //   this.templateSource,
    //   this.templateTarget
    // );
    this.step = event.step.label;
    if (this.step == 'Test mapping') {
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector('#editorTestingRequest');
      if (editorTestingRequestRef != null) {
        editorTestingRequestRef.setAttribute('schema', undefined);
      }
    } else if (this.step == 'Select templates') {
      // this.mapping = this.getCurrentMapping(true);
      // this.expandTemplates();
    } else if (this.step == 'Define substitutions') {
      // this.mapping = this.getCurrentMapping(true);
      // this.expandTemplates();
    }

    this.mapping = this.getCurrentMapping(true);
    this.expandTemplates();
    // console.log(
    //   'onBackStep after',
    //   event.step.label,
    //   this.mapping,
    //   this.getCurrentMapping(false),
    //   this.getCurrentMapping(true),
    //   this.templateSource,
    //   this.templateTarget
    // );
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
      // console.log(
      //  'Sample template',
      //  this.templateTarget,
      //  getSchema(this.mapping.targetAPI, this.mapping.direction, true)
      // );
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
        message: this.mapping.snoopedTemplates[this.snoopedTemplateCounter]
      };
      console.warn(
        'The payload was not in JSON format, now wrap it:',
        this.templateSource
      );
    }
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateSource = expandExternalTemplate(
        this.templateSource,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample)
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

  async onSelectSnoopedSourceTemplate(index: any) {
    try {
      this.templateSource = JSON.parse(this.mapping.snoopedTemplates[index]);
    } catch (error) {
      this.templateSource = {
        message: this.mapping.snoopedTemplates[index]
      };
      console.warn(
        'The payload was not in JSON format, now wrap it:',
        this.templateSource
      );
    }
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.templateSource = expandExternalTemplate(
        this.templateSource,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample)
      );
    } else {
      this.templateSource = expandC8YTemplate(
        this.templateSource,
        this.mapping
      );
    }
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
  }

  async onTargetAPIChanged(changedTargetAPI) {
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.mapping.target = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.source = getExternalTemplate(this.mapping);
      this.schemaUpdateTarget.emit(
        getSchema(this.mapping.targetAPI, this.mapping.direction, true)
      );
    } else {
      this.mapping.source = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.target = getExternalTemplate(this.mapping);
      this.schemaUpdateSource.emit(
        getSchema(this.mapping.targetAPI, this.mapping.direction, false)
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
      // console.log(
      //  'New substitution',
      //  this.templateModel,
      //  this.mapping.substitutions
      // );
    } else {
      this.alertService.warning(
        'Please select two nodes: one node in the template source, one node in the template target to define a substitution.'
      );
    }
  }

  onDeleteSubstitution(selected: number) {
    // console.log('Delete selected substitution', selected);
    if (selected < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(selected, 1);
    }
    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
    // console.log('Deleted substitution', this.mapping.substitutions.length);
  }
  togglePowermode() {
    this.expertMode = !this.expertMode;
  }

  onUpdateSubstitution() {
    if (this.selectedSubstitution != -1) {
      const selected = this.selectedSubstitution;
      // console.log('Edit selected substitution', selected);
      const initialState = {
        substitution: _.clone(this.mapping.substitutions[selected]),
        mapping: this.mapping,
        stepperConfiguration: this.stepperConfiguration,
        isUpdate: true
      };
      if (
        this.substitutionModel.sourceExpression?.severity != 'text-danger' &&
        this.substitutionModel.targetExpression?.severity != 'text-danger'
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
        // console.log('Mapping after edit:', editedSub);
        if (editedSub) {
          this.mapping.substitutions[selected] = editedSub;
          this.substitutionModel = editedSub;
          this.updateSourceExpressionResult();
          this.updateTargetExpressionResult();
        }
      });
      this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
      // console.log('Edited substitution', this.mapping.substitutions.length);
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
      // modalRef.content.closeSubject.subscribe((result) => {
      //   console.log('results:', result);
      // });
      modalRef.content.closeSubject.subscribe((newSub: MappingSubstitution) => {
        // console.log('About to add new substitution:', newSub);
        if (newSub && !isDuplicate) {
          this.mapping.substitutions.push(newSub);
        } else if (newSub && isDuplicate) {
          this.mapping.substitutions[duplicateSubstitutionIndex] = newSub;
        }
      });
    } else {
      this.mapping.substitutions.push(sub);
    }
  }

  async onSelectSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length && selected > -1) {
      this.selectedSubstitution = selected;
      this.substitutionModel = _.clone(this.mapping.substitutions[selected]);
      this.substitutionModel.stepperConfiguration = this.stepperConfiguration;
      await this.editorSourceStep4?.setSelectionToPath(
        this.substitutionModel.pathSource
      );
      await this.editorTargetStep4.setSelectionToPath(
        this.substitutionModel.pathTarget
      );
    }
  }

  onTemplateChanged(templateTarget: any): void {
    this.editorTargetStep4.set(templateTarget);
  }

  ngOnDestroy() {
    this.countDeviceIdentifiers$.complete();
    this.extensionEvents$.complete();
    this.onDestroy$.complete();
  }
}
