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
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { Location } from '@angular/common';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, CoreModule, TabComponent, TabsOutletComponent } from '@c8y/ngx-components';
import { GlobalContextService } from '@c8y/ngx-components/global-context';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ActivatedRoute, Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, Observable, ReplaySubject, shareReplay, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  DeploymentMapEntry,
  Direction,
  Extension,
  ExtensionEntry,
  getExternalTemplate,
  getSchema,
  JsonEditorComponent,
  Mapping,
  SAMPLE_TEMPLATES_C8Y,
  SharedService,
  StepperConfiguration,
  Feature,
  isSubstitutionsAsCode,
  TransformationType,
  MappingTypeLabels,
  MappingType
} from '../../shared';
import { createCompletionProviderFlowFunction, EditorMode } from '../shared/stepper.model';
import { MappingService } from '../core/mapping.service';
import { MappingEditData } from '../core/mapping-edit.resolver';
import { gettext } from '@c8y/ngx-components/gettext';
import {
  base64ToString,
  checkTransformationType,
  expandC8YTemplate,
  expandExternalTemplate,
  isCodeOrExtensionTransformation,
  reduceSourceTemplate,
  splitTopicExcludingSeparator,
  stringToBase64,
  stripTemplateMetadataTags,
  validateProtectedFields
} from '../shared/util';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType, toTemplateType } from '../../configuration/shared/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { AIPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { MappingStepTestingComponent } from '../step-testing/mapping-testing.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-property/mapping-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-transformation/mapping-transformation-step.component';
import { MappingTemplateStepComponent } from '../step-template/mapping-template-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { StepperViewModel, StepperViewModelFactory } from '../stepper-mapping/stepper-view.model';
import * as jsYaml from 'js-yaml';

// Tab index constants
const TAB_CONNECTOR = 0;
const TAB_GENERAL_SETTINGS = 1;
const TAB_SELECT_TEMPLATES = 2;
const TAB_DEFINE_TRANSFORMATION = 3;
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
    MappingTemplateStepComponent,
    MappingStepTestingComponent
  ]
})
export class MappingUnifiedEditorComponent implements OnInit, AfterViewInit, OnDestroy {
  mapping!: Mapping;
  stepperConfiguration!: StepperConfiguration;
  deploymentMapEntry!: DeploymentMapEntry;

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

  @ViewChild('templateStep', { static: false }) templateStepRef!: MappingTemplateStepComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;

  private readonly cdr = inject(ChangeDetectorRef);
  private readonly bsModalService = inject(BsModalService);
  private readonly sharedService = inject(SharedService);
  private readonly alertService = inject(AlertService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly substitutionService = inject(SubstitutionManagementService);
  private readonly mappingService = inject(MappingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly globalContextService = inject(GlobalContextService);

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly TransformationType = TransformationType;
  readonly EditorMode = EditorMode;

  updateTestingTemplate = new ReplaySubject<Mapping>(1);
  schemaSource: any;
  schemaTarget: any;

  templateForm!: FormGroup;
  templateModel: { stepperConfiguration?: StepperConfiguration; mapping?: Mapping } = {};
  filterFormly = new FormGroup({});
  filterFormlyFields!: FormlyFieldConfig[];
  propertyFormly = new FormGroup({});
  isGenerateSubstitutionOpen = false;

  codeTemplateDecoded?: CodeTemplate;
  codeTemplatesDecoded = new Map<string, CodeTemplate>();
  codeTemplates?: CodeTemplateMap;
  codeTemplateEntries: { key: string; name: string; type: TemplateType }[] = [];
  mappingCode?: string;
  templateId?: TemplateType;

  sourceTemplate?: any;
  sourceSystem!: string;
  targetTemplate?: any;
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
  extensionEventItems$: Observable<{ label: string; value: string }[]>;
  /** True when the selected extension event has a configuration block defined */
  hasExtensionParameter = false;
  codeTemplateItems: Array<{ label: string, value: string }> = [];

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

  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  codeEditorHelp!: string;
  codeEditorLabel!: string;

  private completionProviderDisposable: any;
  private readonly destroy$ = new Subject<void>();

  // Snapshots for change detection — set once at load time
  private initialMappingJson = '';
  private initialSourceTemplateJson = '';
  private initialTargetTemplateJson = '';
  private initialMappingCode = '';
  private initialDeploymentConnectors = '';

  private updateExtensionItems(): void {
    this.extensionItems = Array.from(this.extensions.keys());
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
    this.initialDeploymentConnectors = JSON.stringify(this.deploymentMapEntry?.connectors ?? []);
    // Initialize the Save-button gate: a mapping requires at least one selected connector
    this.isButtonDisabled$.next(this.isConnectorSelectionEmpty());

    // For EXTENSION_JAVA the transformation is configured in the templates tab
    this.activeTabIndex = this.mapping.mappingType === MappingType.PROTOBUF_INTERNAL || this.mapping.transformationType === TransformationType.EXTENSION_JAVA
      ? TAB_GENERAL_SETTINGS
      : TAB_DEFINE_TRANSFORMATION;
    this.currentStepIndex = this.activeTabIndex;

    // Initialize view model from stepper configuration
    this.stepperViewModel = StepperViewModelFactory.create(this.stepperConfiguration);

    this.extensionEventItems$ = this.stepperService.extensionEvents$.pipe(
      map((events: ExtensionEntry[]) =>
        (events || []).map(e => ({
          label: e.description ? `${e.eventName} — ${e.description}` : e.eventName,
          value: e.eventName
        }))
      ),
      shareReplay(1)
    );

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

    this.setTemplateForm();

    this.feature = await this.sharedService.getFeatures();
    if (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole) {
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

    this.schemaSource = getSchema(this.mapping.targetAPI, this.mapping.direction, false, false);
    this.schemaTarget = getSchema(this.mapping.targetAPI, this.mapping.direction, true, false);
  }

  private async initializeTemplates(): Promise<void> {
    // Load extensions needed for the template display
    this.extensions = await this.stepperService.loadExtensions(this.mapping);
    this.updateExtensionItems();

    // Load filter model – handled by MappingTemplateStepComponent on init
    // when filterFormly changes; no action needed here.

    // Load code if present
    if (this.mapping.code) {
      this.mappingCode = stripTemplateMetadataTags(base64ToString(this.mapping.code));
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

    // Snapshot initial state so we can distinguish connector-only changes from content changes
    this.initialMappingJson = JSON.stringify(this.mapping);
    this.initialSourceTemplateJson = JSON.stringify(this.sourceTemplate);
    this.initialTargetTemplateJson = JSON.stringify(this.targetTemplate);
    this.initialMappingCode = this.mappingCode ?? '';

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
          eventName: this.mapping.extension.eventName,
          extensionParameter: this.configurationToYaml(this.mapping.extension.parameter)
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
              onInit: (_field: FormlyFieldConfig) => {
                // valueChanges is subscribed inside MappingTemplateStepComponent via ngOnChanges
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
    this.registerCompletionProvider();
  }

  ngOnDestroy(): void {
    this.completionProviderDisposable?.dispose();
    this.globalContextService.unregister('mapping-unified-editor');
    this.stepperService.cleanup();
    this.destroy$.next();
    this.destroy$.complete();
  }

  async registerCompletionProvider(): Promise<void> {
    if (this.completionProviderDisposable) {
      this.completionProviderDisposable.dispose();
    }
    const monacoModule = await import('monaco-editor');
    const monaco = (monacoModule as any).default || monacoModule;
    const d1 = createCompletionProviderFlowFunction(monaco, this.mapping.direction);
    this.completionProviderDisposable = { dispose: () => { d1.dispose(); } };
  }

  private setTemplateForm(): void {
    this.templateForm = new FormGroup({
      extensionName: new FormControl({
        value: this.mapping?.extension?.extensionName,
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }, Validators.required),
      eventName: new FormControl({
        value: this.mapping?.extension?.eventName,
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }, Validators.required),
      extensionParameter: new FormControl({
        value: this.configurationToYaml(this.mapping?.extension?.parameter),
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
      }),
      sampleTargetTemplatesButton: new FormControl({
        value: !this.stepperConfiguration.showEditorSource ||
          this.stepperConfiguration.editorMode === EditorMode.READ_ONLY,
        disabled: undefined
      })
    });

    // Subscribe to extension configuration changes
    this.templateForm.get('extensionParameter')?.valueChanges
      .pipe(debounceTime(300), takeUntil(this.destroy$))
      .subscribe(yaml => {
        if (!this.mapping.extension) {
          this.mapping.extension = {} as any;
        }
        this.mapping.extension.parameter = this.yamlToConfiguration(yaml);
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

    this.isSubstitutionValid$.pipe(takeUntil(this.destroy$)).subscribe(valid => {
      if (valid) {
        this.templateForm.setErrors(null);
      } else {
        this.templateForm.setErrors({ 'incorrect': true });
      }
    });

    this.stepperService.mappingPropertyChanged$.pipe(takeUntil(this.destroy$)).subscribe(mapping => {
      if (mapping.direction === Direction.OUTBOUND && this.sourceTemplate) {
        this.sourceTemplate = expandC8YTemplate(this.sourceTemplate, mapping);
      }
    });
  }

  /**
   * Returns true if the tab at the given index should be visible.
   * Tabs between advanceFromStepToEndStep (exclusive) and the last tab (exclusive) are hidden.
   * The last tab (Testing) is always visible.
   */
  isTabVisible(tabIndex: number): boolean {
    // Deprecated SUBSTITUTION_AS_CODE mappings: hide Testing tab (can't be processed)
    // eslint-disable-next-line deprecation/deprecation
    if (this.mapping?.transformationType === TransformationType.SUBSTITUTION_AS_CODE && tabIndex === TAB_TEST_MAPPING) {
      return false;
    }
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
      case TAB_DEFINE_TRANSFORMATION:
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
      // Show config textarea if the mapping already has configuration
      if (this.mapping.extension.parameter) {
        this.hasExtensionParameter = true;
      }
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName,
          extensionParameter: this.configurationToYaml(this.mapping.extension.parameter)
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
    if (this.mapping.filterMapping) {
      await this.templateStepRef?.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    if (this.mapping.code) {
      this.mappingCode = stripTemplateMetadataTags(base64ToString(this.mapping.code));
    }

    if (this.mapping?.extension?.extensionName && this.extensions) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      queueMicrotask(() => {
        this.templateForm.patchValue({
          extensionName: this.mapping.extension.extensionName,
          eventName: this.mapping.extension.eventName,
          extensionParameter: this.configurationToYaml(this.mapping.extension.parameter)
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

    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    this.updateTestingTemplate.next(testMapping);
  }

  private handleTestMappingTab(): void {
    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    if (this.mapping.code || this.mappingCode) {
      testMapping.code = stringToBase64(this.mappingCode);
    }
    this.updateTestingTemplate.next(testMapping);
  }

  private updateTemplatesInEditors(): void {
    if (this.templateStepRef?.sourceTemplateUpdated) {
      this.sourceTemplate = this.templateStepRef.sourceTemplateUpdated;
    }
  }

  onTestingSourceTemplateChanged(template: any): void {
    this.sourceTemplate = template;
  }

  raiseAlert(alert: Alert): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
    });
    this.alertService.add(alert);
  }

  clearAlerts(): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
    });
  }

  async onCommitButton(): Promise<void> {
    // A mapping must be bound to at least one connector
    if (this.isConnectorSelectionEmpty()) {
      this.raiseAlert({ type: 'warning', text: gettext('Select at least one connector before saving.') });
      this.activeTabIndex = TAB_CONNECTOR; // navigate to Connector tab
      return;
    }

    // Validate General Settings form (e.g. mappingTopic required for INBOUND).
    // Belt-and-suspenders: also check the value directly because Formly's group
    // validator strips falsy-keyed errors, so propertyFormly.invalid may be stale.
    if (this.stepperConfiguration.direction === Direction.INBOUND && !this.mapping.mappingTopic?.trim()) {
      this.propertyFormly.get('mappingTopic')?.setErrors({ required: true });
      this.propertyFormly.get('mappingTopic')?.markAsTouched();
      this.activeTabIndex = TAB_GENERAL_SETTINGS;
      return;
    }
    if (this.propertyFormly.invalid) {
      this.propertyFormly.markAllAsTouched();
      this.activeTabIndex = TAB_GENERAL_SETTINGS;
      return;
    }

    // Only validate extensionName/eventName when the user-visible selectors are shown.
    // showExtensionSelectors also covers showInternalExtensionNote (PROTOBUF_INTERNAL) where
    // no selectors are rendered and the form controls are always null.
    if (this.stepperViewModel.showExtensionSelectorsSource || this.stepperViewModel.showExtensionSelectorsTarget) {
      const extensionName = this.templateForm.get('extensionName');
      const eventName = this.templateForm.get('eventName');
      extensionName?.markAsTouched();
      eventName?.markAsTouched();
      if (extensionName?.invalid || eventName?.invalid) {
        this.activeTabIndex = 2; // navigate to Templates tab
        return;
      }
    }

    // Sync any pending template edits before saving
    this.updateTemplatesInEditors();

    // Determine what changed after template sync but before transforms mutate the mapping.
    // Connector-only changes must not create a draft — a draft only tracks content changes.
    const mappingContentChanged = this.stepperConfiguration.editorMode === EditorMode.UPDATE
      ? this.hasMappingContentChanged()
      : true; // CREATE / COPY always persist
    const deploymentChanged =
      JSON.stringify(this.deploymentMapEntry?.connectors ?? []) !== this.initialDeploymentConnectors;

    if (this.stepperConfiguration.allowTemplateExpansion) {
      this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
      this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);
    } else {
      this.mapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
      this.mapping.targetTemplate = JSON.stringify(this.targetTemplate);
    }

    if (this.mapping.code || this.mappingCode) {
      this.mapping.code = stringToBase64(stripTemplateMetadataTags(this.mappingCode));
    }

    if (isSubstitutionsAsCode(this.mapping) && (!this.mapping.code || this.mapping.code === null || this.mapping.code === '')) {
      this.raiseAlert({ type: 'warning', text: "Internal error in editor. Try again!" });
      return;
    }

    // Do NOT stamp lastUpdate here: for a draft save it is the optimistic-concurrency
    // token that must be echoed back unchanged (the server assigns a fresh one on save).
    try {
      if (this.stepperConfiguration.editorMode === EditorMode.UPDATE) {
        if (mappingContentChanged) {
          // Edits are saved to the line's draft; the running configuration is unchanged
          // until the draft is published as a version and that version is activated.
          await this.mappingService.saveDraft(this.mapping.id, this.mapping);
          this.mappingService.refreshMappings(this.stepperConfiguration.direction);
        }
      } else {
        await this.mappingService.createMapping(this.mapping);
        this.mappingService.refreshMappings(this.stepperConfiguration.direction);
        this.alertService.success(gettext(`Mapping ${this.mapping.name} created successfully`));
      }
    } catch (error) {
      this.alertService.danger(gettext(`Failed to save mapping ${this.mapping.name}: `) + error.message);
      return;
    }

    if (deploymentChanged || this.stepperConfiguration.editorMode !== EditorMode.UPDATE) {
      try {
        await this.mappingService.updateDefinedDeploymentMapEntry(this.deploymentMapEntry);
      } catch (error) {
        this.alertService.danger(gettext('Failed to update connector assignments: ') + error.message);
      }
    }

    if (this.stepperConfiguration.editorMode === EditorMode.UPDATE) {
      if (mappingContentChanged && deploymentChanged) {
        this.alertService.success(
          gettext(`Saved draft and connector assignments for ${this.mapping.name}. Publish and activate it (Versions) to apply the changes.`)
        );
      } else if (mappingContentChanged) {
        this.alertService.success(
          gettext(`Saved draft for ${this.mapping.name}. Publish and activate it (Versions) to apply the changes.`)
        );
      } else if (deploymentChanged) {
        this.alertService.success(gettext(`Connector assignments for ${this.mapping.name} saved.`));
      }
    }

    this.navigateToGrid();
  }

  onCancel(): void {
    this.navigateToGrid();
  }

  private navigateToGrid(): void {
    const gridUrl = this.router.url.replace(/\/edit\/[^\/]+$/, '');
    this.router.navigateByUrl(gridUrl);
  }

  async onSampleTargetTemplatesButton(): Promise<void> {
    if (this.stepperConfiguration.direction === Direction.INBOUND) {
      if (isCodeOrExtensionTransformation(this.mapping.transformationType)) {
        this.targetTemplate = {};
      } else {
        const template = JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]);
        this.targetTemplate = this.stepperConfiguration.allowTemplateExpansion
          ? expandC8YTemplate(template, this.mapping)
          : template;
      }
    } else {
      const levels: string[] = splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false);
      const template = JSON.parse(getExternalTemplate(this.mapping));
      this.targetTemplate = this.stepperConfiguration.allowTemplateExpansion
        ? expandExternalTemplate(template, this.mapping, levels)
        : template;
    }
    this.templateStepRef?.editorTargetStepTemplate?.set(this.targetTemplate);
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
          // Show parameter textarea only if the extension definition has a parameter block
          this.hasExtensionParameter = !!eventEntry.parameter;
          // Pre-fill parameter from the extension definition if not already set
          if (!this.mapping.extension.parameter && eventEntry.parameter) {
            this.mapping.extension.parameter = eventEntry.parameter;
            this.templateForm.get('extensionParameter')?.setValue(
              this.configurationToYaml(eventEntry.parameter), { emitEvent: false });
          }
        }
      }
    }
  }

  configurationToYaml(configuration: Record<string, any> | undefined): string {
    if (!configuration) {
      return '';
    }
    try {
      return jsYaml.dump(configuration, { indent: 2 });
    } catch {
      return '';
    }
  }

  yamlToConfiguration(yaml: string): Record<string, any> | undefined {
    if (!yaml?.trim()) {
      return undefined;
    }
    try {
      const parsed = jsYaml.load(yaml);
      return (parsed && typeof parsed === 'object') ? parsed as Record<string, any> : undefined;
    } catch {
      return undefined;
    }
  }

  async onTargetAPIChanged(changedTargetAPI: string): Promise<void> {
    if (this.stepperConfiguration.direction === Direction.INBOUND) {
      this.mapping.targetTemplate = isCodeOrExtensionTransformation(this.mapping.transformationType)
        ? '{}'
        : SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.sourceTemplate = getExternalTemplate(this.mapping);
      this.schemaTarget = getSchema(this.mapping.targetAPI, this.mapping.direction, true, false);
    } else {
      this.mapping.sourceTemplate = SAMPLE_TEMPLATES_C8Y[changedTargetAPI];
      this.mapping.targetTemplate = getExternalTemplate(this.mapping);
      this.schemaSource = getSchema(this.mapping.targetAPI, this.mapping.direction, false, false);
    }
  }


  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    this.deploymentMapEntry = deploymentMapEntry;
    queueMicrotask(() => {
      this.isButtonDisabled$.next(this.isConnectorSelectionEmpty());
      this.cdr.markForCheck();
    });
  }

  /** A mapping must be bound to at least one connector before it can be saved. */
  private isConnectorSelectionEmpty(): boolean {
    return !this.deploymentMapEntry?.connectors || this.deploymentMapEntry.connectors.length === 0;
  }

  /** Returns true when any mapping content field has changed since the editor was opened. */
  private hasMappingContentChanged(): boolean {
    return JSON.stringify(this.mapping) !== this.initialMappingJson
      || JSON.stringify(this.sourceTemplate) !== this.initialSourceTemplateJson
      || JSON.stringify(this.targetTemplate) !== this.initialTargetTemplateJson
      || (this.mappingCode ?? '') !== this.initialMappingCode;
  }

  onValueCodeChange(value: string): void {
    this.mappingCode = value;
  }

  onSelectCodeTemplate(): void {
    const template = this.codeTemplatesDecoded.get(this.templateId);
    if (!template) return;

    let code = stripTemplateMetadataTags(template.code);

    if (this.serviceConfiguration?.supportESM) {
      const exportName =
        this.mapping.transformationType === TransformationType.SMART_FUNCTION ? 'onMessage' :
        this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE ? 'extractFromSource' :
        null;

      if (exportName) {
        const exportStatement = `export { ${exportName} };`;
        const escapedExportName = exportName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const namedExportRegex = new RegExp(
          `export\\s*\\{[^}]*\\b${escapedExportName}\\b(?:\\s+as\\s+\\w+)?[^}]*\\}`,
          'm'
        );
        const directExportRegex = new RegExp(
          `export\\s+(?:default\\s+)?(?:async\\s+)?(?:function|const|let|var|class)\\s+${escapedExportName}\\b`,
          'm'
        );
        const hasExistingExport = namedExportRegex.test(code) || directExportRegex.test(code);

        if (!hasExistingExport) {
          code = code.trimEnd() +
            '\n\n// ── ESM export (added automatically because Support ESM is enabled) ──────────\n' +
            exportStatement + '\n';
        }
      }
    }

    this.mappingCode = code;
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
    const templateType = toTemplateType(this.stepperConfiguration.direction!, this.mapping!.transformationType);
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
      initialState: { mapping: testMapping, aiAgent: this.aiAgent, editorMode: this.stepperConfiguration.editorMode }
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

        } else {
          // this.raiseAlert({ type: 'warning', text: 'No valid JavaScript code was generated.' });
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
          // this.raiseAlert({ type: 'warning', text: 'No substitutions were generated.' });
        }
      }
    } catch (error) {
      console.error('AI generation error:', error);
    }

    this.isGenerateSubstitutionOpen = false;
  }

}
