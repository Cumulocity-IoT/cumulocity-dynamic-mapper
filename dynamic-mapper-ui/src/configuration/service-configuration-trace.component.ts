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

  // agents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  agents$: Observable<string[]>;
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
      jsonataAgent: new FormControl({ value: '', disabled: false }),
    });
    const values_01 = [
      'Austria',
      'Bulgaria',
      'Germany'
    ];

    this.formValues$ = of(values_01);
    this.agents$ = of(values_01);
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
    //   country: new FormControl('Austria')
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
      jsonataAgent:
      ///'Austria'
      'Netherland'
        // { value: 'Wien', label: 'Wien' },
      //  { value: 'Austria', label: 'Austria' },
      // this.serviceConfiguration.jsonataAgent,
    });

  //  this.myGroup.patchValue({firstName:'Italy'});
   this.myGroup.patchValue({firstName:'Germany'});
  }
}
