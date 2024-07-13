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
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject } from 'rxjs';
import { BrokerConfigurationService } from '../../configuration';
import { Direction, Mapping } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { countDeviceIdentifiers, isDisabled } from '../shared/util';
import { EditorMode } from '../shared/stepper-model';
import { StepperConfiguration } from 'src/shared/model/shared.model';
import { SnoopStatus } from '../../shared/model/shared.model';

@Component({
  selector: 'd11r-snooping-stepper',
  templateUrl: 'snooping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class SnoopingStepperComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
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
  step: any;
  onDestroy$ = new Subject<void>();

  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public brokerConfigurationService: BrokerConfigurationService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) {}

  ngOnInit() {
    // console.log('Formly to be updated:', this.configService);
    // set value for backward compatibility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    // console.log(
    //  'Mapping to be updated:',
    //  this.mapping,
    //  this.stepperConfiguration
    // );

    this.countDeviceIdentifiers$.next(countDeviceIdentifiers(this.mapping));

    // this.extensionEvents$.subscribe((events) => {
    //   console.log('New events from extension', events);
    // });
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

  ngOnDestroy() {
    this.countDeviceIdentifiers$.complete();
    this.onDestroy$.complete();
  }
}
