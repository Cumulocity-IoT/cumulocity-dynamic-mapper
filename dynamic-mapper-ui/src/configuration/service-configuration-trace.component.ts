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
import { Component, inject, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { BehaviorSubject, Observable, of, Subject } from 'rxjs';
import { Feature, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';


@Component({
  selector: 'd11r-mapping-service-trace-configuration',
  styleUrls: ['./service-configuration.component.style.css'],
  templateUrl: 'service-configuration-trace.component.html',
  standalone: false
})
export class ServiceConfigurationTraceComponent implements OnInit, OnDestroy {


  myGroup = new FormGroup({
    firstName: new FormControl('Austria')
  });

  private route = inject(ActivatedRoute);
  private sharedService = inject(SharedService);
  private fb = inject(FormBuilder);

  // formValues$ : any = of([
  //   'Austria',
  //   'Bulgaria',
  //   'Germany',
  //   'Madagascar',
  //   'Poland',
  //   'Portugal',
  //   'UK',
  //   'USA'
  // ]);

  formValues$: Observable<string[]>;
  serviceForm: FormGroup;

  feature: Feature;

  agents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  destroy$: Subject<void> = new Subject<void>();
  aiAgentDeployed: boolean = false;

  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
    logConnectorErrorInBackend: false,
    sendConnectorLifecycle: false,
    sendMappingStatus: true,
    sendSubscriptionEvents: false,
    sendNotificationLifecycle: false,
    outboundMappingEnabled: true,
    inboundExternalIdCacheSize: 0,
    inboundExternalIdCacheRetention: 0,
    inventoryCacheSize: 0,
    inventoryCacheRetention: 0,
    maxCPUTimeMS: 5000,  // 5 seconds
    jsonataAgent: undefined,
    javaScriptAgent: undefined,
  };

  async ngOnInit() {


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
      inboundExternalIdCacheSize: new FormControl(''),
      inboundExternalIdCacheRetention: new FormControl(''),
      inventoryCacheRetention: new FormControl(''),
      inventoryCacheSize: new FormControl(''),
      inventoryFragmentsToCache: new FormControl(''),
      maxCPUTimeMS: new FormControl(''),
      jsonataAgent: new FormControl({ value: '', disabled: true }),
      javaScriptAgent: new FormControl({ value: '', disabled: true }),
    });
    const values_01 = [
      'Austria',
      'Bulgaria',
      'Germany'
    ];

    this.formValues$ = of(values_01);
    await this.loadData();

       const values_02 = [
      'Austria',
      'Bulgaria',
      'Germany',
      'Madagascar',
      'Poland',
      'Portugal',
      'UK',
      'USA'
    ];

    this.formValues$ = of(values_02);


    // this.myGroup = new FormGroup({
    //   firstName: new FormControl('Austria')
    // });

    // this.formValues$ = of([
    //   'Austria',
    //   'Bulgaria',
    //   'Germany',
    //   'Madagascar',
    //   'Poland',
    //   'Portugal',
    //   'UK',
    //   'USA'
    // ]);
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
        // {value:'Austria', label:'Austria'},
        this.serviceConfiguration.jsonataAgent,
      javaScriptAgent:
        this.serviceConfiguration.javaScriptAgent,
    });
  }


}
