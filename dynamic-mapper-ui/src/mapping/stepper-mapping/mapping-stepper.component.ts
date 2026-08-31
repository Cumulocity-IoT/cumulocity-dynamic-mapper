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
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Alert, AlertService, BottomDrawerService, C8yStepper, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { debounceTime, distinctUntilChanged, map, Observable, ReplaySubject, shareReplay, Subject, takeUntil } from 'rxjs';
import { Mode } from 'vanilla-jsoneditor';
import {
  API,
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
  MappingTypeLabels
} from '../../shared';
import { createCompletionProviderFlowFunction, EditorMode, STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../shared/stepper.model';
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
import { gettext } from '@c8y/ngx-components/gettext';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-property/mapping-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-transformation/mapping-transformation-step.component';
import { MappingTemplateStepComponent } from '../step-template/mapping-template-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { StepperViewModel, StepperViewModelFactory } from './stepper-view.model';
import * as jsYaml from 'js-yaml';

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
  @Output() commit = new EventEmitter<Mapping>();

  // View model with computed properties for template simplification
  stepperViewModel!: StepperViewModel;

  @ViewChild('templateStep', { static: false }) templateStepRef!: MappingTemplateStepComponent;
  @ViewChild('transformationStepRef', { static: false }) transformationStepRef!: MappingSubstitutionStepComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('stepper', { static: false }) stepper!: C8yStepper;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;

  private readonly cdr = inject(ChangeDetectorRef);
  private readonly bsModalService = inject(BsModalService);
  private readonly sharedService = inject(SharedService);
  private readonly alertService = inject(AlertService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly substitutionService = inject(SubstitutionManagementService);

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

  // Cached properties for c8y-select components (to avoid recreating arrays on every change detection)
  extensionItems: string[] = [];
  extensionEventItems$: Observable<{ label: string; value: string }[]>;
  /** True when the selected extension event has a parameter block defined */
  hasExtensionParameter = false;
  codeTemplateItems: Array<{label: string, value: string}> = [];

  private updateExtensionItems(): void {
    this.extensionItems = Array.from(this.extensions.keys());
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

  step?: string;
  expertMode = false;
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

  private completionProviderDisposable: any;
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

    // Use service method
    const aiResult = await this.stepperService.checkAIAgentDeployment(this.mapping, this.serviceConfiguration);
    this.aiAgent = aiResult.aiAgent;
    this.aiAgentDeployed = aiResult.aiAgentDeployed;

    this.initializeFormlyFields();
    await this.initializeCodeTemplates();

    this.codeEditorHelp = 'JavaScript for creating complete payloads as Smart Functions.';

    this.codeEditorLabel = 'JavaScript callback for Smart functions';

    this.schemaSource = getSchema(this.mapping.targetAPI, this.mapping.direction, false, false);
    this.schemaTarget = getSchema(this.mapping.targetAPI, this.mapping.direction, true, false);
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
             || !(this.stepperViewModel.showExtensionSelectorsSource || this.stepperViewModel.showExtensionSelectorsTarget)
      }, Validators.required),
      eventName: new FormControl({
        value: this.mapping?.extension?.eventName,
        disabled: this.stepperConfiguration.editorMode === EditorMode.READ_ONLY
             || !(this.stepperViewModel.showExtensionSelectorsSource || this.stepperViewModel.showExtensionSelectorsTarget)
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

    // Subscribe to extension parameter changes
    this.templateForm.get('extensionParameter')?.valueChanges
      .pipe(debounceTime(300), takeUntil(this.destroy$))
      .subscribe(yaml => {
        if (!this.mapping.extension) {
          this.mapping.extension = {} as any;
        }
        this.mapping.extension.parameter = this.yamlToConfiguration(yaml);
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

  deploymentMapEntryChange(deploymentMapEntry: DeploymentMapEntry): void {
    const isDisabled = !this.deploymentMapEntry?.connectors || this.deploymentMapEntry?.connectors?.length === 0;
    // Use queueMicrotask for change detection cycle completion
    queueMicrotask(() => {
      this.isButtonDisabled$.next(isDisabled);
      this.cdr.markForCheck();
    });
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

  async onCommitButton(): Promise<void> {
    if (this.stepperConfiguration.allowTemplateExpansion) {
      this.mapping.sourceTemplate = reduceSourceTemplate(this.sourceTemplate, false);
      this.mapping.targetTemplate = reduceSourceTemplate(this.targetTemplate, false);
    } else {
      this.mapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
      this.mapping.targetTemplate = JSON.stringify(this.targetTemplate);
    }

    if (this.mappingCode) {
      this.mapping.code = stringToBase64(stripTemplateMetadataTags(this.mappingCode));
    }

    if (isSubstitutionsAsCode(this.mapping) && (!this.mapping.code || this.mapping.code === null || this.mapping.code === '')) {
      this.raiseAlert({ type: 'warning', text: "Internal error in editor. Try again!" });
      return;
    }

    this.commit.emit(this.mapping);
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

  async onCancelButton(): Promise<void> {
    this.cancel.emit();
  }

  onSelectExtensionName(extensionName: string): void {
    // Initialize extension object if it doesn't exist
    if (!this.mapping.extension) {
      this.mapping.extension = {} as any;
    }

    this.mapping.extension.extensionName = extensionName;
    this.stepperService.selectExtensionName(extensionName, this.extensions, this.mapping);
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
        const eventEntry = Object.values(extension.extensionEntries)
          .find(entry => entry.eventName === extensionEvent);

        if (eventEntry) {
          // Copy all properties from the extension entry
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

  async onStepChange(event: Pick<StepperSelectionEvent, 'selectedIndex'>): Promise<void> {
    this.currentStepIndex = event.selectedIndex;
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

  /** Patches extensionName/eventName/extensionParameter into templateForm on the next microtask (form isn't ready synchronously right after selectExtensionName). */
  private patchExtensionFormValues(): void {
    queueMicrotask(() => {
      this.templateForm.patchValue({
        extensionName: this.mapping.extension.extensionName,
        eventName: this.mapping.extension.eventName,
        extensionParameter: this.configurationToYaml(this.mapping.extension.parameter)
      });
      this.cdr.markForCheck();
    });
  }

  /** Builds the mapping snapshot sent to the Testing step / drawer, optionally including the encoded code. */
  private buildTestMapping(includeCode: boolean): Mapping {
    const testMapping = structuredClone(this.mapping);
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate);
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate);
    if (includeCode && this.mappingCode) {
      testMapping.code = stringToBase64(stripTemplateMetadataTags(this.mappingCode));
    }
    return testMapping;
  }

  private async handleGeneralSettingsStep(): Promise<void> {
    this.templatesInitialized = false;
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
    this.stepperService.updateSubstitutionValidity(
      this.mapping,
      this.stepperConfiguration.allowNoDefinedIdentifier,
      this.currentStepIndex,
      this.stepperConfiguration.showCodeEditor
    );

    this.updateTestingTemplate.next(this.buildTestMapping(false));

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
    this.updateTestingTemplate.next(this.buildTestMapping(true));
  }

  /**
   * Reads content directly from the underlying vanilla-jsoneditor instance, bypassing our own
   * `(contentChanged)` mirror (`sourceTemplateUpdated`/`targetTemplateUpdated`). That mirror only
   * updates when the library's `onChange` fires, which in practice doesn't fire for every edit
   * path (e.g. some tree-mode interactions) — `.get()` is the library's own source of truth and
   * is never stale.
   */
  private tryGetLiveEditorContent(editor: JsonEditorComponent | undefined): any {
    if (!editor) return undefined;
    try {
      return editor.get();
    } catch (error) {
      console.warn('Failed to read live editor content, falling back', error);
      return undefined;
    }
  }

  private updateTemplatesInEditors(): void {
    const liveSource = this.tryGetLiveEditorContent(this.templateStepRef?.editorSourceStepTemplate);
    const liveTarget = this.tryGetLiveEditorContent(this.templateStepRef?.editorTargetStepTemplate);
    this.sourceTemplate = liveSource ?? this.templateStepRef?.sourceTemplateUpdated ?? this.sourceTemplate;
    this.targetTemplate = liveTarget ?? this.templateStepRef?.targetTemplateUpdated ?? this.targetTemplate;
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
    const liveTarget = this.tryGetLiveEditorContent(this.templateStepRef?.editorTargetStepTemplate);
    if (liveTarget === undefined) return true; // can't verify — don't block on a guess
    return JSON.stringify(liveTarget) !== this.aiReviewBaseline;
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


  onValueCodeChange(value: string): void {
    this.mappingCode = value;
  }

  private hasEsmExport(code: string, exportName: string): boolean {
    const escapedExportName = exportName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const namedExportPattern = new RegExp(
      `\\bexport\\s*\\{[^}]*\\b${escapedExportName}\\b(?:\\s+as\\s+\\w+)?[^}]*\\}`,
      'm'
    );
    const directExportPattern = new RegExp(
      `\\bexport\\s+(?:async\\s+function|function|const|let|var|class)\\s+${escapedExportName}\\b`,
      'm'
    );

    return namedExportPattern.test(code) || directExportPattern.test(code);
  }

  onSelectCodeTemplate(): void {
    const template = this.codeTemplatesDecoded.get(this.templateId);
    if (!template) return;

    let code = stripTemplateMetadataTags(template.code);

    if (this.serviceConfiguration?.supportESM) {
      const exportName =
        this.mapping.transformationType === TransformationType.SMART_FUNCTION ? 'onMessage' :
        null;

      if (exportName) {
        const exportStatement = `export { ${exportName} };`;
        if (!this.hasEsmExport(code, exportName)) {
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

    const testMapping = this.buildTestMapping(false);

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