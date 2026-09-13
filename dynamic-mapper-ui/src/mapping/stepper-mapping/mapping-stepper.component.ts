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

import { CdkStep, StepperSelectionEvent } from '@angular/cdk/stepper';
import {
  AfterViewInit,
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
import { FormGroup } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, C8yStepper, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable, ReplaySubject, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  API,
  DeploymentMapEntry,
  Direction,
  Extension,
  Mapping,
  StepperConfiguration,
  Feature,
  isSubstitutionsAsCode,
  TransformationType
} from '../../shared';
import { EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
import {
  base64ToString,
  buildTestMapping,
  captureMappingContentSnapshot,
  checkTransformationType,
  isCodeOrExtensionTransformation,
  isConnectorSelectionEmpty,
  MappingContentSnapshot,
  stripTemplateMetadataTags,
  tryGetLiveEditorContent,
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
import { StepperViewModel } from './stepper-view.model';

const STEP_LABEL_TEST_MAPPING = 'Test mapping';
const STEP_LABEL_GENERAL_SETTINGS = 'General settings';
const STEP_LABEL_SELECT_TEMPLATES = 'Select templates';

interface StepperStepChange {
  stepper: C8yStepper;
  step: CdkStep;
}

@Component({
  selector: 'd11r-mapping-stepper',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'mapping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  providers: [MappingStepperService, SubstitutionManagementService],
  imports: [CoreModule, CommonModule, EditorComponent, PopoverModule, MappingStepPropertiesComponent, MappingConnectorComponent, MappingSubstitutionStepComponent, MappingStepTestingComponent, MappingTemplateStepComponent]
})
export class MappingStepperComponent implements OnInit, AfterViewInit, OnDestroy {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() deploymentMapEntry!: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<{ mapping: Mapping; contentChanged: boolean }>();

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

  @ViewChild('templateStep', { static: false }) templateStepRef!: MappingTemplateStepComponent;
  @ViewChild('transformationStepRef', { static: false }) transformationStepRef!: MappingSubstitutionStepComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('stepper', { static: false }) stepper!: C8yStepper;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;

  private readonly cdr = inject(ChangeDetectorRef);
  private readonly bsModalService = inject(BsModalService);
  private readonly alertService = inject(AlertService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly substitutionService = inject(SubstitutionManagementService);

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
  readonly Direction = Direction;
  readonly TransformationType = TransformationType;
  readonly EditorMode = EditorMode;
  readonly STEP_DEFINE_SUBSTITUTIONS = STEP_DEFINE_SUBSTITUTIONS;

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

  // Snapshot of the mapping's initial content, taken once in ngOnInit() for UPDATE mode — see
  // hasMappingContentChanged() in onCommitButton(). undefined for CREATE/COPY, which always persist.
  private initialContentSnapshot?: MappingContentSnapshot;
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
  extensionEventItems$: Observable<{ label: string; value: string }[]>;
  /** True when the selected extension event has a parameter block defined */
  hasExtensionParameter = false;
  /** True while extensions are being (re-)loaded from the backend, e.g. on entering the General Settings step */
  isLoadingExtensions = false;
  codeTemplateItems: Array<{label: string, value: string}> = [];

  private updateExtensionItems(): void {
    this.extensionItems = this.stepperService.computeExtensionItems(this.extensions);
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

  step?: string;
  templatesInitialized = false;
  extensions = new Map<string, Extension>();
  editorOptions?: EditorComponent['editorOptions'];
  stepperForward = true;
  currentStepIndex!: number;

  /**
   * Snapshot of the Cumulocity-side template's freshly-expanded default content, taken once when
   * "Generate with AI" was chosen at mapping creation. Used to detect whether the user has since
   * customized it before allowing them to leave the Select Templates step — an unedited generic
   * default (or an empty Smart Function target) makes for a poor AI generation prompt. Cleared
   * (undefined) once the check has passed, so it only ever gates the first attempt to move on.
   */
  private aiReviewBaseline?: string;

  private readonly destroy$ = new Subject<void>();
  codeEditorHelp!: string;
  codeEditorLabel!: string;
  targetTemplateHelp = 'The template contains the dummy field <code>_TOPIC_LEVEL_</code> for outbound to map device identifiers.';
  feature!: Feature;
  serviceConfiguration!: ServiceConfiguration;

  async ngOnInit(): Promise<void> {
    // Snapshot the mapping's initial content before anything below mutates `this.mapping` in
    // place, so onCommitButton() can later tell a real content edit apart from a connector-only
    // reassignment (which must not create a draft). Mirrors the unified editor's
    // initializeTemplates() snapshot — CREATE/COPY always persist regardless, so no snapshot
    // is needed for those modes.
    if (this.stepperConfiguration.editorMode === EditorMode.UPDATE) {
      const initialTemplates = this.stepperService.expandExistingTemplates(
        this.mapping,
        this.stepperConfiguration.direction,
        this.stepperConfiguration.allowTemplateExpansion
      );
      const initialCode = this.mapping.code
        ? stripTemplateMetadataTags(base64ToString(this.mapping.code))
        : '';
      this.initialContentSnapshot = captureMappingContentSnapshot(
        this.mapping,
        initialTemplates.sourceTemplate,
        initialTemplates.targetTemplate,
        initialCode
      );
    }

    const init = await this.stepperService.initializeEditorSession(
      this.mapping,
      this.stepperConfiguration,
      this.destroy$,
      {
        onSelectExtensionName: (name) => this.onSelectExtensionName(name),
        onSelectExtensionEvent: (event) => this.onSelectExtensionEvent(event),
        getSourceTemplate: () => this.sourceTemplate,
        setSourceTemplate: (template) => { this.sourceTemplate = template; }
      },
      true // stepper-only: also disable extensionName/eventName while no selector is shown
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
    this.schemaSource = init.schemaSource;
    this.schemaTarget = init.schemaTarget;
  }

  ngAfterViewInit(): void {
    this.registerCompletionProvider();
  }

  ngOnDestroy(): void {
    this.stepperService.cleanup();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Also bound directly to the Monaco editor's `(editorInit)` in the template — see stepper-mapping/mapping-stepper.component.html. */
  registerCompletionProvider(): Promise<void> {
    return this.stepperService.registerCompletionProvider(this.mapping.direction);
  }

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    this.deploymentMapEntry = deploymentMapEntry;
    // Use queueMicrotask for change detection cycle completion
    queueMicrotask(() => {
      this.isButtonDisabled$.next(isConnectorSelectionEmpty(this.deploymentMapEntry));
      this.cdr.markForCheck();
    });
  }

  onTestingSourceTemplateChanged(template: any): void {
    this.sourceTemplate = template;
  }

  raiseAlert(alert: Alert): void {
    this.stepperService.raiseAlert(alert);
  }

  async onCommitButton(): Promise<void> {
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

    this.commit.emit(result);
  }

  async onSampleTargetTemplatesButton(): Promise<void> {
    this.targetTemplate = this.stepperService.computeSampleTargetTemplate(this.mapping, this.stepperConfiguration);
    this.templateStepRef?.editorTargetStepTemplate?.set(this.targetTemplate);
  }

  async onCancelButton(): Promise<void> {
    this.cancel.emit();
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

  // Serializes step transitions: onStepChange() is async and does real awaited work per step
  // (extension loading, filter-expression evaluation, ...), but the CDK stepper's
  // selectionChange fires once per click without waiting for the previous call to settle. Two
  // rapid "Next" clicks would otherwise run two onStepChange() invocations concurrently, both
  // mutating shared instance state (this.extensions, this.templateForm, ...) with no ordering
  // guarantee — the slower call's deferred work could land after the user has already moved
  // past that step. Chaining through this queue makes each transition wait for the previous
  // one's async work to fully finish before its own handler starts.
  private stepTransitionQueue: Promise<void> = Promise.resolve();

  async onStepChange(event: Pick<StepperSelectionEvent, 'selectedIndex'>): Promise<void> {
    this.stepTransitionQueue = this.stepTransitionQueue
      .catch(() => { /* don't let a prior transition's rejection break the chain */ })
      .then(() => this.handleStepChange(event));
    return this.stepTransitionQueue;
  }

  private async handleStepChange(event: Pick<StepperSelectionEvent, 'selectedIndex'>): Promise<void> {
    this.currentStepIndex = event.selectedIndex;
    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < STEP_DEFINE_SUBSTITUTIONS
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

  private patchExtensionFormValues(): void {
    this.stepperService.patchExtensionFormValues(this.templateForm, this.mapping, this.cdr);
  }

  private async handleGeneralSettingsStep(): Promise<void> {
    this.templatesInitialized = false;
    this.isLoadingExtensions = true;
    try {
      this.extensions = await this.stepperService.loadExtensions(this.mapping);
      this.updateExtensionItems(); // Update cached extension items

      // Re-patch form values after items are loaded so c8y-select can match them
      if (this.mapping?.extension?.extensionName) {
        // Guard: the extension may no longer be loaded on the tenant (removed/renamed).
        if (!this.extensions.get(this.mapping.extension.extensionName)) {
          const msg = `The extension ${this.mapping.extension.extensionName} with event ${this.mapping.extension.eventName} is not loaded...`;
          this.raiseAlert({ type: 'warning', text: msg });
          return;
        }

        // First, load the extension events for this extension
        this.stepperService.selectExtensionName(
          this.mapping.extension.extensionName,
          this.extensions,
          this.mapping
        );

        // Show parameter textarea if the mapping already has a parameter block
        if (this.mapping.extension.parameter) {
          this.hasExtensionParameter = true;
        }

        // Use queueMicrotask to ensure items are rendered before setting values
        // This allows c8y-select to properly detect and display the selected values
        this.patchExtensionFormValues();
      }
    } finally {
      this.isLoadingExtensions = false;
    }
  }

  private async handleSelectTemplatesStep(): Promise<void> {
    // Expand templates FIRST so this.sourceTemplate is populated before we
    // evaluate the filter expression (which needs real template data).
    if (this.stepperForward) {
      this.expandTemplates();
    }

    if (this.mapping.filterMapping) {
      // Pass the (now-expanded) sourceTemplate explicitly so the child does not
      // have to rely on its own @Input or the not-yet-rendered editor ViewChild.
      await this.templateStepRef?.updateFilterExpressionResult(
        this.mapping.filterMapping,
        this.sourceTemplate
      );
    }

    // Seed mappingCode from the persisted mapping once. mappingCode is the live,
    // editable state from here on — re-deriving it from mapping.code on every visit
    // to this step (e.g. after navigating back from Transformation) would clobber
    // any in-progress code edits, since mapping.code is only updated at save time.
    if (this.mappingCode === undefined && this.mapping.code) {
      this.mappingCode = stripTemplateMetadataTags(base64ToString(this.mapping.code));
    }

    // Trigger extension event filtering if extension is already selected
    // This handles the case when navigating to step 3 with a pre-selected extension
    if (this.mapping?.extension?.extensionName && this.extensions?.get(this.mapping.extension.extensionName)) {
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );

      // Patch form values to ensure c8y-select components display the selected values
      this.patchExtensionFormValues();
    }
  }

  private handleDefineSubstitutionsStep(): void {
    this.updateTemplatesInEditors();
    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < STEP_DEFINE_SUBSTITUTIONS
    );

    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false));

    // One-shot: user chose "Generate with AI" back in the type-selection drawer, when
    // source/target templates were still empty placeholders. Now that this step has the
    // real templates (just updated above), launch the actual generation.
    if (this.stepperConfiguration.triggerAIGenerationOnStart) {
      this.stepperConfiguration.triggerAIGenerationOnStart = false;
      if (this.aiAgentDeployed) {
        this.launchAIGenerationOnStart();
      }
    }
  }

  /**
   * Clicking "Next" out of the template step can race a pending edit in the JSON editor:
   * some content-commit paths (e.g. a tree-node edit) only flush on blur, which may not
   * have happened yet when the click fired. Blurring the active element and deferring by a
   * macrotask gives that a chance to land before we re-pull the templates and open the AI
   * drawer — otherwise the AI agent can receive a stale/default template instead of the
   * user's latest edit.
   */
  private launchAIGenerationOnStart(): void {
    (document.activeElement as HTMLElement | null)?.blur?.();
    setTimeout(() => {
      this.updateTemplatesInEditors();
      this.cdr.detectChanges();
      this.transformationStepRef?.openGenerateSubstitutionDrawer();
    }, 0);
  }

  private handleTestMappingStep(): void {
    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, true));
  }

  private updateTemplatesInEditors(): void {
    const result = updateTemplatesInEditors(this.templateStepRef, this.sourceTemplate, this.targetTemplate);
    this.sourceTemplate = result.sourceTemplate;
    this.targetTemplate = result.targetTemplate;
  }

  onNextStep(event: StepperStepChange): void {
    this.stepperForward = true;

    if (this.currentStepIndex === STEP_SELECT_TEMPLATES && (this.stepperViewModel.showExtensionSelectorsSource || this.stepperViewModel.showExtensionSelectorsTarget)) {
      const extensionName = this.templateForm.get('extensionName');
      const eventName = this.templateForm.get('eventName');
      extensionName?.markAsTouched();
      eventName?.markAsTouched();
      if (extensionName?.invalid || eventName?.invalid) {
        return;
      }
    }

    if (this.currentStepIndex === STEP_SELECT_TEMPLATES && this.aiReviewBaseline !== undefined) {
      if (!this.hasReviewedAITemplate()) {
        this.alertService.warning(
          'Please review and adjust the target template to reflect your actual data before generating with AI in the next step.'
        );
        return;
      }
      // One-shot: only gate the first attempt to leave this step.
      this.aiReviewBaseline = undefined;
    }

    if (this.stepperConfiguration.advanceFromStepToEndStep != null &&
      this.stepperConfiguration.advanceFromStepToEndStep === this.currentStepIndex) {
      this.goToLastStep();
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
    // instead of landing on the first skipped step (e.g. "Transformation")
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
      // Message Explorer prefill always lands on sourceTemplate, regardless of direction (see
      // addMapping()'s comment: sourceTemplate is always "the side Message Explorer captured").
      // Honour it directly instead of overwriting with the generic SAMPLE_TEMPLATES_C8Y default.
      const hasPrefilledSource = this.mapping.sourceTemplate && this.mapping.sourceTemplate !== '{}';
      if (hasPrefilledSource) {
        const templates = this.stepperService.expandExistingTemplates(
          this.mapping,
          this.stepperConfiguration.direction,
          this.stepperConfiguration.allowTemplateExpansion
        );
        this.sourceTemplate = templates.sourceTemplate;
        this.targetTemplate = templates.targetTemplate;
      } else {
        const templates = this.stepperService.expandTemplates(
          this.mapping,
          this.stepperConfiguration.direction,
          this.stepperConfiguration.allowTemplateExpansion
        );
        this.sourceTemplate = templates.sourceTemplate;
        this.targetTemplate = templates.targetTemplate;
      }
      this.captureAIReviewBaselineIfNeeded();
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

  /**
   * Only relevant when the user chose "Generate with AI" at creation time, and only for JSONata.
   * Snapshots the target template's just-expanded default content so `hasReviewedAITemplate()`
   * can later detect, when leaving this step, whether the user customized it — an untouched
   * generic default makes for a poor AI generation prompt.
   *
   * Smart Function is excluded: its targetTemplate is always forced back to `{}` by
   * `onTargetAPIChanged()` and is explicitly ignored by both the generation prompt and the
   * deployed agent's system prompt, so there is nothing there to meaningfully review — the
   * AI prompt drawer's own pre-generation screen (targetAPI + optional sample payload) is the
   * actual review step for Smart Function instead.
   */
  private captureAIReviewBaselineIfNeeded(): void {
    if (!this.stepperConfiguration.triggerAIGenerationOnStart) return;
    if (isCodeOrExtensionTransformation(this.mapping.transformationType)) return;
    this.aiReviewBaseline = JSON.stringify(this.targetTemplate);
  }

  /** True if the user has edited the target template since `aiReviewBaseline` was captured. */
  private hasReviewedAITemplate(): boolean {
    const liveTarget = tryGetLiveEditorContent(this.templateStepRef?.editorTargetStepTemplate);
    if (liveTarget === undefined) return true; // can't verify — don't block on a guess
    return JSON.stringify(liveTarget) !== this.aiReviewBaseline;
  }

  async onTargetAPIChanged(changedTargetAPI: string): Promise<void> {
    const result = this.stepperService.applyTargetAPIChange(this.mapping, this.stepperConfiguration.direction, changedTargetAPI);
    if (result.schemaTarget !== undefined) this.schemaTarget = result.schemaTarget;
    if (result.schemaSource !== undefined) this.schemaSource = result.schemaSource;
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
            // Use queueMicrotask for view update cycle completion
            queueMicrotask(() => {
              this.codeEditor.writeValue(result);
              this.cdr.markForCheck();
            });
          }

        } else {
          this.alertService.warning('No valid JavaScript code was generated.');
        }
      } else {
        if (Array.isArray(result) && result.length > 0) {
          this.alertService.success(`Generated ${result.length} substitutions.`);
          this.mapping.substitutions.splice(0);
          result.forEach(sub => {
            this.substitutionService.addSubstitution(
              sub,
              this.mapping,
              () => {
                this.stepperService.refreshSubstitutionValidity(
                  this.mapping,
                  this.stepperConfiguration,
                  this.currentStepIndex < STEP_DEFINE_SUBSTITUTIONS
                );
              }
            );
          });
        } else {
          this.alertService.warning('No substitutions were generated.');
        }
      }
    } catch (error) {
      // The drawer rejects with this exact string when the user clicks "Cancel" — not a
      // failure, so it shouldn't surface as an error to the user.
      if (error !== 'User canceled') {
        console.error('AI generation error:', error);
        this.alertService.danger('AI generation failed. Please try again.');
      }
    }

    this.isGenerateSubstitutionOpen = false;
  }

}