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
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { Location } from '@angular/common';
import { FormControl, FormGroup } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, CoreModule, TabComponent, TabsOutletComponent } from '@c8y/ngx-components';
import { GlobalContextService } from '@c8y/ngx-components/global-context';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ActivatedRoute } from '@angular/router';
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
  Feature,
  isSubstitutionsAsCode,
  TransformationType,
  MappingTypeLabels,
  ContentChanges,
  MappingTypeDescriptions,
  Substitution
} from '../../shared';
import { ValidationError } from '../shared/mapping.model';
import { EditorMode } from '../shared/stepper.model';
import { MappingService } from '../core/mapping.service';
import { MappingEditData } from '../core/mapping-edit.resolver';
import { gettext } from '@c8y/ngx-components/gettext';
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
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-property/mapping-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-substitution/mapping-substitution-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { StepperViewModel, StepperViewModelFactory } from '../stepper-mapping/stepper-view.model';

/**
 * Update event for JSON editors with schema information
 */
interface EditorUpdateEvent {
  schema: any;
  identifier?: string;
}

/**
 * Extended substitution model with UI-specific properties
 */
interface SubstitutionModel extends Partial<Substitution> {
  stepperConfiguration?: StepperConfiguration;
  pathSourceIsExpression?: boolean;
  pathTargetIsExpression?: boolean;
  targetExpression?: { result: string; resultType: string; valid: boolean };
  sourceExpression?: { result: string; resultType: string; valid: boolean };
}

// Tab index constants
const TAB_CONNECTOR = 0;
const TAB_GENERAL_SETTINGS = 1;
const TAB_SELECT_TEMPLATES = 2;
const TAB_DEFINE_SUBSTITUTIONS = 3;
const TAB_TEST_MAPPING = 4;

/**
 * Unified editor component that presents all 5 mapping configuration sections as tabs
 * instead of a sequential stepper. Intended for use when editing a fully-defined mapping.
 */
@Component({
  selector: 'd11r-mapping-unified-editor',
  templateUrl: 'mapping-unified-editor.component.html',
  styleUrls: ['../shared/mapping.style.css', './mapping-unified-editor.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  providers: [MappingStepperService, SubstitutionManagementService],
  imports: [
    CoreModule,
    CommonModule,
    TabComponent,
    TabsOutletComponent,
    EditorComponent,
    PopoverModule,
    MappingStepPropertiesComponent,
    MappingConnectorComponent,
    MappingSubstitutionStepComponent,
    MappingStepTestingComponent,
    JsonEditorComponent
  ]
})
export class MappingUnifiedEditorComponent implements OnInit, OnDestroy {
  mapping!: Mapping;
  stepperConfiguration!: StepperConfiguration;
  deploymentMapEntry!: DeploymentMapEntry;

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

  @ViewChild('editorSourceStepTemplate', { static: false }) editorSourceStepTemplate!: JsonEditorComponent;
  @ViewChild('editorTargetStepTemplate', { static: false }) editorTargetStepTemplate!: JsonEditorComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('editorSourceStepSubstitution', { static: false }) editorSourceStepSubstitution!: JsonEditorComponent;
  @ViewChild('editorTargetStepSubstitution', { static: false }) editorTargetStepSubstitution!: JsonEditorComponent;
  @ViewChild(SubstitutionRendererComponent, { static: false }) substitutionChild!: SubstitutionRendererComponent;
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
  private readonly mappingService = inject(MappingService);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly globalContextService = inject(GlobalContextService);

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

  // Cached properties for c8y-select components
  extensionItems: string[] = [];
  extensionEventItems$: Observable<string[]>;
  snoopedTemplateItems: Array<{ label: string, value: string }> = [];
  codeTemplateItems: Array<{ label: string, value: string }> = [];

  selectedSubstitution = -1;
  snoopedTemplateCounter = -1;
  expertMode = false;
  templatesInitialized = false;
  extensions = new Map<string, Extension>();
  editorOptions?: EditorComponent['editorOptions'];
  currentStepIndex = TAB_CONNECTOR;
  activeTabIndex = TAB_CONNECTOR;

  feature!: Feature;
  serviceConfiguration!: ServiceConfiguration;

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

  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  codeEditorHelp!: string;
  codeEditorLabel!: string;

  private readonly destroy$ = new Subject<void>();

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

  async ngOnInit(): Promise<void> {
    // Hide auto-refresh button – this is an editor page, not a live-data view
    this.globalContextService.register('mapping-unified-editor', { showAutoRefresh: false, showTimeContext: false });

    // Load mapping data resolved by the route
    const editData: MappingEditData = this.route.snapshot.data['mappingEdit'];
    this.mapping = editData.mapping;
    this.stepperConfiguration = editData.stepperConfiguration;
    this.deploymentMapEntry = editData.deploymentMapEntry;

    // Initialize view model from stepper configuration
    this.stepperViewModel = StepperViewModelFactory.create(this.stepperConfiguration);

    this.extensionEventItems$ = this.stepperService.extensionEvents$.pipe(
      map((events: ExtensionEntry[]) => events?.map((event: ExtensionEntry) => event.eventName) || []),
      shareReplay(1)
    );
    this.updateSnoopedTemplateItems();

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

    const aiResult = await this.stepperService.checkAIAgentDeployment(this.mapping, this.serviceConfiguration);
    this.aiAgent = aiResult.aiAgent;
    this.aiAgentDeployed = aiResult.aiAgentDeployed;

    this.initializeFormlyFields();
    await this.initializeCodeTemplates();

    this.codeEditorHelp = this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE
      ? 'JavaScript for creating substitutions...'
      : 'JavaScript for creating complete payloads as Smart Functions.';

    this.codeEditorLabel = this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE
      ? 'JavaScript callback for creating substitutions'
      : 'JavaScript callback for Smart functions';

    // For the unified editor, expand existing templates upfront since mapping is fully defined
    await this.initializeTemplates();
  }

  private async initializeTemplates(): Promise<void> {
    // Load extensions needed for the template display
    this.extensions = await this.stepperService.loadExtensions(this.mapping);
    this.updateExtensionItems();

    // Load filter model
    this.filterModel['filterMapping'] = this.mapping.filterMapping;
    if (this.mapping.filterMapping) {
      await this.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    // Load code if present
    if (this.mapping.code) {
      this.mappingCode = base64ToString(this.mapping.code);
    }

    // Expand existing templates (mapping is fully defined)
    const templates = this.stepperService.expandExistingTemplates(
      this.mapping,
      this.stepperConfiguration.direction,
      this.stepperConfiguration.allowTemplateExpansion
    );
    this.sourceTemplate = templates.sourceTemplate;
    this.targetTemplate = templates.targetTemplate;
    this.templatesInitialized = true;

    // Re-patch form values for extension selects if extension is selected
    if (this.mapping?.extension?.extensionName) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName
        });
        this.cdr.markForCheck();
      });
    }

    // Validate substitutions with initial tab index
    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );
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

  ngOnDestroy(): void {
    this.globalContextService.unregister('mapping-unified-editor');
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

    this.templateForm.get('extensionName')?.valueChanges
      .pipe(distinctUntilChanged(), debounceTime(100), takeUntil(this.destroy$))
      .subscribe(selected => {
        const extensionName = typeof selected === 'string' ? selected : selected?.value ?? selected;
        if (extensionName) {
          this.onSelectExtensionName(extensionName);
        }
      });

    this.templateForm.get('eventName')?.valueChanges
      .pipe(distinctUntilChanged(), debounceTime(100), takeUntil(this.destroy$))
      .subscribe(selected => {
        const eventName = typeof selected === 'string' ? selected : selected?.value ?? selected;
        if (eventName) {
          this.onSelectExtensionEvent(eventName);
        }
      });

    this.templateForm.get('snoopedTemplateIndex')?.valueChanges
      .pipe(distinctUntilChanged(), debounceTime(100), takeUntil(this.destroy$))
      .subscribe(selected => {
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

  /**
   * Returns true if the tab at the given index should be visible.
   * Tabs between advanceFromStepToEndStep (exclusive) and the last tab (exclusive) are hidden.
   * The last tab (Testing) is always visible.
   */
  isTabVisible(tabIndex: number): boolean {
    const skip = this.stepperConfiguration?.advanceFromStepToEndStep;
    if (skip == null) return true;
    return tabIndex <= skip || tabIndex === TAB_TEST_MAPPING;
  }

  /**
   * Handles tab selection. Syncs templates when leaving the Templates tab,
   * and triggers appropriate handlers for the newly selected tab.
   */
  async onTabSelected(newIndex: number): Promise<void> {
    // Sync template changes when leaving the Templates tab
    if (this.activeTabIndex === TAB_SELECT_TEMPLATES) {
      this.updateTemplatesInEditors();
    }

    this.activeTabIndex = newIndex;
    this.currentStepIndex = newIndex;

    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );

    switch (newIndex) {
      case TAB_GENERAL_SETTINGS:
        await this.handleGeneralSettingsTab();
        break;
      case TAB_SELECT_TEMPLATES:
        await this.handleSelectTemplatesTab();
        break;
      case TAB_DEFINE_SUBSTITUTIONS:
        this.handleDefineSubstitutionsTab();
        break;
      case TAB_TEST_MAPPING:
        this.handleTestMappingTab();
        break;
    }
  }

  private async handleGeneralSettingsTab(): Promise<void> {
    this.templateModel.mapping = this.mapping;
    this.extensions = await this.stepperService.loadExtensions(this.mapping);
    this.updateExtensionItems();

    if (this.mapping?.extension?.extensionName) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
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

  private async handleSelectTemplatesTab(): Promise<void> {
    this.filterModel['filterMapping'] = this.mapping.filterMapping;
    if (this.mapping.filterMapping) {
      await this.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    if (this.mapping.code) {
      this.mappingCode = base64ToString(this.mapping.code);
    }

    this.updateSnoopedTemplateItems();

    if (this.mapping?.extension?.extensionName && this.extensions) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName
        });
        this.cdr.markForCheck();
      });
    }
  }

  private handleDefineSubstitutionsTab(): void {
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

  private handleTestMappingTab(): void {
    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    if (this.mapping.code || this.mappingCode) {
      testMapping.code = stringToBase64(this.mappingCode);
    }
    this.updateTestingTemplate.emit(testMapping);
  }

  private updateTemplatesInEditors(): void {
    this.sourceTemplate = this.sourceTemplateUpdated ? this.sourceTemplateUpdated : this.sourceTemplate;
    this.targetTemplate = this.targetTemplateUpdated ? this.targetTemplateUpdated : this.targetTemplate;
    this.editorSourceStepSubstitution?.set(this.sourceTemplate);
    this.editorTargetStepSubstitution?.set(this.targetTemplate);
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
    const { updatedContent } = contentChanges;

    let updatedContentAsJson;

    if ('text' in updatedContent && updatedContent['text']) {
      try {
        updatedContentAsJson = JSON.parse(updatedContent['text']);
      } catch (error) {
        this.sourceTemplateUpdated = updatedContent;
        this.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedContentAsJson = updatedContent['json'];
    }

    this.sourceTemplateUpdated = updatedContentAsJson;

    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion && !validateProtectedFields(
      this.sourceTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

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
    const { updatedContent } = contentChanges;

    let updatedContentAsJson;

    if ('text' in updatedContent && updatedContent['text']) {
      try {
        updatedContentAsJson = JSON.parse(updatedContent['text']);
      } catch (error) {
        this.targetTemplateUpdated = updatedContent;
        this.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedContentAsJson = updatedContent['json'];
    }

    this.targetTemplateUpdated = updatedContentAsJson;

    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion && !validateProtectedFields(
      this.targetTemplate,
      updatedContentAsJson
    );

    const isTransformationTypeValid = checkTransformationType(
      this.mapping.transformationType,
      updatedContentAsJson
    );

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
    // Sync any pending template edits before saving
    this.updateTemplatesInEditors();

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
      return;
    }

    this.mapping.lastUpdate = Date.now();
    try {
      await this.mappingService.updateMapping(this.mapping);
      this.alertService.success(gettext(`Mapping ${this.mapping.name} updated successfully`));
    } catch (error) {
      this.alertService.danger(gettext(`Failed to update mapping ${this.mapping.name}: `) + error.message);
      return;
    }

    try {
      await this.mappingService.updateDefinedDeploymentMapEntry(this.deploymentMapEntry);
    } catch (error) {
      this.alertService.danger(gettext('Failed to update connector assignments: ') + error.message);
    }

    this.location.back();
  }

  onCancel(): void {
    this.location.back();
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

  onSelectExtensionName(extensionName: string): void {
    if (!this.mapping.extension) {
      this.mapping.extension = {} as any;
    }
    this.mapping.extension.extensionName = extensionName;
    this.stepperService.selectExtensionName(extensionName, this.extensions, this.mapping);
  }

  onSelectExtensionEvent(extensionEvent: string): void {
    if (!this.mapping.extension) {
      this.mapping.extension = {} as any;
    }
    this.mapping.extension.eventName = extensionEvent;

    if (this.mapping.extension.extensionName && this.extensions) {
      const extension = this.extensions.get(this.mapping.extension.extensionName);
      if (extension && extension.extensionEntries) {
        const eventEntry = Object.values(extension.extensionEntries as Map<string, ExtensionEntry>)
          .find(entry => entry.eventName === extensionEvent);

        if (eventEntry) {
          this.mapping.extension.extensionType = eventEntry.extensionType;
          this.mapping.extension.direction = eventEntry.direction;
          this.mapping.extension.fqnClassName = eventEntry.fqnClassName;
          this.mapping.extension.loaded = eventEntry.loaded;
          this.mapping.extension.message = eventEntry.message;
        }
      }
    }
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

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    const isDisabled = !this.deploymentMapEntry?.connectors || this.deploymentMapEntry?.connectors?.length === 0;
    queueMicrotask(() => {
      this.isButtonDisabled$.next(isDisabled);
      this.cdr.markForCheck();
    });
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
      this.updateCodeTemplateItems();
      return;
    }
    const expectedType = `${this.stepperConfiguration.direction.toString()}_${this.mapping?.transformationType.toString()}`;
    this.codeTemplateEntries = Object.entries(this.codeTemplates)
      .filter(([key, template]) => template.templateType.toString() === expectedType)
      .map(([key, template]) => ({
        key,
        name: template.name,
        type: template.templateType
      }));
    this.updateCodeTemplateItems();
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

  async onSelectSnoopedSourceTemplate(event: Event): Promise<void> {
    const selected = this.templateForm.get('snoopedTemplateIndex')?.value;
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
