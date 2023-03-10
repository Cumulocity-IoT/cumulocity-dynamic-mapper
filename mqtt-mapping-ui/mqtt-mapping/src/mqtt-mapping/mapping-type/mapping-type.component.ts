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
import { Component, Input, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { C8yStepper, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Direction, MappingType } from '../../shared/mapping.model';
import { isDisabled } from '../stepper/util';

@Component({
  selector: 'mapping-type',
  templateUrl: './mapping-type.component.html',
  styleUrls: ['./mapping-type.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingTypeComponent implements OnInit {

  isDisabled = isDisabled;
  formGroupStep: FormGroup;

  @ViewChild(C8yStepper, { static: true })

  closeSubject: Subject<MappingType>;
  labels: ModalLabels = { cancel: "Cancel" };

  @Input()
  direction: Direction;

  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;

  mappingType: MappingType.JSON;

  constructor(private fb: FormBuilder) { }

  ngOnInit(): void {
    this.closeSubject = new Subject();
    console.log("Subject:", this.closeSubject, this.labels)
    this.formGroupStep = this.fb.group({
      mappingType: ['', Validators.required]
    });
  }


  onDismiss(event) {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose(event) {
    this.closeSubject.next(this.mappingType);
    this.closeSubject.complete();
  }

  onSelectMappingType(t) {
    this.mappingType = t;
    this.closeSubject.next(this.mappingType);
    this.closeSubject.complete();
  }
}
