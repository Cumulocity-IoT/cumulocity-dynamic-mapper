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
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { WizardComponent } from '@c8y/ngx-components';
import { MappingType } from '../../shared/mapping.model';

@Component({
  selector: 'mapping-type',
  templateUrl: './mapping-type.component.html',
})
export class MappingTypeComponent implements OnInit {

  headerText: string;
  headerIcon: string;
  bodyHeaderText: string;

  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  mappingType: MappingType;

  constructor(
    private wizardComponent: WizardComponent
  ) {}

  ngOnInit(): void {
    this.headerText = this.wizardComponent.wizardConfig.headerText;
    this.headerIcon = this.wizardComponent.wizardConfig.headerIcon;
    this.bodyHeaderText = this.wizardComponent.wizardConfig.bodyHeaderText;
  }
  cancel() {
    this.wizardComponent.close();
  }
  done() {
    this.wizardComponent.close(this.mappingType);
  }
  onSelect(t){
    this.mappingType = t;
    this.wizardComponent.close(this.mappingType);
  }
}
