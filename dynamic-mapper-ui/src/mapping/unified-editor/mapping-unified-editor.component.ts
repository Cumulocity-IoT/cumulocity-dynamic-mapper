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
import { FormGroup } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, CoreModule, TabComponent, TabsOutletComponent } from '@c8y/ngx-components';
import { GlobalContextService } from '@c8y/ngx-components/global-context';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, ReplaySubject, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  DeploymentMapEntry,
  Direction,
  Extension,
  Mapping,
  StepperConfiguration,
  Feature,
  isSubstitutionsAsCode,
  TransformationType,
  MappingTypeLabels,
  MappingType
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';
import { MappingEditData } from '../core/mapping-edit.resolver';
import { gettext } from '@c8y/ngx-components/gettext';
import {
  base64ToString,
  buildTestMapping,
  captureMappingContentSnapshot,
  checkTransformationType,
  isConnectorSelectionEmpty,
  MappingContentSnapshot,
  stripTemplateMetadataTags,
  updateTemplatesInEditors,
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
import { StepperViewModel } from '../stepper-mapping/stepper-view.model';

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
  styleUrls: ['../shared/mapping.style.css'],
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
  private readonly alertService = inject(AlertService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly substitutionService = inject(SubstitutionManagementService);
  private readonly mappingService = inject(MappingService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly globalContextService = inject(GlobalContextService);

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly TransformationType = TransformationType;
  readonly EditorMode = EditorMode;

  // Exposed so the template can reference named tabs instead of magic numbers
  readonly TAB_CONNECTOR = TAB_CONNECTOR;
  readonly TAB_GENERAL_SETTINGS = TAB_GENERAL_SETTINGS;
  readonly TAB_SELECT_TEMPLATES = TAB_SELECT_TEMPLATES;
  readonly TAB_DEFINE_TRANSFORMATION = TAB_DEFINE_TRANSFORMATION;
  readonly TAB_TEST_MAPPING = TAB_TEST_MAPPING;

  updateTestingTemplate = new ReplaySubject<Mapping>(1);
  schemaSource: any;
  schemaTarget: any;

  templateForm!: FormGroup;
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

  private readonly destroy$ = new Subject<void>();

  // Snapshots for change detection — set once at load time
  private initialContentSnapshot: MappingContentSnapshot = { mappingJson: '', sourceTemplateJson: '', targetTemplateJson: '', mappingCode: '' };
  private initialDeploymentConnectors = '';

  private updateExtensionItems(): void {
    this.extensionItems = this.stepperService.computeExtensionItems(this.extensions);
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
    this.isButtonDisabled$.next(isConnectorSelectionEmpty(this.deploymentMapEntry));

    // For EXTENSION_JAVA the transformation is configured in the templates tab
    // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy type still rendered for not-yet-migrated mappings
    this.activeTabIndex = this.mapping.mappingType === MappingType.PROTOBUF_INTERNAL || this.mapping.transformationType === TransformationType.EXTENSION_JAVA
      ? TAB_GENERAL_SETTINGS
      : TAB_DEFINE_TRANSFORMATION;
    this.currentStepIndex = this.activeTabIndex;

    const init = await this.stepperService.initializeEditorSession(
      this.mapping,
      this.stepperConfiguration,
      this.destroy$,
      {
        onSelectExtensionName: (name) => this.onSelectExtensionName(name),
        onSelectExtensionEvent: (event) => this.onSelectExtensionEvent(event),
        getSourceTemplate: () => this.sourceTemplate,
        setSourceTemplate: (template) => { this.sourceTemplate = template; }
      }
      // disableExtensionSelectorsWhenHidden defaults to false — unified editor's current behavior
    );

    this.stepperViewModel = init.stepperViewModel;
    this.extensionEventItems$ = init.extensionEventItems$;
    this.targetSystem = init.targetSystem;
    this.sourceSystem = init.sourceSystem;
    this.editorOptions = init.editorOptions;
    this.templateForm = init.templateForm;
    if (init.editorTemplatesReadOnly) {
      this.editorOptionsSourceTemplate.readOnly = true;
      this.editorOptionsTargetTemplate.readOnly = true;
    }
    this.feature = init.feature;
    this.serviceConfiguration = init.serviceConfiguration;
    this.aiAgent = init.aiAgent;
    this.aiAgentDeployed = init.aiAgentDeployed;
    this.filterFormlyFields = init.filterFormlyFields;
    this.codeTemplates = init.codeTemplates;
    this.codeTemplatesDecoded = init.codeTemplatesDecoded;
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
    this.codeTemplateEntries = init.codeTemplateEntries;
    this.codeTemplateItems = init.codeTemplateItems;
    this.codeEditorHelp = init.codeEditorHelp;
    this.codeEditorLabel = init.codeEditorLabel;

    // For the unified editor, expand existing templates upfront since mapping is fully defined
    await this.initializeTemplates();

    this.schemaSource = init.schemaSource;
    this.schemaTarget = init.schemaTarget;
  }

  private patchExtensionFormValues(): void {
    this.stepperService.patchExtensionFormValues(this.templateForm, this.mapping, this.cdr);
  }

  private async initializeTemplates(): Promise<void> {
    // Load extensions needed for the template display
    this.extensions = await this.stepperService.loadExtensions(this.mapping);
    this.updateExtensionItems();

    // Load filter model – handled by MappingTemplateStepComponent on init
    // when filterFormly changes; no action needed here.

    // Seed mappingCode from the persisted mapping once. mappingCode is the live,
    // editable state from here on — it must never be re-derived from mapping.code
    // later (mapping.code is stale until save), or in-progress edits get clobbered.
    if (this.mappingCode === undefined && this.mapping.code) {
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
    this.cdr.detectChanges();

    // Snapshot initial state so we can distinguish connector-only changes from content changes
    this.initialContentSnapshot = captureMappingContentSnapshot(
      this.mapping,
      this.sourceTemplate,
      this.targetTemplate,
      this.mappingCode
    );

    // Re-patch form values for extension selects if extension is selected
    if (this.mapping?.extension?.extensionName) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      this.patchExtensionFormValues();
    }

    // Validate substitutions with initial tab index
    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < TAB_DEFINE_TRANSFORMATION
    );
  }

  ngAfterViewInit(): void {
    this.registerCompletionProvider();
  }

  ngOnDestroy(): void {
    this.globalContextService.unregister('mapping-unified-editor');
    this.stepperService.cleanup();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Also bound directly to the Monaco editor's `(editorInit)` in the template — see mapping-unified-editor.component.html. */
  registerCompletionProvider(): Promise<void> {
    return this.stepperService.registerCompletionProvider(this.mapping.direction);
  }

  /**
   * Returns true if the tab at the given index should be visible.
   * Tabs between advanceFromStepToEndStep (exclusive) and the last tab (exclusive) are hidden.
   * The last tab (Testing) is always visible.
   */
  isTabVisible(tabIndex: number): boolean {
    // Deprecated SUBSTITUTION_AS_CODE mappings: hide Testing tab (can't be processed)
    // eslint-disable-next-line @typescript-eslint/no-deprecated
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
  // Serializes tab transitions: onTabSelected() is async and does real awaited work per tab, but
  // nothing prevents the user from clicking a different tab again before the previous click's
  // work has settled — arguably more likely here than in the linear stepper, since a tab UI
  // invites fast back-and-forth clicking. Chaining through this queue makes each transition wait
  // for the previous one's async work to fully finish before its own handler starts, so two
  // concurrent handlers can't race on shared instance state (this.extensions, this.templateForm, ...).
  private tabTransitionQueue: Promise<void> = Promise.resolve();

  async onTabSelected(newIndex: number): Promise<void> {
    this.tabTransitionQueue = this.tabTransitionQueue
      .catch(() => { /* don't let a prior transition's rejection break the chain */ })
      .then(() => this.handleTabSelected(newIndex));
    return this.tabTransitionQueue;
  }

  private async handleTabSelected(newIndex: number): Promise<void> {
    // Sync template changes when leaving the Templates tab
    if (this.activeTabIndex === TAB_SELECT_TEMPLATES) {
      this.updateTemplatesInEditors();
    }

    this.activeTabIndex = newIndex;
    this.currentStepIndex = newIndex;

    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < TAB_DEFINE_TRANSFORMATION
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
    this.extensions = await this.stepperService.loadExtensions(this.mapping);
    this.updateExtensionItems();

    if (this.mapping?.extension?.extensionName) {
      // Guard: the extension may no longer be loaded on the tenant (removed/renamed).
      if (!this.extensions.get(this.mapping.extension.extensionName)) {
        const msg = `The extension ${this.mapping.extension.extensionName} with event ${this.mapping.extension.eventName} is not loaded...`;
        this.raiseAlert({ type: 'warning', text: msg });
        return;
      }
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      // Show config textarea if the mapping already has configuration
      if (this.mapping.extension.parameter) {
        this.hasExtensionParameter = true;
      }
      this.patchExtensionFormValues();
    }
  }

  private async handleSelectTemplatesTab(): Promise<void> {
    if (this.mapping.filterMapping) {
      await this.templateStepRef?.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    // Do NOT re-derive mappingCode from mapping.code here: mapping.code is only
    // updated at save time, so this would clobber any in-progress code edits made
    // on the Transformation tab. Seeding already happened once in initializeTemplates().

    if (this.mapping?.extension?.extensionName && this.extensions?.get(this.mapping.extension.extensionName)) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      this.patchExtensionFormValues();
    }
  }

  private handleDefineSubstitutionsTab(): void {
    this.updateTemplatesInEditors();
    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < TAB_DEFINE_TRANSFORMATION
    );

    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false));
  }

  private handleTestMappingTab(): void {
    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, true));
  }

  private updateTemplatesInEditors(): void {
    const result = updateTemplatesInEditors(this.templateStepRef, this.sourceTemplate, this.targetTemplate);
    this.sourceTemplate = result.sourceTemplate;
    this.targetTemplate = result.targetTemplate;
  }

  onTestingSourceTemplateChanged(template: any): void {
    this.sourceTemplate = template;
  }

  raiseAlert(alert: Alert): void {
    this.stepperService.raiseAlert(alert);
  }

  async onCommitButton(): Promise<void> {
    // A mapping must be bound to at least one connector
    if (isConnectorSelectionEmpty(this.deploymentMapEntry)) {
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
        this.activeTabIndex = TAB_SELECT_TEMPLATES;
        return;
      }
    }

    // Sync any pending template edits before saving
    this.updateTemplatesInEditors();

    // Connector-only changes must not create a draft — a draft only tracks content changes.
    const deploymentChanged =
      JSON.stringify(this.deploymentMapEntry?.connectors ?? []) !== this.initialDeploymentConnectors;

    const result = this.stepperService.encodeMappingForCommit(
      this.mapping,
      this.sourceTemplate,
      this.targetTemplate,
      this.mappingCode,
      this.initialContentSnapshot,
      this.stepperConfiguration.allowTemplateExpansion,
      this.stepperConfiguration.editorMode
    );

    if ('error' in result) {
      this.raiseAlert({ type: 'warning', text: result.error });
      return;
    }

    const mappingContentChanged = result.contentChanged;

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

    // Shared with the stepper's commit path (mapping.component.ts::onCommitMapping) so both
    // editors apply the same post-save check — an OUTBOUND mapping with no device subscribed
    // to receive it otherwise silently does nothing once activated.
    await this.subscriptionService.validateSubscriptionOutbound(this.stepperConfiguration.direction);

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
    this.targetTemplate = this.stepperService.computeSampleTargetTemplate(this.mapping, this.stepperConfiguration);
    this.templateStepRef?.editorTargetStepTemplate?.set(this.targetTemplate);
  }

  onSelectExtensionName(extensionName: string): void {
    this.stepperService.applyExtensionNameSelection(extensionName, this.mapping, this.extensions);
  }

  onSelectExtensionEvent(extensionEvent: string): void {
    const hasParameter = this.stepperService.applyExtensionEventSelection(
      extensionEvent, this.mapping, this.extensions, this.templateForm
    );
    if (hasParameter !== undefined) {
      this.hasExtensionParameter = hasParameter;
    }
  }

  async onTargetAPIChanged(changedTargetAPI: string): Promise<void> {
    const result = this.stepperService.applyTargetAPIChange(this.mapping, this.stepperConfiguration.direction, changedTargetAPI);
    if (result.schemaTarget !== undefined) this.schemaTarget = result.schemaTarget;
    if (result.schemaSource !== undefined) this.schemaSource = result.schemaSource;
  }


  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    this.deploymentMapEntry = deploymentMapEntry;
    queueMicrotask(() => {
      this.isButtonDisabled$.next(isConnectorSelectionEmpty(this.deploymentMapEntry));
      this.cdr.markForCheck();
    });
  }

  onValueCodeChange(value: string): void {
    this.mappingCode = value;
  }

  onSelectCodeTemplate(): void {
    const code = this.stepperService.computeCodeFromTemplate(
      this.codeTemplatesDecoded, this.templateId, this.serviceConfiguration, this.mapping.transformationType
    );
    if (code !== undefined) {
      this.mappingCode = code;
    }
  }

  private updateCodeTemplateEntries(): void {
    const result = this.stepperService.computeCodeTemplateEntries(
      this.codeTemplates, this.stepperConfiguration.direction, this.mapping?.transformationType
    );
    this.codeTemplateEntries = result.entries;
    this.codeTemplateItems = result.items;
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
        this.codeTemplates = await this.stepperService.createCodeTemplateAndRefresh(
          codeTemplate.name,
          codeTemplate.description,
          this.mappingCode,
          this.stepperConfiguration.direction,
          this.mapping.transformationType
        );
        this.updateCodeTemplateEntries();
      }
    });
  }

  async openGenerateSubstitutionDrawer(): Promise<void> {
    this.isGenerateSubstitutionOpen = true;

    const testMapping = buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false);

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
                this.stepperService.refreshSubstitutionValidity(
                  this.mapping,
                  this.stepperConfiguration,
                  this.currentStepIndex < TAB_DEFINE_TRANSFORMATION
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
