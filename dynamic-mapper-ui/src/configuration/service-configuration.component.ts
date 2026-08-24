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
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BehaviorSubject, from, map, Subject, takeUntil } from 'rxjs';
import packageJson from '../../package.json';
import { AIAgentService } from '../mapping/core/ai-agent.service';
import { Feature, Operation, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';

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
    this.subscribeToAIAgents();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initializeSettingsSection(): void {
    const href = this.router.url;
    if (href.includes('/serviceConfiguration/general')) {
      this.section = "general";
    } else if (href.includes('/serviceConfiguration/ai')) {
      this.section = "ai";
    } else if (href.includes('/serviceConfiguration/caching')) {
      this.section = "caching";
    } else {
      this.section = "logging";
    }
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
      explorerSessionTTLMinutes: [''],
      supportESM: [''],
      jsonataAgent: [{ value: '', disabled: true }],
      javaScriptAgent: [{ value: '', disabled: true }],
      smartFunctionAgent: [{ value: '', disabled: true }],
      suppressDeprecationWarning: [''],
      cacheAliasMaps: [''],
      externalIdBinding: [''],
    });
  }

  private subscribeToAIAgents(): void {
    from(this.aiAgentService.getAIAgents())
      .pipe(
        map(agents => agents.map(agent => agent.name)),
        takeUntil(this.destroy$)
      )
      .subscribe(agentNames => {
        this.agents$.next(agentNames);
        this.aiAgentDeployed = agentNames.length > 0;
        this.updateAgentControlsState();
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

  async clickedSaveServiceConfiguration() {
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
