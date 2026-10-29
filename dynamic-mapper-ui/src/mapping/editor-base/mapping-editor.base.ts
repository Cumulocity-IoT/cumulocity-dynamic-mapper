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

import { AfterViewInit, ChangeDetectorRef, Directive, inject, OnDestroy } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService } from '@c8y/ngx-components';
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
  TransformationType
} from '../../shared';
import {
  buildTestMapping,
  checkTransformationType,
  isConnectorSelectionEmpty,
  updateTemplatesInEditors,
  validateProtectedFields
} from '../../shared/mapping/util';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType, tryToTemplateType } from '../../shared/configuration/configuration.model';
import { ManageTemplateComponent } from '../../shared/component/code-template/manage-template.component';
import { AiPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../../shared/mapping/ai-prompt.model';
import { EditorSessionResult, MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { MappingTemplateStepComponent } from '../step-template/mapping-template-step.component';
import { StepperViewModel } from '../stepper/stepper-view.model';

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
  abstract mapping: Mapping;
  abstract stepperConfiguration: StepperConfiguration;
  abstract deploymentMapEntry: DeploymentMapEntry;
  abstract templateStepRef: MappingTemplateStepComponent;
  abstract codeEditor: EditorComponent;
  abstract currentStepIndex: number;
  /** Index of the transformation step/tab; anything before it counts as "before substitutions". */
  protected abstract readonly substitutionStepIndex: number;

  protected readonly cdr = inject(ChangeDetectorRef);
  protected readonly bsModalService = inject(BsModalService);
  protected readonly alertService = inject(AlertService);
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
          text: `The extension ${extension.extensionName} with event ${extension.eventName} is not loaded...`
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
        `Code templates are only available for Smart Function mappings, not ${this.mapping.transformationType}.`
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

  async openGenerateSubstitutionDrawer(): Promise<void> {
    this.isGenerateSubstitutionOpen = true;

    const testMapping = buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false);

    const drawer = this.bottomDrawerService.openDrawer(AiPromptComponent, {
      initialState: { mapping: testMapping, aiAgent: this.aiAgent, existingMapping: this.stepperConfiguration.persistence === 'draft' }
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
          // Same bulk replace as the transformation step: the generated set replaces the whole
          // list atomically, with one validity refresh instead of one per item.
          this.substitutionService.replaceAllSubstitutions(result, this.mapping, () => this.refreshSubstitutionValidity());
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
