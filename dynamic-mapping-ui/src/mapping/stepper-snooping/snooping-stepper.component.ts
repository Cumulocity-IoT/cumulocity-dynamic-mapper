/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * distributed under the License is distributed on an "AS IS" BASIS,
 * Unless required by applicable law or agreed to in writing, software
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { CdkStep } from '@angular/cdk/stepper';
import { Component, EventEmitter, Input, OnDestroy, OnInit, Output, ViewEncapsulation } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';

import {
  ConnectorType,
  DeploymentMapEntry,
  Direction,
  Mapping,
  SAMPLE_TEMPLATES_C8Y, SnoopStatus,
  StepperConfiguration
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';

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
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class SnoopingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<Mapping>();

  Direction = Direction;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;

  propertyFormly = new FormGroup({});
  isButtonDisabled$ = new BehaviorSubject<boolean>(true);
  supportsMessageContext: boolean;
  private readonly destroy$ = new Subject<void>();

  snoopedTemplateCounter = 0;
  stepLabel: any;
  labels: StepperLabels = {
    next: 'Next',
    cancel: 'Cancel'
  };

  constructor(
    private alertService: AlertService,
  ) { }



  ngOnInit(): void {
    // Initial check for button state
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.isButtonDisabled$.complete();
  }


  private cleanup(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.isButtonDisabled$.complete();
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  deploymentMapEntryChange(e) {
    this.isButtonDisabled$.next(
      !this.deploymentMapEntry?.connectors ||
      this.deploymentMapEntry?.connectors?.length == 0
    );

    setTimeout(() => {
      this.supportsMessageContext =
        this.deploymentMapEntry.connectorsDetailed?.some(
          (con) => con.connectorType == ConnectorType.KAFKA
        );
    });
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

  private showSnoopingInfo(): void {
    const message = `Wait ${CONSTANTS.HOUSEKEEPING_INTERVAL_SECONDS} seconds before snooped messages are visible. ` +
      `Only the last ${CONSTANTS.SNOOP_TEMPLATES_MAX} messages are visible!`;
    this.alertService.info(message);
  }

  private handleError(message: string, error: Error): void {
    console.error(message, error);
    this.alertService.danger(`${message}: ${error.message}`);
  }
}
