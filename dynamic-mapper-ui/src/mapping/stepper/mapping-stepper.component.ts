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
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { C8yStepper, CoreModule } from '@c8y/ngx-components';
// Side-effect import, kept first: the type-only imports below are elided at build time, and
// shared/mapping/* have a circular import (mapping.model <-> processor.model) that only resolves
// when the `shared` barrel is evaluated before them. Without this the spec dies with
// "Cannot read properties of undefined (reading 'INBOUND')".
import '../../shared';
import {
  DeploymentMapEntry,
  Mapping,
  StepperConfiguration
} from '../../shared';
import { STEP_DEFINE_SUBSTITUTIONS, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_TEST_MAPPING } from '../../shared/mapping/stepper.model';
import {
  base64ToString,
  buildTestMapping,
  isCodeOrExtensionTransformation,
  stripTemplateMetadataTags,
  tryGetLiveEditorContent
} from '../../shared/mapping/util';
import { MappingStepTestingComponent } from '../step-testing/mapping-step-testing.component';
import { CommitEditorState, MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { MappingEditorBase } from '../editor-base/mapping-editor.base';
import { MappingExtensionSelectorComponent } from '../editor-base/mapping-extension-selector.component';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-properties/mapping-step-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-transformation/mapping-substitution-step.component';
import { MappingTemplateStepComponent } from '../step-template/mapping-template-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';

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
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  providers: [MappingStepperService, SubstitutionManagementService],
  imports: [CoreModule, CommonModule, EditorComponent, PopoverModule, MappingStepPropertiesComponent, MappingConnectorComponent, MappingSubstitutionStepComponent, MappingStepTestingComponent, MappingTemplateStepComponent, MappingExtensionSelectorComponent]
})
export class MappingStepperComponent extends MappingEditorBase implements OnInit {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() deploymentMapEntry!: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<CommitEditorState>();

  @ViewChild('templateStep', { static: false }) templateStepRef!: MappingTemplateStepComponent;
  @ViewChild('transformationStepRef', { static: false }) transformationStepRef!: MappingSubstitutionStepComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('stepper', { static: false }) stepper!: C8yStepper;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;

  protected readonly substitutionStepIndex = STEP_DEFINE_SUBSTITUTIONS;
  readonly STEP_DEFINE_SUBSTITUTIONS = STEP_DEFINE_SUBSTITUTIONS;

  labels: any = { next: 'Next', cancel: 'Cancel' };

  step?: string;
  stepperForward = true;
  currentStepIndex = 0;

  /**
   * Snapshot of the Cumulocity-side template's freshly-expanded default content, taken once when
   * "Generate with AI" was chosen at mapping creation. Used to detect whether the user has since
   * customized it before allowing them to leave the Select Templates step — an unedited generic
   * default (or an empty Smart Function target) makes for a poor AI generation prompt. Cleared
   * (undefined) once the check has passed, so it only ever gates the first attempt to move on.
   */
  private aiReviewBaseline?: string;

  async ngOnInit(): Promise<void> {
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

    this.applyEditorSession(init);
    this.schemaSource = init.schemaSource;
    this.schemaTarget = init.schemaTarget;
  }

  onCommitButton(): void {
    // Encoding and persistence belong to the host: the stepper is a controlled child, and the
    // grid decides from the CommitResult whether to close it. See
    // docs/planning/IMPLEMENTATION-PLAN-COMMIT-MAPPING.md.
    this.commit.emit({
      mapping: this.mapping,
      stepperConfiguration: this.stepperConfiguration,
      sourceTemplate: this.sourceTemplate,
      targetTemplate: this.targetTemplate,
      mappingCode: this.mappingCode
    });
  }

  /**
   * Moves to the substitutions step and selects one, so a validation failure reported after a
   * rejected save can put the user directly on the substitution that caused it.
   */
  async goToSubstitution(index: number): Promise<void> {
    if (this.stepper && this.stepper.selectedIndex !== STEP_DEFINE_SUBSTITUTIONS) {
      this.stepper.selectedIndex = STEP_DEFINE_SUBSTITUTIONS;
    }
    // The step's content is rendered lazily, so the child only exists after the switch above.
    await this.transformationStepRef?.onSelectSubstitution(index);
  }

  async onCancelButton(): Promise<void> {
    this.cancel.emit();
  }

  // Serialized (see enqueueTransition): the CDK stepper's selectionChange fires once per click without
  // waiting for the previous async handler, so two rapid "Next" clicks would otherwise overlap.
  async onStepChange(event: Pick<StepperSelectionEvent, 'selectedIndex'>): Promise<void> {
    return this.enqueueTransition(() => this.handleStepChange(event));
  }

  private async handleStepChange(event: Pick<StepperSelectionEvent, 'selectedIndex'>): Promise<void> {
    this.currentStepIndex = event.selectedIndex;
    this.refreshSubstitutionValidity();

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
    this.templatesInitialized = false;
    this.isLoadingExtensions = true;
    try {
      this.extensions = await this.stepperService.loadExtensions(this.mapping);
      this.updateExtensionItems(); // Update cached extension items

      // Re-patches form values after items are loaded so c8y-select can match them
      this.restoreExtensionSelection(true);
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
    this.restoreExtensionSelection();
  }

  private handleDefineSubstitutionsStep(): void {
    this.updateTemplatesInEditors();
    this.refreshSubstitutionValidity();

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
    if (this.stepperConfiguration.persistence === 'create' && !this.stepperConfiguration.isCopy && !this.templatesInitialized) {
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
}
