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
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup, FormBuilder } from '@angular/forms';
import { IIdentified } from '@c8y/client';

@Component({
  selector: 'd11r-device-selector-subscription',
  templateUrl: 'device-selector-subscription.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class DeviceSelectorSubscriptionComponent implements OnInit {
  @Input() deviceList: IIdentified[];

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<IIdentified[]>();

  form: FormGroup;

  constructor(private fb: FormBuilder) {
    this.createForm();
  }
  ngOnInit(): void {
    this.form.patchValue({
      deviceList: this.deviceList
    });
  }

  createForm() {
    this.form = this.fb.group({
      deviceList: ['']
    });
  }
  selectionChanged(e) {
    console.log(e);
  }

  clickedUpdateSubscription() {
    const formValue = this.form.value;
    this.commit.emit(formValue.deviceList);
  }

  clickedCancel() {
    this.cancel.emit();
  }
}
