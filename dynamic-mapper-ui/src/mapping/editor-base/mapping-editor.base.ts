/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { AfterViewInit, ChangeDetectorRef, Directive, inject, OnDestroy, Injector, computed, signal } from '@angular/core';
import { afterNextRenderInZone } from '../../shared/misc/after-render';
import { FormGroup } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable, ReplaySubject, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  DeploymentMapEntry,
  Direction,
  Extension,
  Feature,
  isSubstitutionsAsCode,
  Mapping,
  StepperConfiguration,
  Substitution,
  TransformationType
} from '../../shared';
import {
  base64ToString,
  buildTestMapping,
  checkTransformationType,
  isConnectorSelectionEmpty,
  stripTemplateMetadataTags,
  TemplateEditorRef,
  updateTemplatesInEditors,
  validateProtectedFields
} from '../../shared/mapping/util';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType, tryToTemplateType } from '../../shared/configuration/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { AiPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../../shared/mapping/ai-prompt.model';
import { EditorSessionResult, MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { StepperViewModel } from './stepper-view.model';

/**
 * State and behavior shared by the linear stepper and the unified (tabbed) editor.
 *
 * Both hosts drive the same {@link MappingStepperService} session and differ only in how they
 * navigate between the five sections, so everything that does not depend on navigation lives
 * here. The base carries no Angular decorators: `@Input`/`@ViewChild` members are declared
 * abstract and implemented by the concrete component, and the providers
 * (`MappingStepperService`, `SubstitutionManagementService`) stay on the concrete component so
 * each editor instance gets its own.
 */
// Decorated although it has no selector: Angular requires an explicit decorator on a base class that
// declares lifecycle hooks (ngAfterViewInit/ngOnDestroy), even when it is abstract.
@Directive()
export abstract class MappingEditorBase implements AfterViewInit, OnDestroy {
  private readonly injector = inject(Injector);
  abstract mapping: Mapping;
  abstract stepperConfiguration: StepperConfiguration;
  /**
   * The connectors this mapping is deployed to. Held in a signal so what is derived from it, such
   * as {@link connectorSelectionEmpty}, is always current, even when the connector step reports
   * its selection while the view is being checked. Hosts declare the `deploymentMapEntry` input
   * through `inputs: [...]` in their `@Component`, not with a field of their own.
   */
  private readonly deploymentMapEntrySignal = signal<DeploymentMapEntry | undefined>(undefined);
  get deploymentMapEntry(): DeploymentMapEntry {
    return this.deploymentMapEntrySignal()!;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this.deploymentMapEntrySignal.set(value);
  }
  /** True while no connector is selected; a mapping needs one to be saved. Derived, never set. */
  readonly connectorSelectionEmpty = computed(() => isConnectorSelectionEmpty(this.deploymentMapEntrySignal()));
  /** The rendered template step, if any; the base only needs what {@link TemplateEditorRef} offers. */
  abstract templateStepRef: TemplateEditorRef | undefined;
  abstract codeEditor: EditorComponent;
  abstract currentStepIndex: number;
  /** Index of the transformation step/tab; anything before it counts as "before substitutions". */
  protected abstract readonly substitutionStepIndex: number;

  protected readonly cdr = inject(ChangeDetectorRef);
  protected readonly bsModalService = inject(BsModalService);
  protected readonly alertService = inject(AlertService);
  protected readonly translateService = inject(TranslateService);
  protected readonly bottomDrawerService = inject(BottomDrawerService);
  protected readonly stepperService = inject(MappingStepperService);
  protected readonly substitutionService = inject(SubstitutionManagementService);

  protected readonly destroy$ = new Subject<void>();

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
  readonly Direction = Direction;
  readonly TransformationType = TransformationType;

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

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
  aiCapabilitiesDisabled = false;
  aiAgent: AgentObjectDefinition | AgentTextDefinition | null = null;

  // Use service observables
  get isSubstitutionValid$() { return this.stepperService.isSubstitutionValid$; }
  get extensionEvents$() { return this.stepperService.extensionEvents$; }
  get sourceCustomMessage$() { return this.stepperService.sourceCustomMessage$; }
  get targetCustomMessage$() { return this.stepperService.targetCustomMessage$; }

  // Cached properties for c8y-select components (to avoid recreating arrays on every change detection)
  extensionItems: string[] = [];
  extensionEventItems$: Observable<{ label: string; value: string }[]>;
  /** True when the selected extension event has a parameter block defined */
  hasExtensionParameter = false;
  /** True while extensions are being (re-)loaded from the backend */
  isLoadingExtensions = false;
  codeTemplateItems: Array<{ label: string; value: string }> = [];

  templatesInitialized = false;
  extensions = new Map<string, Extension>();
  editorOptions?: EditorComponent['editorOptions'];

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

  private transitionQueue: Promise<void> = Promise.resolve();

  /**
   * Serializes step/tab transitions. The handlers are async and do real awaited work (extension
   * loading, filter evaluation, ...) while mutating shared state (`this.extensions`,
   * `this.templateForm`, ...). Chaining each transition behind the previous one's completion keeps
   * a slower call's deferred work from landing after the user has already moved on.
   */
  protected enqueueTransition(handler: () => Promise<void>): Promise<void> {
    this.transitionQueue = this.transitionQueue
      .catch(() => { /* don't let a prior transition's rejection break the chain */ })
      .then(handler);
    return this.transitionQueue;
  }

  ngAfterViewInit(): void {
    this.registerCompletionProvider();
  }

  ngOnDestroy(): void {
    this.stepperService.cleanup();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Starts the editor session shared by both hosts and applies it; the caller assigns the schemas. */
  protected async initSession(disableExtensionSelectorsWhenHidden = false): Promise<EditorSessionResult> {
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
      disableExtensionSelectorsWhenHidden
    );
    this.applyEditorSession(init);
    return init;
  }

  /**
   * Seeds mappingCode from the persisted mapping, once. mappingCode is the live, editable state
   * from then on and must never be re-derived from mapping.code (stale until save), or in-progress
   * edits get clobbered.
   */
  protected seedMappingCode(): void {
    if (this.mappingCode === undefined && this.mapping.code) {
      this.mappingCode = stripTemplateMetadataTags(base64ToString(this.mapping.code));
    }
  }

  /** (Re-)loads the tenant's extensions and re-patches the extension selects. */
  protected async reloadExtensions(): Promise<void> {
    this.isLoadingExtensions = true;
    try {
      this.extensions = await this.stepperService.loadExtensions(this.mapping);
      this.updateExtensionItems();
      // Re-patches form values after items are loaded so c8y-select can match them
      this.restoreExtensionSelection(true);
    } finally {
      this.isLoadingExtensions = false;
    }
  }

  /** Entering the transformation step/tab: pull the latest templates and refresh the test mapping. */
  protected enterTransformation(): void {
    this.updateTemplatesInEditors();
    this.refreshSubstitutionValidity();
    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false));
  }

  protected enterTesting(): void {
    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, true));
  }

  /**
   * Copies the session result onto the host. The schemas are deliberately left out: the stepper
   * assigns them with the rest, the unified editor only after its templates are expanded, and
   * that ordering is observable — so each host assigns them itself.
   */
  protected applyEditorSession(init: EditorSessionResult): void {
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
    this.aiCapabilitiesDisabled = init.aiCapabilitiesDisabled;
    this.filterFormlyFields = init.filterFormlyFields;
    this.codeTemplates = init.codeTemplates;
    this.codeTemplatesDecoded = init.codeTemplatesDecoded;
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
    this.codeTemplateEntries = init.codeTemplateEntries;
    this.codeTemplateItems = init.codeTemplateItems;
    this.codeEditorHelp = init.codeEditorHelp;
    this.codeEditorLabel = init.codeEditorLabel;
  }

  /** Also bound directly to the Monaco editor's `(editorInit)` in the templates. */
  registerCompletionProvider(): Promise<void> {
    return this.stepperService.registerCompletionProvider(this.mapping.direction);
  }

  protected updateExtensionItems(): void {
    this.extensionItems = this.stepperService.computeExtensionItems(this.extensions);
  }

  protected patchExtensionFormValues(): void {
    this.stepperService.patchExtensionFormValues(this.templateForm, this.mapping, this.cdr);
  }

  /**
   * Re-selects the mapping's extension (so the event list is filtered) and re-patches the form
   * once the c8y-selects can match the values. No-op if the mapping has no extension, or — after
   * raising a warning — if the extension is no longer loaded on the tenant (removed/renamed).
   *
   * @param warnIfMissing true where the user is looking at the extension settings
   * @returns false when the extension is missing and the caller should stop
   */
  protected restoreExtensionSelection(warnIfMissing = false): boolean {
    const extension = this.mapping?.extension;
    if (!extension?.extensionName) return true;

    if (!this.extensions?.get(extension.extensionName)) {
      if (warnIfMissing) {
        this.raiseAlert({
          type: 'warning',
          text: this.translateService.instant(gettext('The extension {{extensionName}} with event {{eventName}} is not loaded...'), {
            extensionName: extension.extensionName,
            eventName: extension.eventName
          })
        });
      }
      return !warnIfMissing;
    }

    this.stepperService.selectExtensionName(extension.extensionName, this.extensions, this.mapping);
    // Show the parameter textarea if the mapping already has a parameter block
    if (extension.parameter) this.hasExtensionParameter = true;
    this.patchExtensionFormValues();
    return true;
  }

  protected updateTemplatesInEditors(): void {
    const result = updateTemplatesInEditors(this.templateStepRef, this.sourceTemplate, this.targetTemplate);
    this.sourceTemplate = result.sourceTemplate;
    this.targetTemplate = result.targetTemplate;
  }

  protected refreshSubstitutionValidity(): void {
    this.stepperService.refreshSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration,
      this.currentStepIndex < this.substitutionStepIndex
    );
  }

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    this.deploymentMapEntry = deploymentMapEntry;
  }

  /** The host owns the mapping: children report changes, only the host writes them. */
  onSubstitutionsChange(substitutions: Substitution[]): void {
    this.mapping.substitutions = substitutions;
  }

  onPropertiesChange(patch: Partial<Mapping>): void {
    Object.assign(this.mapping, patch);
  }

  onFilterMappingChange(filterMapping: string): void {
    this.mapping.filterMapping = filterMapping;
  }

  onTestingSourceTemplateChanged(template: any): void {
    this.sourceTemplate = template;
    // The payload the user edited in the test step becomes the mapping's source template.
    this.mapping.sourceTemplate = JSON.stringify(template || {});
  }

  raiseAlert(alert: Alert): void {
    this.stepperService.raiseAlert(alert);
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
      this.codeTemplates, this.stepperConfiguration.direction, this.mapping.transformationType
    );
    this.codeTemplateEntries = result.entries;
    this.codeTemplateItems = result.items;
  }

  async onCreateCodeTemplate(): Promise<void> {
    // Reachable only because the "Create new code template" button renders inside the
    // code-editor section, which is gated on a StepperConfiguration flag two hops away from
    // the transformation type. Check here rather than rely on that: an unsupported
    // combination should tell the user, not throw out of an event handler.
    const templateType = tryToTemplateType(this.stepperConfiguration.direction!, this.mapping.transformationType);
    if (!templateType) {
      this.alertService.warning(
        this.translateService.instant(
          gettext('Code templates are only available for Smart Function mappings, not {{transformationType}}.'),
          { transformationType: this.mapping.transformationType }
        )
      );
      return;
    }
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

  /**
   * The "Generate with AI" button is unavailable without a deployed agent or in read-only mode.
   * Substitutions are generated against the source editor, so they also need it. A Smart Function
   * does not: ANY_PAYLOAD has no source editor because the message is binary, but its code can
   * still be generated (the AI gets the base64 sample from the mapping).
   */
  get aiGenerationDisabled(): boolean {
    const needsSourceEditor = !isSubstitutionsAsCode(this.mapping);
    return !!this.stepperViewModel?.config.readOnly
      || !this.aiAgentDeployed
      || (needsSourceEditor && !this.stepperViewModel?.config.showEditorSource);
  }

  /** An existing mapping is being refined; a new one generated. */
  get aiButtonLabel(): string {
    return this.stepperConfiguration.persistence === 'draft' ? 'Review / Refine with AI' : 'Generate with AI';
  }

  async openGenerateSubstitutionDrawer(): Promise<void> {
    this.isGenerateSubstitutionOpen = true;

    let drawer;
    try {
      const testMapping: any = buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false);
      // What is known about the captured message (type, size, sender, broker metadata) lives on the
      // stepper configuration only; attach it to the drawer's copy so AI generation sees it. Never
      // persisted on the mapping itself.
      if (this.stepperConfiguration.sampleTransportFields) {
        testMapping.sampleTransportFields = this.stepperConfiguration.sampleTransportFields;
      }
      if (this.stepperConfiguration.sampleMessageInfo) {
        testMapping.sampleMessageInfo = this.stepperConfiguration.sampleMessageInfo;
      }
      if (this.stepperConfiguration.additionalSamples?.length) {
        testMapping.additionalSamples = this.stepperConfiguration.additionalSamples;
      }
      drawer = this.bottomDrawerService.openDrawer(AiPromptComponent, {
        initialState: { mapping: testMapping, aiAgent: this.aiAgent, existingMapping: this.stepperConfiguration.persistence === 'draft' }
      });
    } catch (error) {
      // Without this the click handler's promise rejects silently and the button just does nothing.
      console.error('Could not open the AI assistant:', error);
      this.alertService.danger(
        this.translateService.instant(gettext('Could not open the AI assistant: {{message}}'), {
          message: (error as Error)?.message ?? error
        })
      );
      this.isGenerateSubstitutionOpen = false;
      return;
    }

    try {
      const result = await drawer.instance.result;

      if (isSubstitutionsAsCode(this.mapping)) {
        if (typeof result === 'string' && result.trim()) {
          this.mappingCode = result;

          if (this.codeEditor) {
            // After the render that applies the new `mappingCode` to the editor's input.
            afterNextRenderInZone(this.injector, () => {
              this.codeEditor.writeValue(result);
              this.cdr.markForCheck();
            });
          }
        } else {
          this.alertService.warning(gettext('No valid JavaScript code was generated.'));
        }
      } else {
        if (Array.isArray(result) && result.length > 0) {
          this.alertService.success(
            this.translateService.instant(gettext('Generated {{count}} substitutions.'), { count: result.length })
          );
          // Same bulk replace as the transformation step: the generated set replaces the whole
          // list atomically, with one validity refresh instead of one per item.
          this.onSubstitutionsChange(this.substitutionService.replaceAllSubstitutions(result));
          this.refreshSubstitutionValidity();
        } else {
          this.alertService.warning(gettext('No substitutions were generated.'));
        }
      }
    } catch (error) {
      // The drawer rejects with this exact string when the user clicks "Cancel" — not a
      // failure, so it shouldn't surface as an error to the user.
      if (error !== 'User canceled') {
        console.error('AI generation error:', error);
        this.alertService.danger(gettext('AI generation failed. Please try again.'));
      }
    }

    this.isGenerateSubstitutionOpen = false;
  }
}
