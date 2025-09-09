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
import { AfterViewInit, ChangeDetectorRef, Component, EventEmitter, inject, OnDestroy, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { ChangeDetectionStrategy } from '@angular/core';

import {
  Feature,
  SharedService,
} from '../../shared';
import { IIdentified } from '@c8y/client';
import { AssetSelectionChangeEvent } from '@c8y/ngx-components/assets-navigator';
import { ClientRelationService } from '../core/client-relation.service';
import { Subject } from 'rxjs';

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
export class ClientRelationStepperComponent implements OnInit, OnDestroy, AfterViewInit {
  clients: string[] = [];
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<any>();

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

  client: any;
  filteredClients: any = [];
  selectedClient: any;
  pattern = "";

  selectedDevices: IIdentified[] = [];

  searchTerm: any;

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly clientRelationService = inject(ClientRelationService);
  private readonly cdr = inject(ChangeDetectorRef);

  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  async ngAfterViewInit(): Promise<void> {
    const { clients } = await this.clientRelationService.getAllClients();

    if (clients?.length) {
      this.clients = clients;
      this.clientsAsOptions = clients.map((client, index) => ({
        id: index,
        name: client
      }));
      this.filteredClients = [...this.clientsAsOptions];
    } else {
      this.clients = [];
      this.clientsAsOptions = [];
      this.filteredClients = [];
    }
  }

  async onCommitButton(): Promise<void> {
    try {
      const devices = this.selectedDevices?.map(d => d.id) || [];
      this.commit.emit({ client: this.selectedClient.name, devices });
    } catch (error) {
      this.handleError('Error committing changes', error);
    }
  }

  onCancelButton(): void {
    this.cancel.emit();
  }

  async onNextStep(event: { stepper: C8yStepper; step: CdkStep }): Promise<void> {
    try {
      console.log("Step next step", event, this.selectedDevices);

      event.stepper.next();
    } catch (error) {
      this.handleError('Error moving to next step', error);
    }
  }

  async onStepChange(event: any): Promise<void> {
    const currentStepIndex = event['selectedIndex'];
    if (currentStepIndex == 1) {
      try {
        const { devices } = await this.clientRelationService.getDevicesForClient(this.selectedClient.name);

        // Use setTimeout to ensure the component is fully rendered
        setTimeout(() => {
          this.selectedDevices = devices?.map(id => ({ id })) || [];
          console.log("Step change - devices updated", this.selectedDevices);
          this.cdr.detectChanges();
        }, 100); // Small delay to ensure component is ready

      } catch (error) {
        // ignore not found error
        setTimeout(() => {
          this.selectedDevices = [];
          this.cdr.detectChanges();
        }, 100);
      }
    }
  }

  selectionChanged(event: AssetSelectionChangeEvent) {
    // console.log(event);
  }

  selectClient(client: any): void {
    this.selectedClient = client;
    this.isButtonDisabled = !client;
  }

  onSearch(term: string) {
    this.searchTerm = term;
    this.filteredClients = this.clientsAsOptions.filter(cl =>
      cl.name.toLowerCase().includes(term.toLowerCase())
    );
  }

  addNewClient(client: any) {
    this.selectedClient = { id: client, name: client };
    this.isButtonDisabled = !client;
  }

  private handleError(message: string, error: Error): void {
    console.error(message, error);
    this.alertService.danger(`${message}: ${error.message}`);
  }
}
