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
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, C8yStepper, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { debounceTime, distinctUntilChanged, map, Observable, shareReplay, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  API,
  COLOR_HIGHLIGHTED,
  DeploymentMapEntry,
  Direction,
  Extension,
  ExtensionEntry,
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
  Substitution
} from '../../shared';
import { ValidationError } from '../shared/mapping.model';
import { EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
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
import { StepperViewModel, StepperViewModelFactory } from './stepper-view.model';

/**
 * Update event for JSON editors with schema information
 */
interface EditorUpdateEvent {
  schema: any;
  identifier?: string;
}

/**
 * Extended substitution model used in the stepper with UI-specific properties
 */
interface SubstitutionModel extends Partial<Substitution> {
  stepperConfiguration?: StepperConfiguration;
  pathSourceIsExpression?: boolean;
  pathTargetIsExpression?: boolean;
  targetExpression?: { result: string; resultType: string; valid: boolean };
  sourceExpression?: { result: string; resultType: string; valid: boolean };
}

const STEP_LABEL_TEST_MAPPING = 'Test mapping';
const STEP_LABEL_GENERAL_SETTINGS = 'General settings';
const STEP_LABEL_SELECT_TEMPLATES = 'Select templates';

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
  imports: [CoreModule, CommonModule, EditorComponent, PopoverModule, MappingStepPropertiesComponent, MappingConnectorComponent, MappingSubstitutionStepComponent, MappingStepTestingComponent, JsonEditorComponent]
})
export class MappingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() deploymentMapEntry!: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<Mapping>();

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

  @ViewChild('editorSourceStepTemplate', { static: false }) editorSourceStepTemplate!: JsonEditorComponent;
  @ViewChild('editorTargetStepTemplate', { static: false }) editorTargetStepTemplate!: JsonEditorComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('editorSourceStepSubstitution', { static: false }) editorSourceStepSubstitution!: JsonEditorComponent;
  @ViewChild('editorTargetStepSubstitution', { static: false }) editorTargetStepSubstitution!: JsonEditorComponent;
  @ViewChild(SubstitutionRendererComponent, { static: false }) substitutionChild!: SubstitutionRendererComponent;
  @ViewChild('stepper', { static: false }) stepper!: C8yStepper;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;
  @ViewChild('filterModelFilterExpression') filterModelFilterExpression!: ElementRef<HTMLTextAreaElement>;
  @ViewChild('substitutionModelSourceExpression') substitutionModelSourceExpression!: ElementRef<HTMLTextAreaElement>;
  @ViewChild('substitutionModelTargetExpression') substitutionModelTargetExpression!: ElementRef<HTMLTextAreaElement>;

  private readonly cdr = inject(ChangeDetectorRef);
  private readonly bsModalService = inject(BsModalService);
  private readonly sharedService = inject(SharedService);
  private readonly alertService = inject(AlertService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly substitutionService = inject(SubstitutionManagementService);

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

  updateTestingTemplate = new EventEmitter<Mapping>();
  updateSourceEditor = new EventEmitter<EditorUpdateEvent>();
  updateTargetEditor = new EventEmitter<EditorUpdateEvent>();

  templateForm!: FormGroup;
  templateModel: { stepperConfiguration?: StepperConfiguration; mapping?: Mapping } = {};
  substitutionFormly = new FormGroup({});
  filterFormly = new FormGroup({});
  filterFormlyFields!: FormlyFieldConfig[];
  filterModel: {
    filterMapping?: string;
    filterExpression?: { result: string; resultType: string; valid: boolean };
  } = {};
  selectedPathFilterFilterMapping?: string;
  substitutionModel: SubstitutionModel = {};
  propertyFormly = new FormGroup({});
  codeFormly = new FormGroup({});
  isGenerateSubstitutionOpen = false;

  codeTemplateDecoded?: CodeTemplate;
  codeTemplatesDecoded = new Map<string, CodeTemplate>();
  codeTemplates?: CodeTemplateMap;
  codeTemplateEntries: { key: string; name: string; type: TemplateType }[] = [];
  mappingCode?: string;
  templateId?: TemplateType;

  sourceTemplate?: any;
  sourceTemplateUpdated?: any;
  sourceSystem!: string;
  targetTemplate?: any;
  targetTemplateUpdated?: any;
  targetSystem!: string;
  aiAgentDeployed = false;
  aiAgent: AgentObjectDefinition | AgentTextDefinition | null = null;

  // Use service observables
  get countDeviceIdentifiers$() { return this.stepperService.countDeviceIdentifiers$; }
  get isSubstitutionValid$() { return this.stepperService.isSubstitutionValid$; }
  get isContentChangeValid$() { return this.stepperService.isContentChangeValid$; }
  get extensionEvents$() { return this.stepperService.extensionEvents$; }
  get isButtonDisabled$() { return this.stepperService.isButtonDisabled$; }
  get sourceCustomMessage$() { return this.stepperService.sourceCustomMessage$; }
  get targetCustomMessage$() { return this.stepperService.targetCustomMessage$; }

  // Cached properties for c8y-select components (to avoid recreating arrays on every change detection)
  extensionItems: string[] = [];
  extensionEventItems$: Observable<string[]>;
  snoopedTemplateItems: Array<{label: string, value: string}> = [];
  codeTemplateItems: Array<{label: string, value: string}> = [];

  private updateExtensionItems(): void {
    this.extensionItems = Array.from(this.extensions.keys());
  }

  private updateSnoopedTemplateItems(): void {
    if (!this.mapping?.snoopedTemplates) {
      this.snoopedTemplateItems = [];
      return;
    }
    this.snoopedTemplateItems = Array.from({ length: this.mapping.snoopedTemplates.length }, (_, i) => ({
      label: `Template - ${i}`,
      value: String(i)
    }));
  }

  private updateCodeTemplateItems(): void {
    this.codeTemplateItems = this.codeTemplateEntries.map(item => ({
      label: `${item.name.charAt(0).toUpperCase() + item.name.slice(1)} (${item.type})`,
      value: item.key
    }));
  }

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

  selectedSubstitution = -1;
  snoopedTemplateCounter = -1;
  step?: string;
  expertMode = false;
  templatesInitialized = false;
  extensions = new Map<string, Extension>();
  editorOptions?: EditorComponent['editorOptions'];
  stepperForward = true;
  currentStepIndex!: number;

  private readonly destroy$ = new Subject<void>();
  codeEditorHelp!: string;
  codeEditorLabel!: string;
  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  feature!: Feature;
  serviceConfiguration!: ServiceConfiguration;

  async ngOnInit(): Promise<void> {
    // Initialize view model from stepper configuration
    this.stepperViewModel = StepperViewModelFactory.create(this.stepperConfiguration);

    // Initialize cached arrays for c8y-select
    this.extensionEventItems$ = this.stepperService.extensionEvents$.pipe(
      map((events: ExtensionEntry[]) => events?.map((event: ExtensionEntry) => event.eventName) || []),
      shareReplay(1)
    );
    this.updateSnoopedTemplateItems();

    if (this.mapping.snoopStatus === SnoopStatus.NONE || this.mapping.snoopStatus === SnoopStatus.STOPPED) {
      this.labels = { ...this.labels, custom: 'Start snooping' } as const;
    }

    this.targetSystem = this.mapping.direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = this.mapping.direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    this.templateModel = {
      stepperConfiguration: this.stepperConfiguration,
      mapping: this.mapping
    };

    this.editorOptions = {
      minimap: { enabled: true },
      language: 'javascript',
      renderWhitespace: 'none',
      tabSize: 4,
      readOnly: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
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

    this.codeEditorHelp = this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE  ?
      'JavaScript for creating substitutions...' :
      'JavaScript for creating complete payloads as Smart Functions.';

    this.codeEditorLabel = this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE ?
      'JavaScript callback for creating substitutions' :
      'JavaScript callback for Smart functions';
  }

  private initializeFormlyFields(): void {
    this.filterFormlyFields = [
      {
        fieldGroup: [
          {
            key: 'filterMapping',
            type: 'd11r-input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Filter execution mapping',
              class: 'input-sm',
              disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY ||
                !this.stepperConfiguration.allowDefiningSubstitutions ||
                (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              placeholder: '$exists(c8y_TemperatureMeasurement)',
              description: 'This expression is required...',
              required: this.mapping.direction === Direction.OUTBOUND,
              customMessage: this.sourceCustomMessage$
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  debounceTime(500),
                  distinctUntilChanged()
                ).pipe(takeUntil(this.destroy$)).subscribe(path => this.updateFilterExpressionResult(path));
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
    this.updateCodeTemplateEntries();
  }

  ngAfterViewInit(): void {
    // Monaco is now loaded in ngOnInit
  }

  ngOnDestroy(): void {
    this.stepperService.cleanup();
    this.destroy$.next();
    this.destroy$.complete();
  }

  private setTemplateForm(): void {
    this.templateForm = new FormGroup({
      extensionName: new FormControl({
        value: this.mapping?.extension?.extensionName,
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }),
      eventName: new FormControl({
        value: this.mapping?.extension?.eventName,
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }),
      snoopedTemplateIndex: new FormControl({
        value: '-1',
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

    // Master-Detail: Subscribe to extension name changes to update available events
    this.templateForm.get('extensionName')?.valueChanges
      .pipe(
        distinctUntilChanged(),
        debounceTime(100),
        takeUntil(this.destroy$)
      )
      .subscribe(selected => {
        // When using simple string arrays, c8y-select binds the string directly
        const extensionName = typeof selected === 'string' ? selected : selected?.value ?? selected;
        if (extensionName) {
          this.onSelectExtensionName(extensionName);
        }
      });

    // Subscribe to event name changes to update mapping
    this.templateForm.get('eventName')?.valueChanges
      .pipe(
        distinctUntilChanged(),
        debounceTime(100),
        takeUntil(this.destroy$)
      )
      .subscribe(selected => {
        // When using simple string arrays, c8y-select binds the string directly
        const eventName = typeof selected === 'string' ? selected : selected?.value ?? selected;
        if (eventName) {
          this.onSelectExtensionEvent(eventName);
        }
      });

    // Subscribe to snooped template selection changes
    this.templateForm.get('snoopedTemplateIndex')?.valueChanges
      .pipe(
        distinctUntilChanged(),
        debounceTime(100),
        takeUntil(this.destroy$)
      )
      .subscribe(selected => {
        // c8y-select with labelProperty binds the entire object {label, value}
        const index = selected?.value ?? selected;
        if (index !== null && index !== undefined && index !== '-1') {
          this.onSelectSnoopedSourceTemplate(null as any);
        }
      });

    this.isSubstitutionValid$.pipe(takeUntil(this.destroy$)).subscribe(valid => {
      if (valid) {
        this.templateForm.setErrors(null);
      } else {
        this.templateForm.setErrors({ 'incorrect': true });
      }
    });
  }

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    const isDisabled = !this.deploymentMapEntry?.connectors || this.deploymentMapEntry?.connectors?.length === 0;
    // Use queueMicrotask for change detection cycle completion
    queueMicrotask(() => {
      this.isButtonDisabled$.next(isDisabled);
      this.cdr.markForCheck();
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
    // Use queueMicrotask for DOM update cycle completion
    queueMicrotask(() => {
      this.manualResize('filterModelFilterExpression');
      this.cdr.markForCheck();
    });
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

  onTestingSourceTemplateChanged(template: any): void {
    this.sourceTemplate = template;
  }

  onSourceTemplateChanged(contentChanges: ContentChanges): void {
    const { previousContent, updatedContent } = contentChanges;

    // Always allow the change during editing
    let updatedContentAsJson;

    if ('text' in updatedContent && updatedContent['text']) {
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
    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion && !validateProtectedFields(
      this.sourceTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

    // Consider both validations
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges && this.stepperConfiguration.allowTemplateExpansion) {
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

    if ('text' in updatedContent && updatedContent['text']) {
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
    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion && !validateProtectedFields(
      this.targetTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

    // Consider both validations
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges && this.stepperConfiguration.allowTemplateExpansion) {
      this.raiseAlert({
        type: 'warning',
        text: "Warning: Changes to _IDENTITY_, _TOPIC_LEVEL_, or _CONTEXT_DATA_ will be reverted when saving."
      });
    }
  }



  raiseAlert(alert: Alert): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info') this.alertService.remove(a);
    });
    this.alertService.add(alert);
  }

  clearAlerts(): void {
    this.alertService.clearAll();
  }

  async onCommitButton(): Promise<void> {
    if (this.stepperConfiguration.allowTemplateExpansion) {
      this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
      this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);
    } else {
      this.mapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
      this.mapping.targetTemplate = JSON.stringify(this.targetTemplate);
    }

    if (this.mapping.code || this.mappingCode) {
      this.mapping.code = stringToBase64(this.mappingCode);
    }

    if (isSubstitutionsAsCode(this.mapping) && (!this.mapping.code || this.mapping.code === null || this.mapping.code === '')) {
      this.raiseAlert({ type: 'warning', text: "Internal error in editor. Try again!" });
      this.commit.emit();
    }

    this.commit.emit(this.mapping);
  }

  async onSampleTargetTemplatesButton(): Promise<void> {
    if (this.stepperConfiguration.direction === Direction.INBOUND) {
      const template = JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]);
      this.targetTemplate = this.stepperConfiguration.allowTemplateExpansion
        ? expandC8YTemplate(template, this.mapping)
        : template;
    } else {
      const levels: string[] = splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false);
      const template = JSON.parse(getExternalTemplate(this.mapping));
      this.targetTemplate = this.stepperConfiguration.allowTemplateExpansion
        ? expandExternalTemplate(template, this.mapping, levels)
        : template;
    }
    this.editorTargetStepTemplate.set(this.targetTemplate);
  }

  async onCancelButton(): Promise<void> {
    this.cancel.emit();
  }

  onSelectExtensionName(extensionName: string): void {
    // console.log('===== onSelectExtensionName COMPONENT DEBUG =====');
    // console.log('Selected extension name:', extensionName);
    // console.log('Current mapping:', this.mapping);
    // console.log('Current extensions:', this.extensions);
    // console.log('Mapping direction:', this.mapping.direction);
    // console.log('Mapping transformation type:', this.mapping.transformationType);

    // Initialize extension object if it doesn't exist
    if (!this.mapping.extension) {
      this.mapping.extension = {} as any;
    }

    this.mapping.extension.extensionName = extensionName;
    this.stepperService.selectExtensionName(extensionName, this.extensions, this.mapping);

    // console.log('===== onSelectExtensionName COMPONENT DEBUG END =====');
  }

  onSelectExtensionEvent(extensionEvent: string): void {
    // Initialize extension object if it doesn't exist
    if (!this.mapping.extension) {
      this.mapping.extension = {} as any;
    }

    this.mapping.extension.eventName = extensionEvent;

    // Look up the full extension entry to populate extensionType and other properties
    if (this.mapping.extension.extensionName && this.extensions) {
      const extension = this.extensions.get(this.mapping.extension.extensionName);
      if (extension && extension.extensionEntries) {
        // Find the matching event entry
        const eventEntry = Object.values(extension.extensionEntries as Map<string, ExtensionEntry>)
          .find(entry => entry.eventName === extensionEvent);

        if (eventEntry) {
          // Copy all properties from the extension entry
          this.mapping.extension.extensionType = eventEntry.extensionType;
          this.mapping.extension.direction = eventEntry.direction;
          this.mapping.extension.fqnClassName = eventEntry.fqnClassName;
          this.mapping.extension.loaded = eventEntry.loaded;
          this.mapping.extension.message = eventEntry.message;
        }
      }
    }
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
    this.updateExtensionItems(); // Update cached extension items

    // Re-patch form values after items are loaded so c8y-select can match them
    if (this.mapping?.extension?.extensionName) {
      // First, load the extension events for this extension
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );

      // Use queueMicrotask to ensure items are rendered before setting values
      // This allows c8y-select to properly detect and display the selected values
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName
        });
        this.cdr.markForCheck();
      });

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

    // Update snooped template items in case new templates were added
    this.updateSnoopedTemplateItems();

    // Trigger extension event filtering if extension is already selected
    // This handles the case when navigating to step 3 with a pre-selected extension
    if (this.mapping?.extension?.extensionName && this.extensions) {
      // console.log('===== handleSelectTemplatesStep: Triggering selectExtensionName =====');
      // console.log('Extension name from mapping:', this.mapping.extension.extensionName);
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );

      // Patch form values to ensure c8y-select components display the selected values
      // Use queueMicrotask to ensure the components are rendered before setting values
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName
        });
        this.cdr.markForCheck();
      });
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

    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    this.updateTestingTemplate.emit(testMapping);
  }

  private handleTestMappingStep(): void {
    if (this.mapping.code || this.mappingCode) {
      const testMapping = structuredClone(this.mapping);
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
    if (this.stepperConfiguration.advanceFromStepToEndStep != null &&
      this.stepperConfiguration.advanceFromStepToEndStep === this.currentStepIndex) {
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

    if (this.step === STEP_LABEL_TEST_MAPPING) {
      this.mappingTestingStep.editorTestingRequest.setSchema({});
    } else if (this.step === STEP_LABEL_GENERAL_SETTINGS || this.step === STEP_LABEL_SELECT_TEMPLATES) {
      this.templatesInitialized = false;
    }

    // When steps were skipped via advanceFromStepToEndStep, jump back to that step
    // instead of landing on the first skipped step (e.g. "Define substitutions")
    if (this.stepperConfiguration.advanceFromStepToEndStep != null &&
        event.stepper.selectedIndex === event.stepper.steps.length - 1) {
      event.stepper.steps.forEach((step, index) => {
        if (index > this.stepperConfiguration.advanceFromStepToEndStep) {
          step.completed = false;
        }
      });
      event.stepper.selectedIndex = this.stepperConfiguration.advanceFromStepToEndStep;
    } else {
      event.stepper.previous();
    }
  }

  private expandTemplates(): void {
    if (this.stepperConfiguration.editorMode === EditorMode.CREATE && !this.templatesInitialized) {
      this.templatesInitialized = true;
      const templates = this.stepperService.expandTemplates(
        this.mapping,
        this.stepperConfiguration.direction,
        this.stepperConfiguration.allowTemplateExpansion
      );
      this.sourceTemplate = templates.sourceTemplate;
      this.targetTemplate = templates.targetTemplate;
      return;
    }

    const templates = this.stepperService.expandExistingTemplates(
      this.mapping,
      this.stepperConfiguration.direction,
      this.stepperConfiguration.allowTemplateExpansion
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

    if (this.stepperConfiguration.allowTemplateExpansion) {
      if (this.stepperConfiguration.direction === Direction.INBOUND) {
        this.sourceTemplate = expandExternalTemplate(
          this.sourceTemplate,
          this.mapping, splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
        );
      } else {
        this.sourceTemplate = expandC8YTemplate(this.sourceTemplate, this.mapping);
      }
    }

    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onSelectSnoopedSourceTemplate(event: Event): Promise<void> {
    const selected = this.templateForm.get('snoopedTemplateIndex')?.value;
    // c8y-select with labelProperty binds the entire object {label, value}
    const indexValue = selected?.value ?? selected;
    const index = typeof indexValue === 'string' ? parseInt(indexValue, 10) : indexValue;
    this.sourceTemplate = this.stepperService.parseSnoopedTemplate(
      this.mapping.snoopedTemplates[index]
    );

    if (this.stepperConfiguration.allowTemplateExpansion) {
      if (this.stepperConfiguration.direction === Direction.INBOUND) {
        this.sourceTemplate = expandExternalTemplate(
          this.sourceTemplate,
          this.mapping,
          splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false)
        );
      } else {
        this.sourceTemplate = expandC8YTemplate(this.sourceTemplate, this.mapping);
      }
    }

    this.mapping.snoopStatus = SnoopStatus.STOPPED;
  }

  async onTargetAPIChanged(changedTargetAPI: string): Promise<void> {
    if (this.stepperConfiguration.direction === Direction.INBOUND) {
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
    const template = this.codeTemplatesDecoded.get(this.templateId);
    if (template) {
      this.mappingCode = template.code;
    }
  }

  private updateCodeTemplateEntries(): void {
    if (!this.codeTemplates) {
      this.codeTemplateEntries = [];
      this.updateCodeTemplateItems(); // Update cached items
      return;
    }
    const expectedType = `${this.stepperConfiguration.direction.toString()}_${this.mapping?.transformationType.toString()}`;
    this.codeTemplateEntries = Object.entries(this.codeTemplates)
      .filter(([key, template]) =>
        template.templateType.toString() === expectedType
      )
      .map(([key, template]) => ({
        key,
        name: template.name,
        type: template.templateType
      }));
    this.updateCodeTemplateItems(); // Update cached items
  }

  async onCreateCodeTemplate(): Promise<void> {
    const templateType = `${this.stepperConfiguration.direction.toString()}_${this.mapping?.transformationType.toString()}` as TemplateType;
    const initialState = {
      action: 'CREATE',
      codeTemplate: { name: `New code template - ${templateType}`, templateType }
    };

    const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

    modalRef.content.closeSubject.pipe(takeUntil(this.destroy$)).subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
      if (codeTemplate) {
        const response = await this.stepperService.createCodeTemplate(
          codeTemplate.name,
          codeTemplate.description,
          this.mappingCode,
          this.stepperConfiguration.direction,
          this.mapping.transformationType
        );

        this.codeTemplates = await this.sharedService.getCodeTemplates();
        this.updateCodeTemplateEntries();

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

    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);

    const drawer = this.bottomDrawerService.openDrawer(AIPromptComponent, {
      initialState: { mapping: testMapping, aiAgent: this.aiAgent }
    });

    try {
      const result = await drawer.instance.result;

      if (isSubstitutionsAsCode(this.mapping)) {
        if (typeof result === 'string' && result.trim()) {
          this.mappingCode = result;

          if (this.codeEditor) {
            // Use queueMicrotask for view update cycle completion
            queueMicrotask(() => {
              this.codeEditor.writeValue(result);
              this.cdr.markForCheck();
            });
          }

          this.alertService.success('Generated JavaScript code successfully.');
        } else {
          this.raiseAlert({ type: 'warning', text: 'No valid JavaScript code was generated.' });
        }
      } else {
        if (Array.isArray(result) && result.length > 0) {
          this.alertService.success(`Generated ${result.length} substitutions.`);
          this.mapping.substitutions.splice(0);
          result.forEach(sub => {
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
    } catch (error) {
      console.error('AI generation error:', error);
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

    if (source === 'filterModelFilterExpression' && this.filterModelFilterExpression?.nativeElement) {
      element = this.filterModelFilterExpression.nativeElement;
    } else if (source === 'substitutionModelSourceExpression' && this.substitutionModelSourceExpression?.nativeElement) {
      element = this.substitutionModelSourceExpression.nativeElement;
    } else if (source === 'substitutionModelTargetExpression' && this.substitutionModelTargetExpression?.nativeElement) {
      element = this.substitutionModelTargetExpression.nativeElement;
    }

    if (element) {
      element.style.height = '32px';
      element.style.height = element.scrollHeight + 'px';
    }
  }
}