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
import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import packageJson from '../../package.json';
import { Feature, Operation, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';
import { ActivatedRoute } from '@angular/router';
import { AIAgentService } from 'src/mapping/core/ai-agent.service';
import { BehaviorSubject, from, map, Subject, takeUntil } from 'rxjs';
import { gettext } from '@c8y/ngx-components/gettext';
import { CommonModule } from '@angular/common';
import { PopoverModule } from 'ngx-bootstrap/popover';

@Component({
  selector: 'd11r-mapping-service-configuration',
  styleUrls: ['./service-configuration.component.style.css'],
  templateUrl: 'service-configuration.component.html',
  standalone: true,
  imports:[CoreModule, CommonModule, PopoverModule]
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
    // console.log('Running version', this.version);
    this.feature = this.route.snapshot.data['feature'];
    this.serviceForm = this.fb.group({
      logPayload: new FormControl(''),
      logSubstitution: new FormControl(''),
      logConnectorErrorInBackend: new FormControl(''),
      sendConnectorLifecycle: new FormControl(''),
      sendMappingStatus: new FormControl(''),
      sendSubscriptionEvents: new FormControl(''),
      sendNotificationLifecycle: new FormControl(''),
      outboundMappingEnabled: new FormControl(''),
      deviceIsolationMQTTServiceEnabled: new FormControl(''),
      inboundExternalIdCacheSize: new FormControl(''),
      inboundExternalIdCacheRetention: new FormControl(''),
      inventoryCacheRetention: new FormControl(''),
      inventoryCacheSize: new FormControl(''),
      inventoryFragmentsToCache: new FormControl(''),
      maxCPUTimeMS: new FormControl(''),
      jsonataAgent: new FormControl({ value: '', disabled: true }),
      javaScriptAgent: new FormControl({ value: '', disabled: true }),
      smartFunctionAgent: new FormControl({ value: '', disabled: true }),
    });
    await this.loadData();
    from(this.aiAgentService.getAIAgents())
      .pipe(map(agents => agents.map(agent => agent.name)), takeUntil(this.destroy$))
      .subscribe(agentNames => {
        this.agents$.next(agentNames);
        this.aiAgentDeployed = agentNames.length > 0;

        // Enable/disable controls based on whether agents are available
        if (this.aiAgentDeployed) {
          this.serviceForm.get('javaScriptAgent')?.enable();
          this.serviceForm.get('jsonataAgent')?.enable();
          this.serviceForm.get('smartFunctionAgent')?.enable();
        } else {
          this.serviceForm.get('javaScriptAgent')?.disable();
          this.serviceForm.get('jsonataAgent')?.disable();
          this.serviceForm.get('smartFunctionAgent')?.disable();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  async loadData(): Promise<void> {
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();
    this.serviceForm.patchValue({
      logPayload: this.serviceConfiguration.logPayload,
      logSubstitution: this.serviceConfiguration.logSubstitution,
      logConnectorErrorInBackend:
        this.serviceConfiguration.logConnectorErrorInBackend,
      sendConnectorLifecycle: this.serviceConfiguration.sendConnectorLifecycle,
      sendMappingStatus: this.serviceConfiguration.sendMappingStatus,
      sendSubscriptionEvents: this.serviceConfiguration.sendSubscriptionEvents,
      sendNotificationLifecycle:
        this.serviceConfiguration.sendNotificationLifecycle,
      outboundMappingEnabled: this.serviceConfiguration.outboundMappingEnabled,
      deviceIsolationMQTTServiceEnabled: this.serviceConfiguration.deviceIsolationMQTTServiceEnabled,
      inboundExternalIdCacheSize:
        this.serviceConfiguration.inboundExternalIdCacheSize,
      inboundExternalIdCacheRetention:
        this.serviceConfiguration.inboundExternalIdCacheRetention,
      inventoryCacheSize:
        this.serviceConfiguration.inventoryCacheSize,
      inventoryCacheRetention:
        this.serviceConfiguration.inventoryCacheRetention,
      inventoryFragmentsToCache:
        this.serviceConfiguration.inventoryFragmentsToCache.join(","),
      maxCPUTimeMS:
        this.serviceConfiguration.maxCPUTimeMS,
      jsonataAgent:
        this.serviceConfiguration.jsonataAgent,
      javaScriptAgent:
        this.serviceConfiguration.javaScriptAgent,
      smartFunctionAgent:
        this.serviceConfiguration.smartFunctionAgent,
    });
  }

  async clickedClearInboundExternalIdCache() {
    const response1 = await this.sharedService.runOperation(
      {
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'INBOUND_ID_CACHE' }
      }
    );
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

  async clickedClearInventoryCache() {
    const response1 = await this.sharedService.runOperation(
      {
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'INVENTORY_CACHE' }
      }
    );
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

  async clickedSaveServiceConfiguration() {
    const conf = this.serviceForm.value;
    // trim the separated fragments
    conf.inventoryFragmentsToCache = this.serviceForm.value['inventoryFragmentsToCache']
      .split(",")
      .map(fragment => fragment.trim())
      .filter(fragment => fragment.length > 0);

    // console.log("VALUE", this.serviceForm.value['jsonataAgent'])
    conf.javaScriptAgent = this.serviceForm.value['javaScriptAgent']
      ? this.serviceForm.value['javaScriptAgent']?.trim()
      : undefined;
    ;
    conf.jsonataAgent = this.serviceForm.value['jsonataAgent']
      ? this.serviceForm.value['jsonataAgent']?.trim()
      : undefined;

    conf.smartFunctionAgent = this.serviceForm.value['smartFunctionAgent']
      ? this.serviceForm.value['smartFunctionAgent']?.trim()
      : undefined;

    const response = await this.sharedService.updateServiceConfiguration(conf);
    if (response.status >= 200 && response.status < 300) {
      this.alertService.success(gettext('Update successful'));
    } else {
      this.alertService.danger(
        gettext('Failed to update service configuration')
      );
    }
  }
}
