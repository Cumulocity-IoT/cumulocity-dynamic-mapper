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
  ElementRef,
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
import { Alert, AlertService, BottomDrawerService, C8yStepper, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import * as _ from 'lodash';
import { BsModalService } from 'ngx-bootstrap/modal';
import { debounceTime, distinctUntilChanged, Subject } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  API,
  COLOR_HIGHLIGHTED,
  DeploymentMapEntry,
  Direction,
  Extension,
  getExternalTemplate,
  getSchema,
  JsonEditorComponent,
  Mapping,
  RepairStrategy,
  SAMPLE_TEMPLATES_C8Y,
  SharedService,
  SnoopStatus,
  StepperConfiguration,
  MappingType,
  Feature,
  isSubstitutionsAsCode,
  TransformationType,
  MappingTypeLabels,
  ContentChanges,
  MappingTypeDescriptions,
  CapitalizeCasePipe
} from '../../shared';
import { ValidationError } from '../shared/mapping.model';
import { createCompletionProviderFlowFunction, createCompletionProviderSubstitutionAsCode, EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
import {
  base64ToString,
  checkTransformationType,
  expandC8YTemplate,
  expandExternalTemplate,
  isExpression,
  reduceSourceTemplate,
  splitTopicExcludingSeparator,
  stringToBase64,
  validateProtectedFields
} from '../shared/util';
import { SubstitutionRendererComponent } from '../substitution/substitution-grid.component';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType } from '../../configuration/shared/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { AIPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { MappingStepTestingComponent } from '../step-testing/mapping-testing.component';
import { gettext } from '@c8y/ngx-components/gettext';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-property/mapping-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-substitution/mapping-substitution-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';

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
  standalone: true,
  providers: [MappingStepperService, SubstitutionManagementService],
  imports: [CoreModule, CommonModule, EditorComponent, CapitalizeCasePipe, PopoverModule, MappingStepPropertiesComponent, MappingConnectorComponent, MappingSubstitutionStepComponent, MappingStepTestingComponent, JsonEditorComponent]
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
  @ViewChild('filterModelFilterExpression') filterModelFilterExpression: ElementRef<HTMLTextAreaElement>;
  @ViewChild('substitutionModelSourceExpression') substitutionModelSourceExpression: ElementRef<HTMLTextAreaElement>;
  @ViewChild('substitutionModelTargetExpression') substitutionModelTargetExpression: ElementRef<HTMLTextAreaElement>;

  private cdr = inject(ChangeDetectorRef);
  private bsModalService = inject(BsModalService);
  private sharedService = inject(SharedService);
  private alertService = inject(AlertService);
  private bottomDrawerService = inject(BottomDrawerService);

  // Inject new services
  private stepperService = inject(MappingStepperService);
  private substitutionService = inject(SubstitutionManagementService);

  readonly ValidationError = ValidationError;
  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  readonly TransformationType = TransformationType;
  readonly EditorMode = EditorMode;
  readonly SnoopStatus = SnoopStatus;
  readonly MappingTypeDescriptions = MappingTypeDescriptions;

  updateTestingTemplate = new EventEmitter<any>();
  updateSourceEditor: EventEmitter<any> = new EventEmitter<any>();
  updateTargetEditor: EventEmitter<any> = new EventEmitter<any>();

  templateForm: FormGroup;
  templateModel: any = {};
  substitutionFormly: FormGroup = new FormGroup({});
  filterFormly: FormGroup = new FormGroup({});
  filterFormlyFields: FormlyFieldConfig[];
  filterModel: any = {};
  selectedPathFilterFilterMapping: any;
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

  // Use service observables
  get countDeviceIdentifiers$() { return this.stepperService.countDeviceIdentifiers$; }
  get isSubstitutionValid$() { return this.stepperService.isSubstitutionValid$; }
  get isContentChangeValid$() { return this.stepperService.isContentChangeValid$; }
  get extensionEvents$() { return this.stepperService.extensionEvents$; }
  get isButtonDisabled$() { return this.stepperService.isButtonDisabled$; }
  get sourceCustomMessage$() { return this.stepperService.sourceCustomMessage$; }
  get targetCustomMessage$() { return this.stepperService.targetCustomMessage$; }

  labels: any = { next: 'Next', cancel: 'Cancel' };

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
    readOnly: false
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
  destroy$ = new Subject<void>();
  editorOptions: EditorComponent['editorOptions'];
  stepperForward: boolean = true;
  currentStepIndex: number;
  codeEditorHelp: string;
  codeEditorLabel: string;
  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  feature: Feature;
  serviceConfiguration: ServiceConfiguration;

  async ngOnInit(): Promise<void> {
    if (this.mapping.snoopStatus === SnoopStatus.NONE || this.mapping.snoopStatus === SnoopStatus.STOPPED) {
      this.labels = { ...this.labels, custom: 'Start snooping' } as const;
    }

    this.targetSystem = this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
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
      targetExpression: { result: '', resultType: 'empty', valid: false },
      sourceExpression: { result: '', resultType: 'empty', valid: false }
    };

    this.filterModel = {
      filterExpression: { result: '', resultType: 'empty', valid: false }
    };

    this.setTemplateForm();

    this.feature = await this.sharedService.getFeatures();
    if (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole) {
      this.editorOptionsSourceSubstitution.readOnly = true;
      this.editorOptionsTargetSubstitution.readOnly = true;
      this.editorOptionsSourceTemplate.readOnly = true;
      this.editorOptionsTargetTemplate.readOnly = true;
    }

    this.serviceConfiguration = await this.sharedService.getServiceConfiguration();

    // Use service method
    const aiResult = await this.stepperService.checkAIAgentDeployment(this.mapping, this.serviceConfiguration);
    this.aiAgent = aiResult.aiAgent;
    this.aiAgentDeployed = aiResult.aiAgentDeployed;

    this.initializeFormlyFields();
    await this.initializeCodeTemplates();

    this.codeEditorHelp = this.mapping.transformationType == TransformationType.SUBSTITUTION_AS_CODE ||
      this.mapping.mappingType == MappingType.CODE_BASED ?
      'JavaScript for creating substitutions...' :
      'JavaScript for creating complete payloads as Smart Functions.';

    this.codeEditorLabel = this.mapping.transformationType == TransformationType.SUBSTITUTION_AS_CODE ||
      this.mapping.mappingType == MappingType.CODE_BASED ?
      'JavaScript callback for creating substitutions' :
      'JavaScript callback for Smart functions';
  }

  private initializeFormlyFields(): void {
    // Filter fields initialization
    this.filterFormlyFields = [
      {
        fieldGroup: [
          {
            key: 'filterMapping',
            type: 'input-custom',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Filter execution mapping',
              class: 'input-sm',
              customWrapperClass: 'm-b-24',
              disabled: this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions ||
                (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              placeholder: '$exists(c8y_TemperatureMeasurement)',
              description: 'This expression is required...',
              required: this.mapping.direction == Direction.OUTBOUND,
              customMessage: this.sourceCustomMessage$
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  debounceTime(500),
                  distinctUntilChanged()
                ).subscribe(path => this.updateFilterExpressionResult(path));
              }
            }
          }
        ]
      }
    ];

  }

  async initializeCodeTemplates(): Promise<void> {
    this.codeTemplates = await this.sharedService.getCodeTemplates();
    this.codeTemplatesDecoded = await this.stepperService.loadCodeTemplates();
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      if (this.mapping.transformationType == TransformationType.SMART_FUNCTION) {
        monaco.languages.registerCompletionItemProvider('javascript', createCompletionProviderFlowFunction(monaco));
      } else {
        monaco.languages.registerCompletionItemProvider('javascript', createCompletionProviderSubstitutionAsCode(monaco));
      }
      if (monaco) initializedMonaco = true;
    }
  }

  ngOnDestroy(): void {
    this.stepperService.cleanup();
    this.destroy$.complete();
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
        disabled: !this.stepperConfiguration.showEditorSource ||
          this.mapping.snoopedTemplates.length === 0 ||
          this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }),
      sampleTargetTemplatesButton: new FormControl({
        value: !this.stepperConfiguration.showEditorSource ||
          this.stepperConfiguration.editorMode === EditorMode.READ_ONLY,
        disabled: undefined
      })
    });

    this.isSubstitutionValid$.subscribe(valid => {
      if (valid) {
        this.templateForm.setErrors(null);
      } else {
        this.templateForm.setErrors({ 'incorrect': true });
      }
    });
  }

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    const isDisabled = !this.deploymentMapEntry?.connectors || this.deploymentMapEntry?.connectors?.length == 0;
    setTimeout(() => {
      this.isButtonDisabled$.next(isDisabled);
    });
  }

  onEditorSourceInitialized(): void {
    this.updateSourceEditor.emit({
      schema: getSchema(this.mapping.targetAPI, this.mapping.direction, false, false),
      identifier: API[this.mapping.targetAPI].identifier
    });
  }

  onEditorTargetInitialized(): void {
    this.updateTargetEditor.emit({
      schema: getSchema(this.mapping.targetAPI, this.mapping.direction, true, false),
      identifier: API[this.mapping.targetAPI].identifier
    });
  }


  async updateFilterExpressionResult(path: string): Promise<void> {
    this.clearAlerts();

    try {
      const result = await this.stepperService.evaluateFilterExpression(
        this.editorSourceStepTemplate?.get(),
        path
      );

      this.filterModel.filterExpression = result;
      this.mapping.filterMapping = path;
    } catch (error) {
      this.filterModel.filterExpression.valid = false;
      this.filterFormly.get('filterMapping').setErrors({
        validationError: { message: error.message }
      });
      this.filterFormly.get('filterMapping').markAsTouched();
    }

    this.filterModel = { ...this.filterModel };
    setTimeout(() => this.manualResize('filterModelFilterExpression'), 0);
  }

  isSubstitutionValid(): boolean {
    return this.substitutionService.isSubstitutionValid(this.substitutionModel);
  }


  async onSelectedPathFilterMappingChanged(path: string): Promise<void> {
    this.selectedPathFilterFilterMapping = path;
  }

  async onOverwriteFilterMapping(): Promise<void> {
    this.filterModel.filterMapping = this.selectedPathFilterFilterMapping;
    this.updateFilterExpressionResult(this.selectedPathFilterFilterMapping);
  }

  onSourceTemplateChanged(contentChanges: ContentChanges): void {
    const { previousContent, updatedContent } = contentChanges;

    // Always allow the change during editing
    let updatedContentAsJson;

    if (_.has(updatedContent, 'text') && updatedContent['text']) {
      try {
        updatedContentAsJson = JSON.parse(updatedContent['text']);
      } catch (error) {
        // Syntax error - allow it, user is still typing
        this.sourceTemplateUpdated = updatedContent;
        this.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedContentAsJson = updatedContent['json'];
    }

    this.sourceTemplateUpdated = updatedContentAsJson;

    // Just validate and show warning, don't block
    const hasProtectedChanges = !validateProtectedFields(
      this.sourceTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

    // Show error message if transformation type is invalid
    // if (!isTransformationTypeValid) {
    //   this.raiseAlert({
    //     type: 'warning',
    //     text: 'Wrong Transformation Type: an Array in Source Template or Target Template requires Transformation Type Smart Function'
    //   });
    // }

    // Consider both validations
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges) {
      this.raiseAlert({
        type: 'warning',
        text: "Warning: Changes to _IDENTITY_, _TOPIC_LEVEL_, or _CONTEXT_DATA_ will be reverted when saving."
      });
    }
  }

  onTargetTemplateChanged(contentChanges: ContentChanges): void {
    const { previousContent, updatedContent } = contentChanges;

    // Always allow the change during editing
    let updatedContentAsJson;

    if (_.has(updatedContent, 'text') && updatedContent['text']) {
      try {
        updatedContentAsJson = JSON.parse(updatedContent['text']);
      } catch (error) {
        // Syntax error - allow it, user is still typing
        this.targetTemplateUpdated = updatedContent;
        this.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedContentAsJson = updatedContent['json'];
    }

    this.targetTemplateUpdated = updatedContentAsJson;

    // Just validate and show warning, don't block
    const hasProtectedChanges = !validateProtectedFields(
      this.targetTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

    // Show error message if transformation type is invalid
    // if (!isTransformationTypeValid) {
    //   this.raiseAlert({
    //     type: 'warning',
    //     text: 'Wrong Transformation Type: an Array in Source Template or Target Template requires Transformation Type Smart Function'
    //   });
    // }

    // Consider both validations
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges) {
      this.raiseAlert({
        type: 'warning',
        text: "Warning: Changes to _IDENTITY_, _TOPIC_LEVEL_, or _CONTEXT_DATA_ will be reverted when saving."
      });
    }
  }



  raiseAlert(alert: Alert): void {
    this.alertService.state.forEach(a => {
      if (a.type == 'info') this.alertService.remove(a);
    });
    this.alertService.add(alert);
  }

  clearAlerts(): void {
    this.alertService.clearAll();
  }

  async onCommitButton(): Promise<void> {
    this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
    this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);

    if (this.mapping.code || this.mappingCode) {
      this.mapping.code = stringToBase64(this.mappingCode);
    }

    if (isSubstitutionsAsCode(this.mapping) && (!this.mapping.code || this.mapping.code == null || this.mapping.code == '')) {
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
      const levels: string[] = splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false);
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

  onSelectExtensionName(extensionName: string): void {
    this.mapping.extension.extensionName = extensionName;
    this.stepperService.selectExtensionName(extensionName, this.extensions, this.mapping);
  }

  onSelectExtensionEvent(extensionEvent: string): void {
    this.mapping.extension.eventName = extensionEvent;
  }

  async onStepChange(event: any): Promise<void> {
    this.currentStepIndex = event['selectedIndex'];
    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );

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
    }
  }

  private async handleGeneralSettingsStep(): Promise<void> {
    this.templateModel.mapping = this.mapping;
    this.templatesInitialized = false;
    this.extensions = await this.stepperService.loadExtensions(this.mapping);

    if (this.mapping?.extension?.extensionName) {
      if (!this.extensions.get(this.mapping.extension.extensionName)) {
        const msg = `The extension ${this.mapping.extension.extensionName} with event ${this.mapping.extension.eventName} is not loaded...`;
        this.raiseAlert({ type: 'warning', text: msg });
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
    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );
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
    if (this.stepperConfiguration.advanceFromStepToEndStep &&
      this.stepperConfiguration.advanceFromStepToEndStep == this.currentStepIndex) {
      this.goToLastStep();
      this.raiseAlert({ type: 'info', text: 'The other steps have been skipped for this mapping type!' });
    } else {
      event.stepper.next();
    }
  }

  private goToLastStep(): void {
    this.stepper.steps.forEach((step, index) => {
      if (index < this.stepper.steps.length - 1) {
        step.completed = true;
      }
    });
    this.updateTemplatesInEditors();
    this.stepper.selectedIndex = this.stepper.steps.length - 1;
  }

  async onBackStep(event: StepperStepChange): Promise<void> {
    this.step = event.step.label;
    this.stepperForward = false;

    if (this.step == 'Test mapping') {
      this.mappingTestingStep.editorTestingRequest.setSchema({});
    } else if (this.step == 'General settings' || this.step == 'Select templates') {
      this.templatesInitialized = false;
    }

    event.stepper.previous();
  }

  private expandTemplates(): void {
    if (this.stepperConfiguration.editorMode === EditorMode.CREATE && !this.templatesInitialized) {
      this.templatesInitialized = true;
      const templates = this.stepperService.expandTemplates(
        this.mapping,
        this.stepperConfiguration.direction,
      );
      this.sourceTemplate = templates.sourceTemplate;
      this.targetTemplate = templates.targetTemplate;
      return;
    }

    const templates = this.stepperService.expandExistingTemplates(
      this.mapping,
      this.stepperConfiguration.direction,
    );
    this.sourceTemplate = templates.sourceTemplate;
    this.targetTemplate = templates.targetTemplate;
  }

  async onSnoopedSourceTemplates(): Promise<void> {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }

    this.sourceTemplate = this.stepperService.parseSnoopedTemplate(
      this.mapping.snoopedTemplates[this.snoopedTemplateCounter]
    );

    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.sourceTemplate = expandExternalTemplate(
        this.sourceTemplate,
        this.mapping, splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
      );
    } else {
      this.sourceTemplate = expandC8YTemplate(this.sourceTemplate, this.mapping);
    }

    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onSelectSnoopedSourceTemplate(event: Event): Promise<void> {
    const index = this.templateForm.get('snoopedTemplateIndex')?.value;
    this.sourceTemplate = this.stepperService.parseSnoopedTemplate(
      this.mapping.snoopedTemplates[index]
    );

    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.sourceTemplate = expandExternalTemplate(
        this.sourceTemplate,
        this.mapping,
        splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
      );
    } else {
      this.sourceTemplate = expandC8YTemplate(this.sourceTemplate, this.mapping);
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

  onValueCodeChange(value: string): void {
    this.mappingCode = value;
  }

  onSelectCodeTemplate(): void {
    this.mappingCode = this.codeTemplatesDecoded.get(this.templateId).code;
  }

  getCodeTemplateEntries(): { key: string; name: string; type: TemplateType }[] {
    if (!this.codeTemplates) return [];
    return Object.entries(this.codeTemplates)
      .filter(([key, template]) =>
        template.templateType.toString() == `${this.stepperConfiguration.direction.toString()}_${this.mapping?.transformationType.toString()}`
      )
      .map(([key, template]) => ({
        key,
        name: template.name,
        type: template.templateType
      }));
  }

  async onCreateCodeTemplate(): Promise<void> {
    const templateType = `${this.stepperConfiguration.direction.toString()}_${this.mapping?.transformationType.toString()}` as TemplateType;
    const initialState = {
      action: 'CREATE',
      codeTemplate: { name: `New code template - ${templateType}`, templateType }
    };

    const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

    modalRef.content.closeSubject.subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
      if (codeTemplate) {
        const response = await this.stepperService.createCodeTemplate(
          codeTemplate.name,
          codeTemplate.description,
          this.mappingCode,
          this.stepperConfiguration.direction,
          this.mapping.transformationType
        );

        this.codeTemplates = await this.sharedService.getCodeTemplates();

        if (response.status >= 200 && response.status < 300) {
          this.alertService.success(gettext('Added new code template.'));
        } else {
          this.alertService.danger(gettext('Failed to create new code template'));
        }
      }
    });
  }

  async openGenerateSubstitutionDrawer(): Promise<void> {
    this.isGenerateSubstitutionOpen = true;

    const testMapping = _.clone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);

    const drawer = this.bottomDrawerService.openDrawer(AIPromptComponent, {
      initialState: { mapping: testMapping, aiAgent: this.aiAgent }
    });

    try {
      const resultOf = await drawer.instance.result;

      if (isSubstitutionsAsCode(this.mapping)) {
        if (typeof resultOf === 'string' && resultOf.trim()) {
          this.mappingCode = resultOf;
          this.cdr.detectChanges();

          if (this.codeEditor) {
            setTimeout(() => this.codeEditor.writeValue(resultOf), 100);
          }

          this.alertService.success('Generated JavaScript code successfully.');
        } else {
          this.raiseAlert({ type: 'warning', text: 'No valid JavaScript code was generated.' });
        }
      } else {
        if (Array.isArray(resultOf) && resultOf.length > 0) {
          this.alertService.success(`Generated ${resultOf.length} substitutions.`);
          this.mapping.substitutions.splice(0);
          resultOf.forEach(sub => {
            this.substitutionService.addSubstitution(
              sub,
              this.mapping,
              this.stepperConfiguration,
              this.expertMode,
              () => {
                this.stepperService.updateSubstitutionValidity(
                  this.mapping,
                  this.stepperConfiguration.allowNoDefinedIdentifier,
                  this.currentStepIndex,
                  this.stepperConfiguration.showCodeEditor
                );
              }
            );
          });
        } else {
          this.raiseAlert({ type: 'warning', text: 'No substitutions were generated.' });
        }
      }
    } catch (ex) {
      // User canceled or error occurred
    }

    this.isGenerateSubstitutionOpen = false;
  }

  async onSelectSubstitution(selected: number): Promise<void> {
    if (selected < 0 || selected >= this.mapping.substitutions.length) return;

    this.selectedSubstitution = selected;
    this.substitutionModel = {
      ...this.mapping.substitutions[selected],
      stepperConfiguration: this.stepperConfiguration
    };
    this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);

    await Promise.all([
      this.editorSourceStepSubstitution?.setSelectionToPath(this.substitutionModel.pathSource),
      this.editorTargetStepSubstitution?.setSelectionToPath(this.substitutionModel.pathTarget)
    ]);
  }

  onUpdateSubstitutionValidity(): void {
    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );
  }

  private manualResize(source: string): void {
    let element;

    if (source == 'filterModelFilterExpression' && this.filterModelFilterExpression?.nativeElement) {
      element = this.filterModelFilterExpression.nativeElement;
    } else if (source == 'substitutionModelSourceExpression' && this.substitutionModelSourceExpression?.nativeElement) {
      element = this.substitutionModelSourceExpression.nativeElement;
    } else if (source == 'substitutionModelTargetExpression' && this.substitutionModelTargetExpression?.nativeElement) {
      element = this.substitutionModelTargetExpression.nativeElement;
    }

    if (element) {
      element.style.height = '32px';
      element.style.height = element.scrollHeight + 'px';
    }
  }
}