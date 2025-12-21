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
import { CommonModule } from '@angular/common';
import { CdkStep, STEP_STATE } from '@angular/cdk/stepper';
import {
  AfterViewInit,
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService, C8yStepper, CoreModule } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import {
  DeploymentMapEntry,
  Direction,
  Feature,
  Mapping,
  SAMPLE_TEMPLATES_C8Y,
  SharedService,
  SnoopStatus,
  StepperConfiguration
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { MappingConnectorComponent } from '../step-connector/mapping-connector.component';
import { MappingStepPropertiesComponent } from '../step-property/mapping-properties.component';

interface StepperLabels {
  next: string;
  cancel: string;
}

const CONSTANTS = {
  HOUSEKEEPING_INTERVAL_SECONDS: 30,
  SNOOP_TEMPLATES_MAX: 10
} as const;

@Component({
  selector: 'd11r-snooping-stepper',
  templateUrl: 'snooping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css', 'snooping-stepper.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, MappingConnectorComponent, MappingStepPropertiesComponent]
})
export class SnoopingStepperComponent implements OnInit, OnDestroy, AfterViewInit {
  @ViewChild('stepper', { static: false }) stepper: C8yStepper;
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<Mapping>();

  readonly Direction = Direction;
  readonly EditorMode = EditorMode;
  readonly SnoopStatus = SnoopStatus;
  readonly propertyFormly = new FormGroup({});
  readonly isButtonDisabled$ = new BehaviorSubject<boolean>(true);
  readonly labels: StepperLabels = {
    next: 'Next',
    cancel: 'Cancel'
  };

  stepLabel: any;
  stepperForward = true;
  currentStepIndex: number;
  feature: Feature;
  snoopedTemplateCounter = 0;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly alertService: AlertService,
    public readonly sharedService: SharedService
  ) {}

  getState(): any {
    return STEP_STATE.ERROR;
  }


  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();
    this.propertyFormly.setErrors({
      validationError: { message: 'You do not have permission to change this mapping.' }
    });
  }

  ngAfterViewInit(): void {
    this.currentStepIndex = 0;
    if (!this.stepperConfiguration.advanceFromStepToEndStep &&
        this.stepperConfiguration.advanceFromStepToEndStep === this.currentStepIndex) {
      // Wrap changes in setTimeout to avoid ExpressionChangedAfterItHasBeenCheckedError
      setTimeout(() => {
        this.goToLastStep();
        this.alertService.info('The other steps have been skipped for this mapping type!');
      });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.isButtonDisabled$.complete();
  }

  deploymentMapEntryChange(): void {
    this.isButtonDisabled$.next(
      !this.deploymentMapEntry?.connectors ||
      this.deploymentMapEntry?.connectors?.length === 0
    );
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

  async onStepChange(index: number): Promise<void> {
    this.currentStepIndex = index;
    try {
      this.showSnoopingInfo();
    } catch (error) {
      this.handleError('Error changing step', error);
    }
  }

  onNextStep(event: { stepper: C8yStepper; step: CdkStep }): void {
    try {
      event.stepper.next();
    } catch (error) {
      this.handleError('Error moving to next step', error);
    }
  }

  private goToLastStep(): void {
    // Mark all previous steps as completed
    this.stepper.steps.forEach((step, index) => {
      if (index < this.stepper.steps.length - 1) {
        step.completed = true;
      }
    });
    // Select the last step
    this.stepper.selectedIndex = this.stepper.steps.length - 1;
  }

  private showSnoopingInfo(): void {
    const message =
      `Wait ${CONSTANTS.HOUSEKEEPING_INTERVAL_SECONDS} seconds before snooped messages are visible. ` +
      `Only the last ${CONSTANTS.SNOOP_TEMPLATES_MAX} messages are visible!`;
    this.alertService.info(message);
  }

  private handleError(message: string, error: Error): void {
    console.error(message, error);
    this.alertService.danger(`${message}: ${error.message}`);
  }
}
