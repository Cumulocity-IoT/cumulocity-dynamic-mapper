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
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';

@Component({
  selector: 'd11r-device-selector-subscription4',
  templateUrl: 'device-selector-subscription4.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class DeviceSelectorSubscription4Component implements OnInit{
  @Input() typeList: string[];

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<string[]>();

  trackByFn(index: any, _item: any) {
    return index;
  }

  ngOnInit(): void {
    this.add();  
  }

  add() {
    this.typeList.push("");
  }

  remove(index) {
    this.typeList.splice(index, 1);
  }

  clickedUpdateSubscription() {
    this.commit.emit(this.typeList);
  }

  clickedCancel() {
    this.cancel.emit();
  }
}
