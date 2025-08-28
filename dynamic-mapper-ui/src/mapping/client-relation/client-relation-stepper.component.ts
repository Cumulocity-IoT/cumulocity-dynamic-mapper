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
import { CdkStep } from '@angular/cdk/stepper';
import { Component, EventEmitter, inject, Input, OnDestroy, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, C8yStepper, ForOfFilterPipe } from '@c8y/ngx-components';
import { BehaviorSubject, map, pipe, Subject, tap } from 'rxjs';
import { ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';

import {
  Feature,
  SharedService,
} from '../../shared';
import { IIdentified, IResultList } from '@c8y/client';
import { SubscriptionService } from '../core/subscription.service';
import { AssetSelectionChangeEvent } from '@c8y/ngx-components/assets-navigator';

interface StepperLabels {
  next: string;
  cancel: string;
}

const CONSTANTS = {
  HOUSEKEEPING_INTERVAL_SECONDS: 30,
  SNOOP_TEMPLATES_MAX: 10
} as const;

@Component({
  selector: 'd11r-relation-stepper',
  templateUrl: 'client-relation-stepper.component.html',
  // styleUrls: ['../shared/mapping.style.css', 'client-relation-stepper.component.css'],
  styleUrls: ['client-relation-stepper.component.css'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: false
})
export class ClientRelationStepperComponent implements OnInit, OnDestroy {
  @Input() selectedDevices: IIdentified[] = [];
  @Input() clients: string[] = [];
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<any>();

  isButtonDisabled$ = new BehaviorSubject<boolean>(true);
  isButtonDisabled = true;
  private readonly destroy$ = new Subject<void>();

  snoopedTemplateCounter = 0;
  clientsAsOptions: any[] = [];

  @ViewChild('stepper', { static: false })
  stepper: C8yStepper;

  stepLabel: any;
  labels: StepperLabels = {
    next: 'Next',
    cancel: 'Cancel'
  };
  feature: Feature;
  selectedClient: any;
  client: any;

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly subscriptionService = inject(SubscriptionService);

  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();
    this.clients.forEach((cl, ind) => this.clientsAsOptions.push({ id: ind, name: cl }));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.isButtonDisabled$.complete();
  }

  async onCommitButton(): Promise<void> {
    try {
      this.commit.emit(null);
    } catch (error) {
      this.handleError('Error committing changes', error);
    }
  }

  onCancelButton(): void {
    this.cancel.emit();
  }

  onNextStep(event: { stepper: C8yStepper; step: CdkStep }): void {
    try {
      event.stepper.next();
    } catch (error) {
      this.handleError('Error moving to next step', error);
    }
  }

  selectionChanged(event: AssetSelectionChangeEvent) {
    // console.log(event);
  }

  selectClient(client: any): void {
    this.selectedClient = client;
    this.isButtonDisabled = !client;
  }

  private handleError(message: string, error: Error): void {
    console.error(message, error);
    this.alertService.danger(`${message}: ${error.message}`);
  }
}
