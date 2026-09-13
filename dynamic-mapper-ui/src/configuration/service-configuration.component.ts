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
import { HttpStatusCode } from '@angular/common/http';
import { Component, inject, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { saveAs } from 'file-saver';
import { BehaviorSubject, from, map, Subject, takeUntil } from 'rxjs';
import packageJson from '../../package.json';
import { AIAgentService } from '../mapping/core/ai-agent.service';
import { Feature, Operation, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';
import { ImportServiceConfigurationComponent } from './import/import-service-configuration-modal.component';

@Component({
  selector: 'd11r-mapping-service-configuration',
  styleUrls: ['./service-configuration.component.style.css'],
  templateUrl: 'service-configuration.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, ReactiveFormsModule]
})
export class ServiceConfigurationComponent implements OnInit, OnDestroy {

  private alertService = inject(AlertService);
  private sharedService = inject(SharedService);
  private fb = inject(FormBuilder);
  private route = inject(ActivatedRoute);
  private aiAgentService = inject(AIAgentService);
  private readonly router = inject(Router);
  private readonly bsModalService = inject(BsModalService);

  version: string = packageJson.version;
  serviceForm: FormGroup;
  feature: Feature;
  section: string;

  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
    logConnectorErrorInBackend: false,
    sendConnectorLifecycle: false,
    sendMappingStatus: true,
    sendSubscriptionEvents: false,
    sendNotificationLifecycle: false,
    outboundMappingEnabled: true,
    deviceIsolationMQTTServiceEnabled: false,
    inboundExternalIdCacheSize: 0,
    inboundExternalIdCacheRetention: 0,
    outboundExternalIdCacheSize: 0,
    outboundExternalIdCacheRetention: 0,
    inventoryCacheSize: 0,
    inventoryCacheRetention: 0,
    flowStateRetention: 1440,
    mappingVersionRetention: 10,
    inventoryFragmentsToCache: ['type'],  // always add type
    maxCPUTimeMS: 5000,  // 5 seconds
    pipelineTimeoutMS: 8000,  // 8 seconds
    engineRotationThreshold: 100,
    engineMaxAgeMinutes: 0,
    contextPoolSize: 20,
    explorerSessionTTLMinutes: 10,
    jsonataAgent: undefined,
    javaScriptAgent: undefined,
    smartFunctionAgent: undefined,
    suppressDeprecationWarning: false,
    cacheAliasMaps: false,
    externalIdBinding: true,
  };
  agents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  destroy$: Subject<void> = new Subject<void>();
  aiAgentDeployed: boolean = false;
  inventoryFragmentsList: string[] = [''];

  trackByFragmentFn(index: any, _item: any) {
    return index;
  }

  addFragment() {
    this.inventoryFragmentsList.push('');
  }

  removeFragment(index: number) {
    this.inventoryFragmentsList.splice(index, 1);
  }


  async ngOnInit() {
    this.feature = this.route.snapshot.data['feature'];
    this.initializeForm();
    await this.loadData();
    this.initializeSettingsSection();
    this.restoreExpertMode();
    this.subscribeToAIAgents();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initializeSettingsSection(): void {
    const href = this.router.url;
    if (href.includes('/serviceConfiguration/processing')) {
      this.section = 'processing';
    } else if (href.includes('/serviceConfiguration/ai')) {
      this.section = 'ai';
    } else if (href.includes('/serviceConfiguration/caching')) {
      this.section = 'caching';
    } else if (href.includes('/serviceConfiguration/monitoring')
      // 'logging' is the pre-6.5 path; keep it working so bookmarks do not 404 into General.
      || href.includes('/serviceConfiguration/logging')) {
      this.section = 'monitoring';
    } else {
      this.section = 'general';
    }
  }

  /**
   * Settings that require understanding the runtime internals (GraalVM engine sizing, alias-map
   * caching, per-substitution logging). Hidden by default so the tabs show what an administrator
   * actually has to decide; revealed by the "Expert settings" toggle.
   *
   * Keyed by section so the "N settings hidden" hint below the list can be accurate — a count of
   * *everything* hidden would be misleading on a tab that hides nothing.
   */
  private static readonly EXPERT_SETTINGS: Readonly<Record<string, string[]>> = {
    processing: ['supportESM', 'engineRotationThreshold', 'engineMaxAgeMinutes', 'contextPoolSize'],
    caching: ['cacheAliasMaps'],
    monitoring: ['logSubstitution', 'logConnectorErrorInBackend']
  };

  private static readonly EXPERT_MODE_STORAGE_KEY = 'dynamic-mapper.serviceConfiguration.expertMode';

  expertMode = false;

  /** How many settings the current tab is hiding, for the hint under the list. */
  get hiddenExpertCount(): number {
    return ServiceConfigurationComponent.EXPERT_SETTINGS[this.section]?.length ?? 0;
  }

  toggleExpertMode(): void {
    this.expertMode = !this.expertMode;
    try {
      localStorage.setItem(
        ServiceConfigurationComponent.EXPERT_MODE_STORAGE_KEY,
        String(this.expertMode)
      );
    } catch {
      // Private browsing / blocked storage: the preference simply does not persist.
    }
  }

  private restoreExpertMode(): void {
    try {
      this.expertMode =
        localStorage.getItem(ServiceConfigurationComponent.EXPERT_MODE_STORAGE_KEY) === 'true';
    } catch {
      this.expertMode = false;
    }
  }

  /**
   * The JavaScript CPU budget is nested inside the end-to-end pipeline budget: if the pipeline
   * timeout is not strictly larger, the callback cancels the pipeline before the JavaScript can
   * ever reach its own limit, making `maxCPUTimeMS` unreachable. The backend defends itself by
   * raising the value at runtime; catching it here means the user sees what they actually get.
   */
  static pipelineTimeoutExceedsCpuBudget(group: AbstractControl): ValidationErrors | null {
    const cpu = Number(group.get('maxCPUTimeMS')?.value);
    const pipeline = Number(group.get('pipelineTimeoutMS')?.value);
    if (!Number.isFinite(cpu) || !Number.isFinite(pipeline) || cpu <= 0) {
      return null;
    }
    return pipeline > cpu ? null : { pipelineTimeoutTooSmall: { cpu, pipeline } };
  }

  private initializeForm(): void {
    this.serviceForm = this.fb.group({
      logPayload: [''],
      logSubstitution: [''],
      logConnectorErrorInBackend: [''],
      sendConnectorLifecycle: [''],
      sendMappingStatus: [''],
      sendSubscriptionEvents: [''],
      sendNotificationLifecycle: [''],
      outboundMappingEnabled: [''],
      deviceIsolationMQTTServiceEnabled: [''],
      inboundExternalIdCacheSize: [''],
      inboundExternalIdCacheRetention: [''],
      outboundExternalIdCacheSize: [''],
      outboundExternalIdCacheRetention: [''],
      inventoryCacheRetention: [''],
      inventoryCacheSize: [''],
      flowStateRetention: [''],
      mappingVersionRetention: [''],
      maxCPUTimeMS: [''],
      pipelineTimeoutMS: [''],
      engineRotationThreshold: [''],
      engineMaxAgeMinutes: [''],
      contextPoolSize: [''],
      explorerSessionTTLMinutes: [''],
      supportESM: [''],
      jsonataAgent: [{ value: '', disabled: true }],
      javaScriptAgent: [{ value: '', disabled: true }],
      smartFunctionAgent: [{ value: '', disabled: true }],
      suppressDeprecationWarning: [''],
      cacheAliasMaps: [''],
      externalIdBinding: [''],
    }, { validators: ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget });
  }

  private subscribeToAIAgents(): void {
    from(this.aiAgentService.getAIAgents())
      .pipe(
        map(agents => agents.map(agent => agent.name)),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: agentNames => {
          this.agents$.next(agentNames);
          this.aiAgentDeployed = agentNames.length > 0;
          this.updateAgentControlsState();
        },
        error: error => {
          console.error('Failed to check AI agent availability:', error);
          this.agents$.next([]);
          this.aiAgentDeployed = false;
          this.updateAgentControlsState();
        }
      });
  }

  private updateAgentControlsState(): void {
    const agentControls = ['javaScriptAgent', 'jsonataAgent', 'smartFunctionAgent'];
    agentControls.forEach(controlName => {
      const control = this.serviceForm.get(controlName);
      if (this.aiAgentDeployed) {
        control?.enable();
      } else {
        control?.disable();
      }
    });
  }

  private readonly SPARKPLUGB_BIRTH_FRAGMENTS = ['sparkPlugB_NBIRTH', 'sparkPlugB_DBIRTH'];

  async loadData(): Promise<void> {
    this.serviceConfiguration = await this.sharedService.getServiceConfiguration();
    const visibleFragments = (this.serviceConfiguration.inventoryFragmentsToCache ?? [])
      .filter(f => !this.SPARKPLUGB_BIRTH_FRAGMENTS.includes(f.trim()));

    this.inventoryFragmentsList = visibleFragments.length > 0 ? [...visibleFragments] : [''];

    this.serviceForm.patchValue({
      ...this.serviceConfiguration,
    });
  }

  async clickedClearInboundExternalIdCache() {
    await this.clearCache('INBOUND_ID_CACHE');
  }

  async clickedClearOutboundExternalIdCache() {
    await this.clearCache('OUTBOUND_ID_CACHE');
  }

  async clickedClearInventoryCache() {
    await this.clearCache('INVENTORY_CACHE');
  }

  async clickedClearFlowStateCache() {
    await this.clearCache('FLOW_STATE_CACHE');
  }

  async clickedRotateGraalVMEngine() {
    const response = await this.sharedService.runOperation({
      operation: Operation.ROTATE_GRAALVM_ENGINE,
    });
    if (response.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('GraalVM Engine rotation triggered.'));
    } else {
      this.alertService.danger(gettext('Failed to rotate GraalVM Engine!'));
    }
  }

  private async clearCache(cacheId: string): Promise<void> {
    const response = await this.sharedService.runOperation({
      operation: Operation.CLEAR_CACHE,
      parameter: { cacheId }
    });

    if (response.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

  /**
   * Writes the tenant's current service configuration to a JSON file.
   *
   * <p>A snapshot for restoring after a reset, so it deliberately contains the whole document —
   * including `codeTemplates`, which `ServiceConfigurationService.initialize()` wipes along with
   * everything else and which may hold Smart Function templates written by the customer.
   * There are no credentials in this document, unlike a connector export.
   */
  async clickedExportServiceConfiguration(): Promise<void> {
    try {
      // Read through the service rather than the form: the form only binds the settings the UI
      // renders, and a snapshot has to cover the whole document.
      const configuration = await this.sharedService.getServiceConfiguration();
      const blob = new Blob([JSON.stringify(configuration, undefined, 2)], {
        type: 'application/json'
      });
      saveAs(blob, 'service-configuration.json');
    } catch (error) {
      this.alertService.danger(gettext('Failed to export the service configuration'));
    }
  }

  clickedImportServiceConfiguration(): void {
    const modalRef: BsModalRef = this.bsModalService.show(ImportServiceConfigurationComponent, {
      initialState: {}
    });
    modalRef.content.closeSubject
      .pipe(takeUntil(this.destroy$))
      .subscribe(async (didImport: boolean) => {
        if (didImport) {
          // The restored document is what the service now holds — reload so the form shows it
          // instead of the values the user was looking at before the import.
          await this.loadData();
        }
        modalRef.hide();
      });
  }

  get pipelineTimeoutInvalid(): boolean {
    return !!this.serviceForm?.errors?.['pipelineTimeoutTooSmall'];
  }

  async clickedSaveServiceConfiguration() {
    if (this.pipelineTimeoutInvalid) {
      this.alertService.danger(
        gettext('The processing timeout must be greater than the CPU time limit.')
      );
      return;
    }
    const conf = this.serviceForm.value;

    conf.inventoryFragmentsToCache = this.inventoryFragmentsList
      .map(f => f.trim())
      .filter(f => f.length > 0 && !this.SPARKPLUGB_BIRTH_FRAGMENTS.includes(f));

    conf.javaScriptAgent = this.trimOrUndefined(this.serviceForm.value['javaScriptAgent']);
    conf.jsonataAgent = this.trimOrUndefined(this.serviceForm.value['jsonataAgent']);
    conf.smartFunctionAgent = this.trimOrUndefined(this.serviceForm.value['smartFunctionAgent']);

    const response = await this.sharedService.updateServiceConfiguration(conf);

    if (response.status >= 200 && response.status < 300) {
      this.alertService.success(gettext('Update successful'));
    } else {
      this.alertService.danger(gettext('Failed to update service configuration'));
    }
  }

  private trimOrUndefined(value: string | null | undefined): string | undefined {
    return value?.trim() || undefined;
  }
}
