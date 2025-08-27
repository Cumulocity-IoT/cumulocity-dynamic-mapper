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
import { AfterViewInit, Component, EventEmitter, Input, OnDestroy, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';

import {
  ConnectorType,
  Direction,
  Feature,
  Mapping,
  SAMPLE_TEMPLATES_C8Y, SharedService, SnoopStatus,
} from '../../shared';
import { STEP_STATE } from '@angular/cdk/stepper';
import { EditorMode } from '../shared/stepper.model';
import { IIdentified } from '@c8y/client';

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
  styleUrls: ['../shared/mapping.style.css', 'client-relation-stepper.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class ClientRelationStepperComponent implements OnInit, OnDestroy {
  getState(): any {
    return STEP_STATE.ERROR
  }
  @Input() mapping: Mapping;
  @Input() selectedDevices: IIdentified[];
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<Mapping>();

  Direction = Direction;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;

  propertyFormly = new FormGroup({});
  isButtonDisabled$ = new BehaviorSubject<boolean>(false);
  supportsMessageContext: boolean;
  private readonly destroy$ = new Subject<void>();

  snoopedTemplateCounter = 0;

  @ViewChild('stepper', { static: false })
  stepper: C8yStepper;

  stepLabel: any;
  labels: StepperLabels = {
    next: 'Next',
    cancel: 'Cancel'
  };
  stepperForward: boolean = true;
  currentStepIndex: number;
  feature: Feature;

  constructor(
    private alertService: AlertService,
    public sharedService: SharedService,
  ) { }



  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();
    this.propertyFormly.setErrors({ validationError: { message: 'You do not have permission to change this mapping.' } });

  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.isButtonDisabled$.complete();
  }


  getCurrentMapping(): Mapping {
    try {
      return {
        ...this.mapping,
        targetTemplate: SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI],
        lastUpdate: Date.now()
      };
    } catch (error) {
      this.handleError('Error getting current mapping', error);
      return this.mapping;
    }
  }

  async onCommitButton(): Promise<void> {
    try {
      const currentMapping = this.getCurrentMapping();
      this.commit.emit(currentMapping);
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

  private goToLastStep() {
    // Mark all previous steps as completed
    this.stepper.steps.forEach((step, index) => {
      if (index < this.stepper.steps.length - 1) {
        step.completed = true;
      }
    });
    // Select the last step
    this.stepper.selectedIndex = this.stepper.steps.length - 1;
  }

  private handleError(message: string, error: Error): void {
    console.error(message, error);
    this.alertService.danger(`${message}: ${error.message}`);
  }
}
