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
import { Component, inject, OnDestroy, OnInit, ViewChild, ViewEncapsulation, ChangeDetectionStrategy, signal, viewChild } from '@angular/core';
import {
  ActionControl,
  AlertService,
  BottomDrawerService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  CoreModule,
  DataGridComponent,
  DisplayOptions,
  Pagination
} from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import {
  createCustomUuid,
  DeploymentMapEntry,
  Direction,
  ExtensionEntry,
  Feature,
  isSubstitutionsAsCode,
  Mapping,
  MappingEnriched,
  MappingType,
  MappingTypeDescriptionMap,
  Operation,
  SharedModule,
  SharedService,
  StepperConfiguration,
  TransformationType
} from '../../shared';
import { ConfirmationModalService } from '../../shared/service/confirmation-modal.service';
import {
  StepperConfigurationContext,
  StepperConfigurationResolver
} from '../../shared/mapping/stepper-configuration.strategy';
import { HttpStatusCode } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, finalize, firstValueFrom, Subject, take, takeUntil } from 'rxjs';
import { CodeTemplate } from '../../shared/configuration/configuration.model';
import { MappingService } from '../core/mapping.service';
import { CopySource, CopySourceModalComponent } from './copy-source-modal/copy-source-modal.component';
import { MappingBulkOperationsService } from '../core/mapping-bulk-operations.service';
import { ExplorerMappingHandoffService } from '../core/explorer-mapping-handoff.service';
import { SubscriptionSuggestions } from '../step-subscription/outbound-subscription-selection.model';
import { buildMappingColumns } from './mapping-grid.columns';
import { buildNewMapping, ExplorerPreFill } from './new-mapping.factory';
import { SubscriptionService } from '../core/subscription.service';
import { ImportMappingsComponent } from '../import/import-mappings.component';
import { MappingVersionDrawerComponent } from '../versions/mapping-version-drawer.component';
import { MappingPublishService } from '../versions/mapping-publish.service';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';
import { MappingValidationDrawerComponent } from '../validation/mapping-validation-drawer.component';
import { MappingTypeDrawerComponent } from '../mapping-create/mapping-type-drawer.component';
import { MappingPersistence } from '../../shared/mapping/stepper.model';
import { serializeMappingsForExport } from '../../shared/mapping/util';
import { CommonModule } from '@angular/common';
import { isRawPayload } from '../message-explorer/message-explorer.service';
import { MappingStepperComponent } from '../stepper/mapping-stepper.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import {
  CommitEditorState,
  commitSuccessMessage,
  MappingCommitService,
  snapshotConnectors
} from '../service/mapping-commit.service';
import { provideGridPersistence } from '../../shared/misc/grid-persistence';



@Component({
  selector: 'd11r-mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CoreModule, CommonModule, SharedModule, MappingStepperComponent],
  // The grid owns the persistence half of the stepper's commit (the child owns the editing half),
  // so it needs its own MappingStepperService — the child provides its own instance for its
  // editing state, and commitMapping() keeps none.
  providers: [
    ...provideGridPersistence((url) => (url.includes('/mappings/inbound') ? 'mapping-inbound' : 'mapping-outbound')),
    MappingStepperService
  ],
})
export class MappingComponent implements OnInit, OnDestroy {
  readonly mappingGrid = viewChild<DataGridComponent>('mappingGrid');
  // Used to move the stepper to a substitution a validation failure points at.
  readonly mappingStepperRef = viewChild<MappingStepperComponent>('mappingStepper');

  private readonly showConfigMappingState = signal<boolean>(false);
  get showConfigMapping(): boolean { return this.showConfigMappingState(); }
  set showConfigMapping(value: boolean) { this.showConfigMappingState.set(value); }
  private readonly isLoadingState = signal<boolean>(false);
  get isLoading(): boolean { return this.isLoadingState(); }
  set isLoading(value: boolean) { this.isLoadingState.set(value); }

  readonly mappingsEnriched$ = new BehaviorSubject<MappingEnriched[]>([]);
  mappingsCount = 0;
  private readonly mappingToUpdateState = signal<Mapping>(undefined as unknown as Mapping);
  get mappingToUpdate(): Mapping { return this.mappingToUpdateState(); }
  set mappingToUpdate(value: Mapping) { this.mappingToUpdateState.set(value); }
  substitutionsAsCode = false;
  devices: IIdentified[] = [];
  Direction = Direction;

  private readonly stepperConfigurationState = signal<StepperConfiguration>({});
  get stepperConfiguration(): StepperConfiguration { return this.stepperConfigurationState(); }
  set stepperConfiguration(value: StepperConfiguration) { this.stepperConfigurationState.set(value); }
  titleMapping!: string;
  private readonly deploymentMapEntryState = signal<DeploymentMapEntry>(undefined as unknown as DeploymentMapEntry);
  get deploymentMapEntry(): DeploymentMapEntry { return this.deploymentMapEntryState(); }
  set deploymentMapEntry(value: DeploymentMapEntry) { this.deploymentMapEntryState.set(value); }
  /** {@link snapshotConnectors} as the editor opened, so a connector-only change is detectable
   * and an unchanged deployment is not rewritten. */
  private initialDeploymentConnectors = '';

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  columnsMappings!: Column[];

  mappingType!: MappingType;
  transformationType!: TransformationType;
  private readonly destroy$ = new Subject<void>();
  private explorerPreFill: ExplorerPreFill | null = null;
  /** Source device of the Message Explorer message, offered as choices in the Subscription step. */
  private explorerSubscriptionSuggestions: SubscriptionSuggestions | undefined;

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  actionControls: ActionControl[] = [];
  bulkActionControls: BulkActionControl[] = [];

  private readonly featureState = signal<Feature>(undefined as unknown as Feature);
  get feature(): Feature { return this.featureState(); }
  set feature(value: Feature) { this.featureState.set(value); }
  codeTemplate!: CodeTemplate;
  private extension?: Partial<ExtensionEntry>;
  private generateSmartFunctionWithAI = false;

  get canManageMappings(): boolean {
    return this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole;
  }

  private readonly subscriptionService = inject(SubscriptionService);
  private readonly mappingService = inject(MappingService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly commitService = inject(MappingCommitService);
  private readonly mappingBulkOpsService = inject(MappingBulkOperationsService);
  private readonly sharedService = inject(SharedService);
  private readonly alertService = inject(AlertService);
  private readonly translateService = inject(TranslateService);
  private readonly bsModalService = inject(BsModalService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly confirmationService = inject(ConfirmationModalService);
  private readonly publishService = inject(MappingPublishService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly explorerMappingHandoff = inject(ExplorerMappingHandoffService);

  constructor() {}

  async ngOnInit(): Promise<void> {
    this.isLoading = true;
    try {
      // Parse direction from route
      const href = this.router.url;
      this.stepperConfiguration.direction = href.includes('/mappings/inbound')
        ? Direction.INBOUND
        : Direction.OUTBOUND;

      // Initialize columns and title
      this.columnsMappings = this.getColumnsMappings();
      this.titleMapping = `Mapping ${this.stepperConfiguration.direction.toLowerCase()}`;

      this.setupActionControls();
      this.setupBulkActionControls();

      this.feature = this.route.snapshot.data['feature'];

      // Subscribe to mappings observable
      this.mappingService
        .getMappingsObservable(this.stepperConfiguration.direction)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: mappings => this.mappingsEnriched$.next(this.sortByName(mappings)),
          error: error => console.error('Unexpected error in mappings stream:', error)
        });

      // Track mappings count
      this.mappingsEnriched$
        .pipe(takeUntil(this.destroy$))
        .subscribe(maps => {
          this.mappingsCount = maps.length;
        });

      // Start listening to mapping changes
      await this.mappingService.startChangedMappingEvents();

      this.mappingService
        .listenToUpdateMapping()
        .pipe(takeUntil(this.destroy$))
        .subscribe((m: MappingEnriched) => {
          this.updateMapping(m);
        });

      // If navigated from Message Explorer, auto-open the stepper with pre-filled data.
      // consume() is single-shot, so navigating away (e.g. into the stepper) and back here
      // later never replays a stale hand-off.
      const explorerHandoff = this.explorerMappingHandoff.consume();
      if (explorerHandoff) {
        this.explorerPreFill = {
          sessionTopic: explorerHandoff.sessionTopic,
          topic: explorerHandoff.topic ?? '',
          payload: explorerHandoff.payload ?? '{}',
          binary: explorerHandoff.binary,
          payloadType: explorerHandoff.payloadType,
          binaryFormat: explorerHandoff.binaryFormat,
          payloadSize: explorerHandoff.payloadSize,
          clientId: explorerHandoff.clientId,
          connectorType: explorerHandoff.connectorType,
          key: explorerHandoff.key,
          metadata: explorerHandoff.metadata,
          additionalMessages: explorerHandoff.additionalMessages,
          targetAPI: explorerHandoff.targetAPI,
          publishTopic: explorerHandoff.publishTopic,
          publishTopicSample: explorerHandoff.publishTopicSample
        };
        this.explorerSubscriptionSuggestions = explorerHandoff.subscriptionSuggestions;
        this.mappingType = explorerHandoff.mappingType;
        this.transformationType = explorerHandoff.transformationType;
        // A binary, XML or plain-text payload cannot be parsed as JSON; the backend only passes raw
        // bytes (as base64 in msg.payload) to ANY_PAYLOAD + Smart Function mappings.
        if (isRawPayload({ binary: !!explorerHandoff.binary, payloadType: explorerHandoff.payloadType })
            && this.mappingType === MappingType.JSON
            && this.transformationType === TransformationType.SMART_FUNCTION) {
          this.mappingType = MappingType.ANY_PAYLOAD;
        }
        this.substitutionsAsCode = this.transformationType === TransformationType.SMART_FUNCTION;
        this.codeTemplate = explorerHandoff.codeTemplate;
        this.generateSmartFunctionWithAI = !!explorerHandoff.generateSmartFunctionWithAI;
        this.addMapping();
      }
    } finally {
      this.isLoading = false;
    }

  }

  /** Default row order for the grid; see the 'name' column in {@link getColumnsMappings}. */
  private sortByName(mappings: MappingEnriched[]): MappingEnriched[] {
    return [...mappings].sort((a, b) =>
      (a.mapping?.name ?? '').localeCompare(b.mapping?.name ?? '')
    );
  }

  private isDeprecatedMapping(row: unknown): boolean {
    const item = row as MappingEnriched;
    // eslint-disable-next-line @typescript-eslint/no-deprecated -- detecting the legacy type is the whole point of this check
    return item.mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE;
  }

  private setupActionControls() {
    this.actionControls.push(
      {
        // Active mappings are editable too: edits are saved to a draft (D-8), not applied
        // to the running configuration until published and activated.
        type: BuiltInActionType.Edit,
        callback: this.updateMapping.bind(this),
        showIf: item => this.canManageMappings && !this.isDeprecatedMapping(item),
      },
      {
        type: 'VIEW',
        icon: 'eye',
        callback: this.updateMapping.bind(this),
        showIf: item => !this.canManageMappings && !this.isDeprecatedMapping(item)
      },
      {
        type: 'VIEW_DEPRECATED',
        text: gettext('View (deprecated)'),
        icon: 'eye',
        callback: this.updateMapping.bind(this),
        showIf: item => this.isDeprecatedMapping(item)
      },
      {
        text: gettext('Duplicate'),
        type: 'DUPLICATE',
        icon: 'duplicate',
        callback: this.copyMapping.bind(this),
        showIf: item => this.canManageMappings && !this.isDeprecatedMapping(item)
      },
      {
        // Only offered when there is actually something to publish. Publishing otherwise lives
        // inside the version drawer, which makes the required follow-up after editing a mapping
        // easy to miss — this brings it one click from the grid.
        text: gettext('Publish draft'),
        type: 'PUBLISH_DRAFT',
        icon: 'upload',
        callback: this.publishDraft.bind(this),
        showIf: item => !!item['mapping']['draftDirty'] && this.canManageMappings
          && !this.isDeprecatedMapping(item)
      },
      {
        text: gettext('Versions'),
        type: 'VERSIONS',
        icon: 'list',
        callback: this.openVersions.bind(this),
        showIf: item => !this.isDeprecatedMapping(item)
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingWithConfirmation.bind(this),
        showIf: item => !item['mapping']['active'] && this.canManageMappings
      },
      {
        type: 'ENABLE_DEBUG',
        text: gettext('Enable debugging'),
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: item => !item['mapping']['debug'] && this.canManageMappings && !this.isDeprecatedMapping(item)
      },
      {
        type: 'DISABLE_DEBUG',
        text: gettext('Disable debugging'),
        icon: 'bug1',
        callback: this.toggleDebugMapping.bind(this),
        showIf: item => item['mapping']['debug'] && this.canManageMappings && !this.isDeprecatedMapping(item)
      },
      {
        type: 'EXPORT',
        text: gettext('Export mapping'),
        icon: 'export',
        callback: this.exportSingle.bind(this)
      }
    );
  }

  private setupBulkActionControls() {
    this.bulkActionControls.push(
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMappingBulkWithConfirmation.bind(this),
        showIf: (selectedItemIds: string[]) => {
          const activeMappings = this.mappingsEnriched$
            .getValue()
            ?.filter(m => m.mapping.active);
          const result = activeMappings?.some(m =>
            selectedItemIds?.includes(m.mapping.id)
          );
          return !result && this.canManageMappings;
        }
      },
      {
        type: 'ACTIVATE',
        text: gettext('Activate'),
        icon: 'toggle-on',
        callback: this.activateMappingBulk.bind(this),
        showIf: () => this.canManageMappings
      },
      {
        type: 'DEACTIVATE',
        text: gettext('Deactivate'),
        icon: 'toggle-off',
        callback: this.deactivateMappingBulk.bind(this),
        showIf: () => this.canManageMappings
      },
      {
        type: 'DEBUG_ENABLE',
        text: gettext('Enable debug'),
        icon: 'bug',
        callback: this.enableDebugMappingBulk.bind(this),
        showIf: () => this.canManageMappings
      },
      {
        type: 'DEBUG_DISABLE',
        text: gettext('Disable debug'),
        icon: 'ban',
        callback: this.disableDebugMappingBulk.bind(this),
        showIf: () => this.canManageMappings
      },
      {
        type: 'EXPORT',
        text: gettext('Export mapping'),
        icon: 'export',
        callback: this.exportMappingBulk.bind(this)
      }
    );
  }

  getColumnsMappings(): Column[] {
    return buildMappingColumns(this.stepperConfiguration.direction, row => this.openVersions(row));
  }

  async onAddMapping() {
    const initialState = {
      direction: this.stepperConfiguration.direction
    };

    const drawer = this.bottomDrawerService.openDrawer(MappingTypeDrawerComponent, { initialState: initialState });
    const resultOf = await drawer.instance.result;

    if (resultOf && typeof resultOf !== 'string') {
      this.transformationType = resultOf.transformationType;
      this.substitutionsAsCode = this.transformationType == TransformationType.SMART_FUNCTION;
      this.mappingType = resultOf.mappingType;
      this.codeTemplate = resultOf.codeTemplate;
      this.extension = resultOf.extension;
      this.generateSmartFunctionWithAI = !!resultOf.generateSmartFunctionWithAI;
      this.addMapping();
    }
  }

  async addMapping() {
    this.setStepperConfiguration(
      this.mappingType,
      this.transformationType,
      this.stepperConfiguration.direction,
      'create', this.substitutionsAsCode
    );

    // setStepperConfiguration() always builds a fresh StepperConfiguration object, so this flag
    // must be set on the resulting instance, not before the call.
    if (this.generateSmartFunctionWithAI) {
      this.stepperConfiguration.triggerAIGenerationOnStart = true;
      this.generateSmartFunctionWithAI = false;
    }

    if (this.explorerSubscriptionSuggestions) {
      this.stepperConfiguration.subscriptionSuggestions = this.explorerSubscriptionSuggestions;
      this.explorerSubscriptionSuggestions = undefined;
    }

    const direction = this.stepperConfiguration.direction;
    const { mapping, sampleMessageInfo, sampleTransportFields, additionalSamples } = buildNewMapping({
      direction,
      mappingType: this.mappingType,
      transformationType: this.transformationType,
      substitutionsAsCode: this.substitutionsAsCode,
      codeTemplate: this.codeTemplate,
      extension: this.extension,
      mappingsCount: this.mappingsCount,
      preFill: this.explorerPreFill
    });
    // Bump optimistically so a second "Add mapping" click before this one's save + list
    // refresh round-trips still gets a distinct name, instead of reading the same
    // not-yet-updated mappingsCount and generating a duplicate "Mapping - NN" name.
    this.mappingsCount++;
    this.explorerPreFill = null;
    this.extension = undefined;
    // Stepper state about the captured message; never persisted with the mapping.
    if (sampleMessageInfo) this.stepperConfiguration.sampleMessageInfo = sampleMessageInfo;
    if (sampleTransportFields) this.stepperConfiguration.sampleTransportFields = sampleTransportFields;
    if (additionalSamples) this.stepperConfiguration.additionalSamples = additionalSamples;

    this.mappingToUpdate = mapping;
    this.deploymentMapEntry = { identifier: mapping.identifier, connectors: [] };
    this.initialDeploymentConnectors = snapshotConnectors(this.deploymentMapEntry);
    this.showConfigMapping = true;
  }

  /**
   * Opens the mapping in the unified editor. Everything the editor needs — the mapping or its
   * draft, the deployment entry, the stepper configuration — is loaded by `mappingEditResolver`,
   * so nothing is prepared here: the edit route replaces this grid, it does not nest in it.
   */
  updateMapping(m: MappingEnriched): void {
    this.router.navigate(['edit', m.mapping.identifier], { relativeTo: this.route });
  }

  async publishDraft(m: MappingEnriched): Promise<void> {
    const outcome = await this.publishService.publishDraft(m.mapping);
    if (outcome.published) {
      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    }
  }

  openVersions(m: MappingEnriched) {
    const drawer = this.bottomDrawerService.openDrawer(MappingVersionDrawerComponent, {
      initialState: { mapping: m.mapping, canManage: this.canManageMappings }
    });
    drawer.instance.closeSubject
      .pipe(takeUntil(this.destroy$))
      .subscribe((changed: boolean) => {
        if (changed) {
          this.mappingService.refreshMappings(this.stepperConfiguration.direction);
        }
      });
  }

  async copyMapping(m: MappingEnriched) {
    const source = await this.chooseCopySource(m.mapping);
    if (!source) return;
    // The list holds the active configuration; edits live in the draft until published.
    const mapping = source === 'draft' ? await this.mappingService.getDraft(m.mapping.id) ?? m.mapping : m.mapping;
    this.setStepperConfiguration(
      mapping.mappingType,
      mapping.transformationType,
      mapping.direction,
      'create',
      isSubstitutionsAsCode(mapping),
      true
    );
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = structuredClone(mapping) as Mapping;

    this.mappingToUpdate = {
      ...this.mappingToUpdate,
      name: `${this.mappingToUpdate.name} - Copy`,
      identifier: createCustomUuid(),
      id: createCustomUuid(),
      active: true,
      version: undefined,
      draftDirty: false,
      versionNote: `Copy from ${mapping.name}`,
    }

    const deploymentMapEntry =
      await this.mappingService.getDefinedDeploymentMapEntry(mapping.identifier);
    this.deploymentMapEntry = {
      identifier: this.mappingToUpdate.identifier,
      connectors: deploymentMapEntry.connectors
    };
    this.initialDeploymentConnectors = snapshotConnectors(this.deploymentMapEntry);
    if (mapping.direction === Direction.OUTBOUND && deploymentMapEntry.connectors.length > 0) {
      this.stepperConfiguration = { ...this.stepperConfiguration, allowTestSending: true };
    }

    this.showConfigMapping = true;
  }

  /**
   * A line with unpublished changes has two candidates to copy. Resolves to the choice, to
   * 'active' without asking when there is no draft, or to null when the user cancels.
   */
  private async chooseCopySource(mapping: Mapping): Promise<CopySource | null> {
    if (!mapping.draftDirty) return 'active';
    const modalRef = this.bsModalService.show(CopySourceModalComponent, {
      initialState: { mappingName: mapping.name, activeVersion: mapping.version }
    });
    return (await firstValueFrom(modalRef.content!.closeSubject, { defaultValue: null })) ?? null;
  }

  async toggleDebugMapping(m: MappingEnriched) {
    const { mapping } = m;
    const newDebug = !mapping.debug;
    const parameter = { id: mapping.id, debug: newDebug };
    await this.mappingService.changeDebuggingMapping(parameter);
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
  }

  async deleteMappingWithConfirmation(
    m: MappingEnriched,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean> {
    if (!confirmation) {
      return await this.deleteMapping(m);
    }

    const confirmed = await this.confirmationService.confirmDeletion('mapping', multiple);
    if (confirmed) {
      return await this.deleteMapping(m);
    }
    return false;
  }

  async deleteMapping(m: MappingEnriched): Promise<boolean> {
    const { mapping } = m;
    try {
      await this.mappingService.deleteMapping(mapping.id, mapping.direction);
      this.alertService.success(
        this.translateService.instant(gettext('Mapping {{name}} deleted successfully'), { name: mapping.name })
      );
      return true;
    } catch (error) {
      this.alertService.danger(
        this.translateService.instant(gettext('Failed to delete mapping {{name}}:'), { name: mapping.name }) + error
      );
      return false;
    }
  }

  async onCommitMapping(editorState: CommitEditorState): Promise<void> {
    const result = await this.commitService.commitMapping({
      ...editorState,
      initialDeploymentConnectors: this.initialDeploymentConnectors
      // No `forms`: cdk-stepper enforces linear completion, so the user cannot reach Save with an
      // earlier step invalid. Those blockers are the unified editor's concern.
    });

    if (result.status === 'blocked') {
      if (result.message) this.alertService.warning(result.message);
      return;
    }

    // Closing the editor on a failed save discarded the user's edits along with it, leaving them
    // nothing to correct. Keep it open so the reported problems can actually be fixed and re-saved.
    if (result.status === 'rejected') {
      await this.showValidationIssues(editorState.mapping, result.error);
      return;
    }
    if (result.status === 'failed') {
      return; // the alert has already been raised
    }

    const alert = commitSuccessMessage(
      result,
      editorState.mapping.name,
      this.stepperConfiguration.persistence,
      this.translateService
    );
    // add() rather than success(): success() leaves the timeout unset, and Cumulocity then
    // auto-dismisses a detail-less success alert after 3s — too short to read an instruction.
    if (alert) this.alertService.add({ text: alert.text, type: 'success', timeout: alert.timeout });

    this.showConfigMapping = false;
  }

  /**
   * Lists the rejected-save problems and, if the user picks one tied to a substitution, moves the
   * stepper to the transformation step and selects it so it is ready to edit.
   */
  private async showValidationIssues(mapping: Mapping, error: MappingValidationError): Promise<void> {
    const drawer = this.bottomDrawerService.openDrawer(MappingValidationDrawerComponent, {
      initialState: {
        issues: error.details,
        errors: error.errors,
        mappingName: mapping?.name
      }
    });

    const result = await drawer.instance.result;
    if (result.action !== 'goto') {
      return;
    }
    await this.mappingStepperRef()?.goToSubstitution(result.index);
  }

  async onReload() {
    this.isLoading = true;
    try {
      await this.reloadMappingsInBackend();
    } finally {
      this.isLoading = false;
    }
  }

  async exportMappings(mappings2Export: Mapping[]): Promise<void> {
    const json = serializeMappingsForExport(mappings2Export);
    const blob = new Blob([json]);
    saveAs(blob, `mappings-${this.stepperConfiguration.direction}.json`);
  }

  private exportMappingBulk(ids: string[]) {
    this.mappingBulkOpsService.exportBulk(
      ids,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );
  }

  private activateMappingBulk(ids: string[]) {
    this.mappingBulkOpsService.activateBulk(
      ids,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );

    this.subscriptionService.validateSubscriptionOutbound(this.stepperConfiguration.direction);
  }

  private deactivateMappingBulk(ids: string[]) {
    this.mappingBulkOpsService.deactivateBulk(
      ids,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );
  }

  private enableDebugMappingBulk(ids: string[]) {
    this.mappingBulkOpsService.debugBulk(
      ids,
      true,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );
  }

  private disableDebugMappingBulk(ids: string[]) {
    this.mappingBulkOpsService.debugBulk(
      ids,
      false,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );
  }

  private async deleteMappingBulkWithConfirmation(ids: string[]) {
    await this.mappingBulkOpsService.deleteBulkWithConfirmation(
      ids,
      this.mappingsEnriched$,
      this.stepperConfiguration.direction,
      this.mappingGrid(),
      this.destroy$,
      (loading) => this.isLoading = loading
    );
  }

  async onAddSampleMappings() {
    this.isLoading = true;
    try {
      const response = await this.mappingService.addSampleMappings({ direction: this.stepperConfiguration.direction });
      if (response.status == HttpStatusCode.Created) {
        this.alertService.success(
          this.translateService.instant(gettext('Added sample mappings for {{direction}}'), { direction: this.stepperConfiguration.direction })
        );
        this.mappingService.refreshMappings(this.stepperConfiguration.direction);
      }
    } catch (error) {
      this.alertService.danger(gettext('Failed to add sample mappings'), error);
    } finally {
      this.isLoading = false;
    }
  }

  async onExportAll() {
    this.isLoading = true;
    this.mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(this.destroy$),
        finalize(() => {
          this.isLoading = false;
        })
      )
      .subscribe((ms) => {
        try {
          const mappings2Export = ms.map((me) => me.mapping);
          this.exportMappings(mappings2Export);
        } catch (error) {
          this.alertService.danger(gettext('Failed to export mappings'), error);
        }
      });
  }

  async exportSingle(m: MappingEnriched) {
    const { mapping } = m;
    const mappings2Export = [mapping];
    this.exportMappings(mappings2Export);
  }

  async onImport() {
    const initialState = {};
    const modalRef = this.bsModalService.show(ImportMappingsComponent, {
      initialState
    });
    modalRef.content.closeSubject
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.mappingService.refreshMappings(this.stepperConfiguration.direction);
        modalRef.hide();
      });
  }

  private async reloadMappingsInBackend() {
    const response2 = await this.sharedService.runOperation(
      { operation: Operation.RELOAD_MAPPINGS }
    );
    // console.log('Activate mapping response:', response2);
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings reloaded'));
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  setStepperConfiguration(
    mappingType: MappingType,
    transformationType: TransformationType,
    direction: Direction,
    persistence: MappingPersistence,
    substitutionsAsCode: boolean,
    isCopy = false
  ) {
    const baseConfig = MappingTypeDescriptionMap[mappingType].stepperConfiguration;

    const context: StepperConfigurationContext = {
      mappingType,
      transformationType,
      direction,
      persistence,
      isCopy,
      substitutionsAsCode
    };

    this.stepperConfiguration = StepperConfigurationResolver.resolve(
      baseConfig,
      context
    );

    // For debugging (can be removed in production):
    // const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);
    // const descriptions = StepperConfigurationResolver.getAppliedOverrideDescriptions(context);
    // console.log('Applied configuration overrides:', appliedOverrides);
    // console.log('Override descriptions:', descriptions);
    // console.log('Final configuration:', this.stepperConfiguration);
  }

  get realtimeActive(): boolean {
    return this.mappingService.realtimeActive;
  }

  toggleRealtime(): void {
    this.mappingService.setRealtimeActive(!this.mappingService.realtimeActive);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.mappingService.stopChangedMappingEvents();
  }

  async refreshMappings() {
    this.isLoading = true;
    try {
      await this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    } finally {
      this.isLoading = false;
    }
  }

  async clickedResetDeploymentMapEndpoint() {
    this.isLoading = true;
    try {
      const response1 = await this.sharedService.runOperation(
        { operation: Operation.RESET_DEPLOYMENT_MAP }
      );

      const response2 = await this.sharedService.runOperation(
        { operation: Operation.RELOAD_MAPPINGS }
      );
      // console.log('Details reconnect2NotificationEndpoint', response1);
      if (response1.status === HttpStatusCode.Created && response2.status === HttpStatusCode.Created) {
        this.alertService.success(gettext('Reset deployment cache.'));
      } else {
        this.alertService.danger(gettext('Failed to reset deployment cache!'));
      }

      this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    } catch (error) {
      this.alertService.danger(gettext('Failed to reset deployment cache: ') + error.message);
    } finally {
      this.isLoading = false;
    }
  }

}