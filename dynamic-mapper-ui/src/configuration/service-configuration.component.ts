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
import { ActivatedRoute } from '@angular/router';
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

  version: string = packageJson.version;
  serviceForm: FormGroup;
  feature: Feature;

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
    inventoryCacheSize: 0,
    inventoryCacheRetention: 0,
    inventoryFragmentsToCache: ['type'],  // always add type
    maxCPUTimeMS: 5000,  // 5 seconds
    jsonataAgent: undefined,
    javaScriptAgent: undefined,
    smartFunctionAgent: undefined,
  };
  editable2updated: boolean = false;

  agents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  destroy$: Subject<void> = new Subject<void>();
  aiAgentDeployed: boolean = false;


  async ngOnInit() {
    this.feature = this.route.snapshot.data['feature'];
    this.initializeForm();
    await this.loadData();
    this.subscribeToAIAgents();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
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
      inventoryCacheRetention: [''],
      inventoryCacheSize: [''],
      inventoryFragmentsToCache: [''],
      maxCPUTimeMS: [''],
      jsonataAgent: [{ value: '', disabled: true }],
      javaScriptAgent: [{ value: '', disabled: true }],
      smartFunctionAgent: [{ value: '', disabled: true }],
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

  async loadData(): Promise<void> {
    this.serviceConfiguration = await this.sharedService.getServiceConfiguration();

    this.serviceForm.patchValue({
      ...this.serviceConfiguration,
      inventoryFragmentsToCache: this.serviceConfiguration.inventoryFragmentsToCache.join(',')
    });
  }

  async clickedClearInboundExternalIdCache() {
    await this.clearCache('INBOUND_ID_CACHE');
  }

  async clickedClearInventoryCache() {
    await this.clearCache('INVENTORY_CACHE');
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

    conf.inventoryFragmentsToCache = this.parseFragmentsList(
      this.serviceForm.value['inventoryFragmentsToCache']
    );

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

  private parseFragmentsList(fragmentsString: string): string[] {
    return fragmentsString
      .split(',')
      .map(fragment => fragment.trim())
      .filter(fragment => fragment.length > 0);
  }

  private trimOrUndefined(value: string | null | undefined): string | undefined {
    return value?.trim() || undefined;
  }
}
