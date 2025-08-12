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
  ChangeDetectorRef,
  Component,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { EditorComponent, loadMonacoEditor } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, C8yStepper, gettext } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import * as _ from 'lodash';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, debounceTime, distinctUntilChanged, filter, from, map, Subject, take, takeUntil } from 'rxjs';
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
  Substitution,
  RepairStrategy,
  SAMPLE_TEMPLATES_C8Y,
  SharedService,
  SnoopStatus,
  StepperConfiguration,
  createCustomUuid,
  MappingType,
  Feature
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { ValidationError } from '../shared/mapping.model';
import { createCompletionProvider, EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
import {
  base64ToString,
  expandC8YTemplate,
  expandExternalTemplate,
  getTypeOf,
  isExpression,
  reduceSourceTemplate,
  splitTopicExcludingSeparator,
  stringToBase64
} from '../shared/util';
import { EditSubstitutionComponent } from '../substitution/edit/edit-substitution-modal.component';
import { SubstitutionRendererComponent } from '../substitution/substitution-grid.component';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType } from '../../configuration/shared/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { AIPromptComponent } from '../prompt/ai-prompt.component';
import { AIAgentService } from '../core/ai-agent.service';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { MappingStepTestingComponent } from '../step-testing/mapping-testing.component';

let initializedMonaco = false;

interface StepperStepChange {
  stepper: C8yStepper;
  step: CdkStep;
}

@Component({
  selector: 'd11r-mapping-stepper',
  templateUrl: 'mapping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<Mapping>();

  @ViewChild('editorSourceStepTemplate', { static: false }) editorSourceStepTemplate!: JsonEditorComponent;
  @ViewChild('editorTargetStepTemplate', { static: false }) editorTargetStepTemplate!: JsonEditorComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('editorSourceStepSubstitution', { static: false }) editorSourceStepSubstitution!: JsonEditorComponent;
  @ViewChild('editorTargetStepSubstitution', { static: false }) editorTargetStepSubstitution!: JsonEditorComponent;
  @ViewChild(SubstitutionRendererComponent, { static: false }) substitutionChild!: SubstitutionRendererComponent;
  @ViewChild('stepper', { static: false }) stepper!: C8yStepper;
  @ViewChild('codeEditor', { static: false }) codeEditor: EditorComponent;


  private cdr = inject(ChangeDetectorRef);
  private bsModalService = inject(BsModalService);
  private sharedService = inject(SharedService);
  private mappingService = inject(MappingService);
  private extensionService = inject(ExtensionService);
  private alertService = inject(AlertService);
  private bottomDrawerService = inject(BottomDrawerService);
  private aiAgentService = inject(AIAgentService);

  readonly ValidationError = ValidationError;
  readonly Direction = Direction;
  readonly COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  readonly EditorMode = EditorMode;
  readonly SnoopStatus = SnoopStatus;

  updateTestingTemplate = new EventEmitter<any>();
  updateSourceEditor: EventEmitter<any> = new EventEmitter<any>();
  updateTargetEditor: EventEmitter<any> = new EventEmitter<any>();

  templateForm: FormGroup;
  templateModel: any = {};
  substitutionFormly: FormGroup = new FormGroup({});
  filterFormly: FormGroup = new FormGroup({});
  substitutionFormlyFields: FormlyFieldConfig[];
  filterFormlyFields: FormlyFieldConfig[];
  filterModel: any = {};
  substitutionModel: any = {};
  propertyFormly: FormGroup = new FormGroup({});
  codeFormly: FormGroup = new FormGroup({});
  isGenerateSubstitutionOpen = false;

  codeTemplateDecoded: CodeTemplate;
  codeTemplatesDecoded: Map<string, CodeTemplate> = new Map<string, CodeTemplate>();
  codeTemplates: CodeTemplateMap;
  mappingCode: any;
  templateId: TemplateType = undefined;

  sourceTemplate: any;
  sourceTemplateUpdated: any;
  sourceSystem: string;
  targetTemplate: any;
  targetTemplateUpdated: any;
  targetSystem: string;
  aiAgentDeployed: boolean = false;
  aiAgent: AgentObjectDefinition | AgentTextDefinition | null = null;

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
    statusBar: true,
    readOnly: false,

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
  destroy$ = new Subject<void>();
  supportsMessageContext: boolean;
  isButtonDisabled$: BehaviorSubject<boolean> = new BehaviorSubject(true);

  sourceCustomMessage$: Subject<string> = new BehaviorSubject(undefined);
  targetCustomMessage$: Subject<string> = new BehaviorSubject(undefined);

  editorOptions: EditorComponent['editorOptions'];

  stepperForward: boolean = true;
  currentStepIndex: number;
  codeEditorHelp = 'JavaScript for creating substitutions. Please do not change the methods signature <code>function extractFromSource(ctx)</code>. <br> Define substitutions: <code>Substitution(String key, Object value, String type, String repairStrategy)</code> <br> with <code>type</code>: <code>"ARRAY"</code>, <code>"IGNORE"</code>, <code>"NUMBER"</code>, <code>"OBJECT"</code>, <code>"TEXTUAL"</code> <br>and <code>repairStrategy</code>: <br><code>"DEFAULT"</code>, <code>"USE_FIRST_VALUE_OF_ARRAY"</code>, <code>"USE_LAST_VALUE_OF_ARRAY"</code>, <code>"IGNORE"</code>, <code>"REMOVE_IF_MISSING_OR_NULL"</code>,<code>"CREATE_IF_MISSING"</code>';
  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  feature: Feature;
  serviceConfiguration: ServiceConfiguration;

  async ngOnInit(): Promise<void> {
    // console.log('mapping-stepper', this._deploymentMapEntry, this.deploymentMapEntry);
    if (
      this.mapping.snoopStatus === SnoopStatus.NONE ||
      this.mapping.snoopStatus === SnoopStatus.STOPPED
    ) {
      this.labels = {
        ...this.labels,
        custom: 'Start snooping'
      } as const;
    }
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    this.templateModel = {
      stepperConfiguration: this.stepperConfiguration,
      mapping: this.mapping
    };

    this.editorOptions = {
      minimap: { enabled: true },
      language: 'javascript',
      renderWhitespace: 'none',
      tabSize: 4,
      readOnly: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
    };


    this.substitutionModel = {
      stepperConfiguration: this.stepperConfiguration,
      pathSource: '',
      pathTarget: '',
      pathSourceIsExpression: false,
      pathTargetIsExpression: false,
      repairStrategy: RepairStrategy.DEFAULT,
      expandArray: false,
      targetExpression: {
        result: '',
        resultType: 'empty',
        valid: false
      },
      sourceExpression: {
        result: '',
        resultType: 'empty',
        valid: false
      }
    };

    this.filterModel = {
      filterExpression: {
        result: '',
        resultType: 'empty',
        valid: false,
      },
    };

    this.setTemplateForm();

    this.feature = await this.sharedService.getFeatures();
    if (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole) {
      this.editorOptionsSourceSubstitution.readOnly = true;
      this.editorOptionsTargetSubstitution.readOnly = true;
      this.editorOptionsSourceTemplate.readOnly = true;
      this.editorOptionsTargetTemplate.readOnly = true;
    }

    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();

    // implementation such that aiAgent is defined depending on mapping type
    from(this.aiAgentService.getAIAgents())
      .pipe(
        map(agents => {
          const agentNames = agents.map(agent => agent.name);

          const requiredAgentName = this.mapping.mappingType === MappingType.JSON
            ? this.serviceConfiguration?.jsonataAgent
            : this.serviceConfiguration?.javaScriptAgent;

          const hasRequiredAgent = requiredAgentName && agentNames.includes(requiredAgentName);
          const selectedAgent = hasRequiredAgent
            ? agents.find(agent => agent.name === requiredAgentName)
            : null;

          return {
            agents,
            agentNames,
            hasRequiredAgent,
            selectedAgent,
            aiAgentDeployed: agentNames.length > 0 && hasRequiredAgent
          };
        }),
        takeUntil(this.destroy$)
      )
      .subscribe(result => {
        this.aiAgent = result.selectedAgent;
        this.aiAgentDeployed = result.aiAgentDeployed;
      });

    this.initializeFormlyFields();
    this.initializeCodeTemplates();

  }


  private initializeFormlyFields(): void {
    this.filterFormlyFields = [
      {
        fieldGroup: [
          {
            // className: 'col-lg-5 col-lg-offset-1',
            key: 'filterMapping',
            type: 'input-custom',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Filter execution mapping',
              class: 'input-sm',
              customWrapperClass: 'm-b-24',
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                // !this.stepperConfiguration.allowDefiningSubstitutions,
                !this.stepperConfiguration.allowDefiningSubstitutions || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              placeholder: '$exists(c8y_TemperatureMeasurement)',
              description: `This expression is required and must evaluate to <code>true</code> to apply the mapping.
              Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>Check if a fragment exists:
                  <code>$exists(c8y_TemperatureMeasurement)</code>
                </li>
                <li>Check if a value mets a condition:
                   <code>c8y_TemperatureMeasurement.T.value > 15</code></li>
                <li>function chaining using <code>~</code> is not supported, instead use function
                  notation. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              required: this.mapping.direction == Direction.OUTBOUND,
              customMessage: this.sourceCustomMessage$
            },
            expressionProperties: {
              'templateOptions.class': (model) => {
                if (model.pathSource == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions) {
                  return 'input-sm';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  // Wait for 500ms pause in typing before processing
                  debounceTime(500),

                  // Only trigger if the value has actually changed
                  distinctUntilChanged()
                ).subscribe(path => {
                  this.updateFilterExpressionResult(path);
                });
              }
            }
          }
        ]
      }
    ];


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
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
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
                if (model.pathSource == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions) {
                  return 'input-sm';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  // Wait for 500ms pause in typing before processing
                  debounceTime(500),

                  // Only trigger if the value has actually changed
                  distinctUntilChanged()
                ).subscribe(path => {
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
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
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
                if (model.pathTarget == '' &&
                  model.stepperConfiguration.allowDefiningSubstitutions) {
                  return 'input-sm';
                } else {
                  return 'input-sm';
                }
              }
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  // Wait for 500ms pause in typing before processing
                  debounceTime(500),

                  // Only trigger if the value has actually changed
                  distinctUntilChanged()
                ).subscribe(path => {
                  this.updateTargetExpressionResult(path);
                });
              }
            }
          }
        ]
      }
    ];
  }

  async initializeCodeTemplates(): Promise<void> {
    this.codeTemplates = await this.sharedService.getCodeTemplates();
    this.codeTemplatesDecoded = new Map<string, CodeTemplate>();
    // Iterate and decode
    Object.entries(this.codeTemplates).forEach(([key, template]) => {
      try {
        const decodedCode = base64ToString(template.code);
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name,
          templateType: template.templateType, code: decodedCode, internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      } catch (error) {
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name,
          templateType: template.templateType, code: "// Code Template not valid!", internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      }
    });
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
    // console.log("Code",)
  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      monaco.languages.registerCompletionItemProvider('javascript', createCompletionProvider(monaco));
      if (monaco) {
        initializedMonaco = true;
      }
    }
  }

  ngOnDestroy(): void {
    this.countDeviceIdentifiers$.complete();
    this.isSubstitutionValid$.complete();
    this.extensionEvents$.complete();
    this.destroy$.complete();
  }

  /**
 * Updates the substitution validity state based on the number of device identifiers.
 */
  private updateSubstitutionValid(): void {
    const ni = countDeviceIdentifiers(this.mapping);
    // console.log('Updated number identifiers', ni, (ni == 1 && this.mapping.direction == Direction.INBOUND) , ni >= 1 && this.mapping.direction == Direction.OUTBOUND, (ni == 1 && this.mapping.direction == Direction.INBOUND) ||
    // (ni >= 1 && this.mapping.direction == Direction.OUTBOUND) || this.stepperConfiguration.allowNoDefinedIdentifier);
    this.countDeviceIdentifiers$.next(ni);
    // console.log(this.stepperConfiguration.showCodeEditor, ni == 1 && this.mapping.direction == Direction.INBOUND, ni >= 1 && this.mapping.direction == Direction.OUTBOUND, this.stepperConfiguration.allowNoDefinedIdentifier || this.currentStepIndex < 3);
    this.isSubstitutionValid$.next(this.stepperConfiguration.showCodeEditor || (ni == 1 && this.mapping.direction == Direction.INBOUND) ||
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
  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    const isDisabled = !this.deploymentMapEntry?.connectors ||
      this.deploymentMapEntry?.connectors?.length == 0;

    setTimeout(() => {
      this.isButtonDisabled$.next(isDisabled);
      this.supportsMessageContext =
        this.deploymentMapEntry.connectorsDetailed?.some(
          (con) => con.connectorType == ConnectorType.KAFKA || con.connectorType == ConnectorType.WEB_HOOK || this.mapping.mappingType == MappingType.CODE_BASED
        );
    });
  }

  onEditorSourceInitialized(): void {
    this.updateSourceEditor.emit(
      { schema: getSchema(this.mapping.targetAPI, this.mapping.direction, false, false), identifier: API[this.mapping.targetAPI].identifier }
    );
  }

  onEditorTargetInitialized(): void {
    this.updateTargetEditor.emit(
      { schema: getSchema(this.mapping.targetAPI, this.mapping.direction, true, false), identifier: API[this.mapping.targetAPI].identifier }
    );
  }

  async updateSourceExpressionResult(path): Promise<void> {
    this.clearAlerts();
    this.sourceCustomMessage$.next(undefined);
    try {
      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorSourceStepTemplate?.get(),
        path
      );
      this.substitutionModel.sourceExpression = {
        resultType: getTypeOf(r),
        result: JSON.stringify(r, null, 4),
        valid: true,
      };
      this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);
      this.substitutionFormly.get('pathSource').setErrors(null);
      if (
        this.substitutionModel.sourceExpression.resultType == 'Array' &&
        !this.substitutionModel.expandArray
      ) {
        const txt =
          'Current expression extracts an array. Consider to use the option "Expand as array" if you want to create multiple measurements, alarms, events or devices, i.e. "multi-device" or "multi-value"';
        this.raiseAlert({ type: 'info', text: txt });
        // this.sourceCustomMessage$.next(txt);
      }
    } catch (error) {
      this.substitutionModel.sourceExpression.valid = false;
      this.substitutionFormly
        .get('pathSource')
        .setErrors({ validationError: { message: error.message } });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  async updateFilterExpressionResult(path): Promise<void> {
    this.clearAlerts();
    try {
      // const resultExpression: JSON = await this.mappingService.evaluateExpression(
      //   JSON.parse(this.mapping.sourceTemplate),
      //   path
      // );
      // console.log("Content:", this.editorSourceStepTemplate?.get())
      const resultExpression: JSON = await this.mappingService.evaluateExpression(
        this.editorSourceStepTemplate?.get(),
        path
      );
      this.filterModel.filterExpression = {
        resultType: getTypeOf(resultExpression),
        result: JSON.stringify(resultExpression, null, 4),
        valid: true
      };
      if ((path && this.filterModel.filterExpression.resultType != 'Boolean') || (path && this.filterModel.filterExpression.resultType == 'Boolean' && !resultExpression)) throw Error('The filter expression must return true');
      this.mapping.filterMapping = path;
    } catch (error) {
      this.filterModel.filterExpression.valid = false;
      this.filterFormly
        .get('filterMapping')
        .setErrors({ validationError: { message: error.message } });
      this.filterFormly.get('filterMapping').markAsTouched();
    }
    this.filterModel = { ...this.filterModel };
  }

  async updateTargetExpressionResult(path): Promise<void> {
    this.clearAlerts();
    try {
      const r: JSON = await this.mappingService.evaluateExpression(
        this.editorTargetStepTemplate?.get(),
        path
      );
      this.substitutionModel.targetExpression = {
        resultType: getTypeOf(r),
        result: JSON.stringify(r, null, 4),
        valid: true
      };

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
    } catch (error) {
      this.substitutionModel.targetExpression.valid = false;
      this.substitutionFormly
        .get('pathTarget')
        .setErrors({ validationError: { message: error.message } });
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  isSubstitutionValid(): boolean {
    const r1 =
      this.substitutionModel.sourceExpression?.valid;
    const r2 =
      this.substitutionModel.targetExpression?.valid;
    const r3 = this.substitutionModel.pathSource != '';
    const r4 = this.substitutionModel.pathTarget != '';
    const result = r1 && r2 && r3 && r4;
    return result;
  }

  async onSelectedPathSourceChanged(path: string): Promise<void> {
    this.substitutionFormly.get('pathSource').setValue(path);
    this.substitutionModel.pathSource = path;
    this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);

    if (path == API[this.mapping.targetAPI].identifier) {
      const gi = getGenericDeviceIdentifier(this.mapping);
      await this.editorSourceStepSubstitution.setSelectionToPath(
        getGenericDeviceIdentifier(this.mapping)
      );
      this.raiseAlert({ type: 'info', text: `Please use the selected node ${gi} to map the identity from the source` });
    }

  }

  async onSelectedPathFilterMappingChanged(path: string): Promise<void> {
    this.filterModel.filterMapping = path;
    //console.log("Select: "+path);

    this.updateFilterExpressionResult(path);
  }

  async onSelectedPathTargetChanged(path: string): Promise<void> {
    this.substitutionFormly.get('pathTarget').setValue(path);
    this.substitutionModel.pathTarget = path;
 
    if (path == API[this.mapping.targetAPI].identifier) {
      const gi = getGenericDeviceIdentifier(this.mapping);
      await this.editorTargetStepSubstitution.setSelectionToPath(
        gi
      );
      this.raiseAlert({ type: 'info', text: `Please use the selected node ${gi} to map the identity from the source` });
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onSourceTemplateChanged(content: Content) {
    let contentAsJson;
    if (_.has(content, 'text') && content['text']) {
      try {
        contentAsJson = JSON.parse(content['text']);
      } catch (error) {
        // ignore parsing error
      }
    } else {
      contentAsJson = content['json'];
    }
    this.sourceTemplateUpdated = contentAsJson;

    // console.log("Step onSourceTemplateChanged", this.mapping.sourceTemplate, this.mapping.targetTemplate);
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  onTargetTemplateChanged(content: Content): void {
    let contentAsJson;
    if (_.has(content, 'text') && content['text']) {
      try {

      } catch (error) {
        // ignore parsing error
      } contentAsJson = JSON.parse(content['text']);
    } else {
      contentAsJson = content['json'];
    }
    this.targetTemplateUpdated = contentAsJson;

    // console.log("Step onTargetTemplateChanged",this.mapping.sourceTemplate,  this.mapping.targetTemplate);
  }

  raiseAlert(alert: Alert) {
    // clear all info alert
    this.alertService.state.forEach(a => {
      if (a.type == 'info') { this.alertService.remove(a) }
    })
    this.alertService.add(alert);
  }

  clearAlerts() {
    this.alertService.clearAll();
  }

  async onCommitButton(): Promise<void> {
    this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
    this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);
    if (this.mapping.code || this.mappingCode) {
      this.mapping.code = stringToBase64(this.mappingCode);
      //delete this.mappingCode;
    }
    if (this.mapping.mappingType == MappingType.CODE_BASED && (!this.mapping.code || this.mapping.code == null || this.mapping.code == '')) {
      this.raiseAlert({ type: 'warning', text: "Internal error in editor. Try again!" });
      this.commit.emit();
    }
    this.commit.emit(this.mapping);
  }

  async onSampleTargetTemplatesButton(): Promise<void> {
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

  async onCancelButton(): Promise<void> {
    this.cancel.emit();
  }

  onSelectExtensionName(extensionName): void {
    this.mapping.extension.extensionName = extensionName;
    this.extensionEvents$.next(
      Object.values(this.extensions[extensionName].extensionEntries as Map<string, ExtensionEntry>).filter(entry => entry.extensionType == this.mapping.extension.extensionType)
    );
    //console.log("Selected events", Object.values(this.extensions[extensionName].extensionEntries))
  }

  onSelectExtensionEvent(extensionEvent): void {
    this.mapping.extension.eventName = extensionEvent;
  }

  async onStepChange(event: any): Promise<void> {
    // previouslySelectedIndex
    // previouslySelectedStep
    // selectedIndex
    // selectedStep
    this.currentStepIndex = event['selectedIndex'];
    this.updateSubstitutionValid();

    switch (this.currentStepIndex) {
      case STEP_GENERAL_SETTINGS:
        await this.handleGeneralSettingsStep();
        break;
      case STEP_SELECT_TEMPLATES:
        await this.handleSelectTemplatesStep();
        break;
      case STEP_DEFINE_SUBSTITUTIONS:
        this.handleDefineSubstitutionsStep();
        break;
      case STEP_TEST_MAPPING:
        this.handleTestMappingStep();
        break;
      default:
        // No action for other steps
        break;
    }
  }

  private async handleGeneralSettingsStep(): Promise<void> {
    this.templateModel.mapping = this.mapping;
    this.templatesInitialized = false;
    this.extensions = await this.extensionService.getProcessorExtensions() as Map<string, Extension>;

    if (this.mapping?.extension?.extensionName) {
      if (!this.extensions[this.mapping.extension.extensionName]) {
        const msg = `The extension ${this.mapping.extension.extensionName} with event ${this.mapping.extension.eventName} is not loaded. Please load the extension or choose a different one.`;
        this.raiseAlert({ type: 'warning', text: msg });
      } else {
        this.extensionEvents$.next(
          Object.values(
            this.extensions[this.mapping.extension.extensionName].extensionEntries
          )
        );
      }
    }
  }

  private async handleSelectTemplatesStep(): Promise<void> {
    this.filterModel['filterMapping'] = this.mapping.filterMapping;
    if (this.mapping.filterMapping) {
      await this.updateFilterExpressionResult(this.mapping.filterMapping);
    }
    if (this.mapping.code) {
      this.mappingCode = base64ToString(this.mapping.code);
    }
    if (this.stepperForward) {
      this.expandTemplates();
    }
  }

  private handleDefineSubstitutionsStep(): void {
    this.updateTemplatesInEditors();
    this.updateSubstitutionValid();
    this.onSelectSubstitution(0);
    const testMapping = _.clone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    this.updateTestingTemplate.emit(testMapping);
  }

  private handleTestMappingStep(): void {
    if (this.mapping.code || this.mappingCode) {
      const testMapping = _.clone(this.mapping);
      testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
      testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
      testMapping.code = stringToBase64(this.mappingCode);
      this.updateTestingTemplate.emit(testMapping);
    }
  }

  private updateTemplatesInEditors(): void {
    this.sourceTemplate = this.sourceTemplateUpdated ? this.sourceTemplateUpdated : this.sourceTemplate;
    this.targetTemplate = this.targetTemplateUpdated ? this.targetTemplateUpdated : this.targetTemplate;
    this.editorSourceStepSubstitution?.set(this.sourceTemplate);
    this.editorTargetStepSubstitution?.set(this.targetTemplate);
  }

  onNextStep(event: StepperStepChange): void {
    this.stepperForward = true;
    if (this.stepperConfiguration.advanceFromStepToEndStep && this.stepperConfiguration.advanceFromStepToEndStep == this.currentStepIndex) {
      this.goToLastStep();
      this.raiseAlert({ type: 'info', text: 'The other steps have been skipped for this mapping type!' });
    } else {
      event.stepper.next();
    }
  }

  private goToLastStep(): void {
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

  async onBackStep(event: StepperStepChange): Promise<void> {
    this.step = event.step.label;
    this.stepperForward = false;
    if (this.step == 'Test mapping') {
      this.mappingTestingStep.editorTestingRequest.setSchema({});
    } else if (this.step == 'General settings') {
      this.templatesInitialized = false;
    } else if (this.step == 'Select templates') {
      this.templatesInitialized = false;
    }
    event.stepper.previous();
  }

  private expandTemplates(): void {
    // Determine topic levels based on mapping direction
    const levels: string[] = splitTopicExcludingSeparator(
      this.mapping.direction === Direction.INBOUND
        ? this.mapping.mappingTopicSample
        : this.mapping.publishTopicSample,
      false
    );

    // Helper functions for template expansion
    const expandSource = (template: any) =>
      this.mapping.direction === Direction.INBOUND
        ? expandExternalTemplate(template, this.mapping, levels)
        : expandC8YTemplate(template, this.mapping);

    const expandTarget = (template: any) =>
      this.mapping.direction === Direction.INBOUND
        ? expandC8YTemplate(template, this.mapping)
        : expandExternalTemplate(template, this.mapping, levels);

    // Initialize templates if in CREATE mode and not yet initialized
    if (
      this.stepperConfiguration.editorMode === EditorMode.CREATE &&
      !this.templatesInitialized
    ) {
      this.templatesInitialized = true;
      if (this.mapping.direction === Direction.INBOUND) {
        this.sourceTemplate = expandSource(
          JSON.parse(getExternalTemplate(this.mapping))
        );
        this.targetTemplate = expandTarget(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI])
        );
      } else {
        this.sourceTemplate = expandSource(
          JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI])
        );
        this.targetTemplate = expandTarget(
          JSON.parse(getExternalTemplate(this.mapping))
        );
      }
      return;
    }

    // Otherwise, expand from existing mapping templates
    this.sourceTemplate = expandSource(
      JSON.parse(this.mapping.sourceTemplate)
    );
    this.targetTemplate = expandTarget(
      JSON.parse(this.mapping.targetTemplate)
    );
  }

  async onSnoopedSourceTemplates(): Promise<void> {
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

  async onSelectSnoopedSourceTemplate(event: Event) {
    const index = this.templateForm.get('snoopedTemplateIndex')?.value;
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

  async onTargetAPIChanged(changedTargetAPI: string): Promise<void> {
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

  onAddSubstitution(): void {
    if (!this.isSubstitutionValid()) {
      this.raiseAlert({
        type: 'warning', text:
          'Please select two nodes: one node in the template source, one node in the template target to define a substitution.'
      }
      );
      return;
    }
    this.substitutionModel.expandArray = false;
    this.substitutionModel.repairStrategy = RepairStrategy.DEFAULT;
    this.addSubstitution(this.substitutionModel);
    this.selectedSubstitution = -1;
  }

  onDeleteSubstitution(selected: number): void {
    if (selected < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(selected, 1);
    }
    this.updateSubstitutionValid();
  }

  toggleExpertMode(): void {
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
        next: (editedSubstitution: Substitution) => {
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

  private addSubstitution(newSubstitution: Substitution): void {
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
      .subscribe((updatedSubstitution: Substitution) => {
        if (!updatedSubstitution) return;

        if (isDuplicate) {
          mapping.substitutions[duplicateIndex] = updatedSubstitution;
        } else {
          mapping.substitutions.push(updatedSubstitution);
        }

        this.updateSubstitutionValid();
      });
  }

  async onSelectSubstitution(selected: number): Promise<void> {
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
    this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);

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
    return !this.stepperConfiguration.showEditorSource ||
      this.stepperConfiguration.editorMode ===
      EditorMode.READ_ONLY ||
      this.selectedSubstitution === -1 ||
      !this.isSubstitutionValid()
  }

  onValueCodeChange(value): void {
    // console.log("code changed", value);
    this.mappingCode = value;
  }

  onSelectCodeTemplate(): void {
    this.mappingCode = this.codeTemplatesDecoded.get(this.templateId).code;
  }

  getCodeTemplateEntries(): { key: string; name: string, type: TemplateType }[] {
    if (!this.codeTemplates) return [];
    const entries = Object.entries(this.codeTemplates).filter(([key, template]) => (template.templateType.toString() == this.stepperConfiguration.direction.toString())).map(([key, template]) => ({
      key,
      name: template.name,
      type: template.templateType
    }));
    return entries;
  }

  async onCreateCodeTemplate(): Promise<void> {
    const initialState = {
      action: 'CREATE',
      codeTemplate: { name: `New code template - ${this.stepperConfiguration.direction}` }
    };
    const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

    modalRef.content.closeSubject.subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
      // console.log('Configuration after edit:', editedConfiguration);
      if (codeTemplate) {
        const code = stringToBase64(this.mappingCode);
        const id = createCustomUuid();
        const templateType = this.stepperConfiguration.direction == Direction.INBOUND ? TemplateType.INBOUND : TemplateType.OUTBOUND;
        const response = await this.sharedService.createCodeTemplate({
          id, name: codeTemplate.name, description: codeTemplate.description,
          templateType, code, internal: false, readonly: false, defaultTemplate: false
        });
        this.codeTemplates = await this.sharedService.getCodeTemplates();
        if (response.status >= 200 && response.status < 300) {
          this.alertService.success(gettext('Added new code template.'));
        } else {
          this.alertService.danger(
            gettext('Failed to create new code template')
          );
        }
      }
    });
  }

  async openGenerateSubstitutionDrawer() {
    this.isGenerateSubstitutionOpen = true;

    const testMapping = _.clone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);

    const aiAgent = this.aiAgent;
    const drawer = this.bottomDrawerService.openDrawer(AIPromptComponent, {
      initialState: { mapping: testMapping, aiAgent },
    });

    try {
      const resultOf = await drawer.instance.result;

      if (this.mapping.mappingType === MappingType.CODE_BASED) {
        if (typeof resultOf === 'string' && resultOf.trim()) {
          this.mappingCode = resultOf;

          // Multiple approaches to ensure update
          this.cdr.detectChanges();

          if (this.codeEditor) {
            setTimeout(() => {
              this.codeEditor.writeValue(resultOf);
            }, 100);
          }

          this.alertService.success('Generated JavaScript code successfully.');
        } else {
          this.raiseAlert({ type: 'warning', text: 'No valid JavaScript code was generated.' });
        }
      } else {
        if (Array.isArray(resultOf) && resultOf.length > 0) {
          this.alertService.success(`Generated ${resultOf.length} substitutions.`);
          this.mapping.substitutions.splice(0);
          resultOf.forEach(sub => this.addSubstitution(sub));
        } else {
          this.raiseAlert({ type: 'warning', text: 'No substitutions were generated.' });
        }
      }
    } catch (ex) {
      // User canceled or error occurred
    }

    this.isGenerateSubstitutionOpen = false;
  }

}
