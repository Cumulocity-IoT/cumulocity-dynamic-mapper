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
  Component,
  inject,
  isDevMode,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { CoreModule, TabComponent, TabsOutletComponent } from '@c8y/ngx-components';
import { GlobalContextService } from '@c8y/ngx-components/global-context';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  DeploymentMapEntry,
  Mapping,
  StepperConfiguration,
  TransformationType,
  MappingTypeLabels,
  MappingType
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';
import { MappingEditData } from '../core/mapping-edit.resolver';
import {
  base64ToString,
  buildTestMapping,
  captureMappingContentSnapshot,
  describeMappingContentChanges,
  hasMappingContentChanged,
  isConnectorSelectionEmpty,
  MappingContentSnapshot,
  stripTemplateMetadataTags
} from '../../shared/mapping/util';
import { gettext } from '@c8y/ngx-components/gettext';
import { ConfirmationModalComponent } from '../../shared';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';
import { ConfirmsUnsavedChanges } from '../core/unsaved-changes.guard';
import { MappingValidationDrawerComponent } from '../validation/mapping-validation-drawer.component';
import { MappingStepTestingComponent } from '../step-testing/mapping-step-testing.component';
import {
  CommitBlocker,
  commitSuccessMessage,
  MappingStepperService,
  snapshotConnectors
} from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { CommonModule } from '@angular/common';
import { MappingStepPropertiesComponent } from '../step-properties/mapping-step-properties.component';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingSubstitutionStepComponent } from '../step-transformation/mapping-substitution-step.component';
import { MappingTemplateStepComponent } from '../step-template/mapping-template-step.component';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { MappingEditorBase } from '../editor-base/mapping-editor.base';
import { MappingExtensionSelectorComponent } from '../editor-base/mapping-extension-selector.component';

// Tab index constants
const TAB_CONNECTOR = 0;
const TAB_GENERAL_SETTINGS = 1;
const TAB_SELECT_TEMPLATES = 2;
const TAB_DEFINE_TRANSFORMATION = 3;
const TAB_TEST_MAPPING = 4;

/** Where a blocked commit sends the user. This is the only place that knows tabs exist —
 * {@link MappingStepperService.commitMapping} reports the blocker and owns no navigation. */
const TAB_FOR_BLOCKER: Partial<Record<CommitBlocker, number>> = {
  [CommitBlocker.NO_CONNECTOR]: TAB_CONNECTOR,
  [CommitBlocker.MAPPING_TOPIC]: TAB_GENERAL_SETTINGS,
  [CommitBlocker.PROPERTY_FORM]: TAB_GENERAL_SETTINGS,
  [CommitBlocker.EXTENSION_SELECTION]: TAB_SELECT_TEMPLATES
};

/**
 * Unified editor component that presents all 5 mapping configuration sections as tabs
 * instead of a sequential stepper. Intended for use when editing a fully-defined mapping.
 */
@Component({
  selector: 'd11r-mapping-unified-editor',
  templateUrl: 'mapping-unified-editor.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
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
    MappingStepTestingComponent,
    MappingExtensionSelectorComponent
  ]
})
export class MappingUnifiedEditorComponent extends MappingEditorBase implements OnInit, ConfirmsUnsavedChanges {
  mapping!: Mapping;
  stepperConfiguration!: StepperConfiguration;
  deploymentMapEntry!: DeploymentMapEntry;

  @ViewChild('templateStep', { static: false }) templateStepRef!: MappingTemplateStepComponent;
  @ViewChild('mappingTestingStep', { static: false }) mappingTestingStep!: MappingStepTestingComponent;
  @ViewChild('codeEditor', { static: false }) codeEditor!: EditorComponent;
  // Needed to select the substitution a validation failure points at.
  @ViewChild('transformationStep', { static: false }) transformationStepRef!: MappingSubstitutionStepComponent;

  private readonly mappingService = inject(MappingService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly globalContextService = inject(GlobalContextService);

  readonly MappingTypeLabels = MappingTypeLabels;

  // Exposed so the template can reference named tabs instead of magic numbers
  readonly TAB_CONNECTOR = TAB_CONNECTOR;
  readonly TAB_GENERAL_SETTINGS = TAB_GENERAL_SETTINGS;
  readonly TAB_SELECT_TEMPLATES = TAB_SELECT_TEMPLATES;
  readonly TAB_DEFINE_TRANSFORMATION = TAB_DEFINE_TRANSFORMATION;
  readonly TAB_TEST_MAPPING = TAB_TEST_MAPPING;

  currentStepIndex = TAB_CONNECTOR;
  activeTabIndex = TAB_CONNECTOR;

  protected readonly substitutionStepIndex = TAB_DEFINE_TRANSFORMATION;

  // Snapshots for change detection — set once at load time
  private initialContentSnapshot: MappingContentSnapshot = { mappingJson: '', sourceTemplateJson: '', targetTemplateJson: '', mappingCode: '' };
  private initialDeploymentConnectors = '';

  async ngOnInit(): Promise<void> {
    // Hide auto-refresh button – this is an editor page, not a live-data view
    this.globalContextService.register('mapping-unified-editor', { showAutoRefresh: false, showTimeContext: false });

    // Load mapping data resolved by the route
    const editData: MappingEditData = this.route.snapshot.data['mappingEdit'];
    this.mapping = editData.mapping;
    this.stepperConfiguration = editData.stepperConfiguration;
    this.deploymentMapEntry = editData.deploymentMapEntry;
    this.initialDeploymentConnectors = snapshotConnectors(this.deploymentMapEntry);
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

    this.applyEditorSession(init);

    // For the unified editor, expand existing templates upfront since mapping is fully defined
    await this.initializeTemplates();

    this.schemaSource = init.schemaSource;
    this.schemaTarget = init.schemaTarget;
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
      // Not left to handleGeneralSettingsTab(): the editor can open on a tab that never runs it.
      if (this.mapping.extension.parameter) this.hasExtensionParameter = true;
      this.stepperService.selectExtensionName(
        this.mapping.extension.extensionName,
        this.extensions,
        this.mapping
      );
      this.patchExtensionFormValues();
    }

    // Validate substitutions with initial tab index
    this.refreshSubstitutionValidity();
  }

  override ngOnDestroy(): void {
    this.globalContextService.unregister('mapping-unified-editor');
    super.ngOnDestroy();
  }

  /**
   * Returns true if the tab at the given index should be visible.
   * Tabs between advanceFromStepToEndStep (exclusive) and the last tab (exclusive) are hidden.
   * The last tab (Testing) is always visible.
   */
  isTabVisible(tabIndex: number): boolean {
    // Deprecated SUBSTITUTION_AS_CODE mappings: hide Testing tab (can't be processed)
    // eslint-disable-next-line @typescript-eslint/no-deprecated
    if (this.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE && tabIndex === TAB_TEST_MAPPING) {
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
  // Serialized (see enqueueTransition): onTabSelected() does real awaited work per tab, and a tab UI
  // invites fast back-and-forth clicking — arguably more than the linear stepper does.
  async onTabSelected(newIndex: number): Promise<void> {
    return this.enqueueTransition(() => this.handleTabSelected(newIndex));
  }

  private async handleTabSelected(newIndex: number): Promise<void> {
    // Sync template changes when leaving the Templates tab
    if (this.activeTabIndex === TAB_SELECT_TEMPLATES) {
      this.updateTemplatesInEditors();
    }

    this.activeTabIndex = newIndex;
    this.currentStepIndex = newIndex;

    this.refreshSubstitutionValidity();

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
    this.isLoadingExtensions = true;
    try {
      this.extensions = await this.stepperService.loadExtensions(this.mapping);
      this.updateExtensionItems();
      this.restoreExtensionSelection(true);
    } finally {
      this.isLoadingExtensions = false;
    }
  }

  private async handleSelectTemplatesTab(): Promise<void> {
    if (this.mapping.filterMapping) {
      await this.templateStepRef?.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    // Do NOT re-derive mappingCode from mapping.code here: mapping.code is only
    // updated at save time, so this would clobber any in-progress code edits made
    // on the Transformation tab. Seeding already happened once in initializeTemplates().

    this.restoreExtensionSelection();
  }

  private handleDefineSubstitutionsTab(): void {
    this.updateTemplatesInEditors();
    this.refreshSubstitutionValidity();

    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, false));
  }

  private handleTestMappingTab(): void {
    this.updateTestingTemplate.next(buildTestMapping(this.mapping, this.sourceTemplate, this.targetTemplate, this.mappingCode, true));
  }

  async onCommitButton(): Promise<void> {
    // Sync any pending template edits before saving
    this.updateTemplatesInEditors();

    const result = await this.stepperService.commitMapping({
      mapping: this.mapping,
      deploymentMapEntry: this.deploymentMapEntry,
      initialDeploymentConnectors: this.initialDeploymentConnectors,
      stepperConfiguration: this.stepperConfiguration,
      sourceTemplate: this.sourceTemplate,
      targetTemplate: this.targetTemplate,
      mappingCode: this.mappingCode,
      initialContentSnapshot: this.initialContentSnapshot,
      forms: {
        propertyFormly: this.propertyFormly,
        templateForm: this.templateForm,
        // showExtensionSelectors also covers showInternalExtensionNote (PROTOBUF_INTERNAL), where
        // no selectors are rendered and the form controls are always null.
        validateExtensionSelection:
          this.stepperViewModel.showExtensionSelectorsSource || this.stepperViewModel.showExtensionSelectorsTarget
      }
    });

    if (result.status === 'blocked') {
      if (result.message) this.raiseAlert({ type: 'warning', text: result.message });
      const tab = TAB_FOR_BLOCKER[result.blocker];
      // Through the normal transition (not a bare activeTabIndex assignment) so currentStepIndex
      // and the per-tab setup stay consistent — same reasoning as showValidationIssues().
      if (tab !== undefined) await this.onTabSelected(tab);
      return;
    }

    if (result.status === 'rejected') {
      // A validation rejection is actionable — show the problems in a drawer and offer to jump to
      // the offending substitution. Returning here keeps the editor open, so the edits survive.
      await this.showValidationIssues(result.error);
      return;
    }

    if (result.status === 'failed') {
      return; // the alert has already been raised
    }

    const alert = commitSuccessMessage(result, this.mapping.name, this.stepperConfiguration.persistence);
    // add() rather than success(): success() leaves the timeout unset, and Cumulocity then
    // auto-dismisses a detail-less success alert after 3s — too short to read an instruction.
    if (alert) this.alertService.add({ text: alert.text, type: 'success', timeout: alert.timeout });

    // Saving closes the editor. Re-baselining first matters: navigation is guarded by
    // unsavedChangesGuard, which would otherwise see the just-saved edits as unsaved and prompt.
    this.rebaselineAfterSave(result.persisted, !!result.deploymentFailed);

    // Shared with the stepper's commit path (mapping.component.ts::onCommitMapping) so both
    // editors apply the same post-save check — an OUTBOUND mapping with no device subscribed
    // to receive it otherwise silently does nothing once activated.
    // It is advisory and the mapping is already saved, so a failure must not strand the user in
    // an editor whose state was just rebaselined.
    try {
      await this.subscriptionService.validateSubscriptionOutbound(this.stepperConfiguration.direction);
    } catch (error) {
      console.error('Post-save subscription check failed:', error);
      this.alertService.warning(gettext('The mapping was saved, but checking the device subscriptions failed.'));
    }

    // The mapping is saved but its connector assignment is not (error alert already raised):
    // stay in the editor so the selection isn't lost and Save can retry the deployment.
    if (result.deploymentFailed) return;

    this.navigateToGrid();
  }

  /**
   * Makes the editor consistent with what the server just stored, so the leave guard sees no
   * unsaved changes and, if the editor is still open, a second Save behaves like a first one.
   *
   * The `lastUpdate` adoption is the load-bearing part: it is the optimistic-concurrency token,
   * the server issues a new one on every draft save, and re-sending the stale one makes the next
   * save fail with "modified concurrently". Re-taking the two snapshots is what stops that second
   * save from writing a draft, or rewriting the deployment, when nothing has changed since.
   */
  private rebaselineAfterSave(persisted: Mapping, deploymentFailed: boolean): void {
    if (persisted) {
      this.mapping.lastUpdate = persisted.lastUpdate;
      this.mapping.version = persisted.version;
      this.mapping.draftDirty = persisted.draftDirty;
      // Defensive: the editor has no create flow today (it is reached only via /edit/:id), but if
      // one is ever added, a second Save must update rather than create a duplicate.
      if (persisted.id) this.mapping.id = persisted.id;
    }

    this.initialContentSnapshot = captureMappingContentSnapshot(
      this.mapping,
      this.sourceTemplate,
      this.targetTemplate,
      this.mappingCode
    );
    // Keep the old baseline when the deployment write failed, so the selection still counts as
    // changed (unsaved-changes guard) and the next Save retries it.
    if (!deploymentFailed) {
      this.initialDeploymentConnectors = snapshotConnectors(this.deploymentMapEntry);
    }
  }

  onCancel(): void {
    // Navigation itself is guarded by unsavedChangesGuard, so there is no confirmation here —
    // otherwise closing with unsaved edits would prompt twice.
    this.navigateToGrid();
  }

  /**
   * Whether the editor holds edits that have not been saved.
   *
   * Syncs the Monaco editors first: `sourceTemplate`/`targetTemplate` are only written back on
   * {@link updateTemplatesInEditors}, so a comparison without it misses whatever the user typed
   * since the last tab switch — which is exactly the work most worth protecting.
   */
  private hasUnsavedChanges(): boolean {
    if (this.stepperConfiguration.readOnly) return false;

    this.updateTemplatesInEditors();
    const contentChanged = hasMappingContentChanged(
      this.mapping,
      this.sourceTemplate,
      this.targetTemplate,
      this.mappingCode,
      this.initialContentSnapshot
    );
    const connectorsChanged = snapshotConnectors(this.deploymentMapEntry) !== this.initialDeploymentConnectors;
    if (isDevMode() && (contentChanged || connectorsChanged)) {
      // Dev builds only. Says what the prompt is about, so an unexpected "unsaved changes" can be traced to its cause.
      console.info('[mapping editor] unsaved changes:', [
        ...describeMappingContentChanges(
          this.mapping,
          this.sourceTemplate,
          this.targetTemplate,
          this.mappingCode,
          this.initialContentSnapshot
        ),
        ...(connectorsChanged
          ? [`connectors: ${this.initialDeploymentConnectors} -> ${snapshotConnectors(this.deploymentMapEntry)}`]
          : [])
      ]);
    }
    return contentChanged || connectorsChanged;
  }

  /** {@link ConfirmsUnsavedChanges} — called by `unsavedChangesGuard` on every way out. */
  async confirmLeaveWithUnsavedChanges(): Promise<boolean> {
    if (!this.hasUnsavedChanges()) return true;

    const modalRef = this.bsModalService.show(ConfirmationModalComponent, {
      initialState: {
        title: gettext('Discard unsaved changes'),
        message: gettext(
          'This mapping has changes that have not been saved. Leaving now discards them.'
        ),
        labels: { ok: gettext('Discard and leave'), cancel: gettext('Keep editing') }
      }
    });
    // defaultValue guards the case where the modal is destroyed without answering:
    // ConfirmationModalComponent.ngOnDestroy completes closeSubject without emitting, and a bare
    // firstValueFrom would then reject with EmptyError and break the navigation outright.
    // Defaulting to false keeps the user in the editor, which is the safe direction.
    return firstValueFrom(modalRef.content!.closeSubject, { defaultValue: false });
  }

  private navigateToGrid(): void {
    // Drop any query string / fragment first, or the end-anchored regex below never matches.
    const gridUrl = this.router.url.split(/[?#]/)[0].replace(/\/edit\/[^\/]+$/, '');
    this.router.navigateByUrl(gridUrl);
  }

  /**
   * Lists the rejected-save problems and, if the user picks one tied to a substitution, switches
   * to the transformation tab and selects it so it is ready to edit.
   */
  private async showValidationIssues(error: MappingValidationError): Promise<void> {
    const drawer = this.bottomDrawerService.openDrawer(MappingValidationDrawerComponent, {
      initialState: {
        issues: error.details,
        errors: error.errors,
        mappingName: this.mapping?.name
      }
    });

    const result = await drawer.instance.result;
    if (result.action !== 'goto') {
      return;
    }

    // Go through the normal transition rather than assigning activeTabIndex: it is queued behind
    // any in-flight tab change and performs the per-tab setup the step depends on.
    await this.onTabSelected(TAB_DEFINE_TRANSFORMATION);
    // The tab's content is rendered lazily, so the step only exists once that has been applied.
    this.cdr.detectChanges();
    await this.transformationStepRef?.onSelectSubstitution(result.index);
    this.cdr.detectChanges();
  }
}
