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
import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject } from 'rxjs';
import { Direction, Mapping, SharedService } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { countDeviceIdentifiers, isDisabled } from '../shared/util';
import { EditorMode } from '../shared/stepper-model';
import { DeploymentMapEntry, StepperConfiguration } from '../../shared';
import { SnoopStatus } from '../../shared/model/shared.model';
import { CdkStep } from '@angular/cdk/stepper';
import {
  HOUSEKEEPING_INTERVAL_SECONDS,
  SNOOP_TEMPLATES_MAX
} from '../shared/mapping.model';

@Component({
  selector: 'd11r-snooping-stepper',
  templateUrl: 'snooping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class SnoopingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  private _deploymentMapEntry: DeploymentMapEntry;
  isButtonDisabled$: BehaviorSubject<boolean> = new BehaviorSubject(true);

  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
  }
  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<Mapping>();

  Direction = Direction;
  EditorMode = EditorMode;
  SnoopStatus = SnoopStatus;
  isDisabled = isDisabled;

  countDeviceIdentifiers$: BehaviorSubject<number> =
    new BehaviorSubject<number>(0);

  propertyFormly: FormGroup = new FormGroup({});
  sourceSystem: string;
  targetSystem: string;

  snoopedTemplateCounter: number = 0;
  stepLabel: any;
  onDestroy$ = new Subject<void>();
  labels: any = {
    next: 'Next',
    cancel: 'Cancel'
  };

  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public alertService: AlertService,
    public sharedService: SharedService
  ) {}

  deploymentMapEntryChange(e) {
    // console.log(
    //   'New getDeploymentMap',
    //   this._deploymentMapEntry,
    //   !this._deploymentMapEntry?.connectors
    // );
    this.isButtonDisabled$.next(
      !this._deploymentMapEntry?.connectors ||
        this._deploymentMapEntry?.connectors?.length == 0
    );
    // console.log('New setDeploymentMap from grid', e);
  }

  ngOnInit() {
    // set value for backward compatibility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;

    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  getCurrentMapping(patched: boolean): Mapping {
    return {
      ...this.mapping,
      lastUpdate: Date.now()
    };
  }

  async onCommitButton() {
    this.commit.emit(this.getCurrentMapping(false));
  }

  async onCancelButton() {
    this.cancel.emit();
  }

  async onNextStep(event: {
    stepper: C8yStepper;
    step: CdkStep;
  }): Promise<void> {
    // ('OnNextStep', event.step.label, this.mapping);
    this.stepLabel = event.step.label;
    if (this.stepLabel == 'Add and select connector') {
      if (
        this.deploymentMapEntry.connectors &&
        this.deploymentMapEntry.connectors.length == 0
      ) {
        // const initialState = {
        //   title: 'No connector selected',
        //   message:
        //     'To snoop for messages you should select at least one connector, unless you want to change this later! Do you want to continue?',
        //   labels: {
        //     ok: 'Continue',
        //     cancel: 'Close'
        //   }
        // };
        // const confirmContinuingModalRef: BsModalRef = this.bsModalService.show(
        //   ConfirmationModalComponent,
        //   { initialState }
        // );
        // confirmContinuingModalRef.content.closeSubject.subscribe(
        //   async (confirmation: boolean) => {
        //     // console.log('Confirmation result:', confirmation);
        //     if (confirmation) {
        //       event.stepper.next();
        //     }
        //     this.alertService.info(
        //       `Wait ${HOUSEKEEPING_INTERVAL_SECONDS} seconds before snooped messages are visible. Only the last ${SNOOP_TEMPLATES_MAX} messages are visible!`
        //     );
        //     confirmContinuingModalRef.hide();
        //   }
        // );
        // this.alertService.warning(
        //   'To snoop for messages you have to select at least one connector. Go back, unless you only want to assign a connector later!'
        // );
      } else {
        this.alertService.info(
          `Wait ${HOUSEKEEPING_INTERVAL_SECONDS} seconds before snooped messages are visible. Only the last ${SNOOP_TEMPLATES_MAX} messages are visible!`
        );
        event.stepper.next();
      }
    }
  }

  ngOnDestroy() {
    this.countDeviceIdentifiers$.complete();
    this.onDestroy$.complete();
  }
}
