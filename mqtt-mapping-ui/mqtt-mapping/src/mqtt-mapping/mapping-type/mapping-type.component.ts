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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { Component, OnInit, ViewChild } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { C8yStepper, WizardComponent } from '@c8y/ngx-components';
import { Direction, MappingType } from '../../shared/mapping.model';
import { isDisabled } from '../stepper/util';

@Component({
  selector: 'mapping-type',
  templateUrl: './mapping-type.component.html',
})
export class MappingTypeComponent implements OnInit {
  isDisabled = isDisabled;
  formGroupStep: FormGroup;

  @ViewChild(C8yStepper, { static: true })
  stepper: C8yStepper;

  headerText: string;
  headerIcon: string;

  direction: Direction;

  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;

  result = {
    mappingType: MappingType.JSON,
  }

  constructor(private wizardComponent: WizardComponent, private fb: FormBuilder) { }

  ngOnInit(): void {

    this.headerText = this.wizardComponent.wizardConfig.headerText;
    this.headerIcon = this.wizardComponent.wizardConfig.headerIcon;
    this.direction = this.wizardComponent['direction'];
    this.formGroupStep = this.fb.group({
      mappingType: ['', Validators.required]
    });

  }
  back() {
    this.wizardComponent.reset();
  }

  cancel() {
    this.wizardComponent.close();
  }

  done() {
    this.wizardComponent.close(this.result);
  }

  onSelectMappingType(t) {
    this.result.mappingType = t;
    this.wizardComponent.close(this.result);
  }
}
