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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { C8yStepper, ModalLabels } from '@c8y/ngx-components';
import { Subject, BehaviorSubject } from 'rxjs';
import { Direction, MAPPING_TYPE_DESCRIPTION, MappingType } from '../../shared';
import { isDisabled } from '../shared/util';

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

  isDisabled = isDisabled;
  formGroupStep: FormGroup;
  snoop: boolean = false;
  snoopDisabled$: Subject<boolean> = new BehaviorSubject(!MAPPING_TYPE_DESCRIPTION[MappingType.JSON].snoopSupported);
  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;
  mappingType: MappingType.JSON;
  mappingTypeDescription: string =
    MAPPING_TYPE_DESCRIPTION[MappingType.JSON].description;

  constructor(
    private fb: FormBuilder,
    private elementRef: ElementRef
  ) {
    this.elementRef = elementRef;
  }

  //   ngAfterViewInit(): void {
  //     const selector = `button[title=${MappingType.JSON}]`;
  //     const preSelectedMappingType =
  //       this.elementRef.nativeElement.querySelector(selector);
  //     preSelectedMappingType.click();
  //   }

  ngOnInit(): void {
    this.closeSubject = new Subject();
    // console.log('Subject:', this.closeSubject, this.labels);
    this.formGroupStep = this.fb.group({
      mappingType: ['', Validators.required]
    });
  }

  onDismiss() {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose() {
	const snoopSupported = !MAPPING_TYPE_DESCRIPTION[this.mappingType].snoopSupported;
    this.closeSubject.next({
      mappingType: this.mappingType,
      snoop: this.snoop && snoopSupported
    });
    this.closeSubject.complete();
  }

  onSelectMappingType(t) {
    this.mappingType = t;
    this.mappingTypeDescription = MAPPING_TYPE_DESCRIPTION[t].description;
    this.snoopDisabled$.next(!MAPPING_TYPE_DESCRIPTION[t].snoopSupported);
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
