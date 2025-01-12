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
import {
  Component,
  ElementRef,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { C8yStepper, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Direction, MappingType, MappingTypeDescriptionMap } from '../../shared';

@Component({
  selector: 'd11r-mapping-type',
  templateUrl: './mapping-type.component.html',
  encapsulation: ViewEncapsulation.None
})
export class MappingTypeComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  @ViewChild('mappingTypes') mappingTypesElement: ElementRef;
  @ViewChild(C8yStepper, { static: true }) closeSubject: Subject<any>;
  labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };

  MappingTypeDescriptionMap = MappingTypeDescriptionMap;
  formGroupStep: FormGroup;
  snoop: boolean = false;
  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;
  mappingType: MappingType = MappingType.JSON;
  mappingTypeDescription: string =
    MappingTypeDescriptionMap[MappingType.JSON].description;
  valid: boolean = false;

  constructor(
    private fb: FormBuilder,
    private elementRef: ElementRef
  ) {
    this.elementRef = elementRef;
  }

  ngOnInit(): void {
    this.closeSubject = new Subject();
    // console.log('Subject:', this.closeSubject, this.labels);
    this.formGroupStep = this.fb.group({
      snoop: [false]
    });
  }

  onDismiss() {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose() {
    if (this.formGroupStep.valid) {
      const formValue = this.formGroupStep.value;
      // Your existing save logic
      const { snoopSupported } =
        MappingTypeDescriptionMap[this.mappingType].properties[this.direction];
      this.closeSubject.next({
        mappingType: this.mappingType,
        snoop: formValue.snoop && snoopSupported
      });
      this.closeSubject.complete();
    }
  }

  onSelectMappingType(t) {
    this.valid = true;
    this.mappingType = t;
    this.mappingTypeDescription = MappingTypeDescriptionMap[t].description;
    if (this.shouldShowSnoop()) {
      this.formGroupStep.addControl('snoop', new FormControl(false));
    } else {
      this.formGroupStep.removeControl('snoop');
    }
  }
  shouldShowSnoop(): boolean {
    // Replace these conditions with your specific requirements
    return (
      this.direction === Direction.INBOUND &&
      MappingTypeDescriptionMap[this.mappingType].properties[this.direction]
        .snoopSupported
    );
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
